package cz.netkit.shared.ssh

import kotlinx.coroutines.withTimeout

object SshPromptPolicy {
    const val PROMPT_TIMEOUT_MILLIS = 180_000L
    // Up to three full UI prompts plus 60 s for network/KEX work; the server can impose a shorter limit.
    const val HANDSHAKE_TIMEOUT_MILLIS = 3 * PROMPT_TIMEOUT_MILLIS + 60_000L
    suspend fun <T> await(reply: suspend () -> T): T = withTimeout(PROMPT_TIMEOUT_MILLIS) { reply() }
}

fun distinctRejectedHostKey(offered: TrustedHostKey, rejected: TrustedHostKey?): TrustedHostKey? =
    rejected?.takeUnless { it.endpoint == offered.endpoint && it.algorithm == offered.algorithm && it.publicKey == offered.publicKey }
