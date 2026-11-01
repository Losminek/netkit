import Foundation
import Darwin
import NetKitShared

/// The codec validates the entire versioned document. Read errors never become an empty store.
final class WolPersistence: NSObject, IosWolPersistence {
    private let codec = IosWolCodec()
    private let limit = 1_048_576
    private let directoryOverride: URL?

    init(directoryOverride: URL? = nil) {
        self.directoryOverride = directoryOverride
        super.init()
    }

    private func directory() throws -> URL {
        if let directoryOverride { return directoryOverride }
        guard let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw CocoaError(.fileNoSuchFile)
        }
        return root.appendingPathComponent("NetKit", isDirectory: true)
    }

    static func isMissingFile(_ error: NSError) -> Bool {
        var current: NSError? = error
        var visited = Set<ObjectIdentifier>()
        while let value = current, visited.insert(ObjectIdentifier(value)).inserted {
            if value.domain == NSCocoaErrorDomain {
                if value.code == NSFileNoSuchFileError || value.code == NSFileReadNoSuchFileError { return true }
                // Specific file errors such as permission denied or corruption remain failures;
                // generic Cocoa wrappers can still carry the actual error underneath.
                if (NSFileReadNoPermissionError...NSFileErrorMaximum).contains(value.code) { return false }
            } else if value.domain == NSPOSIXErrorDomain {
                return value.code == Int(ENOENT)
            }
            current = value.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }

    func load() -> IosWolLoadResult {
        guard let file = try? directory().appendingPathComponent("wol.json") else {
            return IosWolLoadResult(devices: [], failure: .io)
        }
        do {
            let handle = try FileHandle(forReadingFrom: file)
            defer { try? handle.close() }
            let data = try handle.read(upToCount: limit + 1) ?? Data()
            guard data.count <= limit else { return IosWolLoadResult(devices: [], failure: .corrupt) }
            let bytes = KotlinByteArray(size: Int32(data.count))
            for (index, byte) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: byte)) }
            return codec.decode(bytes: bytes)
        } catch {
            if Self.isMissingFile(error as NSError) {
                return IosWolLoadResult(devices: [], failure: nil)
            }
            return IosWolLoadResult(devices: [], failure: .io)
        }
    }

    func save(devices: [WolDevice]) -> Bool {
        guard let bytes = codec.encode(devices: devices) else { return false }
        let data = Data((0..<bytes.size).map { UInt8(bitPattern: bytes.get(index: $0)) })
        guard data.count <= limit else { return false }
        do {
            var dir = try directory()
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try dir.setResourceValues(values)
            try data.write(to: dir.appendingPathComponent("wol.json"), options: [.atomic, .completeFileProtection])
            return true
        } catch {
            return false
        }
    }
}
