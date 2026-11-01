package cz.netkit.app.ssh

import android.app.Application
import android.net.Uri
import android.net.Network
import android.net.ConnectivityManager
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import com.jcraft.jsch.KeyPair
import cz.netkit.app.net.ActiveNetworkMonitor
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.ssh.*
import cz.netkit.app.wol.NetKitApplication
import cz.netkit.app.wol.BoundedStreamIo
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SshSessionState(val id: String, val name: String, val terminal: TerminalSnapshot,
    val connected: Boolean = false, val ended: Boolean = false, val failed: Boolean = false, val failure: SshFailure? = null, val networkLost: Boolean = false) {
    fun copy(terminal: TerminalSnapshot = this.terminal, connected: Boolean = this.connected,
        ended: Boolean = this.ended, failed: Boolean = this.failed, failure: SshFailure? = this.failure, networkLost: Boolean = this.networkLost) = SshSessionState(id, name, terminal, connected, ended, failed, failure, networkLost)
}
data class HostChallenge(val requestId: String, val sessionId: String, val offered: TrustedHostKey, val previous: TrustedHostKey?, val lastRejected: TrustedHostKey? = null)
data class AuthChallenge(val requestId: String, val sessionId: String, val trustedIdentity: String, val name: String, val instruction: String, val prompts: List<String>, val echo: List<Boolean>)
data class SshUiState(val data: SshData = SshData(), val loaded: Boolean = false, val storeFailed: Boolean = false,
    val keySelectionRequired: SshProfile? = null,
    val exportFailure: SshProfileExportError? = null, val profileUnavailable: Boolean = false,
    val busy: Boolean = false, val error: Boolean = false, val failure: SshFailure? = null,
    val storeRecoverable: Boolean = false, val archivePath: String? = null, val protection: KeyProtection? = null,
    val sessions: List<SshSessionState> = emptyList(), val selectedSession: String? = null,
    val hostChallenge: HostChallenge? = null, val authChallenge: AuthChallenge? = null, val exported: Boolean = false,
    val pendingProfileImport: PendingSshProfileImport? = null, val profileImportBusy: Boolean = false,
    val profileImportFailure: SshProfileImportError? = null, val profileImportBlockedByDialog: Boolean = false,
    /** Default network for the "local target without VPN" hint; the connection itself does not use it. */
    val activeKind: NetKind? = null, val activeViaVpn: Boolean = false)

/** Restore export after process death; validate before opening/truncating the destination. */
internal suspend fun completeSshProfileExport(
    prepared: ByteArray?, read: suspend () -> SshData, write: suspend (ByteArray) -> Unit,
): SshProfileExportError? {
    val bytes = try { prepared ?: encodeSshProfileExport(read()) }
    catch (error: SshProfileExportException) { return error.reason }
    write(bytes) // Provider/IO failures still use the normal operation error handler.
    return null
}

