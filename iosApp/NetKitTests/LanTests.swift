@testable import NetKit
import Combine
import Darwin
import Network
import XCTest
import NetKitShared

final class LanTests: XCTestCase {
    func testScanConcurrencyAndPortLimit() {
        let scan = PortScan(host: "127.0.0.1", ports: Array(1...64).map(UInt16.init))
        scan.start { _, _, _ in }
        XCTAssertEqual(scan.peakActive, 4)
        scan.cancel()
        let rejected = PortScan(host: "127.0.0.1", ports: Array(1...65).map(UInt16.init))
        var completed = false
        rejected.start { _, result, done in
            completed = done
            XCTAssertEqual(result, String(localized: "network.invalid"))
        }
        XCTAssertTrue(completed)
        XCTAssertEqual(rejected.peakActive, 0)
    }

    func testInputValidationAndCancellationStatus() {
        let session = LanSession()
        session.pingHost(" not-an-ip ")
        XCTAssertFalse(session.running)
        XCTAssertEqual(session.status, String(localized: "network.invalid"))
        session.dns(host: "example.invalid", server: "not-an-ip")
        XCTAssertFalse(session.running)
        XCTAssertEqual(session.status, String(localized: "network.invalid"))
        session.browse(loopbackOnly: true)
        session.cancel()
        XCTAssertEqual(session.status, "")
    }

    func testConfirmationReasonsUseWifiCIDR() {
        let domain = IosNetworkDomain()
        let within = domain.confirmationReasons(address: "192.0.2.2", ownCidr: "192.0.2.1/24", portCount: 1)
        XCTAssertFalse(within.contains("OUTSIDE_WIFI"))
        XCTAssertTrue(within.contains("NON_PRIVATE_ADDRESS"))
        let outside = domain.confirmationReasons(address: "198.51.100.1", ownCidr: "192.0.2.1/24", portCount: 1)
        XCTAssertTrue(outside.contains("OUTSIDE_WIFI"))
    }

    func testPermissionDeniedClassification() {
        XCTAssertTrue(LanSession.permissionDenied(.dns(-65570), localNetworkDenied: false))
        XCTAssertTrue(LanSession.permissionDenied(.posix(.EHOSTUNREACH), localNetworkDenied: true))
        XCTAssertFalse(LanSession.permissionDenied(.posix(.EHOSTUNREACH), localNetworkDenied: false))
        XCTAssertFalse(LanSession.permissionDenied(.dns(-65537), localNetworkDenied: false))
    }

