@testable import NetKit
import XCTest
import Foundation
import CryptoKit
import NIOSSH
import NetKitShared

private final class PendingSshApproval {
    private let lock = NSLock()
    private var callback: ((Bool) -> Void)?
    func set(_ callback: @escaping (Bool) -> Void) { lock.lock(); self.callback = callback; lock.unlock() }
    func accept() { lock.lock(); let callback = self.callback; self.callback = nil; lock.unlock(); callback?(true) }
}

private final class SshTestDiagnostics {
    private let lock = NSLock()
    private var events: [String] = []

    func recordState(_ state: SshTransport.State) {
        let name: String
        switch state {
        case .connected: name = "state.connected"
        case .closed: name = "state.closed"
        case .failed: name = "state.failed"
        case .algorithmFailure: name = "state.algorithmFailure"
        }
        append(name)
    }

    func recordUnexpectedPrompt() { append("validator.unexpectedPrompt") }

    func recordFailure(_ failure: SshHostKeyValidator.Failure) {
        let name: String
        switch failure {
        case .invalid: name = "validator.invalid"
        case .changed: name = "validator.changed"
        case .rejected: name = "validator.rejected"
        case .storage: name = "validator.storage"
        case .cancelled: name = "validator.cancelled"
        case .timeout: name = "validator.timeout"
        }
        append(name)
    }

    private func append(_ event: String) {
        lock.lock(); events.append(event); lock.unlock()
    }
    func snapshot() -> [String] {
        lock.lock(); defer { lock.unlock() }
        return events
    }
}

final class SshIntegrationTests: XCTestCase {
    @MainActor
    func testSceneLifecycleDisconnectsAfterInjectedSleepInclusiveDeadline() async throws {
        let server = LocalSshServer()
        try await server.start()
        addTeardownBlock { try await server.stop() }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        defer { try? chain.deleteServiceItems() }
        var now: Int64 = 1000
        let model = SshViewModel(nowMillis: { now }, keychain: chain, directory: directory)
        let credential = server.password
        XCTAssertTrue(model.saveProfile(id: nil, name: "profile", host: "127.0.0.1", port: String(server.port),
                                        username: server.username, keyRef: nil, password: credential))
        let profile = try XCTUnwrap(model.data?.profiles.first)
        model.connect(profile)
        for _ in 0..<150 where model.hostPrompt == nil { try await Task.sleep(nanoseconds: 100_000_000) }
        let prompt = try XCTUnwrap(model.hostPrompt)
        model.answerHost(id: prompt.id, accept: true)
        for _ in 0..<150 where !model.connected { try await Task.sleep(nanoseconds: 100_000_000) }
        XCTAssertTrue(model.connected)
        model.background()
        now += 299_999
        model.foreground()
        XCTAssertTrue(model.connected)
        model.background()
        now += 300_000
        model.foreground()
        XCTAssertFalse(model.connected)
    }

