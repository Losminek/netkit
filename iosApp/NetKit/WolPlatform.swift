import Darwin
import Foundation
import Network
import NetKitShared

protocol WolPending: AnyObject {
    func networkLost()
}

/// All methods and NWPath callbacks run on the main queue.
final class WolPlatform: NSObject, IosWolPlatform {
    private let monitor = NWPathMonitor()
    private var epoch = UUID().uuidString
    private var current: (NWInterface, UInt32, UInt32)?
    private var operations: [UUID: WolPending] = [:]

    override init() {
        super.init()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let next: (NWInterface, UInt32, UInt32)?
            if let interface = path.availableInterfaces.first(where: { $0.type == .wifi }),
               let (address, mask) = Self.ipv4(interface: interface.name) {
                next = (interface, address, mask)
            } else { next = nil }
            guard !Self.sameNetwork(self.current, next) else { return }
            self.current = next
            self.epoch = UUID().uuidString
            let outstanding = Array(self.operations.values)
            self.operations.removeAll()
            outstanding.forEach { $0.networkLost() }
        }
        monitor.start(queue: .main)
    }

    deinit { monitor.cancel() }

    struct NetworkIdentity: Equatable {
        let interfaceIndex: Int
        let address: UInt32
        let mask: UInt32
    }

    static func identity(_ value: (NWInterface, UInt32, UInt32)?) -> NetworkIdentity? {
        value.map { NetworkIdentity(interfaceIndex: $0.0.index, address: $0.1, mask: $0.2) }
    }

    static func sameNetwork(_ lhs: (NWInterface, UInt32, UInt32)?, _ rhs: (NWInterface, UInt32, UInt32)?) -> Bool {
        identity(lhs) == identity(rhs)
    }

    func activeWifiCIDR() -> String? {
        guard let (_, address, mask) = current else { return nil }
        return "\(Self.text(address))/\(mask.nonzeroBitCount)"
    }

    func currentWifi() -> IosWifiSnapshot? {
        guard let (_, address, mask) = current else { return nil }
        return IosWifiSnapshot(token: epoch, ipv4: Self.text(address), prefixLength: KotlinInt(value: Int32(mask.nonzeroBitCount)))
    }

    func sendUdp(networkToken: String, host: String, port: Int32, payload: KotlinByteArray,
                 completion: @escaping (SendFailure?) -> Void) -> IosCancellation {
        let operation = WolDatagram(completion: completion)
        guard networkToken == epoch, let (interface, address, mask) = current else {
            operation.finish(.networkLost)
            return operation
        }
        guard let destination = Self.number(host), port > 0, port <= 65535 else {
            operation.finish(.io)
            return operation
        }
        // Restricted multicast entitlement is absent. Permit only a unicast host in the
        // selected interface's subnet; reject local network and directed broadcast.
        guard Self.permittedUnicast(destination, address: address, mask: mask) else {
            operation.finish(.noBroadcast)
            return operation
        }
        let parameters = NWParameters.udp
        parameters.requiredInterfaceType = .wifi
        parameters.requiredInterface = interface
        let conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: UInt16(port))!, using: parameters)
        let id = UUID()
        operations[id] = operation
        operation.onEnd = { [weak self] in self?.operations.removeValue(forKey: id) }
        let bytes = Data((0..<payload.size).map { UInt8(bitPattern: payload.get(index: $0)) })
        operation.start(connection: conn, payload: bytes)
        return operation
    }

    func probe(networkToken: String, host: String, port: KotlinInt?,
               completion: @escaping (WakeProbeStatus) -> Void) -> IosCancellation {
        let operation = WolProbe(completion: completion)
        guard networkToken == epoch, let (interface, address, mask) = current,
              let destination = Self.number(host),
              Self.permittedUnicast(destination, address: address, mask: mask) else {
            operation.finish(.unavailable)
            return operation
        }
        let id = UUID()
        operations[id] = operation
        operation.onEnd = { [weak self] in self?.operations.removeValue(forKey: id) }
        if let port {
            let rawPort = port.intValue
            guard rawPort > 0, rawPort <= 65535, let endpoint = NWEndpoint.Port(rawValue: UInt16(rawPort)) else {
                operation.finish(.unavailable)
                return operation
            }
            let parameters = NWParameters.tcp
            parameters.requiredInterfaceType = .wifi
            parameters.requiredInterface = interface
            operation.startTCP(NWConnection(host: NWEndpoint.Host(host), port: endpoint, using: parameters))
        } else {
            operation.startPing(address: host, interfaceName: interface.name)
        }
        return operation
    }

    static func permittedUnicast(_ destination: UInt32, address: UInt32, mask: UInt32) -> Bool {
        let firstOctet = destination >> 24
        guard firstOctet != 0, firstOctet != 127, firstOctet < 224 else { return false }
        let network = address & mask
        guard destination & mask == network else { return false }
        if mask.nonzeroBitCount >= 31 { return true }
        return destination != network && destination != (network | ~mask)
    }

    private static func ipv4(interface: String) -> (UInt32, UInt32)? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0 else { return nil }
        defer { freeifaddrs(list) }
        var item = list
        while let entry = item {
            defer { item = entry.pointee.ifa_next }
            guard String(cString: entry.pointee.ifa_name) == interface,
                  let addr = entry.pointee.ifa_addr, let netmask = entry.pointee.ifa_netmask,
                  Int32(addr.pointee.sa_family) == AF_INET else { continue }
            let value = UnsafeRawPointer(addr).assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr.s_addr.bigEndian
            let mask = UnsafeRawPointer(netmask).assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr.s_addr.bigEndian
            let bits = mask.nonzeroBitCount
            let expected: UInt32 = bits == 0 ? 0 : UInt32.max << (32 - bits)
            guard mask == expected else { return nil }
            return (value, mask)
        }
        return nil
    }

    private static func number(_ text: String) -> UInt32? {
        var addr = in_addr()
        guard text.withCString({ inet_pton(AF_INET, $0, &addr) }) == 1 else { return nil }
        return addr.s_addr.bigEndian
    }

    private static func text(_ value: UInt32) -> String {
        [24, 16, 8, 0].map { String((value >> $0) & 255) }.joined(separator: ".")
    }
}

