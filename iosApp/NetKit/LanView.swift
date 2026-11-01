import Darwin
import Network
import NetKitShared
import SwiftUI

/// Owns one operation. All state and callbacks are serialized on the main queue.
final class LanSession: ObservableObject {
    @Published var status = ""
    @Published var results: [String] = []
    @Published var running = false
    private var connection: NWConnection?
    private var browser: NWBrowser?
    private var timer: DispatchWorkItem?
    private var ping: IcmpPing?
    private let domain = IosNetworkDomain()
    private let wifi = WolPlatform()
    var activeWifiCIDR: String? { wifi.activeWifiCIDR() }

    func cancel() {
        scan?.cancel()
        scan = nil
        ping?.cancel()
        ping = nil
        timer?.cancel()
        timer = nil
        connection?.cancel()
        connection = nil
        browser?.cancel()
        browser = nil
        running = false
        if status == String(localized: "network.running") || status == String(localized: "network.waiting") { status = "" }
    }

    private var scan: PortScan?

    func scanPorts(host: String, input: String) {
        cancel()
        guard !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let parsed = domain.ports(input: input), !parsed.isEmpty, parsed.count <= 64 else {
            status = String(localized: "network.invalid")
            return
        }
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let ports = parsed.map { UInt16($0.intValue) }
        let reasons = domain.confirmationReasons(address: host, ownCidr: activeWifiCIDR, portCount: Int32(ports.count))
        guard !reasons.contains("INVALID_TARGET"), !reasons.contains("INVALID_PORT_COUNT") else {
            status = String(localized: "network.invalid")
            return
        }
        results = []
        running = true
        status = String(localized: "network.running")
        let operation = PortScan(host: host, ports: ports)
        scan = operation
        operation.start { [weak self, weak operation] port, result, done in
            guard let self, let operation, self.scan === operation else { return }
            if let port, let result {
                self.results.append("\(port): \(result)")
            }
            if done {
                self.status = String(localized: "lan.scan.finished")
                self.cancel()
            }
        }
    }

    func dns(host: String, server: String, serverPort: UInt16 = 53) {
        cancel()
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let server = server.trimmingCharacters(in: .whitespacesAndNewlines)
        let id = Int32.random(in: 0...65535)
        guard let query = domain.dnsQuery(host: host, type: 1, id: id),
              Self.validIPv4(server),
              let endpointPort = NWEndpoint.Port(rawValue: serverPort) else {
            status = String(localized: "network.invalid")
            return
        }
        let bytes = Data((0..<query.size).map { UInt8(bitPattern: query.get(index: $0)) })
        running = true
        results = []
        status = String(localized: "network.running")
        let conn = NWConnection(host: NWEndpoint.Host(server), port: endpointPort, using: .udp)
        connection = conn
        let timeout = DispatchWorkItem { [weak self, weak conn] in
            guard let self, let conn, self.connection === conn else { return }
            self.status = String(localized: "network.timeout")
            self.cancel()
        }
        timer = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: timeout)
        conn.stateUpdateHandler = { [weak self, weak conn] state in
            guard let self, let conn, self.connection === conn else { return }
            switch state {
            case .ready:
                conn.stateUpdateHandler = nil
                conn.send(content: bytes, completion: .contentProcessed { error in
                    guard self.connection === conn else { return }
                    if error != nil {
                        self.status = String(localized: "network.failed")
                        self.cancel()
                        return
                    }
                    conn.receiveMessage { data, _, _, error in
                        guard self.connection === conn else { return }
                        guard error == nil, let data, data.count <= 9000 else {
                            self.status = String(localized: "network.failed")
                            self.cancel()
                            return
                        }
                        let packet = KotlinByteArray(size: Int32(data.count))
                        for (index, byte) in data.enumerated() {
                            packet.set(index: Int32(index), value: Int8(bitPattern: byte))
                        }
                        if let answers = self.domain.dnsResponse(packet: packet, id: id, host: host, type: 1) {
                            self.results = answers
                            self.status = String(localized: "lan.dns.complete")
                        } else {
                            self.status = String(localized: "network.failed")
                        }
                        self.cancel()
                    }
                })
            case .waiting(let error) where Self.permissionDenied(error, connection: conn):
                self.status = String(localized: "network.permission")
                self.cancel()
            case .failed(let error) where Self.permissionDenied(error, connection: conn):
                self.status = String(localized: "network.permission")
                self.cancel()
            case .failed:
                self.status = String(localized: "network.failed")
                self.cancel()
            default: break
            }
        }
        conn.start(queue: .main)
    }

    static func validIPv4(_ value: String) -> Bool {
        var address = in_addr()
        return value.withCString { inet_pton(AF_INET, $0, &address) } == 1
    }

    static func permissionDenied(_ error: NWError, connection: NWConnection) -> Bool {
        let denied = connection.currentPath?.unsatisfiedReason == .localNetworkDenied
        return permissionDenied(error, localNetworkDenied: denied)
    }

    static func permissionDenied(_ error: NWError, localNetworkDenied: Bool) -> Bool {
        if localNetworkDenied { return true }
        if case .dns(let code) = error, code == -65570 { return true }
        return false
    }

    func pingHost(_ host: String) {
        cancel()
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.validIPv4(host) else { status = String(localized: "network.invalid"); return }
        running = true
        status = String(localized: "network.running")
        let probe = IcmpPing()
        ping = probe
        probe.start(address: host) { [weak self, weak probe] result in
            guard let self, let probe, self.ping === probe else { return }
            switch result {
            case .reply(let milliseconds):
                self.status = String(localized: "lan.ping.reply") + " " + milliseconds.formatted(.number.precision(.fractionLength(1))) + " ms"
            case .timeout: self.status = String(localized: "network.timeout")
            case .invalid: self.status = String(localized: "network.failed")
            }
            self.cancel()
        }
    }

    func browse(loopbackOnly: Bool = false, stateObserver: ((NWBrowser.State) -> Void)? = nil) {
        cancel()
        results = []
        running = true
        status = String(localized: "network.running")
        let parameters = NWParameters.tcp
        if loopbackOnly { parameters.requiredInterfaceType = .loopback }
        let browse = NWBrowser(for: .bonjour(type: "_ssh._tcp", domain: "local."), using: parameters)
        browser = browse
        browse.stateUpdateHandler = { [weak self, weak browse] state in
            stateObserver?(state)
            guard let self, let browse, self.browser === browse else { return }
            switch state {
            case .waiting: self.status = String(localized: "network.waiting")
            case .failed:
                self.status = String(localized: "network.failed")
                self.cancel()
            default: break
            }
        }
        browse.browseResultsChangedHandler = { [weak self, weak browse] results, _ in
            guard let self, let browse, self.browser === browse else { return }
            var names = Set<String>()
            for result in results {
                if names.count >= 128 { break }
                if case let .service(name, type, domain, interface) = result.endpoint,
                   let safeName = self.domain.mdnsName(raw: name)?.value {
                    names.insert("\(safeName) — \(type) \(domain) \(interface?.name ?? "")")
                }
            }
            self.results = names.sorted()
        }
        let timeout = DispatchWorkItem { [weak self, weak browse] in
            guard let self, let browse, self.browser === browse else { return }
            if self.status != String(localized: "network.waiting") {
                self.status = String(localized: "lan.browse.finished")
            }
            self.cancel()
        }
        timer = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: timeout)
        browse.start(queue: .main)
    }
}

