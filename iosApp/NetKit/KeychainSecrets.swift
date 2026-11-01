import Foundation
import Security

/// Secret bytes never enter UserDefaults, profile JSON, logs, or backups.
final class KeychainSecrets {
    enum Failure: Error { case invalidReference, tooLarge, unavailable(OSStatus) }
    private let service: String
    private let limit = 256 * 1024

    init(service: String = "cz.netkit.ios.ssh.secrets") {
        self.service = service
    }

    private func query(_ id: String) throws -> [String: Any] {
        guard !id.isEmpty, id.utf8.count <= 80,
              id.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 }) else {
            throw Failure.invalidReference
        }
        return [kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: id,
                kSecAttrSynchronizable as String: false]
    }

    func put(_ id: String, secret: Data) throws {
        guard secret.count <= limit else { throw Failure.tooLarge }
        let search = try query(id)
        let update: [String: Any] = [kSecValueData as String: secret,
                                     kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(search as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw Failure.unavailable(status) }
        var item = search
        for (key, value) in update { item[key] = value }
        let added = SecItemAdd(item as CFDictionary, nil)
        if added == errSecDuplicateItem {
            let retried = SecItemUpdate(search as CFDictionary, update as CFDictionary)
            guard retried == errSecSuccess else { throw Failure.unavailable(retried) }
        } else if added != errSecSuccess {
            throw Failure.unavailable(added)
        }
    }

    func get(_ id: String) throws -> Data? {
        var search = try query(id)
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        let status = SecItemCopyMatching(search as CFDictionary, &value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = value as? Data, data.count <= limit else {
            throw Failure.unavailable(status == errSecSuccess ? errSecDecode : status)
        }
        return data
    }

    func delete(_ id: String) throws {
        let status = SecItemDelete(try query(id) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure.unavailable(status) }
    }

    func deleteServiceItems() throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrSynchronizable as String: false]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure.unavailable(status) }
    }

    func accountIDs() throws -> [String] {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrSynchronizable as String: false,
                                    kSecReturnAttributes as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitAll]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let attributes = result as? [[String: Any]] else {
            throw Failure.unavailable(status == errSecSuccess ? errSecDecode : status)
        }
        return try attributes.map {
            guard let id = $0[kSecAttrAccount as String] as? String else { throw Failure.unavailable(errSecDecode) }
            return id
        }
    }
}

import NetKitShared

final class KeychainSecretBackend: NSObject, IosSecretBackend {
    private let keychain: KeychainSecrets
    init(keychain: KeychainSecrets = KeychainSecrets()) { self.keychain = keychain }

    func store(id: String, bytes: KotlinByteArray) -> SshFailure? {
        let secret = Data((0..<bytes.size).map { UInt8(bitPattern: bytes.get(index: $0)) })
        do { try keychain.put(id, secret: secret); return nil }
        catch { return .secretUnavailable }
    }

    func read(id: String) -> IosSecretReadResult {
        do {
            guard let secret = try keychain.get(id) else { return IosSecretReadResult(bytes: nil, failure: nil) }
            let bytes = KotlinByteArray(size: Int32(secret.count))
            for (index, value) in secret.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: value)) }
            return IosSecretReadResult(bytes: bytes, failure: nil)
        } catch { return IosSecretReadResult(bytes: nil, failure: .secretUnavailable) }
    }

    func remove(id: String) -> SshFailure? {
        do { try keychain.delete(id); return nil }
        catch { return .secretUnavailable }
    }
}
