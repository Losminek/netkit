@testable import NetKit
import Foundation
import CryptoKit
import Combine
import NIOSSH
import NIOCore
import NIOPosix
import NetKitShared
import XCTest

final class SshMetadataStoreTests: XCTestCase {
    func testBackgroundDeadlineUsesInjectedSleepInclusiveClock() {
        var now: Int64 = 1000
        let lifecycle = IosSshLifecycle(nowMillis: { KotlinLong(value: now) })
        lifecycle.background()
        now += 299_999
        XCTAssertFalse(lifecycle.expired())
        now += 1
        XCTAssertTrue(lifecycle.foreground())
    }

    func testProfileEndpointIsSharedCanonicalValue() throws {
        let profile = try XCTUnwrap(IosSshDomain().profile(id: "id", name: "test", host: "2001:db8::1", port: 2222,
            username: "user", authentication: .password, secretRef: nil, keyRef: nil, startupCommand: "", keepaliveSeconds: 30))
        XCTAssertEqual(profile.endpoint, "[2001:db8::1]:2222")
    }

    @MainActor
    func testRejectedHostContextAndPromptMessage() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try SshMetadataStore(directory: directory)
        let first = try offered()
        let other = try offered()
        try store.reject(first)
        let repeated = store.rejectionContext(first)
        XCTAssertTrue(repeated.repeated)
        XCTAssertEqual(repeated.previous?.fingerprint, first.fingerprint)
        let different = store.rejectionContext(other)
        XCTAssertFalse(different.repeated)
        XCTAssertEqual(different.previous?.fingerprint, first.fingerprint)
        let prompt = SshViewModel.HostPrompt(endpoint: first.endpoint, algorithm: first.algorithm,
            fingerprint: first.fingerprint, previousRejected: repeated.previous?.fingerprint,
            repeatedRejected: repeated.repeated, reply: { _ in })
        let message = sshHostPromptMessage(prompt)
        XCTAssertTrue(message.contains(first.endpoint))
        XCTAssertTrue(message.contains(first.fingerprint))
        XCTAssertTrue(message.contains(String(localized: "ssh.host.rejected.again")))
    }

    func testChangedHostWarningSurvivesPersistenceFailure() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try SshMetadataStore(directory: directory)
        let original = try offered()
        try store.trustUnknown(original)
        try FileManager.default.removeItem(at: directory)
        try Data(UUID().uuidString.utf8).write(to: directory)
        let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        defer { try? group.syncShutdownGracefully() }
        let reported = expectation(description: "changed host warning despite storage failure")
        let rejected = expectation(description: "validation fails closed")
        let validator = SshHostKeyValidator(endpoint: original.endpoint, store: store, onFailure: { failure in
            if case .changed = failure { reported.fulfill() }
        }) { _, _, _, _, _, reply in XCTFail("Changed host must not prompt"); reply(false) }
        let promise = group.next().makePromise(of: Void.self)
        promise.futureResult.whenComplete { result in if case .failure = result { rejected.fulfill() } }
        validator.validateHostKey(hostKey: NIOSSHPrivateKey(ed25519Key: Curve25519.Signing.PrivateKey()).publicKey,
                                  validationCompletePromise: promise)
        await fulfillment(of: [reported, rejected], timeout: 5)
        XCTAssertEqual(store.snapshot().hostKeys.first?.fingerprint, original.fingerprint)
    }
    func testUnsupportedAlgorithmsAreDistinguishedFromOtherFailures() {
        XCTAssertTrue(sshAlgorithmNegotiationFailure(.unknownPublicKey))
        XCTAssertTrue(sshAlgorithmNegotiationFailure(.unknownSignature))
        XCTAssertTrue(sshAlgorithmNegotiationFailure(.keyExchangeNegotiationFailure))
        XCTAssertFalse(sshAlgorithmNegotiationFailure(.tcpShutdown))
        XCTAssertFalse(sshAlgorithmNegotiationFailure(.invalidExchangeHashSignature))
        XCTAssertFalse(sshAlgorithmNegotiationFailure(.invalidHostKeyForKeyExchange))
        XCTAssertTrue(sshSupportedCredentialKey("ssh-ed25519 example"))
        XCTAssertFalse(sshSupportedCredentialKey("ssh-rsa example"))
        XCTAssertFalse(sshSupportedCredentialKey("ecdsa-sha2-nistp256 example"))
    }

    private func offered() throws -> TrustedHostKey {
        let key = NIOSSHPrivateKey(ed25519Key: Curve25519.Signing.PrivateKey()).publicKey
        let parts = String(openSSHPublicKey: key).split(separator: " ")
        let raw = try XCTUnwrap(Data(base64Encoded: String(parts[1])))
        let fingerprint = "SHA256:" + Data(SHA256.hash(data: raw)).base64EncodedString().replacingOccurrences(of: "=", with: "")
        return TrustedHostKey(endpoint: "[192.0.2.1]:22", algorithm: String(parts[0]),
                              publicKey: String(parts[1]), fingerprint: fingerprint)
    }

    func testFirstTrustPersistsChangedKeyRejectedAndResetIsExplicit() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let first = try offered()
        let changed = try offered()
        let store = try SshMetadataStore(directory: directory)
        XCTAssertEqual(store.decision(first), .unknown)
        try store.trustUnknown(first)
        XCTAssertEqual(store.decision(first), .trusted)
        XCTAssertEqual(store.decision(changed), .changed)
        XCTAssertThrowsError(try store.trustUnknown(changed))
        try store.reject(changed)
        let reopened = try SshMetadataStore(directory: directory)
        XCTAssertEqual(reopened.decision(first), .trusted)
        XCTAssertEqual(reopened.decision(changed), .changed)
        try reopened.forget(endpoint: first.endpoint)
        XCTAssertEqual(reopened.decision(changed), .unknown)
        XCTAssertEqual(reopened.snapshot().rejectedHostKeys.count, 1)
    }

    func testCorruptMetadataFailsClosed() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("ssh.json")
        let contents = Data("invalid".utf8)
        try contents.write(to: file)
        XCTAssertThrowsError(try SshMetadataStore(directory: directory))
        XCTAssertEqual(try Data(contentsOf: file), contents)
    }
}

