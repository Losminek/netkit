import CryptoKit
import Foundation
import NIOCore
import NIOPosix
import NIOSSH

enum SshCredential {
    case password(String)
    case ed25519(Data)
}

enum SshTransportFailure: Error { case invalidCredential, unavailable, rejected, timeout, closed, protocolError }

func sshAlgorithmNegotiationFailure(_ type: NIOSSHError.ErrorType) -> Bool {
    type == .unknownPublicKey || type == .unknownSignature || type == .keyExchangeNegotiationFailure
}

func sshSupportedCredentialKey(_ publicKey: String) -> Bool {
    publicKey.hasPrefix("ssh-ed25519 ")
}

private final class SshAuthentication: NIOSSHClientUserAuthenticationDelegate {
    private let username: String
    private var offer: NIOSSHUserAuthenticationOffer.Offer?
    init(username: String, credential: SshCredential) throws {
        self.username = username
        switch credential {
        case .password(let password): offer = .password(.init(password: password))
        case .ed25519(let bytes):
            let key = try Curve25519.Signing.PrivateKey(rawRepresentation: bytes)
            offer = .privateKey(.init(privateKey: NIOSSHPrivateKey(ed25519Key: key)))
        }
    }
    func nextAuthenticationType(availableMethods: NIOSSHAvailableUserAuthenticationMethods,
                                nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>) {
        guard let offer else { nextChallengePromise.succeed(nil); return }
        self.offer = nil
        switch offer {
        case .password where !availableMethods.contains(.password): nextChallengePromise.succeed(nil)
        case .privateKey where !availableMethods.contains(.publicKey): nextChallengePromise.succeed(nil)
        default: nextChallengePromise.succeed(.init(username: username, serviceName: "", offer: offer))
        }
    }
}

/// A single bounded connection; no background thread blocks the UI or logs raw provider errors.
final class SshTransport {
    enum State { case connected, closed, failed, algorithmFailure }
    private let group: MultiThreadedEventLoopGroup
    private let lock = NSLock()
    private var closed = false
    private var parent: Channel?
    private var child: Channel?
    private var pendingWriteBytes = 0
    private var terminalIsReady = false
    private var handshakeTimeout: DispatchWorkItem?
    private var shellTimeout: DispatchWorkItem?
    private let validator: SshHostKeyValidator
    private let auth: SshAuthentication
    private let host: String
    private let port: Int
    private let output: (Data) -> Void
    private let state: (State) -> Void

    init(host: String, port: Int, username: String, credential: SshCredential,
         validator: SshHostKeyValidator, output: @escaping (Data) -> Void,
         state: @escaping (State) -> Void) throws {
        let auth = try SshAuthentication(username: username, credential: credential)
        self.host = host; self.port = port; self.validator = validator
        self.auth = auth
        self.group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        self.output = output; self.state = state
    }

    func start() {
        let timeout = DispatchWorkItem { [weak self] in self?.fail() }
        lock.lock()
        guard !closed, handshakeTimeout == nil else { lock.unlock(); return }
        handshakeTimeout = timeout
        lock.unlock()
        DispatchQueue.main.asyncAfter(deadline: .now() + 600, execute: timeout)
        let bootstrap = ClientBootstrap(group: group)
            .connectTimeout(.seconds(15))
            .channelInitializer { [weak self, auth, validator] channel in
                let handler = NIOSSHHandler(role: .client(.init(userAuthDelegate: auth, serverAuthDelegate: validator)),
                                           allocator: channel.allocator, inboundChildChannelInitializer: nil)
                return channel.pipeline.addHandler(handler).flatMap {
                    channel.pipeline.addHandler(SshParentErrorHandler { [weak self] error in
                        if let error = error as? NIOSSHError, sshAlgorithmNegotiationFailure(error.type) {
                            self?.terminate(.algorithmFailure)
                        } else { self?.fail() }
                    })
                }
            }
        bootstrap.connect(host: host, port: port).whenComplete { [weak self] result in
            guard let self else { if case .success(let channel) = result { channel.close(promise: nil) }; return }
            switch result {
            case .failure: self.fail()
            case .success(let channel):
                self.lock.lock()
                guard !self.closed else { self.lock.unlock(); channel.close(promise: nil); return }
                self.parent = channel
                self.lock.unlock()
                channel.closeFuture.whenComplete { [weak self] _ in self?.finishClosed() }
                channel.pipeline.handler(type: NIOSSHHandler.self).flatMap { handler in
                    let promise = channel.eventLoop.makePromise(of: Channel.self)
                    handler.createChannel(promise, channelType: .session) { child, type in
                        guard type == .session else { return child.eventLoop.makeFailedFuture(SshTransportFailure.protocolError) }
                        let terminal = SshTerminalHandler(output: self.output,
                                                          ready: { [weak self] in self?.terminalReady() },
                                                          failed: { [weak self] in self?.fail() })
                        return child.pipeline.addHandler(terminal)
                    }
                    return promise.futureResult
                }.whenComplete { [weak self] outcome in
                    guard let self else { return }
                    switch outcome {
                    case .failure: self.fail()
                    case .success(let child):
                        self.lock.lock()
                        if self.closed { self.lock.unlock(); child.close(promise: nil); return }
                        self.child = child
                        let ready = self.terminalIsReady
                        let timeout = ready ? nil : DispatchWorkItem { [weak self] in self?.fail() }
                        self.shellTimeout = timeout
                        self.lock.unlock()
                        child.closeFuture.whenComplete { [weak self] _ in self?.finishClosed() }
                        if ready { self.notify(.connected) }
                        if let timeout { DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: timeout) }
                    }
                }
            }
        }
    }

    func send(_ text: String) -> Bool {
        guard text.utf8.count <= 16_384 else { return false }
        let size = text.utf8.count
        lock.lock()
        guard !closed, let channel = child, channel.isActive, channel.isWritable,
              pendingWriteBytes + size <= 65_536 else { lock.unlock(); return false }
        pendingWriteBytes += size
        lock.unlock()
        var buffer = channel.allocator.buffer(capacity: size)
        buffer.writeString(text)
        let promise = channel.eventLoop.makePromise(of: Void.self)
        promise.futureResult.whenComplete { [weak self] result in
            guard let self else { return }
            self.lock.lock(); self.pendingWriteBytes -= size; self.lock.unlock()
            if case .failure = result { self.fail() }
        }
        channel.writeAndFlush(SSHChannelData(type: .channel, data: .byteBuffer(buffer)), promise: promise)
        return true
    }

    func resize(columns: Int, rows: Int) {
        guard (10...240).contains(columns), (2...100).contains(rows) else { return }
        lock.lock(); let channel = child; let ended = closed; lock.unlock()
        guard !ended, let channel else { return }
        channel.triggerUserOutboundEvent(SSHChannelRequestEvent.WindowChangeRequest(
            terminalCharacterWidth: columns, terminalRowHeight: rows, terminalPixelWidth: 0, terminalPixelHeight: 0), promise: nil)
    }

    func close() { terminate(nil) }

    private func terminate(_ terminalState: State?) {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        closed = true; let parent = self.parent; let child = self.child
        handshakeTimeout?.cancel(); handshakeTimeout = nil
        shellTimeout?.cancel(); shellTimeout = nil
        self.parent = nil; self.child = nil
        lock.unlock()
        validator.cancel()
        child?.close(promise: nil); parent?.close(promise: nil)
        group.shutdownGracefully { _ in }
        if let terminalState { notify(terminalState) }
    }

    private func terminalReady() {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        terminalIsReady = true
        handshakeTimeout?.cancel(); handshakeTimeout = nil
        shellTimeout?.cancel(); shellTimeout = nil
        let hasChild = child != nil
        lock.unlock()
        if hasChild { notify(.connected) }
    }
    private func fail() { terminate(.failed) }
    private func finishClosed() {
        lock.lock(); let wasConnected = terminalIsReady && child != nil; lock.unlock()
        terminate(wasConnected ? .closed : .failed)
    }
    private func notify(_ value: State) {
        DispatchQueue.main.async {
            if case .connected = value {
                self.lock.lock(); let ended = self.closed; self.lock.unlock()
                if ended { return }
            }
            self.state(value)
        }
    }
}

