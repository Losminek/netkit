import Darwin
import Foundation
import NetKitShared

/// One IPv4 ICMP datagram probe. The descriptor is owned by the worker until it exits.
enum IcmpResult { case reply(Double), timeout, invalid }

final class IcmpPing {
    private let lock = NSLock()
    private var cancelled = false
    var onProbeStarted: (() -> Void)?

    func cancel() {
        lock.lock()
        cancelled = true
        lock.unlock()
    }

    private var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func start(address: String, interfaceName: String? = nil, completion: @escaping (IcmpResult) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let result = self.probe(address: address, interfaceName: interfaceName)
            DispatchQueue.main.async {
                if !self.isCancelled { completion(result) }
            }
        }
    }

    static func makeRequest(payload: [UInt8]) -> [UInt8] {
        precondition(payload.count > 0 && payload.count % 2 == 0)
        var packet: [UInt8] = [8, 0, 0, 0, 0, 0, 0, 1] + payload
        var sum: UInt32 = 0
        for index in stride(from: 0, to: packet.count, by: 2) {
            sum += UInt32(packet[index]) << 8 | UInt32(packet[index + 1])
        }
        while sum >> 16 != 0 { sum = (sum & 0xffff) + (sum >> 16) }
        let checksum = UInt16(truncatingIfNeeded: ~sum)
        packet[2] = UInt8(checksum >> 8)
        packet[3] = UInt8(checksum & 0xff)
        return packet
    }

    private func probe(address: String, interfaceName: String?) -> IcmpResult {
        var destination = sockaddr_in()
        destination.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        destination.sin_family = sa_family_t(AF_INET)
        guard address.withCString({ inet_pton(AF_INET, $0, &destination.sin_addr) }) == 1 else { return .invalid }
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)
        guard fd >= 0 else { return .invalid }
        defer { close(fd) }
        if let interfaceName {
            var index = if_nametoindex(interfaceName)
            guard index != 0,
                  setsockopt(fd, IPPROTO_IP, IP_BOUND_IF, &index, socklen_t(MemoryLayout.size(ofValue: index))) == 0 else { return .invalid }
        }
        let connected = withUnsafePointer(to: &destination) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard connected == 0 else { return .invalid }
        let request = Self.makeRequest(payload: (0..<16).map { _ in UInt8.random(in: 0...255) })
        guard !isCancelled else { return .invalid }
        let sentAt = ProcessInfo.processInfo.systemUptime
        guard request.withUnsafeBytes({ send(fd, $0.baseAddress, $0.count, 0) }) == request.count else { return .invalid }
        onProbeStarted?()
        let deadline = ProcessInfo.processInfo.systemUptime + 5
        while !isCancelled && ProcessInfo.processInfo.systemUptime < deadline {
            var descriptor = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let ready = poll(&descriptor, 1, 100)
            if ready < 0 { return .invalid }
            if ready == 0 { continue }
            var buffer = [UInt8](repeating: 0, count: 2048)
            let count = buffer.withUnsafeMutableBytes { recv(fd, $0.baseAddress, $0.count, 0) }
            guard count > 0 else { return .invalid }
            let bytes = Array(buffer.prefix(count))
            let reply: [UInt8]
            if let first = bytes.first, first >> 4 == 4 {
                let headerLength = Int(first & 0x0f) * 4
                guard headerLength >= 20, bytes.count >= headerLength, bytes.count >= 20,
                      bytes[9] == UInt8(IPPROTO_ICMP) else { continue }
                reply = Array(bytes[headerLength...])
            } else {
                reply = bytes
            }
            let sent = KotlinByteArray(size: Int32(request.count))
            for (index, byte) in request.enumerated() {
                sent.set(index: Int32(index), value: Int8(bitPattern: byte))
            }
            let received = KotlinByteArray(size: Int32(reply.count))
            for (index, byte) in reply.enumerated() {
                received.set(index: Int32(index), value: Int8(bitPattern: byte))
            }
            if IosNetworkDomain().icmpReplyMatches(request: sent, reply: received) {
                return .reply((ProcessInfo.processInfo.systemUptime - sentAt) * 1000)
            }
        }
        return isCancelled ? .invalid : .timeout
    }
}