    func testScanConsentTracksAllInputsAndLocalizedReasons() throws {
        let consent = ScanConsent(host: "192.0.2.1", ports: "22", cidr: "198.51.100.1/24")
        XCTAssertNotEqual(consent, ScanConsent(host: "192.0.2.2", ports: "22", cidr: consent.cidr))
        XCTAssertNotEqual(consent, ScanConsent(host: consent.host, ports: "23", cidr: consent.cidr))
        XCTAssertNotEqual(consent, ScanConsent(host: consent.host, ports: consent.ports, cidr: nil))
        for language in ["cs", "en"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))
            let resolve = { (key: String) in bundle.localizedString(forKey: key, value: nil, table: nil) }
            let message = consent.message(resolve: resolve)
            for key in ["lan.warning", "lan.reason.outside", "lan.reason.nonprivate"] {
                XCTAssertNotEqual(resolve(key), key)
                XCTAssertTrue(message.contains(resolve(key)), "Missing \(key) for \(language)")
            }
        }
    }

    func testTCPListenerAndClosedPort() throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        let listening = expectation(description: "localhost listener")
        listener.newConnectionHandler = { connection in
            connection.stateUpdateHandler = { state in
                if case .ready = state { DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { connection.cancel() } }
            }
            connection.start(queue: .main)
        }
        listener.stateUpdateHandler = { if case .ready = $0 { listening.fulfill() } }
        listener.start(queue: .main)
        // Loaded simulator runners can delay listener readiness; allow 20 s before probing.
        wait(for: [listening], timeout: 20)
        let openPort = try XCTUnwrap(listener.port?.rawValue)

        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { listener.cancel(); XCTFail("Could not create socket"); return }
        var fdOpen = true
        defer { if fdOpen { close(fd) }; listener.cancel() }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr = in_addr(s_addr: in_addr_t(INADDR_LOOPBACK).bigEndian)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0 else { XCTFail("Could not bind socket"); return }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        guard named == 0 else { XCTFail("Could not obtain port"); return }
        let closedPort = UInt16(bigEndian: address.sin_port)
        // Darwin can drop SYNs for a bound socket that is not listening; release it first.
        guard close(fd) == 0 else { fdOpen = false; XCTFail("Could not close socket"); return }
        fdOpen = false
        XCTAssertNotEqual(openPort, closedPort)
        let done = expectation(description: "port scan")
        var states: [UInt16: [String]] = [:]
        var refusedPorts = Set<UInt16>()
        let scan = PortScan(host: "127.0.0.1", ports: [openPort, closedPort]) { port, state in
            switch state {
            case .waiting(let error), .failed(let error):
                if case .posix(let code) = error {
                    states[port, default: []].append("\(String(reflecting: state)) POSIX=\(code.rawValue)")
                    if code == .ECONNREFUSED { refusedPorts.insert(port) }
                } else { states[port, default: []].append(String(reflecting: state)) }
            default: states[port, default: []].append(String(reflecting: state))
            }
        }
        var results: [UInt16: String] = [:]
        scan.start { port, result, complete in
            if let port, let result { results[port] = result }
            if complete { done.fulfill() }
        }
        wait(for: [done], timeout: 8)
        XCTAssertEqual(results[openPort], String(localized: "lan.open"))
        XCTAssertEqual(results[closedPort], String(localized: "lan.closed"),
                       "NWConnection states: \(states[closedPort] ?? [])")
        XCTAssertTrue(refusedPorts.contains(closedPort), "NWConnection states: \(states[closedPort] ?? [])")
    }

    func testBonjourStartCanBeCancelled() {
        let session = LanSession()
        let cancelled = expectation(description: "NWBrowser cancelled")
        session.browse(loopbackOnly: true) { state in
            if case .cancelled = state { cancelled.fulfill() }
        }
        session.cancel()
        let drained = expectation(description: "callbacks after cancellation")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { drained.fulfill() }
        wait(for: [cancelled, drained], timeout: 2)
        XCTAssertFalse(session.running)
    }

    func testDnsLocalMock() throws {
        let parameters = NWParameters.udp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        let ready = expectation(description: "DNS mock ready")
        listener.stateUpdateHandler = { if case .ready = $0 { ready.fulfill() } }
        listener.newConnectionHandler = { connection in
            connection.start(queue: .main)
            connection.receiveMessage { data, _, _, _ in
                guard var response = data, response.count >= 12 else { return }
                XCTAssertEqual(response[2] & 0x01, 0x01, "DNS query must request recursion")
                response[2] = 0x81; response[3] = 0x80
                response[6] = 0; response[7] = 1
                response.append(contentsOf: [0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 1, 0, 4, 192, 0, 2, 1])
                connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
            }
        }
        listener.start(queue: .main)
        defer { listener.cancel() }
        wait(for: [ready], timeout: 5)
        let port = try XCTUnwrap(listener.port?.rawValue)
        let answer = expectation(description: "DNS result")
        let session = LanSession()
        let observation = session.$results.sink { if $0.contains("192.0.2.1") { answer.fulfill() } }
        session.dns(host: "example.invalid", server: "127.0.0.1", serverPort: port)
        wait(for: [answer], timeout: 7)
        withExtendedLifetime(observation) { }
        session.cancel()
    }

    func testSharedDnsRejectsWrongID() throws {
        let domain = IosNetworkDomain()
        let query = try XCTUnwrap(domain.dnsQuery(host: "example.invalid", type: 1, id: 1))
        XCTAssertNil(domain.dnsResponse(packet: query, id: 2, host: "example.invalid", type: 1))
    }
}