/// Profile exchange and connection gating use synthetic metadata and no network transport.
final class SshProfileExchangeTests: XCTestCase {
    private let domain = IosSshDomain()

    private func profile(_ id: String = "profile-a", name: String = "Target", authentication: SshAuthentication = .password,
                         secretRef: String? = nil, keyRef: String? = nil, command: String = "") throws -> SshProfile {
        try XCTUnwrap(domain.profile(id: id, name: name, host: "192.0.2.1", port: 22, username: "tester",
            authentication: authentication, secretRef: secretRef, keyRef: keyRef, startupCommand: command, keepaliveSeconds: 30))
    }

    private func bytes(_ value: Data) -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(value.count))
        for (index, byte) in value.enumerated() { result.set(index: Int32(index), value: Int8(bitPattern: byte)) }
        return result
    }

    private func raw(_ value: KotlinByteArray) -> Data {
        Data((0..<value.size).map { UInt8(bitPattern: value.get(index: $0)) })
    }

    private func document(schema: Int = 2, authentication: String = "KEY", command: String = "show version",
                          host: String = "192.0.2.1", port: Int = 22, username: String = "tester", keepalive: Int = 30) throws -> Data {
        var row: [String: Any] = ["alias": "Target", "host": host, "port": port, "username": username]
        if schema == 2 {
            row["authentication"] = authentication; row["startupCommand"] = command; row["keepaliveSeconds"] = keepalive
        }
        return try JSONSerialization.data(withJSONObject: ["format": "netkit-ssh-profiles", "schema": schema,
                                                           "profiles": [row], "skipped": []])
    }

    private func withStorage(_ body: (URL, KeychainSecrets) throws -> Void) throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root); try? chain.deleteServiceItems() }
        try body(root, chain)
    }

    func testBridgeExportV2IsOrderedPortableAndDecodable() throws {
        try withStorage { root, _ in
            let store = try SshMetadataStore(directory: root)
            try store.save(profile: profile(secretRef: "credential-a", keyRef: "key-a", command: "show version"))
            try store.save(profile: profile("profile-b", name: "Second", authentication: .key))
            try store.save(key: SshKeyInfo(id: "key-a", name: "Key", publicKey: "public"))
            let result = store.prepareExport()
            XCTAssertNil(result.failure)
            let encoded = try XCTUnwrap(result.bytes)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: raw(encoded)) as? [String: Any])
            XCTAssertEqual(json["schema"] as? Int, 2)
            XCTAssertEqual(json["format"] as? String, "netkit-ssh-profiles")
            let rows = try XCTUnwrap(json["profiles"] as? [[String: Any]])
            XCTAssertEqual(rows.compactMap { $0["alias"] as? String }, ["Target", "Second"])
            for row in rows {
                XCTAssertEqual(Set(row.keys), Set(["alias", "host", "port", "username", "authentication", "startupCommand", "keepaliveSeconds"]))
            }
            XCTAssertEqual(Set(json.keys), Set(["format", "schema", "source", "profiles", "skipped"]))
            XCTAssertEqual(domain.decodeImport(bytes: encoded).imported?.profiles.count, 2)
        }
    }

    @MainActor
    func testPreparedExportIsFrozenAndCancelledExplicitly() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            try store.save(profile: profile())
            let model = SshViewModel(keychain: chain, directory: root)
            let prepared = try XCTUnwrap(model.prepareExport())
            XCTAssertTrue(model.saveProfile(id: "profile-a", name: "Renamed", host: "192.0.2.2", port: "22", username: "tester", keyRef: nil, password: ""))
            XCTAssertEqual(model.exportData, prepared)
            XCTAssertNotEqual(model.prepareExport(), prepared)
            model.cancelExport()
            XCTAssertNil(model.exportData)
        }
    }

    @MainActor
    func testExportDuplicateNamesAndSizeNeverProduceBytes() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            try store.save(profile: profile())
            let model = SshViewModel(keychain: chain, directory: root)
            XCTAssertNotNil(model.prepareExport())
            XCTAssertTrue(model.saveProfile(id: nil, name: "Target", host: "192.0.2.2", port: "22", username: "tester", keyRef: nil, password: ""))
            XCTAssertNil(model.prepareExport())
            XCTAssertEqual(model.exportFailure, .duplicateNames(["Target"]))
            XCTAssertNil(model.exportData)
        }
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            for index in 1...65 {
                try store.save(profile: profile("p-\(index)", name: "Target \(index)", command: String(repeating: "x", count: 4096)))
            }
            let model = SshViewModel(keychain: chain, directory: root)
            XCTAssertNil(model.prepareExport())
            XCTAssertEqual(model.exportFailure, .tooLarge)
            XCTAssertNil(model.exportData)
        }
    }

    func testBridgeImportAcceptsV1V2LegacyAndRejectsCredentialBackup() throws {
        try withStorage { root, _ in
            let store = try SshMetadataStore(directory: root)
            let legacy: [String: Any] = ["schema": 1, "profiles": [["id": "old-id", "name": "Target", "host": "192.0.2.1", "port": 22,
                "username": "tester", "authentication": "KEY", "startupCommand": "", "keepaliveSeconds": 30,
                "secretRef": NSNull(), "keyRef": NSNull()]], "keys": [], "hostKeys": [], "rejectedHostKeys": []]
            let legacyRaw = try JSONSerialization.data(withJSONObject: legacy)
            for input in [try document(schema: 1), try document(), legacyRaw] {
                let plan = try store.previewImport(input)
                let preview = try XCTUnwrap(domain.importProfilePreviews(plan: plan, data: store.snapshot()).first)
                XCTAssertNil(preview.before)
                XCTAssertNotEqual(preview.after.id, "old-id")
                XCTAssertNil(preview.after.secretRef)
                XCTAssertNil(preview.after.keyRef)
            }
            for field in ["keys", "hostKeys", "rejectedHostKeys"] {
                var backup = legacy; backup[field] = [["id": "not-portable"]]
                XCTAssertThrowsError(try store.previewImport(JSONSerialization.data(withJSONObject: backup)))
            }
        }
    }

    @MainActor
    func testImportKeyChoiceReplansNewProfilesAndWarnings() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            try store.save(key: SshKeyInfo(id: "key-a", name: "Key", publicKey: "public"))
            let model = SshViewModel(keychain: chain, directory: root)
            let file = root.appendingPathComponent("import.json")
            try document(schema: 1).write(to: file)
            model.previewImport(file)
            var preview = try XCTUnwrap(model.importPreviews(try XCTUnwrap(model.importPlan)).first)
            XCTAssertEqual(preview.after.authentication, .password)
            model.selectImportKey("key-a")
            preview = try XCTUnwrap(model.importPreviews(try XCTUnwrap(model.importPlan)).first)
            XCTAssertEqual(preview.after.authentication, .key)
            XCTAssertEqual(preview.after.keyRef, "key-a")
            XCTAssertFalse(preview.needsKeySelection)
            model.selectImportKey(nil)
            XCTAssertEqual(model.importPreviews(try XCTUnwrap(model.importPlan)).first?.after.authentication, .password)
            try document().write(to: file)
            model.previewImport(file)
            preview = try XCTUnwrap(model.importPreviews(try XCTUnwrap(model.importPlan)).first)
            XCTAssertTrue(preview.needsKeySelection)
            XCTAssertTrue(preview.startupWarning)
            model.selectImportKey("key-a")
            XCTAssertFalse(model.importPreviews(try XCTUnwrap(model.importPlan)).first?.needsKeySelection ?? true)
            model.selectImportKey("missing-key")
            XCTAssertNil(model.importPlan)
            model.selectImportKey(nil)
            XCTAssertNotNil(model.importPlan)
            model.cancelImport()
            XCTAssertNil(model.importPlan)
            XCTAssertNil(model.importKeyRef)
        }
    }

    @MainActor
    func testImportPreviewShowsSixChangesAndDeletesPasswordOnlyOnConfirmation() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            let reference = UUID().uuidString
            let password = Data(UUID().uuidString.utf8)
            try store.save(profile: profile(secretRef: reference))
            try chain.put(reference, secret: password)
            let model = SshViewModel(keychain: chain, directory: root)
            let file = root.appendingPathComponent("import.json")
            try document(host: "192.0.2.2", port: 2222, username: "operator", keepalive: 60).write(to: file)
            model.previewImport(file)
            let plan = try XCTUnwrap(model.importPlan)
            let preview = try XCTUnwrap(model.importPreviews(plan).first)
            XCTAssertEqual(preview.changes.map(\.field), [.host, .port, .username, .authentication, .startupCommand, .keepaliveSeconds])
            XCTAssertEqual(preview.changes.map(\.before), ["192.0.2.1", "22", "tester", "PASSWORD", "", "30"])
            XCTAssertEqual(preview.changes.map(\.after), ["192.0.2.2", "2222", "operator", "KEY", "show version", "60"])
            XCTAssertTrue(preview.startupWarning)
            XCTAssertTrue(preview.needsKeySelection)
            XCTAssertEqual(try chain.get(reference), password)
            model.confirmImport()
            XCTAssertNil(try chain.get(reference))
            XCTAssertNil(model.data?.profiles.first?.secretRef)
            XCTAssertEqual(model.data?.profiles.first?.authentication, .key)
        }
    }

    @MainActor
    func testAuthenticationOnlyImportCleansKeychainAfterPersistAndKeepsItOnFailure() throws {
        for failPersistence in [false, true] {
            try withStorage { root, chain in
                let store = try SshMetadataStore(directory: root)
                let reference = UUID().uuidString
                let password = Data(UUID().uuidString.utf8)
                try store.save(profile: profile(secretRef: reference))
                try chain.put(reference, secret: password)
                let model = SshViewModel(keychain: chain, directory: root)
                let file = root.appendingPathComponent("import.json")
                try document(command: "").write(to: file)
                model.previewImport(file)
                let preview = try XCTUnwrap(model.importPreviews(try XCTUnwrap(model.importPlan)).first)
                XCTAssertEqual(preview.changes.map(\.field), [.authentication])
                if failPersistence {
                    try FileManager.default.removeItem(at: root)
                    try Data(UUID().uuidString.utf8).write(to: root)
                }
                model.confirmImport()
                if failPersistence {
                    XCTAssertEqual(try chain.get(reference), password)
                    XCTAssertEqual(model.data?.profiles.first?.secretRef, reference)
                    XCTAssertNotNil(model.error)
                } else {
                    XCTAssertNil(try chain.get(reference))
                    XCTAssertNil(try SshMetadataStore(directory: root).snapshot().profiles.first?.secretRef)
                }
            }
        }
    }

    @MainActor
    func testStaleImportAndInvalidReplacementNeverApplyOldPlanOrDeletePassword() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            let reference = UUID().uuidString
            let password = Data(UUID().uuidString.utf8)
            try store.save(profile: profile(secretRef: reference))
            try chain.put(reference, secret: password)
            let model = SshViewModel(keychain: chain, directory: root)
            let file = root.appendingPathComponent("import.json")
            try document().write(to: file)
            model.previewImport(file)
            XCTAssertTrue(model.saveProfile(id: "profile-a", name: "Edited", host: "192.0.2.1", port: "22", username: "tester", keyRef: nil, password: ""))
            model.confirmImport()
            XCTAssertNotNil(model.error)
            XCTAssertEqual(try chain.get(reference), password)
            model.previewImport(file)
            XCTAssertNotNil(model.importPlan)
            try Data("invalid".utf8).write(to: file)
            model.previewImport(file)
            XCTAssertNil(model.importPlan)
            model.confirmImport()
            XCTAssertEqual(model.data?.profiles.first?.name, "Edited")
        }
    }

    @MainActor
    func testArrowMovementBoundsPreviewLockAndRestartExportOrder() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            try store.save(profile: profile())
            try store.save(profile: profile("profile-b", name: "Second"))
            let model = SshViewModel(keychain: chain, directory: root)
            XCTAssertFalse(model.canMoveProfile(id: "profile-a", delta: -1))
            XCTAssertFalse(model.canMoveProfile(id: "profile-b", delta: 1))
            XCTAssertFalse(model.canMoveProfile(id: "profile-a", delta: 2))
            XCTAssertFalse(model.canMoveProfile(id: "missing", delta: 1))
            var operationBlockedMovement = false
            let observation = model.$exportData.dropFirst().sink { _ in
                operationBlockedMovement = !model.canMoveProfile(id: "profile-a", delta: 1)
                model.moveProfile(id: "profile-a", delta: 1)
            }
            XCTAssertNotNil(model.prepareExport())
            XCTAssertTrue(operationBlockedMovement)
            XCTAssertEqual(model.data?.profiles.map(\.id), ["profile-a", "profile-b"])
            observation.cancel()
            model.moveProfile(id: "profile-a", delta: -1)
            model.moveProfile(id: "profile-b", delta: -1)
            XCTAssertEqual(model.data?.profiles.map(\.id), ["profile-b", "profile-a"])
            model.moveProfile(id: "profile-b", delta: 1)
            XCTAssertEqual(model.data?.profiles.map(\.id), ["profile-a", "profile-b"])
            model.moveProfile(id: "profile-a", delta: 1)
            let reopened = SshViewModel(keychain: chain, directory: root)
            XCTAssertEqual(reopened.data?.profiles.map(\.id), ["profile-b", "profile-a"])
            let prepared = try XCTUnwrap(reopened.prepareExport())
            XCTAssertEqual(domain.decodeImport(bytes: bytes(prepared)).imported?.profiles.map(\.alias), ["Second", "Target"])
            let file = root.appendingPathComponent("import.json")
            try document(schema: 1).write(to: file)
            reopened.previewImport(file)
            XCTAssertFalse(reopened.canMoveProfile(id: "profile-b", delta: 1))
            reopened.moveProfile(id: "profile-b", delta: 1)
            XCTAssertEqual(reopened.data?.profiles.map(\.id), ["profile-b", "profile-a"])
        }
    }

    @MainActor
    func testMissingAndDeletedKeyBlockConnectBeforeSessionFactory() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            try store.save(key: SshKeyInfo(id: "other-key", name: "Other", publicKey: "public"))
            let missing = try profile(authentication: .key)
            let deleted = try profile("profile-b", name: "Deleted key", authentication: .key, keyRef: "deleted-key")
            try store.save(profile: missing); try store.save(profile: deleted)
            var attempts = 0
            let model = SshViewModel(keychain: chain, directory: root, sessionFactory: { _, _, _, _, _ in
                attempts += 1; throw SshTransportFailure.unavailable
            })
            for candidate in [missing, deleted] {
                model.connect(candidate)
                XCTAssertEqual(model.keySelectionRequired?.id, candidate.id)
                XCTAssertNil(model.error)
                XCTAssertFalse(model.connected)
                XCTAssertNil(model.terminalSnapshot)
                XCTAssertTrue(model.needsKeySelection(candidate))
                XCTAssertEqual(model.profileForKeySelection()?.id, candidate.id)
                XCTAssertEqual(model.profileForKeySelection(candidate)?.id, candidate.id) // Alert state may already be dismissed.
                XCTAssertFalse(model.saveProfile(id: candidate.id, name: candidate.name, host: candidate.host, port: "22", username: "tester", keyRef: nil, password: ""))
                XCTAssertEqual(model.data?.profiles.first(where: { $0.id == candidate.id })?.authentication, .key)
            }
            XCTAssertEqual(attempts, 0)
            model.generateKey(name: "Replacement")
            let key = try XCTUnwrap(model.data?.keys.last)
            XCTAssertTrue(model.saveProfile(id: missing.id, name: missing.name, host: missing.host, port: "22", username: "tester", keyRef: key.id, password: ""))
            XCTAssertFalse(model.needsKeySelection(try XCTUnwrap(model.data?.profiles.first)))
        }
    }

    @MainActor
    func testDeletedProfileCannotBeOpenedSavedOrConnectedFromPendingPrompt() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            let missing = try profile(authentication: .key)
            try store.save(profile: missing)
            let model = SshViewModel(keychain: chain, directory: root, sessionFactory: { _, _, _, _, _ in
                XCTFail("Deleted profile must not create a transport"); throw SshTransportFailure.unavailable
            })
            model.connect(missing)
            model.deleteProfile(missing)
            XCTAssertNil(model.keySelectionRequired)
            model.keySelectionRequired = missing // A delayed alert still holds the old request.
            XCTAssertNil(model.profileForKeySelection())
            XCTAssertNil(model.profileForKeySelection(missing)) // The captured alert value is checked against live storage too.
            XCTAssertTrue(model.profileUnavailable)
            XCTAssertNil(model.keySelectionRequired)
            XCTAssertFalse(model.saveProfile(id: missing.id, name: missing.name, host: missing.host, port: "22", username: "tester", keyRef: nil, password: ""))
            model.connect(missing)
            XCTAssertTrue(model.profileUnavailable)
            XCTAssertTrue(try SshMetadataStore(directory: root).snapshot().profiles.isEmpty)
        }
    }

    @MainActor
    func testReconnectChecksMissingKeyAndUsesExactConfirmedProfileWithoutTransport() throws {
        try withStorage { root, chain in
            let store = try SshMetadataStore(directory: root)
            let reference = UUID().uuidString
            try store.save(profile: profile(secretRef: reference))
            try chain.put(reference, secret: Data(UUID().uuidString.utf8))
            var attempts: [SshProfile] = []
            let fake = ProfileTestSession()
            let model = SshViewModel(keychain: chain, directory: root, sessionFactory: { profile, _, _, _, state in
                attempts.append(profile)
                return (fake, { state(.closed) })
            })
            model.connect(try XCTUnwrap(model.data?.profiles.first))
            XCTAssertEqual(attempts.count, 1)
            let file = root.appendingPathComponent("import.json")
            try document(command: "").write(to: file)
            model.previewImport(file); model.confirmImport()
            let confirmed = try XCTUnwrap(model.reconnectProfile)
            XCTAssertEqual(confirmed.authentication, .key)
            model.reconnect(confirmed)
            XCTAssertEqual(model.keySelectionRequired?.id, "profile-a")
            XCTAssertEqual(attempts.count, 1)
            XCTAssertTrue(model.saveProfile(id: "profile-a", name: "Target", host: "192.0.2.1", port: "22",
                username: "tester", keyRef: nil, password: UUID().uuidString, authentication: .password))
            try document(authentication: "PASSWORD", command: "show version").write(to: file)
            model.previewImport(file); model.confirmImport()
            let confirmedPassword = try XCTUnwrap(model.reconnectProfile)
            try document(authentication: "PASSWORD", command: "show clock", keepalive: 60).write(to: file)
            model.previewImport(file); model.confirmImport()
            model.reconnect(confirmedPassword)
            XCTAssertEqual(attempts.count, 2)
            XCTAssertEqual(attempts.last?.host, "192.0.2.1")
            XCTAssertEqual(attempts.last?.startupCommand, "show version")
            XCTAssertEqual(model.data?.profiles.first?.startupCommand, "show clock")
            model.deleteProfile(try XCTUnwrap(model.data?.profiles.first))
            model.reconnect(confirmedPassword)
            XCTAssertTrue(model.profileUnavailable)
            XCTAssertNil(model.keySelectionRequired)
            XCTAssertEqual(attempts.count, 2)
            model.disconnect()
        }
    }
}