final class WolDatagram: NSObject, IosCancellation, WolPending {
    var connection: NWConnection?
    var onEnd: (() -> Void)?
    private var completion: ((SendFailure?) -> Void)?
    init(completion: @escaping (SendFailure?) -> Void) { self.completion = completion }
    func start(connection: NWConnection, payload: Data) {
        self.connection = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                connection.stateUpdateHandler = nil
                connection.send(content: payload, completion: .contentProcessed { [weak self] error in
                    self?.finish(error == nil ? nil : .io)
                })
            case .failed: self.finish(.io)
            default: break
            }
        }
        connection.start(queue: .main)
    }
    func cancel() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { self.cancel() }
            return
        }
        completion = nil
        connection?.cancel()
        connection = nil
        onEnd?()
        onEnd = nil
    }
    func networkLost() { finish(.networkLost) }
    func finish(_ failure: SendFailure?) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { self.finish(failure) }
            return
        }
        let callback = completion
        cancel()
        callback?(failure)
    }
}

final class WolProbe: NSObject, IosCancellation, WolPending {
    var onEnd: (() -> Void)?
    private var completion: ((WakeProbeStatus) -> Void)?
    private var connection: NWConnection?
    private var ping: IcmpPing?
    private var timeout: DispatchWorkItem?
    init(completion: @escaping (WakeProbeStatus) -> Void) { self.completion = completion }

    func startTCP(_ connection: NWConnection) {
        self.connection = connection
        let timeout = DispatchWorkItem { [weak self] in self?.finish(.noResponse) }
        self.timeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: timeout)
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.finish(.responds)
            case .waiting(let error):
                if case .posix(.ECONNREFUSED) = error { self?.finish(.responds) }
            case .failed(let error):
                if case .posix(.ECONNREFUSED) = error { self?.finish(.responds) }
                else { self?.finish(.unavailable) }
            default: break
            }
        }
        connection.start(queue: .main)
    }

    func startPing(address: String, interfaceName: String) {
        let ping = IcmpPing()
        self.ping = ping
        ping.start(address: address, interfaceName: interfaceName) { [weak self] result in
            switch result {
            case .reply: self?.finish(.responds)
            case .timeout: self?.finish(.noResponse)
            case .invalid: self?.finish(.unavailable)
            }
        }
    }

    func cancel() {
        if !Thread.isMainThread { DispatchQueue.main.async { self.cancel() }; return }
        completion = nil
        timeout?.cancel(); timeout = nil
        connection?.cancel(); connection = nil
        ping?.cancel(); ping = nil
        onEnd?(); onEnd = nil
    }
    func networkLost() { finish(.unavailable) }
    func finish(_ result: WakeProbeStatus) {
        if !Thread.isMainThread { DispatchQueue.main.async { self.finish(result) }; return }
        let callback = completion
        cancel()
        callback?(result)
    }
}
