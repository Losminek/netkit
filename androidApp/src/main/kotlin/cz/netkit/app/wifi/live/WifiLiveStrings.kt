package cz.netkit.app.wifi.live

import androidx.annotation.StringRes
import cz.netkit.app.R
import cz.netkit.shared.wifi.SignalQuality

/** Localized explanation of a [WifiLiveIssue] (Czech default, English in `values-en`). */
@StringRes
fun wifiLiveIssueText(issue: WifiLiveIssue): Int = when (issue) {
    WifiLiveIssue.NO_WIFI -> R.string.wifi_live_issue_no_wifi
    WifiLiveIssue.PERMISSION_MISSING -> R.string.wifi_live_issue_permission
    WifiLiveIssue.LOCATION_DISABLED -> R.string.wifi_live_issue_location
    WifiLiveIssue.ICMP_UNAVAILABLE -> R.string.wifi_live_issue_icmp
    WifiLiveIssue.NO_GATEWAY -> R.string.wifi_live_issue_no_gateway
    WifiLiveIssue.TRAFFIC_STATS_UNAVAILABLE -> R.string.wifi_live_issue_traffic_stats
    WifiLiveIssue.LINK_MONITOR_FAILED -> R.string.wifi_live_issue_monitor
}

/** Short label of a [SignalQuality]. */
@StringRes
fun signalQualityText(quality: SignalQuality): Int = when (quality) {
    SignalQuality.EXCELLENT -> R.string.wifi_live_quality_excellent
    SignalQuality.GOOD -> R.string.wifi_live_quality_good
    SignalQuality.FAIR -> R.string.wifi_live_quality_fair
    SignalQuality.POOR -> R.string.wifi_live_quality_poor
    SignalQuality.UNKNOWN -> R.string.wifi_live_quality_unknown
}

/** Short label of a latency probe status; `null` for [LiveProbeStatus.OK] (show the value instead). */
@StringRes
fun liveProbeStatusText(status: LiveProbeStatus): Int? = when (status) {
    LiveProbeStatus.OK -> null
    LiveProbeStatus.IDLE -> R.string.wifi_live_probe_idle
    LiveProbeStatus.LOST -> R.string.wifi_live_probe_lost
    LiveProbeStatus.NO_WIFI -> R.string.wifi_live_probe_no_wifi
    LiveProbeStatus.NO_NETWORK -> R.string.net_kind_none
    LiveProbeStatus.NO_TARGET -> R.string.wifi_live_probe_no_target
    LiveProbeStatus.ICMP_UNAVAILABLE -> R.string.wifi_live_probe_icmp
}

/**
 * Short label of a DNS probe status; `null` for [LiveDnsStatus.OK]. [LiveDnsStatus.ERROR_RCODE]
 * takes one `%1$d` argument: [LiveDns.lastRcode].
 */
@StringRes
fun liveDnsStatusText(status: LiveDnsStatus): Int? = when (status) {
    LiveDnsStatus.OK -> null
    LiveDnsStatus.IDLE -> R.string.wifi_live_probe_idle
    LiveDnsStatus.TIMEOUT -> R.string.wifi_live_dns_timeout
    LiveDnsStatus.FAILED -> R.string.wifi_live_dns_failed
    LiveDnsStatus.ERROR_RCODE -> R.string.wifi_live_dns_rcode
    LiveDnsStatus.NO_NETWORK -> R.string.net_kind_none
}

/** Validation message for a rejected [WifiLiveViewModel.setInternetTarget]. */
@StringRes
fun liveInternetTargetInvalidText(): Int = R.string.wifi_live_target_invalid

/** Validation message for a rejected [WifiLiveViewModel.setDnsName]. */
@StringRes
fun liveDnsNameInvalidText(): Int = R.string.wifi_live_dns_name_invalid
