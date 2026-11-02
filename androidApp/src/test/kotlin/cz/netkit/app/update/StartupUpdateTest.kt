package cz.netkit.app.update

import cz.netkit.shared.update.UpdateCheckOutcome
import cz.netkit.shared.update.UpdateSource
import cz.netkit.shared.update.UpdateCheckFailure
import cz.netkit.shared.update.UpdateInfo
import cz.netkit.shared.update.UpdateStatus
import kotlin.test.*

class StartupUpdateTest {
    private val info = UpdateInfo("9.9.9", 90909, 29, "https://github.com/Losminek/netkit/releases/tag/v9.9.9", null, null, null, null, emptyMap())
    private fun available() = UpdateUiState(loaded = true, available = true, info = info,
        status = UpdateStatus.AVAILABLE, startupDialogReady = true)

    @Test fun onlyAvailableEnabledSuccessfulStartupShowsDialog() {
        assertEquals(info, available().dialog)
        for (status in UpdateStatus.entries.filter { it != UpdateStatus.AVAILABLE }) {
            assertNull(available().copy(status = status).dialog)
        }
        assertNull(available().copy(enabled = false).dialog)
        assertNull(available().copy(available = false).dialog)
        assertNull(available().copy(startupDialogReady = false).dialog)
        for (failure in UpdateCheckFailure.entries) assertNull(available().copy(lastFailure = failure).dialog)
    }

    @Test fun closeHidesDialogAndKeepsCard() {
        assertNull(available().copy(dialogClosed = true).dialog)
        assertEquals(info, available().copy(dialogClosed = true).card)
        assertNull(available().copy(status = UpdateStatus.DISMISSED).card)
    }

    @Test fun foregroundAndRotationCannotClaimAnotherStartup() {
        val process = StartupUpdateSession()
        assertTrue(process.claimStart())
        repeat(5) { assertFalse(process.claimStart()) }
        process.close()
        assertTrue(process.closed)
        assertFalse(process.claimStart())
        assertTrue(StartupUpdateSession().claimStart())
    }

    @Test fun restartUsesCacheForSixtyMinutesIncludingFailedAttempts() {
        assertTrue(isStartupCheckDue(null, 100))
        assertFalse(isStartupCheckDue(100, 100))
        assertFalse(isStartupCheckDue(100, 3_600_099))
        assertTrue(isStartupCheckDue(100, 3_600_100))
        assertTrue(isStartupCheckDue(100, 99))
    }

    @Test fun foregroundRefreshUsesTwentyFourHoursAndNeverOpensDialog() {
        val session = StartupUpdateSession()
        assertTrue(session.claimStart())
        assertTrue(session.isAutomaticCheckDue(100, 3_600_100))
        session.complete()
        assertFalse(session.isAutomaticCheckDue(100, 3_600_100))
        assertFalse(session.isAutomaticCheckDue(100, 86_400_099))
        assertTrue(session.isAutomaticCheckDue(100, 86_400_100))
        assertTrue(session.isAutomaticCheckDue(100, 99))
        val outcome = UpdateCheckOutcome.Found(info, UpdateSource.WEB, "{}")
        // Even a previous startup-ready result must not make a later card refresh open a dialog.
        val refreshed = available().afterCheck(outcome, UpdateStatus.AVAILABLE, 86_400_100, session.pending, false)
        assertEquals(info, refreshed.card)
        assertNull(refreshed.dialog)
        assertFalse(refreshed.startupDialogReady)
    }

    @Test fun cancelledStartupRemainsPendingForManualSuccess() {
        val session = StartupUpdateSession()
        session.claimStart()
        // A background/foreground cycle does not complete the cancelled startup.
        assertFalse(session.claimStart())
        assertTrue(session.pending)
        assertFalse(session.isAutomaticCheckDue(100, 200), "Persisted cancelled attempt still respects 60 min")
        val outcome = UpdateCheckOutcome.Found(info, UpdateSource.WEB, "{}")
        val ready = available().copy(startupDialogReady = false).afterCheck(
            outcome, UpdateStatus.AVAILABLE, 200, session.pending, session.closed)
        session.complete()
        assertEquals(info, ready.dialog)
        assertFalse(session.pending)
        assertFalse(session.claimStart())
    }

    @Test fun failedStartupCannotOpenDialogAndCompletesAttempt() {
        val session = StartupUpdateSession()
        session.claimStart()
        val failed = available().afterCheck(UpdateCheckOutcome.Failed(UpdateCheckFailure.NETWORK),
            null, 200, session.pending, false)
        session.complete()
        assertNull(failed.dialog)
        assertEquals(info, failed.card)
        assertFalse(session.pending)
    }

}