final class WolDatagramTests: XCTestCase {
    func testSharedMagicPacketReachesLoopback() throws {
        let parameters = NWParameters.udp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        let ready = expectation(description: "UDP listener ready")
        let received = expectation(description: "magic packet received")
        var actual: Data?
        listener.stateUpdateHandler = { if case .ready = $0 { ready.fulfill() } }
        listener.newConnectionHandler = { connection in
            connection.start(queue: .main)
            connection.receiveMessage { data, _, _, _ in
                actual = data
                received.fulfill()
                connection.cancel()
            }
        }
        listener.start(queue: .main)
        defer { listener.cancel() }
        wait(for: [ready], timeout: 5)
        let port = try XCTUnwrap(listener.port)
        let mac = try XCTUnwrap(WolDomainKt.parseMac(input: "02:00:00:00:00:01"))
        let packet = WolDomainKt.magicPacket(mac: mac)
        let data = Data((0..<packet.size).map { UInt8(bitPattern: packet.get(index: $0)) })
        let sent = expectation(description: "UDP packet sent")
        let operation = WolDatagram { error in
            XCTAssertNil(error)
            sent.fulfill()
        }
        operation.start(connection: NWConnection(host: "127.0.0.1", port: port, using: .udp), payload: data)
        wait(for: [sent, received], timeout: 5)
        XCTAssertEqual(actual, data)
        XCTAssertEqual(data.count, 102)
        XCTAssertEqual(Array(data.prefix(6)), Array(repeating: 0xff, count: 6))
        for index in 0..<16 {
            XCTAssertEqual(Array(data[(6 + index * 6)..<(12 + index * 6)]), [2, 0, 0, 0, 0, 1])
        }
    }
}

final class IcmpPingTests: XCTestCase {
    func testCancelDuringProbeSuppressesCompletion() {
        let ping = IcmpPing()
        let started = expectation(description: "probe started")
        let gate = DispatchSemaphore(value: 0)
        let completed = expectation(description: "cancelled probe completion")
        completed.isInverted = true
        ping.onProbeStarted = { started.fulfill(); gate.wait() }
        ping.start(address: "127.0.0.1") { _ in completed.fulfill() }
        wait(for: [started], timeout: 5)
        ping.cancel()
        gate.signal()
        wait(for: [completed], timeout: 0.3)
    }

    func testRequestChecksumAndSharedReplyMatching() {
        let request = IcmpPing.makeRequest(payload: Array(repeating: 0x5a, count: 16))
        var sum: UInt32 = 0
        for index in stride(from: 0, to: request.count, by: 2) {
            sum += UInt32(request[index]) << 8 | UInt32(request[index + 1])
        }
        while sum >> 16 != 0 { sum = (sum & 0xffff) + (sum >> 16) }
        XCTAssertEqual(sum, 0xffff)
        var reply = request
        reply[0] = 0
        let sent = KotlinByteArray(size: Int32(request.count))
        let received = KotlinByteArray(size: Int32(reply.count))
        for (index, byte) in request.enumerated() { sent.set(index: Int32(index), value: Int8(bitPattern: byte)) }
        for (index, byte) in reply.enumerated() { received.set(index: Int32(index), value: Int8(bitPattern: byte)) }
        XCTAssertTrue(IosNetworkDomain().icmpReplyMatches(request: sent, reply: received))
        received.set(index: 8, value: 0)
        XCTAssertFalse(IosNetworkDomain().icmpReplyMatches(request: sent, reply: received))
    }

    func testLoopbackEcho() {
        let ping = IcmpPing()
        let done = expectation(description: "loopback ICMP echo")
        ping.start(address: "127.0.0.1") { result in
            if case .reply(let milliseconds) = result {
                XCTAssertGreaterThanOrEqual(milliseconds, 0)
            } else {
                XCTFail("Loopback did not return an ICMP echo reply")
            }
            done.fulfill()
        }
        wait(for: [done], timeout: 7)
    }