private final class ProfileTestSession: NSObject, SshSession {
    func send(text: String) -> Bool { true }
    func resize(columns: Int32, rows: Int32) {}
    func close() {}
}

final class SshProfileEditorTests: XCTestCase {
    func testInteractiveDraftRequiresExplicitSupportedChoiceAndKeyDraftDoesNotFallBack() throws {
        let domain = IosSshDomain()
        let interactive = try XCTUnwrap(domain.profile(id: "interactive", name: "Target", host: "192.0.2.1", port: 22,
            username: "tester", authentication: .interactive, secretRef: nil, keyRef: nil, startupCommand: "", keepaliveSeconds: 30))
        var draft = SshProfileDraft(profile: interactive)
        XCTAssertNil(draft.authentication)
        draft.authenticationChoice = "PASSWORD"
        XCTAssertEqual(draft.authentication, .password)
        draft.authenticationChoice = "KEY"
        XCTAssertEqual(draft.authentication, .key)
        XCTAssertTrue(draft.needsKeySelection(keys: [SshKeyInfo(id: "other-key", name: "Other", publicKey: "public")]))
        draft.keyRef = "other-key"
        XCTAssertFalse(draft.needsKeySelection(keys: [SshKeyInfo(id: "other-key", name: "Other", publicKey: "public")]))
        let keyProfile = try XCTUnwrap(domain.profile(id: "key-profile", name: "Key target", host: "192.0.2.1", port: 22,
            username: "tester", authentication: .key, secretRef: nil, keyRef: "deleted-key", startupCommand: "", keepaliveSeconds: 30))
        let missing = SshProfileDraft(profile: keyProfile)
        XCTAssertEqual(missing.authentication, .key)
        XCTAssertTrue(missing.needsKeySelection(keys: []))
    }

