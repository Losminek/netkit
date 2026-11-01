import Foundation
import SwiftUI
import Security
import NetKitShared

final class SpeedCounter {
    private let lock = NSLock()
    private var bytes: Int64 = 0
    private var failures = 0
    private var serverMbps: Double?
    private var completed = false
    func add(_ count: Int64) { lock.lock(); bytes += count; lock.unlock() }
    func fail() { lock.lock(); failures += 1; lock.unlock() }
    func setServerMbps(_ value: Double) { lock.lock(); serverMbps = value; lock.unlock() }
    func serverRate() -> Double? { lock.lock(); defer { lock.unlock() }; return serverMbps }
    func markCompleted() { lock.lock(); completed = true; lock.unlock() }
    func isCompleted() -> Bool { lock.lock(); defer { lock.unlock() }; return completed }
    func snapshot() -> (Int64, Int) { lock.lock(); defer { lock.unlock() }; return (bytes, failures) }
}

/// Each task uses a serial delegate queue, discards download chunks and bounds other bodies.
protocol SpeedTransport {
    func read(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse)
    func download(_ request: URLRequest, counter: SpeedCounter) async throws
    func upload(_ request: URLRequest, payload: Data, counter: SpeedCounter) async throws
}

final class SpeedHTTP: SpeedTransport {
    enum Failure: Error { case invalidResponse, responseTooLarge, payloadTooLarge }
    private let configuration: URLSessionConfiguration
    init(configuration: URLSessionConfiguration = .ephemeral) {
        self.configuration = configuration
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 15
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
    }
    func read(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse) {
        try await SpeedHTTPTask(configuration: configuration, mode: .collect(limit)).run(request)
    }
    func download(_ request: URLRequest, counter: SpeedCounter) async throws {
        _ = try await SpeedHTTPTask(configuration: configuration, mode: .download(counter)).run(request)
    }
    func upload(_ request: URLRequest, payload: Data, counter: SpeedCounter) async throws {
        _ = try await SpeedHTTPTask(configuration: configuration, mode: .upload(counter, payload)).run(request)
    }
}

private final class SpeedHTTPTask: NSObject, URLSessionDataDelegate {
    enum Mode { case collect(Int), download(SpeedCounter), upload(SpeedCounter, Data) }
    private let configuration: URLSessionConfiguration
    private let mode: Mode
    private var session: URLSession!
    private var task: URLSessionTask?
    private var continuation: CheckedContinuation<(Data, HTTPURLResponse), Error>?
    private let lock = NSLock()
    private var cancelled = false
    private var body = Data()
    private var received: Int64 = 0
    private var lastSent: Int64 = 0
    private var response: HTTPURLResponse?
    private var failure: Error?
    init(configuration: URLSessionConfiguration, mode: Mode) {
        self.configuration = configuration; self.mode = mode
    }
    func run(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
                let task: URLSessionTask
                if case .upload(_, let payload) = mode { task = session.uploadTask(with: request, from: payload) }
                else { task = session.dataTask(with: request) }
                lock.lock()
                self.continuation = continuation; self.session = session; self.task = task
                let cancelled = self.cancelled
                lock.unlock()
                if cancelled { task.cancel() } else { task.resume() }
            }
        }, onCancel: {
            self.lock.lock(); self.cancelled = true; let task = self.task; self.lock.unlock()
            task?.cancel()
        })
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            failure = (response as? HTTPURLResponse)?.statusCode == 413 ? SpeedHTTP.Failure.payloadTooLarge : SpeedHTTP.Failure.invalidResponse
            completionHandler(.cancel); return
        }
        self.response = http
        if case .download = mode {
            let mime = (http.mimeType ?? "").lowercased()
            guard !mime.hasPrefix("text/"), !["application/json", "application/xml"].contains(mime),
                  response.expectedContentLength < 0 || response.expectedContentLength >= 1_048_576 else {
                failure = SpeedHTTP.Failure.invalidResponse; completionHandler(.cancel); return
            }
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        received += Int64(data.count)
        switch mode {
        case .download(let counter): counter.add(Int64(data.count))
        case .collect(let limit):
            guard data.count <= limit - body.count else { failure = SpeedHTTP.Failure.responseTooLarge; dataTask.cancel(); return }
            body.append(data)
        case .upload:
            guard data.count <= 512 - body.count else { failure = SpeedHTTP.Failure.responseTooLarge; dataTask.cancel(); return }
            body.append(data)
        }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard case .upload(let counter, _) = mode else { return }
        counter.add(max(0, totalBytesSent - lastSent)); lastSent = totalBytesSent
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if case .download = mode, error == nil, received < 1_048_576 { failure = SpeedHTTP.Failure.invalidResponse }
        let result: Result<(Data, HTTPURLResponse), Error>
        if let failure = failure { result = .failure(failure) }
        else if let error = error { result = .failure(error) }
        else if let response = response { result = .success((body, response)) }
        else { result = .failure(SpeedHTTP.Failure.invalidResponse) }
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil; self.task = nil; self.session = nil
        lock.unlock()
        continuation?.resume(with: result)
        session.finishTasksAndInvalidate()
    }
}

