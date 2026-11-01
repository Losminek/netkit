@testable import NetKit
import Foundation
import Security
import NetKitShared
import XCTest

final class KeychainSecretsTests: XCTestCase {
    func testRoundTripUpdateDeleteAndAccessibility() throws {
        let service = "cz.netkit.ios.tests." + UUID().uuidString
        let store = KeychainSecrets(service: service)
        let id = "test-secret"
        defer { try? store.delete(id) }
        XCTAssertNil(try store.get(id))
        let first = Data(UUID().uuidString.utf8)
        let second = Data(UUID().uuidString.utf8)
        try store.put(id, secret: first)
        XCTAssertEqual(try store.get(id), first)
        try store.put(id, secret: second)
        XCTAssertEqual(try store.get(id), second)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: id,
                                    kSecReturnAttributes as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        let attributes = try XCTUnwrap(result as? [String: Any])
        XCTAssertEqual(attributes[kSecAttrAccessible as String] as? String,
                       kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        XCTAssertEqual(attributes[kSecAttrSynchronizable as String] as? Bool, false)
        try store.delete(id)
        XCTAssertNil(try store.get(id))
    }

    func testFreshMetadataClearsOnlyItsServiceAndCorruptMetadataDoesNot() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let own = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        let other = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        let id = UUID().uuidString
        let value = Data(UUID().uuidString.utf8)
        defer { try? own.delete(id); try? other.delete(id) }
        try own.put(id, secret: value); try other.put(id, secret: value)
        _ = try SshMetadataStore(directory: root, onFreshInstall: { try own.deleteServiceItems() })
        XCTAssertNil(try own.get(id)); XCTAssertEqual(try other.get(id), value)
        try own.put(id, secret: value)
        try Data("invalid".utf8).write(to: root.appendingPathComponent("ssh.json"))
        XCTAssertThrowsError(try SshMetadataStore(directory: root, onFreshInstall: { try own.deleteServiceItems() }))
        XCTAssertEqual(try own.get(id), value)
    }

    func testSharedSecretStoreAdapterRoundTrip() {
        let keychain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        let adapter = IosSecretStoreAdapter(backend: KeychainSecretBackend(keychain: keychain))
        let random = Array(UUID().uuidString.utf8.prefix(3))
        let input = KotlinByteArray(size: Int32(random.count))
        for (index, value) in random.enumerated() { input.set(index: Int32(index), value: Int8(bitPattern: value)) }
        defer { _ = adapter.deleteSecret(id: "temporary") }
        XCTAssertNil(adapter.putSecret(id: "temporary", secret: input))
        let read = adapter.getSecret(id: "temporary")
        XCTAssertNil(read.failure)
        XCTAssertEqual(read.bytes?.size, 3)
        XCTAssertEqual(read.bytes?.get(index: 2), Int8(bitPattern: random[2]))
        XCTAssertNil(adapter.deleteSecret(id: "temporary"))
        XCTAssertNil(adapter.getSecret(id: "temporary").bytes)
    }

    func testInvalidReferenceAndOversizeRejected() {
        let store = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        XCTAssertThrowsError(try store.put("bad/ref", secret: Data(UUID().uuidString.utf8)))
        XCTAssertThrowsError(try store.put("test-secret", secret: Data(repeating: 0, count: 256 * 1024 + 1)))
    }

