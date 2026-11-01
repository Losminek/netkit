import CryptoKit
import Foundation
import NIOCore
import NIOSSH
import NetKitShared

/// Host-key validation is completed before NIOSSH can authenticate the user.
final class SshHostKeyValidator: NIOSSHClientServerAuthenticationDelegate {
    enum Failure: Error { case invalid, changed, rejected, storage, cancelled, timeout }
    typealias Confirmation = (_ endpoint: String, _ algorithm: String, _ fingerprint: String, _ previous: TrustedHostKey?, _ repeated: Bool, _ reply: @escaping (Bool) -> Void) -> Void
    private let endpoint: String
    private let store: SshMetadataStore
    private let prompt: Confirmation
    private let onFailure: (Failure) -> Void
    private let onChanged: (TrustedHostKey, TrustedHostKey) -> Void
    private let timeout: TimeInterval
    private let lock = NSLock()
    private var pending: (UUID, EventLoopPromise<Void>)?
    private var closed = false

    init(endpoint: String, store: SshMetadataStore, timeout: TimeInterval = 180,
         onFailure: @escaping (Failure) -> Void = { _ in },
         onChanged: @escaping (TrustedHostKey, TrustedHostKey) -> Void = { _, _ in }, prompt: @escaping Confirmation) {
        self.endpoint = endpoint
        self.store = store
        self.onFailure = onFailure
        self.onChanged = onChanged
        self.timeout = timeout
        self.prompt = prompt
    }

    private func report(_ failure: Failure) { DispatchQueue.main.async { self.onFailure(failure) } }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise promise: EventLoopPromise<Void>) {
        let parts = String(openSSHPublicKey: hostKey).split(separator: " ", maxSplits: 2)
        let allowed = ["ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"]
        guard parts.count >= 2, allowed.contains(String(parts[0])),
              let blob = Data(base64Encoded: String(parts[1])), !blob.isEmpty, blob.count <= 16_384 else {
            promise.fail(Failure.invalid); return
        }
        let digest = Data(SHA256.hash(data: blob)).base64EncodedString().replacingOccurrences(of: "=", with: "")
        let offered = TrustedHostKey(endpoint: endpoint, algorithm: String(parts[0]),
                                     publicKey: String(parts[1]), fingerprint: "SHA256:" + digest)
        switch store.decision(offered) {
        case .trusted:
            lock.lock(); let isClosed = closed; lock.unlock()
            if isClosed { promise.fail(Failure.cancelled) }
            else { promise.succeed(()) }
        case .changed:
            let saved = store.snapshot().hostKeys.first { $0.endpoint == offered.endpoint }
            try? store.reject(offered)
            if let saved { DispatchQueue.main.async { self.onChanged(saved, offered) } }
            report(.changed)
            promise.fail(Failure.changed)
        case .unknown:
            let context = store.rejectionContext(offered)
            let id = UUID()
            lock.lock()
            guard !closed, pending == nil else { lock.unlock(); promise.fail(Failure.cancelled); return }
            pending = (id, promise)
            lock.unlock()
            DispatchQueue.main.async { [weak self] in
                guard let self, self.isPending(id) else { return }
                self.prompt(self.endpoint, offered.algorithm, offered.fingerprint, context.previous, context.repeated) { [weak self] accepted in
                    self?.answer(id: id, key: offered, accepted: accepted)
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in self?.failPending(id: id, reason: .timeout) }
        default: promise.fail(Failure.invalid)
        }
    }

    func cancel() {
        lock.lock(); closed = true; let item = pending; pending = nil; lock.unlock()
        item?.1.fail(Failure.cancelled)
    }

    private func isPending(_ id: UUID) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return !closed && pending?.0 == id
    }

    private func failPending(id: UUID, reason: Failure) {
        lock.lock()
        guard let item = pending, item.0 == id else { lock.unlock(); return }
        pending = nil
        lock.unlock()
        report(reason)
        item.1.fail(reason)
    }

    private func answer(id: UUID, key: TrustedHostKey, accepted: Bool) {
        lock.lock()
        guard let item = pending, item.0 == id, !closed else { lock.unlock(); return }
        pending = nil
        // Keep the lock through compare-and-insert so cancel cannot race a stale approval.
        if accepted {
            do { try store.trustUnknown(key); lock.unlock(); item.1.succeed(()) }
            catch { lock.unlock(); report(.storage); item.1.fail(Failure.storage) }
        } else {
            do { try store.reject(key); lock.unlock(); report(.rejected); item.1.fail(Failure.rejected) }
            catch { lock.unlock(); report(.storage); item.1.fail(Failure.storage) }
        }
    }
}
