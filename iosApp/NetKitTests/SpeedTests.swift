@testable import NetKit
import XCTest
import Foundation
import NetKitShared

private final class SpeedMockProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var posts = 0
    static func reset() { lock.lock(); posts = 0; lock.unlock() }
    static func postCount() -> Int { lock.lock(); defer { lock.unlock() }; return posts }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        if request.url?.path == "/wait" { return }
        let isDownload = request.url?.path == "/download"
        let body = isDownload ? Data(repeating: 42, count: 1_048_576) : Data()
        if request.httpMethod == "POST" {
            Self.lock.lock(); Self.posts += 1; Self.lock.unlock()
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/octet-stream", "Content-Length": "\(body.count)"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class SpeedTests: XCTestCase {
    private func fixture() -> SpeedHTTP {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [SpeedMockProtocol.self]
        return SpeedHTTP(configuration: config)
    }

    func testBoundedDownloadAndUpload() async throws {
        let http = fixture()
        let counter = SpeedCounter()
        let base = URL(string: "https://example.invalid")!
        try await http.download(URLRequest(url: base.appendingPathComponent("download")), counter: counter)
        XCTAssertEqual(counter.snapshot().0, 1_048_576)
        SpeedMockProtocol.reset()
        var request = URLRequest(url: base.appendingPathComponent("upload"))
        request.httpMethod = "POST"
        try await http.upload(request, payload: Data(repeating: 42, count: 1024), counter: SpeedCounter())
        XCTAssertEqual(SpeedMockProtocol.postCount(), 1)
        do {
            _ = try await http.read(URLRequest(url: base.appendingPathComponent("download")), limit: 3)
            XCTFail("An oversized response must be rejected")
        } catch SpeedHTTP.Failure.responseTooLarge { }
    }

    func testSharedServerAndNdtValidation() {
        let domain = IosSpeedDomain()
        XCTAssertNotNil(domain.libreServer(input: "https://example.invalid/"))
        XCTAssertNil(domain.libreServer(input: "http://example.invalid/"))
        XCTAssertTrue(domain.ndtTargets(text: "{} (invalid)").isEmpty)
        let result = domain.ndtView(text: #"{"AppInfo":{"ElapsedTime":1000000,"NumBytes":1000000}}"#, download: true)
        XCTAssertEqual(result?.appMbps?.doubleValue, 8)
    }

    func testCancelPendingRead() async throws {
        let http = fixture()
        let task = Task { try await http.read(URLRequest(url: URL(string: "https://example.invalid/wait")!), limit: 512) }
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("The pending read should be cancelled")
        } catch let error as URLError where error.code == .cancelled { }
        catch is CancellationError { }
        catch { XCTFail("Unexpected error: \(error)") }
    }
}

