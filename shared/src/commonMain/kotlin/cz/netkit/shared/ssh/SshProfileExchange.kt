package cz.netkit.shared.ssh

import kotlinx.serialization.json.*

sealed interface SshProfileExportError {
    data class DuplicateNames(val names: List<String>) : SshProfileExportError
    data object TooLarge : SshProfileExportError
}
class SshProfileExportException(val reason: SshProfileExportError) : IllegalArgumentException()

/** Explicit wire allowlist: local identities, credential references and server trust never leave here. */
fun encodeSshProfileExport(data: SshData): ByteArray {
    require(data.valid())
    val duplicates = data.profiles.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.toList()
    if (duplicates.isNotEmpty()) throw SshProfileExportException(SshProfileExportError.DuplicateNames(duplicates))
    val document = buildJsonObject {
        put("format", "netkit-ssh-profiles")
        put("schema", 2)
        put("source", "netkit-android")
        putJsonArray("profiles") {
            data.profiles.forEach { profile ->
                addJsonObject {
                    put("alias", profile.name)
                    put("host", profile.host)
                    put("port", profile.port)
                    put("username", profile.username)
                    put("authentication", profile.authentication.name)
                    put("startupCommand", profile.startupCommand)
                    put("keepaliveSeconds", profile.keepaliveSeconds)
                }
            }
        }
        putJsonArray("skipped") { }
    }.toString().encodeToByteArray()
    if (document.size > SSH_PROFILE_IMPORT_LIMIT) throw SshProfileExportException(SshProfileExportError.TooLarge)
    // Never report a successful export of a file the importer cannot accept.
    decodeSshProfileImport(document)
    return document
}

/** List order is persisted directly. Invalid moves leave the entire data object unchanged. */
fun moveSshProfile(data: SshData, id: String, delta: Int): SshData {
    if (delta != -1 && delta != 1) return data
    val index = data.profiles.indexOfFirst { it.id == id }
    val target = index + delta
    if (index < 0 || target !in data.profiles.indices) return data
    return data.copy(profiles = data.profiles.toMutableList().apply { add(target, removeAt(index)) })
}

fun SshProfile.needsKeySelection(data: SshData): Boolean =
    authentication == SshAuthentication.KEY && data.keys.none { it.id == keyRef }
