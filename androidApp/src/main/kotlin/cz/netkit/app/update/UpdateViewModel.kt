package cz.netkit.app.update

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.netkit.app.BuildConfig
import cz.netkit.shared.update.UpdateCheckFailure
import cz.netkit.shared.update.UpdateCheckOutcome
import cz.netkit.shared.update.UpdateInfo
import cz.netkit.shared.update.UpdateSource
import cz.netkit.shared.update.UpdateStatus
import cz.netkit.shared.update.updateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the overview card and About show. [info] is the last valid manifest (also from a previous
 * day); [status] compares it with this build and the dismissed version.
 */
data class UpdateUiState(
    val loaded: Boolean = false,
    /** False when the build has neither a real web nor a GitHub source (nor a debug fixture): the switch is inert. */
    val available: Boolean = false,
    val enabled: Boolean = true,
    val checking: Boolean = false,
    val info: UpdateInfo? = null,
    val status: UpdateStatus? = null,
    val lastCheckMs: Long? = null,
    /** Failure of the last check in this process; `null` after a success or before any check. */
    val lastFailure: UpdateCheckFailure? = null,
    /** Source of the last check when it succeeded (About says "via GitHub"), else `null`. */
    val lastSource: UpdateSource? = null,
    /** Configured web host, `null` without a web. */
    val webHost: String? = null,
    /** GitHub releases are asked (after the web, or alone). */
    val githubSource: Boolean = false,
    val startupDialogReady: Boolean = false,
    val dialogClosed: Boolean = false,
) {
    /** Only a successful startup result (or validated cache) may open the process dialog. */
    val dialog: UpdateInfo? get() = card?.takeIf { startupDialogReady && !dialogClosed && lastFailure == null }

    /** The overview card keeps its existing behavior, including a cached result after errors. */
    val card: UpdateInfo? get() = info?.takeIf { available && enabled && status == UpdateStatus.AVAILABLE }
}

/** Startup may show a dialog; later foreground checks (24 h) only refresh the card. */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val checker = UpdateChecker(application)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var foreground = false
    private val startup = processStartup

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val info = checker.cached()
                UpdateUiState(
                    loaded = true, available = checker.isAvailable(), enabled = checker.isEnabled(), info = info,
                    status = info?.let { statusOf(it, checker.dismissedVersionCode()) }, lastCheckMs = checker.lastCheckMs(),
                    lastSource = checker.lastSuccessSource(), webHost = checker.webHost, githubSource = checker.hasGithubSource,
                )
            }
            _state.value = loaded
            if (foreground) maybeAutomaticCheck()
        }
    }

    fun onForeground() {
        foreground = true
        startup.claimStart()
        if (_state.value.loaded) maybeAutomaticCheck()
    }

    fun onBackground() {
        foreground = false
        cancel()
    }

    fun checkNow() {
        val current = _state.value
        if (!current.loaded || !current.available || current.checking) return
        start(automatic = false)
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) cancel()
        _state.update { it.copy(enabled = enabled) }
        viewModelScope.launch(Dispatchers.IO) { checker.setEnabled(enabled) }
    }

    fun closeDialog() {
        startup.close()
        _state.update { it.copy(dialogClosed = true) }
    }

    /** Hides the card until a version above [info] appears. */
    fun dismiss(info: UpdateInfo) {
        closeDialog()
        _state.update { it.copy(status = if (it.info == info) UpdateStatus.DISMISSED else it.status) }
        viewModelScope.launch(Dispatchers.IO) { checker.dismiss(info.versionCode) }
    }

    private fun maybeAutomaticCheck() {
        val current = _state.value
        if (!current.available || !current.enabled || current.checking) return
        start(automatic = true)
    }

    private fun start(automatic: Boolean) {
        job?.cancel()
        _state.update { it.copy(checking = true) }
        job = viewModelScope.launch {
            try {
                // A cancelled attempt may have persisted its time without delivering an outcome.
                val lastAttempt = withContext(Dispatchers.IO) { checker.lastCheckMs() }
                _state.update { it.copy(lastCheckMs = lastAttempt) }
                if (automatic && !startup.isAutomaticCheckDue(lastAttempt, System.currentTimeMillis())) {
                    if (startup.pending) {
                        _state.update { it.copy(startupDialogReady = true, dialogClosed = startup.closed) }
                        startup.complete()
                    }
                    return@launch
                }
                val forStartup = startup.pending
                val outcome = withContext(Dispatchers.IO) { checker.check() }
                val dismissed = withContext(Dispatchers.IO) { checker.dismissedVersionCode() }
                val lastCheck = withContext(Dispatchers.IO) { checker.lastCheckMs() }
                _state.update { state -> state.afterCheck(outcome,
                    (outcome as? UpdateCheckOutcome.Found)?.info?.let { statusOf(it, dismissed) },
                    lastCheck, forStartup, startup.closed) }
                startup.complete()
            } finally {
                // A replaced job must not clear the flag of its successor.
                val self = coroutineContext[Job]
                if (job == null || job === self) _state.update { it.copy(checking = false) }
            }
        }
    }

    private fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(checking = false) }
    }

    private companion object { val processStartup = StartupUpdateSession() }

    private fun statusOf(info: UpdateInfo, dismissed: Int?): UpdateStatus =
        updateStatus(info, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, dismissed)
}


/** Card-only refreshes explicitly clear startup eligibility, including an earlier up-to-date result. */
internal fun UpdateUiState.afterCheck(
    outcome: UpdateCheckOutcome, status: UpdateStatus?, lastCheck: Long?, forStartup: Boolean, closed: Boolean,
): UpdateUiState = when (outcome) {
    is UpdateCheckOutcome.Found -> copy(info = outcome.info, status = status,
        lastFailure = null, lastCheckMs = lastCheck, lastSource = outcome.source,
        startupDialogReady = forStartup, dialogClosed = closed)
    is UpdateCheckOutcome.Failed -> copy(lastFailure = outcome.failure, lastCheckMs = lastCheck,
        lastSource = null, startupDialogReady = false)
}