struct SpeedResult {
    let downloadMbps: Double
    let uploadMbps: Double
}

enum SpeedFailure: Error { case invalidServer, invalidResponse, incomplete, random }

final class UploadSizing {
    private let lock = NSLock()
    private var size = 8 * 1_048_576
    private var accepted = false
    func current() -> Int { lock.lock(); defer { lock.unlock() }; return size }
    func markAccepted() { lock.lock(); accepted = true; lock.unlock() }
    func reduce(after attempted: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if attempted > size { return true }
        guard !accepted, attempted > 256 * 1024 else { return false }
        if size == attempted { size = attempted / 2 }
        return true
    }
}

/// Parallel HTTP transfers; URLSession may multiplex them over HTTP/2 or HTTP/3.
final class LibreSpeedClient {
    private let http: any SpeedTransport
    private let domain = IosSpeedDomain()
    private let phaseDuration: TimeInterval
    init(http: any SpeedTransport = SpeedHTTP(), phaseDuration: TimeInterval = 10) {
        self.http = http
        self.phaseDuration = phaseDuration
    }

    func measure(input: String) async throws -> SpeedResult {
        guard let server = domain.libreServer(input: input) else { throw SpeedFailure.invalidServer }
        let empty = try url(domain.emptyUrl(base: server.baseUrl, nonce: Int64.random(in: 1...Int64.max)))
        let (probe, _) = try await http.read(URLRequest(url: empty), limit: 512)
        guard probe.count <= 512 else { throw SpeedFailure.invalidResponse }
        var payload = Data(count: 8 * 1_048_576)
        guard payload.withUnsafeMutableBytes({ buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }) == errSecSuccess else { throw SpeedFailure.random }
        let down = try await phase(base: server.baseUrl, download: true, payload: payload)
        let up = try await phase(base: server.baseUrl, download: false, payload: payload)
        return SpeedResult(downloadMbps: down, uploadMbps: up)
    }

    private func url(_ value: String) throws -> URL {
        guard let result = URL(string: value), result.scheme == "https" else { throw SpeedFailure.invalidServer }
        return result
    }