    func testCancelledBeforeStartDoesNotComplete() {
        let ping = IcmpPing()
        ping.cancel()
        let completed = expectation(description: "cancelled ping does not complete")
        completed.isInverted = true
        ping.start(address: "127.0.0.1") { _ in completed.fulfill() }
        wait(for: [completed], timeout: 0.3)
    }
}

final class WolPersistenceTests: XCTestCase {
    func testMissingFileErrorClassification() {
        let cocoaMissing = NSError(domain: NSCocoaErrorDomain, code: NSFileNoSuchFileError)
        let cocoaReadMissing = NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoSuchFileError)
        let posixMissing = NSError(domain: NSPOSIXErrorDomain, code: Int(ENOENT))
        let nestedCocoa4 = NSError(domain: NSCocoaErrorDomain, code: NSFileReadUnknownError,
                                   userInfo: [NSUnderlyingErrorKey: cocoaMissing])
        let nestedCocoa260 = NSError(domain: "fixture.wrapper", code: 1,
                                     userInfo: [NSUnderlyingErrorKey: cocoaReadMissing])
        let nestedPosix = NSError(domain: NSCocoaErrorDomain, code: NSFileReadUnknownError,
                                  userInfo: [NSUnderlyingErrorKey: posixMissing])
        let genericCocoaWrapper = NSError(domain: NSCocoaErrorDomain, code: 1,
                                          userInfo: [NSUnderlyingErrorKey: posixMissing])
        for error in [cocoaMissing, cocoaReadMissing, posixMissing, nestedCocoa4, nestedCocoa260, nestedPosix, genericCocoaWrapper] {
            XCTAssertTrue(WolPersistence.isMissingFile(error))
        }
        let denied = NSError(domain: NSPOSIXErrorDomain, code: Int(EACCES))
        let notDirectory = NSError(domain: NSPOSIXErrorDomain, code: Int(ENOTDIR))
        let ioError = NSError(domain: NSPOSIXErrorDomain, code: Int(EIO))
        let nestedDenied = NSError(domain: NSCocoaErrorDomain, code: NSFileReadUnknownError,
                                   userInfo: [NSUnderlyingErrorKey: denied])
        let explicitDenied = NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoPermissionError,
                                     userInfo: [NSUnderlyingErrorKey: posixMissing])
        let nestedIO = NSError(domain: NSCocoaErrorDomain, code: NSFileReadUnknownError,
                               userInfo: [NSUnderlyingErrorKey: ioError])
        for error in [denied, notDirectory, ioError, nestedDenied, nestedIO, explicitDenied] {
            XCTAssertFalse(WolPersistence.isMissingFile(error))
        }
    }

    func testEmptyRoundTripAndCorruptionDoesNotReset() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = WolPersistence(directoryOverride: directory)
        XCTAssertNil(store.load().failure)
        XCTAssertTrue(store.save(devices: []))
        XCTAssertNil(store.load().failure)
        let file = directory.appendingPathComponent("wol.json")
        let invalid = Data("invalid json".utf8)
        try invalid.write(to: file, options: .atomic)
        XCTAssertNotNil(store.load().failure)
        XCTAssertEqual(try Data(contentsOf: file), invalid)
    }

    func testMissingFileInExistingDirectoryIsEmpty() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let result = WolPersistence(directoryOverride: directory).load()
        XCTAssertNil(result.failure)
        XCTAssertTrue(result.devices.isEmpty)
    }

    func testProfileRoundTrip() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = WolPersistence(directoryOverride: directory)
        let device = WolDevice(id: "synthetic-1", name: "Test", mac: "02:00:00:00:00:01",
                               targetMode: .custom, customTarget: "192.0.2.1", port: 9,
                               probeHost: "192.0.2.2", probePort: KotlinInt(value: 22), note: "Test",
                               createdAt: 1000, updatedAt: 2000, repeatCount: 3, repeatIntervalMs: 250,
                               probeEnabled: true, probeDelaySeconds: 20)
        XCTAssertTrue(store.save(devices: [device]))
        let result = store.load()
        XCTAssertNil(result.failure)
        let loaded = try XCTUnwrap(result.devices.first)
        XCTAssertEqual(result.devices.count, 1)
        XCTAssertEqual(loaded.id, device.id)
        XCTAssertEqual(loaded.name, device.name)
        XCTAssertEqual(loaded.mac, device.mac)
        XCTAssertEqual(loaded.targetMode, device.targetMode)
        XCTAssertEqual(loaded.customTarget, device.customTarget)
        XCTAssertEqual(loaded.port, device.port)
        XCTAssertEqual(loaded.probeHost, device.probeHost)
        XCTAssertEqual(loaded.probePort, device.probePort)
        XCTAssertEqual(loaded.note, device.note)
        XCTAssertEqual(loaded.createdAt, device.createdAt)
        XCTAssertEqual(loaded.updatedAt, device.updatedAt)
        XCTAssertEqual(loaded.repeatCount, device.repeatCount)
        XCTAssertEqual(loaded.repeatIntervalMs, device.repeatIntervalMs)
        XCTAssertEqual(loaded.probeEnabled, device.probeEnabled)
        XCTAssertEqual(loaded.probeDelaySeconds, device.probeDelaySeconds)
    }

    func testOversizeFileIsRejectedWithoutReset() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("wol.json")
        let oversized = Data(repeating: 0x61, count: 1_048_577)
        try oversized.write(to: file)
        let store = WolPersistence(directoryOverride: directory)
        XCTAssertNotNil(store.load().failure)
        XCTAssertEqual(try Data(contentsOf: file), oversized)
    }

    func testStoreFailsWhenDirectoryIsAFile() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let original = Data("unchanged".utf8)
        try original.write(to: file)
        let store = WolPersistence(directoryOverride: file)
        XCTAssertEqual(store.load().failure, .io)
        XCTAssertEqual(try Data(contentsOf: file), original)
        XCTAssertFalse(store.save(devices: []))
        XCTAssertEqual(try Data(contentsOf: file), original)
    }
}