/// Errors before child-channel creation must close the parent and report failure.
private final class SshParentErrorHandler: ChannelInboundHandler {
    typealias InboundIn = Any
    private let failed: (Error) -> Void
    init(failed: @escaping (Error) -> Void) { self.failed = failed }
    func errorCaught(context: ChannelHandlerContext, error: Error) {
        failed(error)
        context.close(promise: nil)
    }
}

private final class SshTerminalHandler: ChannelInboundHandler {
    typealias InboundIn = SSHChannelData
    private let output: (Data) -> Void
    private let ready: () -> Void
    private let failed: () -> Void
    private var stage = 0
    private let deliveryLock = NSLock()
    private var pendingBytes = 0

    init(output: @escaping (Data) -> Void, ready: @escaping () -> Void, failed: @escaping () -> Void) {
        self.output = output; self.ready = ready; self.failed = failed
    }
    func channelActive(context: ChannelHandlerContext) {
        context.triggerUserOutboundEvent(SSHChannelRequestEvent.PseudoTerminalRequest(
            wantReply: true, term: "xterm", terminalCharacterWidth: 80, terminalRowHeight: 24,
            terminalPixelWidth: 0, terminalPixelHeight: 0, terminalModes: SSHTerminalModes([:])), promise: nil)
    }
    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if event is ChannelFailureEvent { failed(); context.close(promise: nil); return }
        guard event is ChannelSuccessEvent else { context.fireUserInboundEventTriggered(event); return }
        if stage == 0 {
            stage = 1
            context.triggerUserOutboundEvent(SSHChannelRequestEvent.ShellRequest(wantReply: true), promise: nil)
        } else if stage == 1 { stage = 2; ready() }
    }
    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let message = unwrapInboundIn(data)
        guard case .byteBuffer(let bytes) = message.data, bytes.readableBytes <= 131_072 else {
            failed(); context.close(promise: nil); return
        }
        let chunk = Data(bytes.readableBytesView)
        deliveryLock.lock()
        guard pendingBytes + chunk.count <= 524_288 else {
            deliveryLock.unlock(); failed(); context.close(promise: nil); return
        }
        pendingBytes += chunk.count
        deliveryLock.unlock()
        // One pending delivery at a time avoids an unbounded main-queue backlog.
        context.channel.setOption(ChannelOptions.autoRead, value: false).whenComplete { _ in
            DispatchQueue.main.async {
                self.output(chunk)
                self.deliveryLock.lock(); self.pendingBytes -= chunk.count; self.deliveryLock.unlock()
                context.eventLoop.execute { if context.channel.isActive { context.read() } }
            }
        }
    }
    func errorCaught(context: ChannelHandlerContext, error: Error) { failed(); context.close(promise: nil) }
}

import NetKitShared

final class SshSessionAdapter: NSObject, SshSession {
    private let transport: SshTransport
    init(transport: SshTransport) { self.transport = transport }
    func send(text: String) -> Bool { transport.send(text) }
    func resize(columns: Int32, rows: Int32) { transport.resize(columns: Int(columns), rows: Int(rows)) }
    func close() { transport.close() }
    deinit { transport.close() }
}