    private func phase(base: String, download: Bool, payload: Data) async throws -> Double {
        let counter = SpeedCounter()
        let trace = domain.createTrace()
        let start = ProcessInfo.processInfo.systemUptime
        let streamCount = download ? 6 : 3
        let sizing = UploadSizing()
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<streamCount {
                group.addTask { [self] in
                    do {
                        while !Task.isCancelled {
                            let nonce = Int64.random(in: 1...Int64.max)
                            let string = download ? domain.garbageUrl(base: base, nonce: nonce) : domain.emptyUrl(base: base, nonce: nonce)
                            var request = URLRequest(url: try url(string))
                            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
                            request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
                            if download {
                                try await http.download(request, counter: counter)
                            } else {
                                request.httpMethod = "POST"
                                request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                                let size = sizing.current()
                                do {
                                    try await http.upload(request, payload: Data(payload.prefix(size)), counter: counter)
                                    sizing.markAccepted()
                                } catch SpeedHTTP.Failure.payloadTooLarge {
                                    guard sizing.reduce(after: size) else { throw SpeedHTTP.Failure.payloadTooLarge }
                                }
                            }
                        }
                    } catch {
                        if !Task.isCancelled { counter.fail() }
                    }
                }
            }
            while (ProcessInfo.processInfo.systemUptime - start) < phaseDuration {
                try await Task.sleep(nanoseconds: 250_000_000)
                let elapsed = Int64((ProcessInfo.processInfo.systemUptime - start) * 1000)
                _ = trace.add(elapsedMs: elapsed, bytes: counter.snapshot().0)
            }
            group.cancelAll()
        }
        try Task.checkCancellation()
        let (bytes, failures) = counter.snapshot()
        guard failures == 0, bytes > 0, let summary = domain.summarize(trace: trace) else {
            throw SpeedFailure.incomplete
        }
        return summary.mbps
    }
}

protocol NdtSocket {
    func open() async throws
    func receive() async throws -> URLSessionWebSocketTask.Message
    func send(_ message: URLSessionWebSocketTask.Message) async throws
    var normalClosure: Bool { get }
    func close()
}

/// URLSession delegate reports the negotiated subprotocol once the upgrade completes.
final class URLSessionNdtSocket: NSObject, NdtSocket, URLSessionWebSocketDelegate {
    private let url: URL
    private let subprotocol: String
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var timeout: Task<Void, Never>?
    private var session: URLSession!
    private var socket: URLSessionWebSocketTask!
    private var isCancelled = false
    private var receivedCloseCode: URLSessionWebSocketTask.CloseCode?
    init(url: URL, subprotocol: String) { self.url = url; self.subprotocol = subprotocol }
    var normalClosure: Bool {
        lock.lock(); let code = receivedCloseCode; let socket = self.socket; lock.unlock()
        return code == .normalClosure || socket?.closeCode == .normalClosure
    }