struct ScanConsent: Equatable {
    let host: String
    let ports: String
    let cidr: String?

    var reasons: [String] {
        let domain = IosNetworkDomain()
        return domain.confirmationReasons(address: host, ownCidr: cidr,
                                          portCount: Int32(domain.ports(input: ports)?.count ?? 0))
    }

    func message(resolve: (String) -> String) -> String {
        var lines = [resolve("lan.warning")]
        if reasons.contains("OUTSIDE_WIFI") { lines.append(resolve("lan.reason.outside")) }
        if reasons.contains("NON_PRIVATE_ADDRESS") { lines.append(resolve("lan.reason.nonprivate")) }
        if reasons.contains("LARGE_PORT_SET") { lines.append(resolve("lan.reason.ports")) }
        return lines.joined(separator: "\n")
    }
}

struct LanView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var session = LanSession()
    @State private var host = ""
    @State private var port = "22"
    @State private var dnsServer = ""
    @State private var pending: Action?
    @State private var showingConsent = false
    @State private var scanConsent: ScanConsent?
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case host, port, dnsServer }
    private enum Action { case scan, dns, ping, browse }
    private var currentScanConsent: ScanConsent {
        ScanConsent(host: host.trimmingCharacters(in: .whitespacesAndNewlines),
                    ports: port.trimmingCharacters(in: .whitespacesAndNewlines), cidr: session.activeWifiCIDR)
    }
    private func request(_ action: Action) {
        focusedField = nil
        pending = action
        scanConsent = action == .scan ? currentScanConsent : nil
        showingConsent = true
    }

    var body: some View {
        Form {
            Text("lan.warning")
            TextField("lan.host", text: $host).textInputAutocapitalization(.never).autocorrectionDisabled()
                .focused($focusedField, equals: .host)
            TextField("lan.port", text: $port).keyboardType(.numbersAndPunctuation)
                .focused($focusedField, equals: .port)
            Button("lan.tcp") { request(.scan) }.disabled(session.running)
            TextField("lan.dns.server", text: $dnsServer).textInputAutocapitalization(.never).autocorrectionDisabled()
                .focused($focusedField, equals: .dnsServer)
            Button("lan.dns") { request(.dns) }.disabled(session.running)
            Button("lan.ping") { request(.ping) }.disabled(session.running)
            Button("lan.browse") { request(.browse) }.disabled(session.running)
            if session.running { Button("network.cancel") { session.cancel() } }
            if !session.status.isEmpty { Text(verbatim: session.status) }
            if session.status.isEmpty && session.results.isEmpty { Text("devices.empty") }
            ForEach(Array(session.results.enumerated()), id: \.offset) { _, value in Text(verbatim: value) }
        }
        .confirmationDialog("lan.consent.title", isPresented: $showingConsent, titleVisibility: .visible) {
            Button("lan.consent.confirm") {
                switch pending {
                case .scan:
                    let current = currentScanConsent
                    if current != scanConsent {
                        scanConsent = current
                        DispatchQueue.main.async { showingConsent = true }
                        return
                    }
                    session.scanPorts(host: current.host, input: current.ports)
                case .dns: session.dns(host: host, server: dnsServer)
                case .ping: session.pingHost(host)
                case .browse: session.browse()
                case nil: break
                }
                pending = nil
            }
            Button("network.cancel", role: .cancel) { pending = nil }
        } message: {
            Text(verbatim: scanConsent?.message { NSLocalizedString($0, comment: "") }
                 ?? String(localized: "lan.warning"))
        }
        .navigationTitle("tab.devices")
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { session.cancel() }
        }
        .onDisappear { session.cancel() }
    }
}
