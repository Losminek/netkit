package cz.netkit.shared.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiDomainTest {
    @Test fun frequencyMappingIncludesSpecialChannels() {
        assertEquals(1, channelForFrequency(2412))
        assertEquals(14, channelForFrequency(2484))
        assertEquals(36, channelForFrequency(5180))
        assertEquals(2, channelForFrequency(5935))
        assertEquals(1, channelForFrequency(5955))
        assertEquals(WifiBand.GHZ_6, bandForFrequency(5935))
        assertEquals(null, channelForFrequency(5940))
        assertEquals(null, channelForFrequency(5801))
    }

    @Test fun securityAndSignalAreClassified() {
        assertEquals(WifiSecurity.WPA3, classifySecurity("[WPA3-SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA2, classifySecurity("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA2, classifySecurity("[RSN-EAP-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA2, classifySecurity("[WPA2-EAP-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA3, classifySecurity("[RSN-EAP/SHA256-CCMP][MFPR][MFPC][ESS]"))
        assertEquals(WifiSecurity.WPA2, classifySecurity("[RSN-EAP/SHA256-CCMP][RSN-EAP/SHA1-CCMP][MFPR][MFPC][ESS]"))
        assertEquals(WifiSecurity.WPA3, classifySecurity("[RSN-EAP-SUITE_B_192-CCMP][MFPR][ESS]"))
        assertEquals(WifiSecurity.OWE_TRANSITION, classifySecurity("[OWE_TRANSITION][ESS]"))
        assertEquals(WifiSecurity.OPEN, classifySecurity("[ESS]"))
        assertEquals(WifiSecurity.OPEN, classifySecurity("[WPS][ESS]"))
        assertEquals(WifiSecurity.UNKNOWN, classifySecurity(""))
        assertEquals(WifiSecurity.UNKNOWN, classifySecurity("[WAPI-PSK][ESS]"))
        assertEquals(4, signalLevel(-45))
        assertEquals(0, signalLevel(-90))
    }

    @Test fun localAddressFlagAndStableRecommendations() {
        assertTrue(isLocallyAdministered("02:11:22:33:44:55"))
        assertFalse(isLocallyAdministered("00:11:22:33:44:55"))
        val points = listOf(AccessPoint(ssid = "a", bssid = "00:00:00:00:00:01", rssiDbm = -40,
            frequencyMhz = 2412, channel = 1, band = WifiBand.GHZ_2_4, widthMhz = 20,
            security = WifiSecurity.WPA2, standard = "n", ageMs = 0, locallyAdministered = false))
        assertEquals(0.0001, overlapScore(1, WifiBand.GHZ_2_4, points), 1e-12)
        assertEquals(0.0, overlapScore(5, WifiBand.GHZ_2_4, points), 1e-12)
        assertEquals(6, recommendChannels(points).first { it.band == WifiBand.GHZ_2_4 }.channel)
        assertEquals(recommendChannels(points), recommendChannels(points))
        assertEquals(listOf("a"), sortedAccessPoints(points, null, WifiSort.NAME).map { it.ssid })
    }

    @Test fun eightyPlusEightyOccupiesTwoDisjointSegments() {
        val bonded = AccessPoint(ssid = "bonded", bssid = "00:00:00:00:00:02", rssiDbm = -40,
            frequencyMhz = 5180, centerFrequencyMhz = 5210, channel = 36, band = WifiBand.GHZ_5,
            widthMhz = 160, security = WifiSecurity.WPA2, standard = "ac", ageMs = 0,
            locallyAdministered = false, secondaryCenterFrequencyMhz = 5530, is80Plus80 = true)

        // 5210±40 and 5530±40 MHz; there is no occupied spectrum around channel 52/5260.
        assertEquals(listOf(OccupiedFrequencySegment(5170.0, 5250.0), OccupiedFrequencySegment(5490.0, 5570.0)),
            occupiedFrequencySegments(bonded))
        assertEquals(0.0, overlapScore(52, WifiBand.GHZ_5, listOf(bonded)), 1e-12)
        // Channel 100/5500 is covered by the second 80 MHz segment.
        assertEquals(0.0001, overlapScore(100, WifiBand.GHZ_5, listOf(bonded)), 1e-12)
        assertEquals(0.0001, overlapScore(36, WifiBand.GHZ_5, listOf(bonded)), 1e-12)
        assertEquals(listOf(OccupiedFrequencySegment(5170.0, 5250.0)),
            occupiedFrequencySegments(bonded.copy(secondaryCenterFrequencyMhz = null)))
    }
}