class SshViewModel(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SshStore(application, storeScope)
    private val secrets = (application as NetKitApplication).createSshSecretStore()
    private val _state = MutableStateFlow(SshUiState())
    val state = _state.asStateFlow()
    private val operations = Mutex(); private val prompts = Mutex()
    private val profileImporter = SshProfileImportController(scope, operations, store::read, store::update,
        blocked = { _state.value.blocksProfileImport() }, generateId = { UUID.randomUUID().toString() }, deleteSecret = secrets::delete)
    private val connections = mutableMapOf<String, JschConnection>()
    private val buffers = java.util.concurrent.ConcurrentHashMap<String, TerminalSessionBuffers>()
    private val attempts = SshAttemptGate()
    private val sessionProfiles = mutableMapOf<String, SshProfile>()
    private val sessionNetworks = java.util.concurrent.ConcurrentHashMap<String, Network>()
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = networkWarning { it == network }
        override fun onAvailable(network: Network) = networkWarning { it != network }
    }
    private var observingNetwork = false
    private fun networkWarning(matches: (Network) -> Boolean) {
        _state.update { state -> state.copy(sessions = state.sessions.map { session ->
            if (session.connected && sessionNetworks[session.id]?.let(matches) == true) session.copy(networkLost = true) else session
        }) }
    }
    fun reconnectProfile(id: String): SshProfile? = sessionProfiles[id]?.let { previous ->
        _state.value.data.profiles.find { it.id == previous.id } ?: previous
    }
    /** Called only after the same connection/startup-command confirmation as a new session. */
    fun reconnect(id: String, confirmedProfile: SshProfile) {
        if (sessionProfiles[id]?.id != confirmedProfile.id || _state.value.data.profiles.none { it.id == confirmedProfile.id }) {
            _state.update { it.copy(profileUnavailable = true, keySelectionRequired = null) }; return
        }
        startConnection(confirmedProfile, id)
    }
    private val hostReplies = PromptReplyGate<Boolean>()
    private val authReplies = PromptReplyGate<List<String>?>()
    private val backgroundPolicy = SshBackgroundPolicy(android.os.SystemClock::elapsedRealtime)
    private var backgroundTimeout: Job? = null
    private var loader: Job? = null
    fun onForeground() {
        backgroundTimeout?.cancel(); backgroundTimeout = null
        if (backgroundPolicy.foreground()) cancelActive()
    }
    fun onBackground() {
        backgroundPolicy.background()
        if (backgroundTimeout?.isActive == true) return
        backgroundTimeout = scope.launch {
            delay(backgroundPolicy.remainingMillis() ?: return@launch)
            if (backgroundPolicy.expired()) cancelActive()
        }
    }
    fun closeAllSessions() { _state.value.sessions.map { it.id }.forEach(::closeSession) }

    init {
        scope.launch {
            profileImporter.state.collect { transfer ->
                _state.update { it.copy(pendingProfileImport = transfer.pendingProfileImport,
                    profileImportBusy = transfer.busy, profileImportFailure = transfer.failure) }
            }
        }
        scope.launch {
            ActiveNetworkMonitor(connectivity).observe().collect { info ->
                _state.update { it.copy(activeKind = info?.kind, activeViaVpn = info?.viaVpn == true) }
            }
        }
        try { connectivity.registerDefaultNetworkCallback(networkCallback); observingNetwork = true }
        catch (_: SecurityException) { /* Connection still has transport errors; no false network status claim. */ }
        reload(); scope.launch { try { val value = secrets.protection(); _state.update { it.copy(protection = value) } } catch (error: Exception) { _state.update { it.copy(error = true, failure = classifySshFailure(error)) } } } }
    fun reload() {
        loader?.cancel()
        loader = scope.launch {
            try { store.data.collect { data -> _state.update { it.copy(data = data, loaded = true, storeFailed = false, archivePath = store.archivePath) } } }
            catch (e: CancellationException) { throw e }
            catch (error: Exception) { _state.update { it.copy(storeFailed = true, loaded = false, storeRecoverable = error is androidx.datastore.core.CorruptionException) } }
        }
    }
    fun recoverStore() {
        if (profileImporter.active || !_state.value.storeRecoverable) return
        loader?.cancel()
        operation {
            loader?.join()
            val data = store.recover()
            _state.update { it.copy(data = data, archivePath = store.archivePath, storeFailed = false, storeRecoverable = false, loaded = true) }
            withContext(Dispatchers.Main) { reload() }
        }
    }
    private fun operation(block: suspend () -> Unit) {
        if (profileImporter.active) { _state.update { it.copy(error = true) }; return }
        scope.launch { operations.withLock {
            if (profileImporter.active) { _state.update { it.copy(error = true) }; return@withLock }
            _state.update { it.copy(busy = true, error = false, failure = null, exported = false) }
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (error: Exception) { _state.update { it.copy(error = true, failure = classifySshFailure(error)) } }
            finally { _state.update { it.copy(busy = false) } }
        } }
    }
    /** UI must acquire this guard before opening a profile/key/connection dialog and release on close. */
    fun setProfileImportBlockedByDialog(open: Boolean): Boolean {
        if (open && profileImporter.active) return false
        _state.update { it.copy(profileImportBlockedByDialog = open) }
        return true
    }
    fun previewProfileImport(uri: Uri) = profileImporter.preview {
        val cancellation = CancellationSignal()
        BoundedStreamIo.read(open = {
            val descriptor = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r", cancellation)
                ?: throw java.io.IOException("Import stream unavailable")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }, cancelOpen = cancellation::cancel, limit = SSH_PROFILE_IMPORT_LIMIT)
    }
    fun confirmProfileImport(keyRef: String?) = profileImporter.confirm(keyRef)
    fun cancelProfileImport() = profileImporter.cancel()
    fun dismissKeySelection() { _state.update { it.copy(keySelectionRequired = null) } }
    fun clearError() { _state.update { it.copy(error = false, failure = null, exported = false, exportFailure = null, profileUnavailable = false) } }
    fun saveProfile(profile: SshProfile, password: String, remember: Boolean, onSaved: () -> Unit) = operation {
        require(profile.valid()); val previous = store.read().profiles.find { it.id == profile.id }
        var newRef: String? = null
        val ref = if (profile.authentication == SshAuthentication.PASSWORD && remember) {
            if (password.isNotEmpty()) {
                newRef = UUID.randomUUID().toString(); val bytes = password.encodeToByteArray()
                try { secrets.put(newRef!!, bytes) } finally { bytes.fill(0) }; newRef
            } else previous?.secretRef
        } else null
        try {
            store.update { data -> data.copy(profiles = if (data.profiles.any { it.id == profile.id })
                data.profiles.map { if (it.id == profile.id) profile.copy(secretRef = ref) else it }
                else data.profiles + profile.copy(secretRef = ref)) }
        } catch (e: Exception) { newRef?.let { secrets.delete(it) }; throw e }
        if (previous?.secretRef != ref) previous?.secretRef?.let { secrets.delete(it) }
        withContext(Dispatchers.Main) { onSaved() }
    }
    fun moveProfile(id: String, delta: Int) {
        if (_state.value.busy || profileImporter.active) return
        operation { store.update { moveSshProfile(it, id, delta) } }
    }
    fun deleteProfile(id: String) = operation {
        val profile = store.read().profiles.find { it.id == id } ?: return@operation
        store.update { it.copy(profiles = it.profiles.filterNot { p -> p.id == id }) }
        profile.secretRef?.let { secrets.delete(it) }
    }
    fun forgetHost(endpoint: String) = operation {
        // Do not let an open session silently retain old trust while the user changes it.
        withContext(Dispatchers.Main) { cancelActive() }
        store.update { it.copy(hostKeys = it.hostKeys.filterNot { key -> key.endpoint == endpoint }) }
    }
    fun generateKey(name: String, type: Int = KeyPair.ED25519) = operation {
        require(name.isNotBlank() && name.length <= 100)
        val (privateKey, publicKey) = SshCrypto.generate(type)
        saveKey(name, privateKey, publicKey)
    }
    fun importKey(uri: Uri, name: String, passphrase: String) = operation {
        require(name.isNotBlank() && name.length <= 100)
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytesBounded(262144) } ?: error("Unreadable key")
        val phrase = passphrase.encodeToByteArray()
        try {
            val (privateKey, publicKey) = SshCrypto.import(bytes, phrase)
            saveKey(name, privateKey, publicKey)
        } finally { bytes.fill(0); phrase.fill(0) }
    }
    private suspend fun saveKey(name: String, privateKey: ByteArray, publicKey: String) {
        val id = UUID.randomUUID().toString()
        try {
            secrets.put(id, privateKey)
            try { store.update { it.copy(keys = it.keys + SshKeyInfo(id, name, publicKey)) } }
            catch (e: Exception) { secrets.delete(id); throw e }
        } finally { privateKey.fill(0) }
    }
    fun deleteKey(id: String) = operation {
        store.update { data -> require(data.profiles.none { it.keyRef == id }); data.copy(keys = data.keys.filterNot { it.id == id }) }
        secrets.delete(id)
    }
    private var preparedExport: ByteArray? = null
    /** Validate and freeze bytes BEFORE the document picker can create a destination. */
    fun prepareExport(onReady: () -> Unit) = operation {
        preparedExport = null
        _state.update { it.copy(exportFailure = null) }
        try { preparedExport = encodeSshProfileExport(store.read()) }
        catch (error: SshProfileExportException) {
            _state.update { it.copy(exportFailure = error.reason) }; return@operation
        }
        withContext(Dispatchers.Main) { onReady() }
    }
    fun cancelExport() { preparedExport = null }
    fun export(uri: Uri) = operation {
        val prepared = preparedExport
        preparedExport = null
        _state.update { it.copy(exportFailure = null) }
        val failure = completeSshProfileExport(prepared, store::read) { bytes ->
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Export failed")
        }
        _state.update { it.copy(exportFailure = failure, exported = failure == null) }
    }
    fun connect(profile: SshProfile) { startConnection(profile, null) }
    private fun startConnection(profile: SshProfile, replacement: String?) {
        if (profileImporter.active || !profile.valid() || !_state.value.loaded ||
            _state.value.sessions.count { !it.ended && it.id != replacement } >= 4 ||
            replacement != null && _state.value.sessions.none { it.id == replacement }) {
            _state.update { it.copy(error = true) }; return
        }
        if (profile.needsKeySelection(_state.value.data)) {
            _state.update { it.copy(keySelectionRequired = profile) }
            return // No session, buffer, attempt, transport or DNS is created.
        }
        _state.update { it.copy(keySelectionRequired = null, profileUnavailable = false) }
        // A replacement reuses its existing tab; keep its previous output until onConnected.
        if (replacement == null) while (_state.value.sessions.size >= 4) {
            val ended = _state.value.sessions.firstOrNull { it.ended } ?: break
            closeSession(ended.id)
        }
        val id = replacement ?: UUID.randomUUID().toString()
        val attempt = attempts.begin(id)
        connections.remove(id)?.close()
        val terminal = buffers.getOrPut(id) { TerminalSessionBuffers() }
        val buffer = synchronized(terminal) { terminal.beginConnection() }
        sessionProfiles[id] = profile
        sessionNetworks.remove(id)
        connectivity.activeNetwork?.let { sessionNetworks[id] = it }
        _state.update { state -> state.copy(sessions = if (replacement == null)
            state.sessions + SshSessionState(id, profile.name, buffer.snapshot())
            else state.sessions.map { if (it.id == id) it.copy(connected = false, ended = false, failed = false, failure = null, networkLost = false) else it },
            selectedSession = id) }
        val replyTransport = AtomicReference<JschConnection?>()
        val connection = JschConnection(profile, store, secrets, object : SshInteraction {
            override suspend fun confirmHost(offered: TrustedHostKey, previous: TrustedHostKey?): Boolean = prompts.withLock {
                if (!attempts.isCurrent(id, attempt)) throw CancellationException()
                val requestId = UUID.randomUUID().toString()
                val answer = hostReplies.open(requestId)
                try {
                    val rejected = store.read().rejectedHostKeys.find { it.endpoint == offered.endpoint }
                    if (!attempts.publish(id, attempt) { _state.update { it.copy(hostChallenge = HostChallenge(requestId, id, offered, previous, distinctRejectedHostKey(offered, rejected))) } }) throw CancellationException()
                    answer.await()
                } finally {
                    withContext(NonCancellable) { hostReplies.close(requestId) }
                    _state.update { if (it.hostChallenge?.requestId == requestId) it.copy(hostChallenge = null) else it }
                }
            }
            override suspend fun authenticate(name: String, instruction: String, prompts: List<String>, echo: List<Boolean>): List<String>? = this@SshViewModel.prompts.withLock {
                if (!attempts.isCurrent(id, attempt)) throw CancellationException()
                val requestId = UUID.randomUUID().toString()
                val answer = authReplies.open(requestId)
                try {
                    if (!attempts.publish(id, attempt) { _state.update { it.copy(authChallenge = AuthChallenge(requestId, id, "${profile.name} · ${profile.username}@${profile.endpoint}", name, instruction, prompts, echo)) } }) throw CancellationException()
                    answer.await()
                } finally {
                    withContext(NonCancellable) { authReplies.close(requestId) }
                    _state.update { if (it.authChallenge?.requestId == requestId) it.copy(authChallenge = null) else it }
                }
            }
        }, scope, onText = { text ->
            var reply = ""
            attempts.publish(id, attempt) {
                synchronized(terminal) {
                    reply = buffer.feed(text)
                    val snapshot = buffer.snapshot()
                    _state.update { state -> state.copy(sessions = state.sessions.map { if (it.id == id) it.copy(terminal = snapshot) else it }) }
                }
            }
            if (reply.isNotEmpty() && attempts.isCurrent(id, attempt)) {
                // Direct, nonblocking send to the transport's 32-item channel; no unbounded Main queue.
                replyTransport.get()?.let { transport ->
                    if (!transport.send(reply)) {
                        attempts.publish(id, attempt) { _state.update { state -> state.copy(error = true, failure = SshFailure.CONNECTION,
                            sessions = state.sessions.map { if (it.id == id) it.copy(failed = true, failure = SshFailure.CONNECTION) else it }) } }
                        transport.close()
                    }
                }
            }
        }, onConnected = {
            attempts.publish(id, attempt) {
                synchronized(terminal) {
                    terminal.connected(buffer)
                    _state.update { state -> state.copy(sessions = state.sessions.map { if (it.id == id) it.copy(terminal = buffer.snapshot(), connected = true) else it }) }
                }
            }
        }, onFailure = { failure ->
            attempts.publish(id, attempt) { _state.update { state -> state.copy(sessions = state.sessions.map { if (it.id == id) it.copy(failure = failure) else it }) } }
        }, onEnd = { failed ->
            synchronized(terminal) { terminal.ended(buffer) }
            attempts.publish(id, attempt) { _state.update { state -> state.copy(sessions = state.sessions.map { if (it.id == id) it.copy(connected = false, ended = true, failed = it.failed || failed) else it }) } }
        })
        replyTransport.set(connection)
        connections[id] = connection
        // Initialize PTY before start; same-tab reconnect does not retrigger the Compose size effect.
        synchronized(terminal) { connection.resize(buffer.columns, buffer.rows) }
        connection.start()
    }
    fun confirmHost(requestId: String, accept: Boolean) {
        val challenge = _state.value.hostChallenge
        val permitted = accept && challenge != null && challenge.requestId == requestId && challenge.previous == null
        scope.launch { hostReplies.answer(requestId, permitted) }
    }
    fun answerAuthentication(requestId: String, answers: List<String>?) {
        scope.launch { authReplies.answer(requestId, answers) }
    }
    fun selectSession(id: String?) { _state.update { it.copy(selectedSession = id) } }
    fun send(id: String, text: String) { if (connections[id]?.send(text) != true) _state.update { it.copy(error = true) } }
    fun resize(id: String, columns: Int, rows: Int) {
        val terminal = buffers[id] ?: return
        synchronized(terminal) {
            terminal.resize(columns, rows)
            val snapshot = terminal.visible.snapshot()
            _state.update { state -> if (buffers[id] !== terminal) state else
                state.copy(sessions = state.sessions.map { if (it.id == id) it.copy(terminal = snapshot) else it }) }
        }
        connections[id]?.resize(columns, rows)
    }
    fun closeSession(id: String) {
        attempts.invalidate(id)
        _state.value.hostChallenge?.takeIf { it.sessionId == id }?.let { confirmHost(it.requestId, false) }
        _state.value.authChallenge?.takeIf { it.sessionId == id }?.let { answerAuthentication(it.requestId, null) }
        connections.remove(id)?.close(); buffers.remove(id); sessionProfiles.remove(id); sessionNetworks.remove(id)
        _state.update { state -> val list = state.sessions.filterNot { it.id == id }; state.copy(sessions = list, selectedSession = if (state.selectedSession == id) list.lastOrNull()?.id else state.selectedSession) }
    }
    fun cancelActive() {
        _state.value.hostChallenge?.let { confirmHost(it.requestId, false) }
        _state.value.authChallenge?.let { answerAuthentication(it.requestId, null) }
        connections.values.forEach { it.close() }
    }
    override fun onCleared() { if (observingNetwork) connectivity.unregisterNetworkCallback(networkCallback); cancelActive(); scope.cancel(); storeScope.cancel(); super.onCleared() }
}
