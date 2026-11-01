@testable import NetKit
import Foundation
import Darwin
import Crypto
import NIOCore
import NIOPosix
import NIOSSH

/// Loopback-only integration fixture. Keys and password exist only for this process.
final class LocalSshServer {
    enum FixtureError: Error { case missingPort, unsupportedChannel }
    let username = "test-user"
    let password = UUID().uuidString + UUID().uuidString
    let userKey = Curve25519.Signing.PrivateKey()
    let hostKey: NIOSSHPrivateKey
    private let group: MultiThreadedEventLoopGroup
    private let state = ServerState()
    private var listener: Channel?
    private let stopLock = NSLock()
    private var stopped = false
    private(set) var port: Int = 0

    init(hostKey: NIOSSHPrivateKey = NIOSSHPrivateKey(ed25519Key: .init())) {
        self.hostKey = hostKey
        self.group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    }

    func start(port requestedPort: Int = 0) async throws {
        let allowedKey = NIOSSHPrivateKey(ed25519Key: userKey).publicKey
        let authenticationValue = self.password
        let username = self.username
        let hostKey = self.hostKey
        let state = self.state
        do {
            let channel = try await ServerBootstrap(group: group)
                .serverChannelOption(ChannelOptions.socket(SocketOptionLevel(SOL_SOCKET), SO_REUSEADDR), value: 1)
                .childChannelInitializer { channel in
                    state.add(channel)
                    let auth = LocalAuthentication(username: username, credential: authenticationValue, allowedKey: allowedKey, state: state)
                    return channel.pipeline.addHandler(NIOSSHHandler(
                        role: .server(.init(hostKeys: [hostKey], userAuthDelegate: auth, banner: nil)),
                        allocator: channel.allocator,
                        inboundChildChannelInitializer: { child, type in
                            guard type == .session else {
                                return child.eventLoop.makeFailedFuture(FixtureError.unsupportedChannel)
                            }
                            state.addChild(child)
                            return child.pipeline.addHandler(LocalTerminal(state: state))
                        }
                    ))
                }
                .bind(host: "127.0.0.1", port: requestedPort).get()
            guard let actualPort = channel.localAddress?.port else {
                try await channel.close().get()
                throw FixtureError.missingPort
            }
            listener = channel
            port = actualPort
        } catch {
            try? await shutdownGroup()
            throw error
        }
    }

    private func markStopping() -> Bool {
        stopLock.lock(); defer { stopLock.unlock() }
        guard !stopped else { return false }
        stopped = true
        return true
    }
    func stop() async throws {
        guard markStopping() else { return }
        if let listener { try await listener.close().get(); self.listener = nil }
        for channel in state.channels() where channel.isActive { try? await channel.close().get() }
        try await shutdownGroup()
    }
    func closeSessions() async throws {
        for channel in state.children() where channel.isActive { try await channel.close().get() }
    }
    var ptyRequests: Int { state.ptyCount() }
    var shellRequests: Int { state.shellCount() }
    var authSuccesses: Int { state.authCount() }
    private func shutdownGroup() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            group.shutdownGracefully { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
    }
}

private final class ServerState: @unchecked Sendable {
    private let lock = NSLock()
    private var connections: [Channel] = []
    private var childConnections: [Channel] = []
    private var ptys = 0
    private var shells = 0
    private var authentications = 0
    func add(_ channel: Channel) { lock.lock(); connections.append(channel); lock.unlock() }
    func channels() -> [Channel] { lock.lock(); defer { lock.unlock() }; return connections }
    func addChild(_ channel: Channel) { lock.lock(); childConnections.append(channel); lock.unlock() }
    func children() -> [Channel] { lock.lock(); defer { lock.unlock() }; return childConnections }
    func pty() { lock.lock(); ptys += 1; lock.unlock() }
    func shell() { lock.lock(); shells += 1; lock.unlock() }
    func auth() { lock.lock(); authentications += 1; lock.unlock() }
    func authCount() -> Int { lock.lock(); defer { lock.unlock() }; return authentications }
    func ptyCount() -> Int { lock.lock(); defer { lock.unlock() }; return ptys }
    func shellCount() -> Int { lock.lock(); defer { lock.unlock() }; return shells }
}

private final class LocalAuthentication: NIOSSHServerUserAuthenticationDelegate {
    let username: String
    let expectedValue: String
    let allowedKey: NIOSSHPublicKey
    let state: ServerState
    init(username: String, credential: String, allowedKey: NIOSSHPublicKey, state: ServerState) {
        self.username = username; self.expectedValue = credential; self.allowedKey = allowedKey; self.state = state
    }
    var supportedAuthenticationMethods: NIOSSHAvailableUserAuthenticationMethods { [.password, .publicKey] }
    func requestReceived(request: NIOSSHUserAuthenticationRequest,
                         responsePromise: EventLoopPromise<NIOSSHUserAuthenticationOutcome>) {
        guard request.username == username else { responsePromise.succeed(.failure); return }
        let valid: Bool
        switch request.request {
        case .password(let candidate): valid = candidate.password == expectedValue
        case .publicKey(let candidate): valid = candidate.publicKey == allowedKey
        default: valid = false
        }
        if valid { state.auth() }
        responsePromise.succeed(valid ? .success : .failure)
    }
}

/// A deterministic echo endpoint; it does not execute local commands.
private final class LocalTerminal: ChannelInboundHandler {
    typealias InboundIn = SSHChannelData
    typealias OutboundOut = SSHChannelData
    private let state: ServerState
    init(state: ServerState) { self.state = state }
    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        switch event {
        case let request as SSHChannelRequestEvent.PseudoTerminalRequest:
            state.pty()
            if request.wantReply { context.channel.triggerUserOutboundEvent(ChannelSuccessEvent(), promise: nil) }
        case let request as SSHChannelRequestEvent.ShellRequest:
            state.shell()
            if request.wantReply { context.channel.triggerUserOutboundEvent(ChannelSuccessEvent(), promise: nil) }
        default:
            context.fireUserInboundEventTriggered(event)
        }
    }
    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let data = unwrapInboundIn(data)
        guard case .channel = data.type, case .byteBuffer = data.data else {
            context.close(promise: nil); return
        }
        context.writeAndFlush(wrapOutboundOut(data), promise: nil)
    }
}