    @MainActor
    func testInteractiveImportShowsSpecificErrorWithoutTransportAndCanBeSavedAsPasswordOrKey() throws {
        let choices: [SshAuthentication] = [.password, .key]
        for choice in choices {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: root); try? chain.deleteServiceItems() }
            let store = try SshMetadataStore(directory: root)
            let document = Data("""
                {"format":"netkit-ssh-profiles","schema":2,"profiles":[{"alias":"Target","host":"192.0.2.1","port":22,"username":"tester","authentication":"INTERACTIVE","startupCommand":"","keepaliveSeconds":30}],"skipped":[]}
                """.utf8)
            try store.applyImport(store.previewImport(document))
            var attempts = 0
            let model = SshViewModel(keychain: chain, directory: root, sessionFactory: { _, _, _, _, _ in
                attempts += 1; throw SshTransportFailure.unavailable
            })
            let imported = try XCTUnwrap(model.data?.profiles.first)
            model.connect(imported)
            XCTAssertEqual(attempts, 0)
            XCTAssertEqual(model.error, .interactiveUnsupported)
            XCTAssertNil(model.terminalSnapshot)
            XCTAssertNil(model.keySelectionRequired)
            model.generateKey(name: "Replacement")
            let key = try XCTUnwrap(model.data?.keys.first)
            XCTAssertTrue(model.saveProfile(id: imported.id, name: imported.name, host: imported.host, port: "22", username: imported.username,
                keyRef: choice == .key ? key.id : nil, password: "", authentication: choice))
            XCTAssertNil(model.error)
            XCTAssertEqual(model.data?.profiles.first?.authentication, choice)
            XCTAssertEqual(try SshMetadataStore(directory: root).snapshot().profiles.first?.authentication, choice)
        }
    }

    @MainActor
    func testExportDocumentRetainsValidatedSnapshotAndDuplicateNamesAreExplained() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root); try? chain.deleteServiceItems() }
        let model = SshViewModel(keychain: chain, directory: root)
        XCTAssertTrue(model.saveProfile(id: nil, name: "Alpha", host: "192.0.2.1", port: "22", username: "tester", keyRef: nil, password: ""))
        let snapshot = try XCTUnwrap(model.prepareExport())
        let document = SshProfileDocument(contents: snapshot)
        XCTAssertTrue(model.saveProfile(id: nil, name: "Beta", host: "192.0.2.2", port: "22", username: "tester", keyRef: nil, password: ""))
        model.cancelExport()
        XCTAssertEqual(document.contents, snapshot)
        let rootObject = try XCTUnwrap(JSONSerialization.jsonObject(with: document.contents) as? [String: Any])
        XCTAssertEqual(rootObject["schema"] as? Int, 2)
        XCTAssertEqual((rootObject["profiles"] as? [[String: Any]])?.count, 1)
        let description = sshExportFailureMessage(.duplicateNames(["Alpha", "Beta"]))
        XCTAssertTrue(description.contains("Alpha"))
        XCTAssertTrue(description.contains("Beta"))
    }
}

extension SshProfileEditorTests {
    @MainActor
    func testProfilePersistenceFailureKeepsSpecificErrorMessage() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root); try? chain.deleteServiceItems() }
        let model = SshViewModel(keychain: chain, directory: root)
        XCTAssertNotNil(model.data)
        try FileManager.default.removeItem(at: root)
        try Data(UUID().uuidString.utf8).write(to: root)
        XCTAssertFalse(model.saveProfile(id: nil, name: "Target", host: "192.0.2.1", port: "22",
            username: "tester", keyRef: nil, password: ""))
        let failure = try XCTUnwrap(model.error)
        XCTAssertEqual(failure, .storage)
        XCTAssertEqual(errorKey(failure), "ssh.error.storage")
        XCTAssertNotEqual(errorKey(failure), errorKey(.invalid))
        XCTAssertNotEqual(errorKey(.credentials), errorKey(.invalid))
        XCTAssertNotEqual(errorKey(.interactiveUnsupported), errorKey(failure))
    }
}