    @MainActor
    func testProfileAndKeyDeletionKeepReferencedSecrets() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        defer { try? chain.deleteServiceItems() }
        let model = SshViewModel(keychain: chain, directory: root)
        model.generateKey(name: "key")
        let key = try XCTUnwrap(model.data?.keys.first)
        XCTAssertTrue(model.saveProfile(id: nil, name: "profile", host: "192.0.2.1", port: "22", username: "user", keyRef: key.id, password: ""))
        let profile = try XCTUnwrap(model.data?.profiles.first)
        XCTAssertNotNil(try chain.get(key.id))
        model.deleteKey(key)
        XCTAssertNotNil(model.data?.keys.first)
        XCTAssertNotNil(try chain.get(key.id))
        model.deleteProfile(profile)
        model.deleteKey(key)
        XCTAssertTrue(model.data?.keys.isEmpty == true)
        XCTAssertNil(try chain.get(key.id))
    }

    @MainActor
    func testReplacingPasswordRemovesDetachedReference() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        defer { try? chain.deleteServiceItems() }
        let model = SshViewModel(keychain: chain, directory: root)
        XCTAssertTrue(model.saveProfile(id: nil, name: "profile", host: "192.0.2.1", port: "22", username: "user", keyRef: nil, password: UUID().uuidString))
        let original = try XCTUnwrap(model.data?.profiles.first)
        let oldRef = try XCTUnwrap(original.secretRef)
        XCTAssertTrue(model.saveProfile(id: original.id, name: "profile", host: "192.0.2.1", port: "22", username: "user", keyRef: nil, password: UUID().uuidString))
        let updated = try XCTUnwrap(model.data?.profiles.first)
        XCTAssertNil(try chain.get(oldRef))
        XCTAssertNotNil(try chain.get(try XCTUnwrap(updated.secretRef)))
        model.deleteProfile(updated)
        XCTAssertNil(try chain.get(try XCTUnwrap(updated.secretRef)))
    }

    @MainActor
    func testSharedReferenceSurvivesProfileDeletionAndImportShowsChangedEndpoint() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        defer { try? chain.deleteServiceItems() }
        let id = UUID().uuidString
        let password = Data(UUID().uuidString.utf8)
        let store = try SshMetadataStore(directory: root)
        try chain.put(id, secret: password)
        let domain = IosSshDomain()
        let first = try XCTUnwrap(domain.profile(id: "first", name: "one", host: "192.0.2.1", port: 22, username: "user", authentication: .password, secretRef: id, keyRef: nil, startupCommand: "", keepaliveSeconds: 30))
        let second = try XCTUnwrap(domain.profile(id: "second", name: "two", host: "192.0.2.2", port: 22, username: "user", authentication: .password, secretRef: id, keyRef: nil, startupCommand: "", keepaliveSeconds: 30))
        try store.save(profile: first); try store.save(profile: second)
        let model = SshViewModel(keychain: chain, directory: root)
        model.deleteProfile(first)
        XCTAssertEqual(try chain.get(id), password)
        let payload: [String: Any] = ["format": "netkit-ssh-profiles", "schema": 1,
            "profiles": [["alias": "two", "host": "198.51.100.1", "port": 2222, "username": "user"]], "skipped": []]
        let file = root.appendingPathComponent("import.json")
        try JSONSerialization.data(withJSONObject: payload).write(to: file)
        model.previewImport(file)
        let plan = try XCTUnwrap(model.importPlan)
        let change = try XCTUnwrap(model.importChanges(plan).first)
        XCTAssertEqual(change.before, "user@[192.0.2.2]:22")
        XCTAssertEqual(change.after, "user@[198.51.100.1]:2222")
        model.confirmImport()
        XCTAssertNil(try chain.get(id))
        XCTAssertNil(model.data?.profiles.first?.secretRef)
    }

    @MainActor
    func testStartupReconcilesOnlyUnreferencedServiceItems() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let chain = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        let other = KeychainSecrets(service: "cz.netkit.ios.tests." + UUID().uuidString)
        defer { try? chain.deleteServiceItems(); try? other.deleteServiceItems() }
        let retained = UUID().uuidString, orphan = UUID().uuidString
        let bytes = Data(UUID().uuidString.utf8)
        let store = try SshMetadataStore(directory: root)
        let profile = try XCTUnwrap(IosSshDomain().profile(id: "profile", name: "profile", host: "192.0.2.1", port: 22,
            username: "user", authentication: .password, secretRef: retained, keyRef: nil, startupCommand: "", keepaliveSeconds: 30))
        try store.save(profile: profile)
        try chain.put(retained, secret: bytes); try chain.put(orphan, secret: bytes); try other.put(orphan, secret: bytes)
        _ = SshViewModel(keychain: chain, directory: root)
        XCTAssertEqual(try chain.get(retained), bytes)
        XCTAssertNil(try chain.get(orphan))
        XCTAssertEqual(try other.get(orphan), bytes)
    }
}