private final class FakeSpeedTransport: SpeedTransport {
    private let lock = NSLock()
    private var downloads = 0
    private var uploads = 0
    let failDownload: Bool
    init(failDownload: Bool = false) { self.failDownload = failDownload }
    func counts() -> (Int, Int) { lock.lock(); defer { lock.unlock() }; return (downloads, uploads) }
    private func countDownload() { lock.lock(); downloads += 1; lock.unlock() }
    private func countUpload() { lock.lock(); uploads += 1; lock.unlock() }
    func read(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse) {
        (Data(), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
    func download(_ request: URLRequest, counter: SpeedCounter) async throws {
        countDownload()
        if failDownload { throw URLError(.cannotConnectToHost) }
        for _ in 0..<10 { try await Task.sleep(nanoseconds: 50_000_000); counter.add(100_000) }
    }
    func upload(_ request: URLRequest, payload: Data, counter: SpeedCounter) async throws {
        countUpload()
        for _ in 0..<10 { try await Task.sleep(nanoseconds: 50_000_000); counter.add(100_000) }
    }
}

private final class LimitedUploadTransport: SpeedTransport {
    private let lock = NSLock()
    private var sizes: [Int] = []
    enum Mode { case limited, always413, otherFailure }
    let mode: Mode
    init(mode: Mode = .limited) { self.mode = mode }
    private func record(_ size: Int) { lock.lock(); sizes.append(size); lock.unlock() }
    func attemptedSizes() -> [Int] { lock.lock(); defer { lock.unlock() }; return sizes }
    func read(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse) {
        (Data(), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
    func download(_ request: URLRequest, counter: SpeedCounter) async throws {
        try await Task.sleep(nanoseconds: 50_000_000); counter.add(100_000)
    }
    func upload(_ request: URLRequest, payload: Data, counter: SpeedCounter) async throws {
        record(payload.count)
        if mode == .otherFailure { throw SpeedHTTP.Failure.invalidResponse }
        if mode == .always413 || payload.count > 256 * 1024 { throw SpeedHTTP.Failure.payloadTooLarge }
        try await Task.sleep(nanoseconds: 50_000_000); counter.add(Int64(payload.count))
    }
}

private actor AsyncTestSignal {
    private var signaled = false
    private var waiter: CheckedContinuation<Void, Never>?
    func wait() async {
        if signaled { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func signal() {
        signaled = true
        waiter?.resume()
        waiter = nil
    }
}

private final class FakeNdtSocket: NdtSocket {
    enum End: Error { case broken }
    private let lock = NSLock()
    private var reads = 0
    private var sends = 0
    private var closed = false
    private var normal = false
    private var sendFailedBeforeReader = false
    private var readerFinished = false
    private let sendFailureSignal = AsyncTestSignal()
    private var startedAt: TimeInterval = 0
    private var appInfoDelivered = false
    let download: Bool
    let failAfterData: Bool
    let streaming: Bool
    let closeDuringUpload: Bool
    let failOpen: Bool
    let noData: Bool
    init(download: Bool, failAfterData: Bool = false, streaming: Bool = false,
         closeDuringUpload: Bool = false, failOpen: Bool = false, noData: Bool = false) {
        self.download = download; self.failAfterData = failAfterData; self.streaming = streaming
        self.closeDuringUpload = closeDuringUpload; self.failOpen = failOpen; self.noData = noData
    }
    var normalClosure: Bool { lock.lock(); defer { lock.unlock() }; return normal }
    func open() async throws {
        if failOpen { throw End.broken }
        lock.lock(); startedAt = ProcessInfo.processInfo.systemUptime; lock.unlock()
    }
    private func nextRead() -> Int { lock.lock(); defer { lock.unlock() }; reads += 1; return reads }
    private func recordSend() { lock.lock(); sends += 1; lock.unlock() }
    func state() -> (Int, Bool) { lock.lock(); defer { lock.unlock() }; return (sends, closed) }
    func readCount() -> Int { lock.lock(); defer { lock.unlock() }; return reads }
    func wasSendFailureFirst() -> Bool { lock.lock(); defer { lock.unlock() }; return sendFailedBeforeReader }
    private func markAppInfoDelivered() { lock.lock(); appInfoDelivered = true; lock.unlock() }
    private func considerNormalClose() {
        lock.lock(); defer { lock.unlock() }
        // open() starts this clock just before the client starts its own; leave a margin over 95%.
        if closeDuringUpload && appInfoDelivered && sends > 1 &&
            ProcessInfo.processInfo.systemUptime - startedAt >= 3 * 0.96 { normal = true }
    }
    private func markReaderFinished() { lock.lock(); readerFinished = true; lock.unlock() }
    private func checkSendFailure() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if normal { sendFailedBeforeReader = !readerFinished; return true }
        return false
    }
    func receive() async throws -> URLSessionWebSocketTask.Message {
        let number = nextRead()
        if noData {
            try await Task.sleep(nanoseconds: 30_000_000_000)
            throw CancellationError()
        }
        if number == 1 {
            if download { return .data(Data(repeating: 42, count: 1_048_576)) }
            markAppInfoDelivered()
            return .string(#"{"AppInfo":{"ElapsedTime":1000000,"NumBytes":1000000}}"#)
        }
        if failAfterData { throw End.broken }
        if download && streaming {
            try await Task.sleep(nanoseconds: 50_000_000)
            return .data(Data(repeating: 42, count: 65_536))
        }
        if !download && closeDuringUpload {
            await sendFailureSignal.wait()
            markReaderFinished()
            throw End.broken
        }
        try await Task.sleep(nanoseconds: 30_000_000_000)
        throw CancellationError()
    }
    func send(_ message: URLSessionWebSocketTask.Message) async throws {
        recordSend(); try await Task.sleep(nanoseconds: 10_000_000)
        considerNormalClose()
        if checkSendFailure() { await sendFailureSignal.signal(); throw End.broken }
    }
    func close() {
        lock.lock(); closed = true; lock.unlock()
        Task { await sendFailureSignal.signal() }
    }
}

extension SpeedTests {
    func testLibreSpeedReducesRejectedUploadToMinimum() async throws {
        let transport = LimitedUploadTransport()
        let result = try await LibreSpeedClient(http: transport, phaseDuration: 2.0)
            .measure(input: "https://example.invalid/")
        let sizes = transport.attemptedSizes()
        XCTAssertTrue(sizes.contains(8 * 1_048_576))
        XCTAssertTrue(sizes.contains(256 * 1024))
        XCTAssertFalse(sizes.contains { $0 < 256 * 1024 })
        XCTAssertGreaterThan(result.uploadMbps, 0)
    }

    func testUploadSizingIgnoresStale413AfterSmallerBodyAccepted() {
        let sizing = UploadSizing()
        let old = sizing.current()
        XCTAssertTrue(sizing.reduce(after: old))
        let smaller = sizing.current()
        sizing.markAccepted()
        XCTAssertTrue(sizing.reduce(after: old))
        XCTAssertEqual(sizing.current(), smaller)
        XCTAssertFalse(sizing.reduce(after: smaller))
    }

    func testPermanent413StopsAtMinimumAndFails() async throws {
        let transport = LimitedUploadTransport(mode: .always413)
        do {
            _ = try await LibreSpeedClient(http: transport, phaseDuration: 2.0).measure(input: "https://example.invalid/")
            XCTFail("Permanent 413 must fail the measurement")
        } catch SpeedFailure.incomplete { }
        let sizes = transport.attemptedSizes()
        XCTAssertTrue(sizes.contains(256 * 1024))
        XCTAssertFalse(sizes.contains { $0 < 256 * 1024 })
        XCTAssertLessThanOrEqual(sizes.count, 18)
    }

    func testNon413DoesNotRetrySmallerBodies() async throws {
        let transport = LimitedUploadTransport(mode: .otherFailure)
        do {
            _ = try await LibreSpeedClient(http: transport, phaseDuration: 2.0).measure(input: "https://example.invalid/")
            XCTFail("A non-413 failure must fail the measurement")
        } catch SpeedFailure.incomplete { }
        XCTAssertFalse(transport.attemptedSizes().isEmpty)
        XCTAssertTrue(transport.attemptedSizes().allSatisfy { $0 == 8 * 1_048_576 })
    }

    func testLibreSpeedRunsBothDirectionsAndFailsClosed() async throws {
        let fake = FakeSpeedTransport()
        let result = try await LibreSpeedClient(http: fake, phaseDuration: 1.5).measure(input: "https://example.invalid/")
        XCTAssertGreaterThan(result.downloadMbps, 0)
        XCTAssertGreaterThan(result.uploadMbps, 0)
        XCTAssertGreaterThanOrEqual(fake.counts().0, 6)
        XCTAssertGreaterThanOrEqual(fake.counts().1, 3)
        do {
            _ = try await LibreSpeedClient(http: FakeSpeedTransport(failDownload: true), phaseDuration: 1.5)
                .measure(input: "https://example.invalid/")
            XCTFail("A failed direction must not produce a result")
        } catch { }
    }

    func testLibreSpeedCancellation() async throws {
        let task = Task { try await LibreSpeedClient(http: FakeSpeedTransport(), phaseDuration: 10)
            .measure(input: "https://example.invalid/") }
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        do { _ = try await task.value; XCTFail("Expected cancellation") }
        catch is CancellationError { }
        catch { XCTFail("Unexpected error: \(error)") }
    }

    func testNdtDownloadWithoutDataIsIncomplete() async throws {
        let socket = FakeNdtSocket(download: true, noData: true)
        let client = Ndt7Client(duration: 1.5, grace: 0, socketFactory: { _, _ in socket })
        do {
            _ = try await client.direction(url: URL(string: "wss://example.invalid/")!, download: true)
            XCTFail("A download without data must be incomplete")
        } catch SpeedFailure.incomplete { }
        XCTAssertTrue(socket.state().1)
    }

    func testNdtBurstThenStallReturnsBoundedRate() async throws {
        let socket = FakeNdtSocket(download: true)
        let client = Ndt7Client(duration: 3, grace: 0, socketFactory: { _, _ in socket })
        let rate = try await client.direction(url: URL(string: "wss://example.invalid/")!, download: true)
        XCTAssertTrue(rate.isFinite)
        XCTAssertGreaterThanOrEqual(rate, 0)
        // One MiB total, divided by at least 1000 ms of measured time.
        XCTAssertLessThanOrEqual(rate, 8.388_609)
        XCTAssertGreaterThanOrEqual(socket.readCount(), 2)
        XCTAssertTrue(socket.state().1)
    }

    func testSharedBurstThenStallHasZeroPostWarmupRate() throws {
        let domain = IosSpeedDomain()
        let trace = domain.createTrace()
        for tick in 1...12 {
            XCTAssertTrue(trace.add(elapsedMs: Int64(tick * 250), bytes: 1_048_576))
        }
        let summary = try XCTUnwrap(domain.summarize(trace: trace))
        XCTAssertEqual(summary.mbps, 0, accuracy: 0.000_001)
    }

    func testNdtFlowingDownloadProducesRate() async throws {
        let socket = FakeNdtSocket(download: true, streaming: true)
        let client = Ndt7Client(duration: 3, grace: 0, socketFactory: { _, _ in socket })
        let rate = try await client.direction(url: URL(string: "wss://example.invalid/")!, download: true)
        XCTAssertGreaterThan(rate, 0)
    }

    func testNdtUploadUsesServerRate() async throws {
        let socket = FakeNdtSocket(download: false)
        let client = Ndt7Client(duration: 3, grace: 0, socketFactory: { _, _ in socket })
        let rate = try await client.direction(url: URL(string: "wss://example.invalid/")!, download: false)
        XCTAssertEqual(rate, 8)
        XCTAssertGreaterThan(socket.state().0, 0)
    }

    func testNdtBrokenConnectionFails() async throws {
        let socket = FakeNdtSocket(download: true, failAfterData: true)
        let client = Ndt7Client(duration: 1.5, grace: 0, socketFactory: { _, _ in socket })
        do {
            _ = try await client.direction(url: URL(string: "wss://example.invalid/")!, download: true)
            XCTFail("Broken socket must fail")
        } catch SpeedFailure.incomplete { }
    }

    func testNdtCancellationClosesSocket() async throws {
        let socket = FakeNdtSocket(download: true)
        let client = Ndt7Client(duration: 10, grace: 0, socketFactory: { _, _ in socket })
        let task = Task { try await client.direction(url: URL(string: "wss://example.invalid/")!, download: true) }
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        do { _ = try await task.value; XCTFail("Expected cancellation") }
        catch is CancellationError { }
        catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertTrue(socket.state().1)
    }
}


extension SpeedTests {
    func testNdtUploadNormalCloseAfterSendError() async throws {
        let socket = FakeNdtSocket(download: false, closeDuringUpload: true)
        let client = Ndt7Client(duration: 3, grace: 0.2, socketFactory: { _, _ in socket })
        let result = try await client.direction(url: URL(string: "wss://example.invalid/")!, download: false)
        XCTAssertEqual(result, 8)
        XCTAssertTrue(socket.wasSendFailureFirst())
    }

    func testNdtOpenFailureStartsNoTransfer() async throws {
        let socket = FakeNdtSocket(download: false, failOpen: true)
        let client = Ndt7Client(duration: 1.5, grace: 0, socketFactory: { _, _ in socket })
        do {
            _ = try await client.direction(url: URL(string: "wss://example.invalid/")!, download: false)
            XCTFail("Failed handshake must fail the direction")
        } catch FakeNdtSocket.End.broken { }
        XCTAssertEqual(socket.state().0, 0)
        XCTAssertTrue(socket.state().1)
    }
}