    func open() async throws {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let config = URLSessionConfiguration.ephemeral
                config.timeoutIntervalForRequest = 10
                // Includes up to 10 s handshake plus the bounded 13 s direction.
                config.timeoutIntervalForResource = 25
                let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
                let session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
                let socket = session.webSocketTask(with: url, protocols: [subprotocol])
                socket.maximumMessageSize = 16 * 1_048_576
                let deadline = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: 10_000_000_000) } catch { return }
                    if self?.finishOpen(.failure(URLError(.timedOut))) == true { self?.close() }
                }
                lock.lock()
                self.continuation = continuation; self.session = session; self.socket = socket; self.timeout = deadline
                let cancelled = isCancelled
                lock.unlock()
                if cancelled { deadline.cancel(); finishOpen(.failure(CancellationError())); socket.cancel(); session.invalidateAndCancel(); return }
                socket.resume()
            }
        }, onCancel: { self.close() })
    }
    @discardableResult private func finishOpen(_ result: Result<Void, Error>) -> Bool {
        lock.lock(); let waiting = continuation; continuation = nil; lock.unlock()
        waiting?.resume(with: result)
        return waiting != nil
    }
    private func cancelTimeout() {
        lock.lock(); let deadline = timeout; timeout = nil; lock.unlock()
        deadline?.cancel()
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocolName: String?) {
        guard protocolName == subprotocol else { finishOpen(.failure(SpeedFailure.invalidResponse)); close(); return }
        cancelTimeout(); finishOpen(.success(()))
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        lock.lock(); receivedCloseCode = closeCode; lock.unlock()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error { cancelTimeout(); finishOpen(.failure(error)) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func receive() async throws -> URLSessionWebSocketTask.Message { try await socket.receive() }
    func send(_ message: URLSessionWebSocketTask.Message) async throws { try await socket.send(message) }
    func close() {
        cancelTimeout()
        lock.lock(); isCancelled = true; let socket = self.socket; let session = self.session; lock.unlock()
        finishOpen(.failure(CancellationError()))
        socket?.cancel(with: .normalClosure, reason: nil)
        session?.invalidateAndCancel()
    }
}

final class Ndt7Client {
    typealias SocketFactory = (URL, String) -> NdtSocket
    private let domain = IosSpeedDomain()
    private let http: any SpeedTransport
    private let socketFactory: SocketFactory
    private let duration: TimeInterval
    private let grace: TimeInterval
    init(http: any SpeedTransport = SpeedHTTP(), duration: TimeInterval = 10, grace: TimeInterval = 3,
         socketFactory: @escaping SocketFactory = { URLSessionNdtSocket(url: $0, subprotocol: $1) }) {
        self.http = http; self.duration = duration; self.grace = grace; self.socketFactory = socketFactory
    }
    func measure() async throws -> SpeedResult {
        guard let locate = URL(string: domain.ndtLocateUrl()) else { throw SpeedFailure.invalidServer }
        let (data, _) = try await http.read(URLRequest(url: locate), limit: 65_536)
        guard let text = String(data: data, encoding: .utf8), let target = domain.ndtTargets(text: text).first,
              let downURL = URL(string: target.downloadUrl), let upURL = URL(string: target.uploadUrl) else {
            throw SpeedFailure.invalidResponse
        }
        let down = try await direction(url: downURL, download: true)
        let up = try await direction(url: upURL, download: false)
        return SpeedResult(downloadMbps: down, uploadMbps: up)
    }
    func direction(url: URL, download: Bool) async throws -> Double {
        let socket = socketFactory(url, domain.ndtSubprotocol())
        defer { socket.close() }
        try await socket.open()
        let counter = SpeedCounter()
        let trace = domain.createTrace()
        let start = ProcessInfo.processInfo.systemUptime
        let reader = Task {
            do {
                while !Task.isCancelled {
                    switch try await socket.receive() {
                    case .data(let data):
                        guard download else { counter.fail(); return }
                        counter.add(Int64(data.count))
                    case .string(let text):
                        if text.utf8.count <= 65_536,
                           let rate = self.domain.ndtView(text: text, download: download)?.appMbps?.doubleValue,
                           rate.isFinite && rate >= 0 { counter.setServerMbps(rate) }
                    @unknown default: counter.fail(); return
                    }
                }
            } catch {
                if !Task.isCancelled {
                    let elapsed = ProcessInfo.processInfo.systemUptime - start
                    let normalDownloadEnd = download && socket.normalClosure && counter.snapshot().0 > 0 && elapsed >= 1
                    let normalUploadEnd = !download && socket.normalClosure && counter.serverRate() != nil && elapsed >= self.duration * 0.95
                    if normalDownloadEnd || normalUploadEnd { counter.markCompleted() }
                    else { counter.fail() }
                }
            }
        }
        let writer = Task {
            guard !download else { return }
            let sizer = self.domain.createMessageSizer()
            do {
                while !Task.isCancelled && ProcessInfo.processInfo.systemUptime - start < self.duration {
                    let size = Int(sizer.size)
                    var bytes = Data(count: size)
                    guard bytes.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!) }) == errSecSuccess else {
                        counter.fail(); return
                    }
                    try await socket.send(.data(bytes))
                    counter.add(Int64(size)); sizer.onQueued(bytes: Int32(size))
                }
            } catch {
                if !Task.isCancelled {
                    let normalEnd = socket.normalClosure && counter.serverRate() != nil &&
                        ProcessInfo.processInfo.systemUptime - start >= self.duration * 0.95
                    if !normalEnd { counter.fail() }
                }
            }
        }
        do {
            try await withTaskCancellationHandler(operation: {
                while ProcessInfo.processInfo.systemUptime - start < duration {
                    try await Task.sleep(nanoseconds: 250_000_000)
                    _ = trace.add(elapsedMs: Int64((ProcessInfo.processInfo.systemUptime - start) * 1000), bytes: counter.snapshot().0)
                    if counter.isCompleted() { break }
                }
                writer.cancel()
                if !counter.isCompleted() { try await Task.sleep(nanoseconds: UInt64(grace * 1_000_000_000)) }
            }, onCancel: { socket.close() })
        } catch {
            writer.cancel(); reader.cancel(); socket.close()
            _ = await writer.result; _ = await reader.result
            throw error
        }
        writer.cancel(); reader.cancel(); socket.close()
        _ = await writer.result; _ = await reader.result
        let (bytes, failures) = counter.snapshot()
        guard failures == 0, bytes > 0, let summary = domain.summarize(trace: trace) else { throw SpeedFailure.incomplete }
        if download { return summary.mbps }
        guard let serverRate = counter.serverRate() else { throw SpeedFailure.incomplete }
        return serverRate
    }
}

