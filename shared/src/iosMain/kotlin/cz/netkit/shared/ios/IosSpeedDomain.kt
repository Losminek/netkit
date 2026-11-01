package cz.netkit.shared.ios

import cz.netkit.shared.speed.*

/** URLSession owns transport; protocol policy, parsers and measurement math remain shared. */
class IosSpeedDomain {
    fun libreServer(input: String): LibreSpeedServer? = (checkLibreSpeedUrl(input) as? LibreSpeedUrlCheck.Valid)?.server
    fun garbageUrl(base: String, nonce: Long): String = LibreSpeedProtocol.garbageUrl(base, nonce)
    fun emptyUrl(base: String, nonce: Long): String = LibreSpeedProtocol.emptyUrl(base, nonce)
    fun ndtLocateUrl(): String = Ndt7Protocol.LOCATE_URL
    fun ndtSubprotocol(): String = Ndt7Protocol.SUBPROTOCOL
    fun createMessageSizer(): Ndt7MessageSizer = Ndt7MessageSizer()
    fun ndtTargets(text: String): List<Ndt7Target> = parseNdt7Locate(text)
    fun ndtView(text: String, download: Boolean): Ndt7ServerView? = parseNdt7Measurement(text)?.let { ndt7ServerView(it, download) }
    fun createTrace(): ThroughputTrace = ThroughputTrace()
    fun summarize(trace: ThroughputTrace): ThroughputSummary? = summarizeThroughput(trace.samples)
    fun latency(samples: List<Double?>): LatencyStats = latencyStats(samples)
}
