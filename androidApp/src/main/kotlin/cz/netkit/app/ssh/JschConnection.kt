package cz.netkit.app.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import cz.netkit.shared.ssh.SshPromptPolicy
import cz.netkit.shared.ssh.SshFailure
import cz.netkit.shared.ssh.TerminalWritePump
import cz.netkit.shared.ssh.HostKeyDecision
import cz.netkit.shared.ssh.SshAlgorithms
import cz.netkit.shared.ssh.SshAuthentication
import cz.netkit.shared.ssh.SshProfile
import cz.netkit.shared.ssh.SshSession
import cz.netkit.shared.ssh.TrustedHostKey
import cz.netkit.shared.ssh.hostKeyDecision
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

interface SshInteraction {
    suspend fun confirmHost(offered: TrustedHostKey, previous: TrustedHostKey?): Boolean
    suspend fun authenticate(name: String, instruction: String, prompts: List<String>, echo: List<Boolean>): List<String>?
}

/** One JSch instance per connection; no shared identities, auth responses, logger, or permissive verifier. */
class JschConnection(
    private val profile: SshProfile,
    private val store: SshStore,
    private val secrets: AndroidSecretStore,
    private val interaction: SshInteraction,
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onEnd: (Boolean) -> Unit,
    private val onFailure: (SshFailure) -> Unit = {},
) : SshSession {
    private val closed = AtomicBoolean(false)
    private val outcome = SshConnectionOutcome(onFailure)
    private val socket = AtomicReference<Socket?>()
    private val session = AtomicReference<Session?>()
    private val channel = AtomicReference<ChannelShell?>()
    private val input = TerminalWritePump(scope)
    private var job: Job? = null
    private var writer: Job? = null
    @Volatile private var columns = 80
    @Volatile private var rows = 24

    fun start() {
        check(job == null)
        job = scope.launch(Dispatchers.IO) {
            val jsch = JSch(); var passwordBytes: ByteArray? = null; var privateKey: ByteArray? = null
            try {
                require(profile.valid()); SshCrypto.configure()
                jsch.hostKeyRepository = verifier()
                if (profile.authentication == SshAuthentication.KEY) {
                    privateKey = secrets.get(requireNotNull(profile.keyRef)); require(privateKey != null)
                    jsch.addIdentity("NetKit", privateKey, null, null)
                } else if (profile.authentication == SshAuthentication.PASSWORD) {
                    passwordBytes = profile.secretRef?.let { secrets.get(it) }
                }
                val transport = jsch.getSession(profile.username, profile.host, profile.port)
                session.set(transport)
                transport.setConfig("StrictHostKeyChecking", "yes")
                transport.setConfig("kex", SshAlgorithms.kex.joinToString(","))
                transport.setConfig("server_host_key", SshAlgorithms.hostKeyPreference(store.read().hostKeys.find { it.endpoint == profile.endpoint }?.algorithm).joinToString(","))
                transport.setConfig("PubkeyAcceptedAlgorithms", SshAlgorithms.hostKeys.joinToString(","))
                for (direction in listOf("c2s", "s2c")) {
                    transport.setConfig("cipher.$direction", SshAlgorithms.ciphers.joinToString(","))
                    transport.setConfig("mac.$direction", SshAlgorithms.macs.joinToString(","))
                    transport.setConfig("compression.$direction", "none")
                }
                transport.setConfig("PreferredAuthentications", when (profile.authentication) {
                    SshAuthentication.KEY -> "publickey"
                    SshAuthentication.PASSWORD -> "password"
                    SshAuthentication.INTERACTIVE -> "keyboard-interactive"
                })
                transport.setConfig("MaxAuthTries", "3")
                transport.setConfig("FingerprintHash", "sha256")
                transport.setConfig("enable_strict_kex", "yes")
                transport.setConfig("prefer_known_host_key_types", "no")
                transport.setServerAliveInterval(profile.keepaliveSeconds * 1000)
                transport.setServerAliveCountMax(3)
                transport.userInfo = authentication()
                passwordBytes?.let { transport.setPassword(it) }
                val addresses = resolveSshHost(profile.host)
                transport.setSocketFactory(object : SocketFactory {
                    override fun createSocket(host: String, port: Int): Socket {
                        val deadline = System.nanoTime() + 15_000_000_000L
                        var failure: Exception = java.io.IOException("Connection failed")
                        for (address in addresses) {
                            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                            if (remaining <= 0 || closed.get()) break
                            val value = Socket(); socket.set(value)
                            if (closed.get()) { value.close(); throw java.io.IOException("Closed") }
                            try {
                                value.connect(InetSocketAddress(address, port), minOf(5000, remaining))
                                if (closed.get()) { value.close(); throw java.io.IOException("Closed") }
                                return value
                            } catch (e: Exception) { value.close(); failure = e }
                        }
                        throw failure
                    }
                    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
                })
                // Each network stage and UI challenge is bounded; cancellation also closes the raw socket.
                withTimeout(SshPromptPolicy.HANDSHAKE_TIMEOUT_MILLIS) { runInterruptible { transport.connect(20000) } }
                passwordBytes?.fill(0); passwordBytes = null; privateKey?.fill(0); privateKey = null
                if (closed.get()) throw CancellationException()
                val shell = transport.openChannel("shell") as ChannelShell; channel.set(shell)
                shell.setPtyType("xterm-256color", columns, rows, 0, 0)
                val reader = shell.inputStream.bufferedReader(Charsets.UTF_8)
                val output = shell.outputStream
                withTimeout(20000) { runInterruptible { shell.connect(15000) } }
                if (closed.get()) throw CancellationException()
                onConnected()
                writer = scope.launch(Dispatchers.IO) {
                    consumeSshWrites(input,
                        text = { text -> runInterruptible { output.write(text.toByteArray(Charsets.UTF_8)); output.flush() } },
                        resize = { width, height -> runInterruptible { shell.setPtySize(width, height, 0, 0) } },
                        onWriteFailure = { if (!closed.get()) outcome.record(SshFailure.CONNECTION); close() },
                    )
                }
                if (profile.startupCommand.isNotBlank()) send(profile.startupCommand + "\r")
                val buffer = CharArray(8192)
                while (!closed.get()) {
                    val count = runInterruptible { reader.read(buffer) }
                    if (count < 0) break
                    onText(buffer.concatToString(0, count))
                }
            } catch (e: TimeoutCancellationException) { if (!closed.get()) outcome.record(classifySshFailure(e)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!closed.get()) outcome.record(classifySshFailure(e)) }
            finally {
                passwordBytes?.fill(0); privateKey?.fill(0)
                closeTransport(); jsch.removeAllIdentity(); onEnd(outcome.failed)
            }
        }
    }
    override fun send(text: String): Boolean = text.length <= 16384 && !closed.get() && input.send(text)
    override fun resize(columns: Int, rows: Int) {
        this.columns = columns.coerceIn(10, 240); this.rows = rows.coerceIn(2, 100)
        input.resize(this.columns, this.rows)
    }
    override fun close() { closeTransport(); job?.cancel() }
    private fun closeTransport() {
        closed.set(true); input.close(); writer?.cancel()
        try { socket.getAndSet(null)?.close() } catch (_: Exception) { }
        channel.getAndSet(null)?.disconnect(); session.getAndSet(null)?.disconnect()
    }
    private fun verifier() = object : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int = runBlocking {
            if (closed.get()) return@runBlocking HostKeyRepository.CHANGED
            val algorithm = HostKey(profile.endpoint, key).type
            val offered = TrustedHostKey(profile.endpoint, algorithm, Base64.getEncoder().encodeToString(key), SshCrypto.fingerprint(key))
            val previous = store.read().hostKeys.find { it.endpoint == profile.endpoint }
            when (hostKeyDecision(previous, offered)) {
                HostKeyDecision.TRUSTED -> HostKeyRepository.OK
                HostKeyDecision.CHANGED -> {
                    outcome.record(SshFailure.HOST_KEY_CHANGED)
                    store.recordRejected(offered)
                    SshPromptPolicy.await { interaction.confirmHost(offered, previous) }
                    HostKeyRepository.CHANGED // Never accept changed keys in an auth dialog.
                }
                HostKeyDecision.UNKNOWN -> {
                    if (SshPromptPolicy.await { interaction.confirmHost(offered, null) } && !closed.get()) {
                        store.trustNew(offered); HostKeyRepository.OK
                    } else {
                        if (!closed.get()) outcome.record(SshFailure.HOST_KEY_REJECTED)
                        store.recordRejected(offered); HostKeyRepository.NOT_INCLUDED
                    }
                }
            }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "NetKit verified hosts"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
    private fun authentication() = object : UserInfo, UIKeyboardInteractive {
        private var answer: String? = null
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = answer.also { answer = null }
        override fun promptPassphrase(message: String?) = false
        override fun promptPassword(message: String?): Boolean {
            if (closed.get()) return false
            answer = runBlocking { SshPromptPolicy.await { interaction.authenticate(profile.name, "", listOf("Password"), listOf(false))?.singleOrNull() } }
            return answer != null
        }
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) = Unit // Never echo potentially sensitive server messages to logs.
        override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?): Array<String>? {
            if (closed.get() || prompt == null || prompt.size > 8) return null
            return runBlocking { SshPromptPolicy.await {
                interaction.authenticate(name.orEmpty().take(200), instruction.orEmpty().take(1000), prompt.map { it.take(200) }, prompt.indices.map { echo?.getOrNull(it) == true })?.toTypedArray()
            } }
        }
    }
}