    private func store() throws -> SshMetadataStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try SshMetadataStore(directory: directory)
    }

    private func roundTripCredential(_ raw: Data) throws -> Data {
        let keychain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        let adapter = IosSecretStoreAdapter(backend: KeychainSecretBackend(keychain: keychain))
        let id = UUID().uuidString
        addTeardownBlock { _ = adapter.deleteSecret(id: id) }
        let input = KotlinByteArray(size: Int32(raw.count))
        for (index, value) in raw.enumerated() { input.set(index: Int32(index), value: Int8(bitPattern: value)) }
        guard adapter.putSecret(id: id, secret: input) == nil else { throw SshTransportFailure.invalidCredential }
        let result = adapter.getSecret(id: id)
        guard result.failure == nil, let bytes = result.bytes else { throw SshTransportFailure.invalidCredential }
        return Data((0..<bytes.size).map { UInt8(bitPattern: bytes.get(index: $0)) })
    }

    func testPasswordTofuConfirmationPtyAndEcho() async throws {
        let server = LocalSshServer()
        try await server.start()
        addTeardownBlock { try await server.stop() }
        let metadata = try store()
        let prompt = expectation(description: "first host key requires explicit confirmation")
        let approval = PendingSshApproval()
        let publicParts = String(openSSHPublicKey: server.hostKey.publicKey).split(separator: " ")
        let rawKey = try XCTUnwrap(Data(base64Encoded: String(publicParts[1])))
        let expectedFingerprint = "SHA256:" + Data(SHA256.hash(data: rawKey)).base64EncodedString().replacingOccurrences(of: "=", with: "")
        let connected = expectation(description: "authenticated and shell ready")
        let echo = expectation(description: "PTY echo")
        var received = Data()
        let validator = SshHostKeyValidator(endpoint: "127.0.0.1:\(server.port)", store: metadata) { _, algorithm, fingerprint, _, _, reply in
            XCTAssertEqual(algorithm, "ssh-ed25519")
            XCTAssertEqual(fingerprint, expectedFingerprint)
            approval.set(reply)
            prompt.fulfill()
        }
        let passwordBytes = try roundTripCredential(Data(server.password.utf8))
        let password = try XCTUnwrap(String(data: passwordBytes, encoding: .utf8))
        let transport = try SshTransport(host: "127.0.0.1", port: server.port, username: server.username,
                                         credential: .password(password), validator: validator,
                                         output: { chunk in
                                             received.append(chunk)
                                             if received == Data("hello\n".utf8) { echo.fulfill() }
                                         },
                                         state: { if case .connected = $0 { connected.fulfill() } })
        addTeardownBlock { transport.close() }
        transport.start()
        await fulfillment(of: [prompt], timeout: 15)
        XCTAssertEqual(server.authSuccesses, 0)
        XCTAssertEqual(server.ptyRequests, 0)
        XCTAssertEqual(server.shellRequests, 0)
        approval.accept()
        await fulfillment(of: [connected], timeout: 15)
        XCTAssertEqual(server.ptyRequests, 1)
        XCTAssertEqual(server.shellRequests, 1)
        XCTAssertTrue(transport.send("hello\n"))
        await fulfillment(of: [echo], timeout: 5)
    }

    func testEd25519Authentication() async throws {
        let server = LocalSshServer()
        try await server.start()
        addTeardownBlock { try await server.stop() }
        let metadata = try store()
        let prompt = expectation(description: "host key prompt")
        let connected = expectation(description: "public key authentication")
        let validator = SshHostKeyValidator(endpoint: "127.0.0.1:\(server.port)", store: metadata) { _, _, _, _, _, reply in
            prompt.fulfill(); reply(true)
        }
        let keyBytes = try roundTripCredential(server.userKey.rawRepresentation)
        let transport = try SshTransport(host: "127.0.0.1", port: server.port, username: server.username,
                                         credential: .ed25519(keyBytes), validator: validator,
                                         output: { _ in }, state: { if case .connected = $0 { connected.fulfill() } })
        addTeardownBlock { transport.close() }
        transport.start()
        await fulfillment(of: [prompt, connected], timeout: 15, enforceOrder: true)
        XCTAssertEqual(server.ptyRequests, 1)
        XCTAssertEqual(server.shellRequests, 1)
    }

    func testChangedHostKeyIsRejectedWithoutAnotherPrompt() async throws {
        let first = LocalSshServer()
        try await first.start()
        addTeardownBlock { try await first.stop() }
        let port = first.port
        let metadata = try store()
        let initialPrompt = expectation(description: "first host key prompt")
        let firstConnected = expectation(description: "first connection")
        let firstValidator = SshHostKeyValidator(endpoint: "127.0.0.1:\(port)", store: metadata) { _, _, _, _, _, reply in
            initialPrompt.fulfill(); reply(true)
        }
        let original = try SshTransport(host: "127.0.0.1", port: port, username: first.username,
                                        credential: .password(first.password), validator: firstValidator,
                                        output: { _ in }, state: { if case .connected = $0 { firstConnected.fulfill() } })
        addTeardownBlock { original.close() }
        original.start()
        await fulfillment(of: [initialPrompt, firstConnected], timeout: 15, enforceOrder: true)
        original.close()
        try await first.stop()

        let changed = LocalSshServer()
        try await changed.start(port: port)
        addTeardownBlock { try await changed.stop() }
        let unexpectedPrompt = expectation(description: "changed key must not be offered for approval")
        unexpectedPrompt.isInverted = true
        let failed = expectation(description: "changed key is rejected")
        let changedWarning = expectation(description: "typed changed host-key warning")
        let diagnostics = SshTestDiagnostics()
        let changedValidator = SshHostKeyValidator(endpoint: "127.0.0.1:\(port)", store: metadata, onFailure: { failure in
            diagnostics.recordFailure(failure)
            if case .changed = failure { changedWarning.fulfill() }
        }) { _, _, _, _, _, reply in
            diagnostics.recordUnexpectedPrompt()
            unexpectedPrompt.fulfill(); reply(true)
        }
        let retry = try SshTransport(host: "127.0.0.1", port: port, username: changed.username,
                                     credential: .password(changed.password), validator: changedValidator,
                                     output: { _ in }, state: { state in
                                         diagnostics.recordState(state)
                                         if case .failed = state { failed.fulfill() }
                                     })
        addTeardownBlock { retry.close() }
        retry.start()
        await fulfillment(of: [failed, changedWarning], timeout: 15)
        await fulfillment(of: [unexpectedPrompt], timeout: 1)
        let events = diagnostics.snapshot()
        if !events.contains("state.failed") || !events.contains("validator.changed") || events.contains("validator.unexpectedPrompt") ||
            changed.authSuccesses != 0 || changed.ptyRequests != 0 || changed.shellRequests != 0 {
            print("SSH_DIAGNOSTICS events=[\(events.joined(separator: ", "))] auth=\(changed.authSuccesses) pty=\(changed.ptyRequests) shell=\(changed.shellRequests)")
        }
        XCTAssertEqual(changed.authSuccesses, 0)
        XCTAssertEqual(changed.ptyRequests, 0)
        XCTAssertEqual(changed.shellRequests, 0)
    }
    func testLateHostApprovalAfterCancelCannotAuthenticateOrPersist() async throws {
        let server = LocalSshServer()
        try await server.start()
        addTeardownBlock { try await server.stop() }
        let metadata = try store()
        let prompt = expectation(description: "host key prompt")
        let approval = PendingSshApproval()
        let validator = SshHostKeyValidator(endpoint: "127.0.0.1:\(server.port)", store: metadata) { _, _, _, _, _, reply in
            approval.set(reply); prompt.fulfill()
        }
        let transport = try SshTransport(host: "127.0.0.1", port: server.port, username: server.username,
                                         credential: .password(server.password), validator: validator,
                                         output: { _ in }, state: { _ in })
        addTeardownBlock { transport.close() }
        transport.start()
        await fulfillment(of: [prompt], timeout: 15)
        transport.close()
        approval.accept()
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertTrue(metadata.snapshot().hostKeys.isEmpty)
        XCTAssertEqual(server.authSuccesses, 0)
    }

    func testHostPromptTimeoutDoesNotAuthenticate() async throws {
        let server = LocalSshServer()
        try await server.start()
        addTeardownBlock { try await server.stop() }
        let metadata = try store()
        let timedOut = expectation(description: "host prompt timed out")
        let validator = SshHostKeyValidator(endpoint: "127.0.0.1:\(server.port)", store: metadata,
                                             timeout: 0.25, onFailure: { error in
            if case .timeout = error { timedOut.fulfill() }
        }) { _, _, _, _, _, _ in }
        let transport = try SshTransport(host: "127.0.0.1", port: server.port, username: server.username,
                                         credential: .password(server.password), validator: validator,
                                         output: { _ in }, state: { _ in })
        addTeardownBlock { transport.close() }
        transport.start()
        await fulfillment(of: [timedOut], timeout: 15)
        XCTAssertEqual(server.authSuccesses, 0)
        XCTAssertTrue(metadata.snapshot().hostKeys.isEmpty)
    }

    func testRemoteChildCloseEndsSession() async throws {
        let server = LocalSshServer()
        try await server.start()
        addTeardownBlock { try await server.stop() }
        let metadata = try store()
        let connected = expectation(description: "shell ready")
        let closed = expectation(description: "remote child closed")
        let validator = SshHostKeyValidator(endpoint: "127.0.0.1:\(server.port)", store: metadata) { _, _, _, _, _, reply in reply(true) }
        let transport = try SshTransport(host: "127.0.0.1", port: server.port, username: server.username,
                                         credential: .password(server.password), validator: validator,
                                         output: { _ in }, state: { state in
            if case .connected = state { connected.fulfill() }
            if case .closed = state { closed.fulfill() }
        })
        addTeardownBlock { transport.close() }
        transport.start()
        await fulfillment(of: [connected], timeout: 15)
        try await server.closeSessions()
        await fulfillment(of: [closed], timeout: 10)
    }

    func testTrustedHostReconnectsWithoutPrompt() async throws {
        let server = LocalSshServer()
        try await server.start()
        addTeardownBlock { try await server.stop() }
        let metadata = try store()
        let prompt = expectation(description: "initial host prompt")
        let firstReady = expectation(description: "initial shell ready")
        let firstValidator = SshHostKeyValidator(endpoint: "127.0.0.1:\(server.port)", store: metadata) { _, _, _, _, _, reply in
            prompt.fulfill(); reply(true)
        }
        let first = try SshTransport(host: "127.0.0.1", port: server.port, username: server.username,
                                     credential: .password(server.password), validator: firstValidator,
                                     output: { _ in }, state: { if case .connected = $0 { firstReady.fulfill() } })
        addTeardownBlock { first.close() }
        first.start()
        await fulfillment(of: [prompt, firstReady], timeout: 15, enforceOrder: true)
        first.close()
        let unexpected = expectation(description: "trusted key must not prompt again")
        unexpected.isInverted = true
        let secondReady = expectation(description: "trusted reconnect shell ready")
        let secondValidator = SshHostKeyValidator(endpoint: "127.0.0.1:\(server.port)", store: metadata) { _, _, _, _, _, reply in
            unexpected.fulfill(); reply(false)
        }
        let second = try SshTransport(host: "127.0.0.1", port: server.port, username: server.username,
                                      credential: .password(server.password), validator: secondValidator,
                                      output: { _ in }, state: { if case .connected = $0 { secondReady.fulfill() } })
        addTeardownBlock { second.close() }
        second.start()
        await fulfillment(of: [secondReady], timeout: 15)
        await fulfillment(of: [unexpected], timeout: 1)
        XCTAssertEqual(server.authSuccesses, 2)
        XCTAssertEqual(server.ptyRequests, 2)
        XCTAssertEqual(server.shellRequests, 2)
    }

}
