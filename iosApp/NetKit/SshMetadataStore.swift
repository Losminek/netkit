import Foundation
import NetKitShared

/// Serializes profile and host-key updates, including compare-and-insert after TOFU approval.
final class SshMetadataStore {
    enum Failure: Error { case io, invalid, conflict }
    private let lock = NSLock()
    private let domain: IosSshDomain
    private let directory: URL
    private var data: SshData

    init(directory: URL? = nil, onFreshInstall: () throws -> Void = {}) throws {
        let domain = IosSshDomain()
        self.domain = domain
        guard let root = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?.appendingPathComponent("NetKit", isDirectory: true) else { throw Failure.io }
        self.directory = root
        let file = root.appendingPathComponent("ssh.json")
        do {
            let handle = try FileHandle(forReadingFrom: file)
            defer { try? handle.close() }
            let raw = try handle.read(upToCount: 1_048_577) ?? Data()
            guard raw.count <= 1_048_576 else { throw Failure.invalid }
            let bytes = KotlinByteArray(size: Int32(raw.count))
            for (index, value) in raw.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: value)) }
            let result = domain.decodeData(bytes: bytes)
            guard let decoded = result.data, result.failure == nil else { throw Failure.invalid }
            self.data = decoded
        } catch let failure as Failure { throw failure
        } catch {
            guard WolPersistence.isMissingFile(error as NSError) else { throw Failure.io }
            try onFreshInstall()
            self.data = domain.emptyData()
            try persist(self.data)
        }
    }

    func snapshot() -> SshData {
        lock.lock(); defer { lock.unlock() }
        return data
    }

    func decision(_ key: TrustedHostKey) -> HostKeyDecision {
        lock.lock(); defer { lock.unlock() }
        return domain.hostDecision(data: data, offered: key)
    }

    func rejectionContext(_ key: TrustedHostKey) -> (previous: TrustedHostKey?, repeated: Bool) {
        lock.lock(); defer { lock.unlock() }
        let previous = data.rejectedHostKeys.first { $0.endpoint == key.endpoint }
        let distinct = domain.previousRejected(data: data, offered: key)
        return (previous, previous != nil && distinct == nil)
    }

    func update(_ transform: (SshData) -> IosSshDataResult) throws {
        lock.lock(); defer { lock.unlock() }
        let result = transform(data)
        guard let next = result.data, result.failure == nil else { throw Failure.conflict }
        try persist(next)
        data = next
    }

    func trustUnknown(_ key: TrustedHostKey) throws {
        try update { domain.trustUnknown(data: $0, offered: key) }
    }

    func reject(_ key: TrustedHostKey) throws {
        try update { domain.recordRejected(data: $0, offered: key) }
    }

    func forget(endpoint: String) throws {
        try update { domain.forgetHost(data: $0, endpoint: endpoint) }
    }

    func save(profile: SshProfile, withCredential updated: SshProfile? = nil) throws {
        try update { current in
            let first = domain.upsertProfile(data: current, profile: profile)
            guard let updated, let intermediate = first.data, first.failure == nil else { return first }
            return domain.upsertProfile(data: intermediate, profile: updated)
        }
    }

    func save(key: SshKeyInfo) throws {
        try update { domain.upsertKey(data: $0, key: key) }
    }

    func deleteProfile(id: String) throws { try update { domain.deleteProfile(data: $0, id: id) } }
    func deleteKey(id: String) throws { try update { domain.deleteKey(data: $0, id: id) } }

    func moveProfile(id: String, delta: Int32) throws {
        try update { domain.moveProfile(data: $0, id: id, delta: delta) }
    }

    func prepareExport() -> IosSshExportResult {
        lock.lock(); defer { lock.unlock() }
        return domain.prepareExport(data: data)
    }

    func previewImport(_ raw: Data, keyRef: String? = nil) throws -> SshImportPlan {
        guard raw.count <= 256 * 1024 else { throw Failure.invalid }
        let bytes = KotlinByteArray(size: Int32(raw.count))
        for (index, value) in raw.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: value)) }
        guard let parsed = domain.decodeImport(bytes: bytes).imported else { throw Failure.invalid }
        return try planImport(parsed, keyRef: keyRef)
    }

    func planImport(_ imported: SshProfileImport, keyRef: String?) throws -> SshImportPlan {
        lock.lock(); defer { lock.unlock() }
        if let keyRef, !data.keys.contains(where: { $0.id == keyRef }) { throw Failure.invalid }
        guard let plan = domain.planImport(current: data, imported: imported, keyRef: keyRef,
                                           generateId: { UUID().uuidString }).plan else { throw Failure.invalid }
        return plan
    }

    func applyImport(_ plan: SshImportPlan) throws {
        lock.lock(); defer { lock.unlock() }
        guard let next = domain.applyImport(current: data, plan: plan).data else { throw Failure.conflict }
        try persist(next)
        data = next
    }

    private func persist(_ value: SshData) throws {
        guard let encoded = domain.encodeData(data: value) else { throw Failure.invalid }
        let bytes = Data((0..<encoded.size).map { UInt8(bitPattern: encoded.get(index: $0)) })
        do {
            var root = directory
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try root.setResourceValues(values)
            try bytes.write(to: root.appendingPathComponent("ssh.json"), options: [.atomic, .completeFileProtection])
        } catch { throw Failure.io }
    }
}
