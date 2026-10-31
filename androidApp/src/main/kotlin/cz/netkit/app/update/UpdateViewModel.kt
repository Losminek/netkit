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
) {
    /** The overview card: a newer, compatible, not dismissed version while checks are on. */
    val card: UpdateInfo? get() = info?.takeIf { available && enabled && status == UpdateStatus.AVAILABLE }
}

/**
 * Drives [UpdateChecker]: an automatic check only while the activity is started ([onForeground]
 * from `MainActivity.onStart`, cancelled by [onBackground]) and at most once per 24 h; a manual
 * [checkNow] from About ignores the interval. Turning the switch off cancels a running check.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val checker = UpdateChecker(application)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var foreground = false

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
            if (foreground) maybeAutoCheck()
        }
    }

    fun onForeground() {
        foreground = true
        if (_state.value.loaded) maybeAutoCheck()
    }

    fun onBackground() {
        foreground = false
        cancel()
    }

    fun checkNow() {
        val current = _state.value
        if (!current.loaded || !current.available || current.checking) return
        start()
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) cancel()
        _state.update { it.copy(enabled = enabled) }
        viewModelScope.launch(Dispatchers.IO) { checker.setEnabled(enabled) }
    }

    /** Hides the card until a version above [info] appears. */
    fun dismiss(info: UpdateInfo) {
        _state.update { it.copy(status = if (it.info == info) UpdateStatus.DISMISSED else it.status) }
        viewModelScope.launch(Dispatchers.IO) { checker.dismiss(info.versionCode) }
    }

    private fun maybeAutoCheck() {
        val current = _state.value
        if (!current.available || !current.enabled || current.checking) return
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { checker.isDue() } && foreground && _state.value.enabled && job?.isActive != true) start()
        }
    }

    private fun start() {
        job?.cancel()
        _state.update { it.copy(checking = true) }
        job = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { checker.check() }
                val dismissed = withContext(Dispatchers.IO) { checker.dismissedVersionCode() }
                val lastCheck = withContext(Dispatchers.IO) { checker.lastCheckMs() }
                _state.update { state ->
                    when (outcome) {
                        is UpdateCheckOutcome.Found -> state.copy(info = outcome.info, status = statusOf(outcome.info, dismissed),
                            lastFailure = null, lastCheckMs = lastCheck, lastSource = outcome.source)
                        is UpdateCheckOutcome.Failed -> state.copy(lastFailure = outcome.failure, lastCheckMs = lastCheck, lastSource = null)
                    }
                }
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
    }

    private fun statusOf(info: UpdateInfo, dismissed: Int?): UpdateStatus =
        updateStatus(info, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, dismissed)
}