@MainActor
final class SpeedViewModel: ObservableObject {
    enum Engine: String, CaseIterable { case libre, ndt7 }
    @Published var engine: Engine = .libre
    @Published var server = ""
    @Published var running = false
    @Published var failed = false
    @Published var result: SpeedResult?
    private var task: Task<Void, Never>?

    func start() {
        guard task == nil else { return }
        result = nil; failed = false; running = true
        let selected = engine
        let input = server
        task = Task {
            do {
                let value: SpeedResult
                if selected == .libre {
                    value = try await LibreSpeedClient().measure(input: input)
                } else {
                    value = try await Ndt7Client().measure()
                }
                if !Task.isCancelled { self.result = value }
            } catch {
                if !Task.isCancelled { self.failed = true }
            }
            self.running = false
            self.task = nil
        }
    }
    func cancel() { task?.cancel() }
}

struct SpeedView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = SpeedViewModel()
    @State private var showConfirmation = false
    var body: some View {
        Form {
            Picker("speed.engine", selection: $model.engine) {
                Text("speed.libre").tag(SpeedViewModel.Engine.libre)
                Text("speed.ndt7").tag(SpeedViewModel.Engine.ndt7)
            }.disabled(model.running)
            if model.engine == .libre {
                TextField("speed.server", text: $model.server)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .disabled(model.running)
                Text("speed.parallel_warning").font(.footnote)
            } else {
                Link("speed.privacy_link", destination: URL(string: "https://www.measurementlab.net/privacy/")!)
                Text("speed.ndt_method").font(.footnote)
            }
            if model.running {
                ProgressView()
                Button("speed.cancel") { model.cancel() }
            } else {
                Button("speed.start") { showConfirmation = true }
            }
            if model.failed { Text("speed.error").foregroundStyle(.red) }
            if let result = model.result {
                LabeledContent("speed.download", value: result.downloadMbps.formatted(.number.precision(.fractionLength(2))) + " Mbit/s")
                LabeledContent("speed.upload", value: result.uploadMbps.formatted(.number.precision(.fractionLength(2))) + " Mbit/s")
            }
        }
        .navigationTitle("speed.title")
        .confirmationDialog("speed.confirm.title", isPresented: $showConfirmation, titleVisibility: .visible) {
            Button("speed.confirm.start") { model.start() }
            Button("speed.cancel", role: .cancel) { }
        } message: {
            if model.engine == .ndt7 { Text("speed.warning.ndt7") }
            else { Text("speed.warning.data") }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.cancel() }
        }
        .onDisappear { model.cancel() }
    }
}