final class WolAddressPolicyTests: XCTestCase {
    func testNetworkIdentityChangesOnlyForInterfaceAddressOrMask() {
        let a = WolPlatform.NetworkIdentity(interfaceIndex: 1, address: 0xc0000201, mask: 0xffffff00)
        XCTAssertEqual(a, WolPlatform.NetworkIdentity(interfaceIndex: 1, address: 0xc0000201, mask: 0xffffff00))
        XCTAssertNotEqual(a, WolPlatform.NetworkIdentity(interfaceIndex: 2, address: 0xc0000201, mask: 0xffffff00))
        XCTAssertNotEqual(a, WolPlatform.NetworkIdentity(interfaceIndex: 1, address: 0xc0000202, mask: 0xffffff00))
        XCTAssertNotEqual(a, WolPlatform.NetworkIdentity(interfaceIndex: 1, address: 0xc0000201, mask: 0xffff0000))
    }

    func testOnlyLocalUnicastIsAllowed() {
        let address: UInt32 = 0xc000020a // 192.0.2.10
        let mask: UInt32 = 0xffffff00
        XCTAssertTrue(WolPlatform.permittedUnicast(0xc000020b, address: address, mask: mask))
        XCTAssertFalse(WolPlatform.permittedUnicast(0xc0000200, address: address, mask: mask))
        XCTAssertFalse(WolPlatform.permittedUnicast(0xc00002ff, address: address, mask: mask))
        XCTAssertFalse(WolPlatform.permittedUnicast(0xc6336401, address: address, mask: mask))
        XCTAssertFalse(WolPlatform.permittedUnicast(0xe0000001, address: address, mask: 0))
        XCTAssertFalse(WolPlatform.permittedUnicast(0xf0000001, address: address, mask: 0))
        XCTAssertFalse(WolPlatform.permittedUnicast(0x7f000001, address: address, mask: 0))
        XCTAssertFalse(WolPlatform.permittedUnicast(0x00000001, address: address, mask: 0))
        XCTAssertTrue(WolPlatform.permittedUnicast(0xc000020b, address: address, mask: 0xfffffffe))
    }
}
