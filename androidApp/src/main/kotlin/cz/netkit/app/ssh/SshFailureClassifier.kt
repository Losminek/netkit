package cz.netkit.app.ssh

import com.jcraft.jsch.JSchAlgoNegoFailException
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchStrictKexException
import cz.netkit.shared.ssh.SshFailure
import kotlinx.coroutines.TimeoutCancellationException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun classifySshFailure(error: Throwable): SshFailure {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SshImportException } -> (causes.first { it is SshImportException } as SshImportException).category
        causes.any { it is SecretUnavailableException } -> SshFailure.SECRET_UNAVAILABLE
        causes.any { it is JSchChangedHostKeyException } -> SshFailure.HOST_KEY_CHANGED
        causes.any { it is JSchAlgoNegoFailException || it is JSchStrictKexException } -> SshFailure.ALGORITHMS
        causes.any { it is UnknownHostException } -> SshFailure.DNS
        causes.any { it is SocketTimeoutException || it is TimeoutCancellationException } -> SshFailure.TIMEOUT
        // JSch exposes password authentication failure as a generic exception. Classify locally only;
        // never display or log this message, which can contain server-controlled text.
        causes.any { it is JSchException && (it.message?.startsWith("Auth fail") == true || it.message?.startsWith("Auth cancel") == true) } -> SshFailure.AUTHENTICATION
        causes.any { it is java.io.IOException || it is JSchException } -> SshFailure.CONNECTION
        else -> SshFailure.UNKNOWN
    }
}
