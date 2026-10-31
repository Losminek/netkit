package cz.netkit.shared.ssh

import cz.netkit.shared.json.JSON_MAX_DEPTH
import cz.netkit.shared.json.jsonNestingDepth
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

const val SSH_PROFILE_IMPORT_LIMIT = 256 * 1024
private const val MAX_IMPORT_ROWS = 200

enum class SshProfileImportFailure {
    SIZE, UTF8, FORMAT, SCHEMA, UNKNOWN_FIELD, DUPLICATE_ALIAS, INVALID_PROFILE, LIMIT,
    INVALID_REFERENCE, DUPLICATE_ID, AMBIGUOUS_ALIAS, INVALID_DATA, STALE_PLAN,
}

class SshProfileImportException(val reason: SshProfileImportFailure) : IllegalArgumentException(reason.name)

enum class SshImportSkipReason { PROXY_JUMP, PROXY_COMMAND, MATCH, WILDCARD, MISSING_HOST, UNSUPPORTED_OPTION }
data class SshImportSkipped(val alias: String, val reason: SshImportSkipReason)
data class SshImportProfile(val alias: String, val host: String, val port: Int, val username: String, val group: String? = null)
data class SshProfileImport(val profiles: List<SshImportProfile>, val skipped: List<SshImportSkipped>, val source: String? = null)

/** Group/source/skipped are preview metadata only, never persisted in SshData. */
data class SshImportEntry(val incoming: SshImportProfile, val before: SshProfile?, val after: SshProfile) {
    val detachedSecretRef: String? get() = before?.secretRef?.takeIf { after.secretRef == null }
}
class SshImportPlan internal constructor(
    internal val baselineProfiles: List<SshProfile>,
    val newProfiles: List<SshImportEntry>,
    val updatedProfiles: List<SshImportEntry>,
    val unchangedProfiles: List<SshImportEntry>,
    val skipped: List<SshImportSkipped>,
    val source: String?,
) {
    val detachedSecretRefs: List<String> = updatedProfiles.mapNotNull { it.detachedSecretRef }.distinct()
}

private fun fail(reason: SshProfileImportFailure): Nothing = throw SshProfileImportException(reason)
private fun validAlias(alias: String) = alias.isNotBlank() && alias.length <= 100 &&
    alias.none { it.code < 32 || it.code in 127..159 }
private fun SshImportProfile.profile(id: String) = SshProfile(id, alias, host, port, username)

private fun validateIncoming(incoming: SshProfileImport) {
    if (incoming.profiles.size > MAX_IMPORT_ROWS || incoming.skipped.size > MAX_IMPORT_ROWS) fail(SshProfileImportFailure.LIMIT)
    if ((incoming.source?.length ?: 0) > 64) fail(SshProfileImportFailure.FORMAT)
    val aliases = incoming.profiles.map { it.alias } + incoming.skipped.map { it.alias }
    if (aliases.distinct().size != aliases.size) fail(SshProfileImportFailure.DUPLICATE_ALIAS)
    if (incoming.profiles.any { !validAlias(it.alias) || (it.group?.length ?: 0) > 60 || !it.profile("validation").valid() } ||
        incoming.skipped.any { !validAlias(it.alias) }) fail(SshProfileImportFailure.INVALID_PROFILE)
}

private fun JsonObject.allowOnly(fields: Set<String>) {
    if (keys.any { it !in fields }) fail(SshProfileImportFailure.UNKNOWN_FIELD)
}
private fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content ?: fail(SshProfileImportFailure.FORMAT)
private fun JsonObject.optionalString(name: String): String? = if (containsKey(name)) string(name) else null
private fun JsonObject.integer(name: String, reason: SshProfileImportFailure): Int = (get(name) as? JsonPrimitive)
    ?.takeUnless { it.isString }?.intOrNull ?: fail(reason)
private fun JsonObject.rows(name: String): JsonArray = get(name) as? JsonArray ?: fail(SshProfileImportFailure.FORMAT)

/** Validate every row, including skipped rows, before exposing anything to the caller. */
fun decodeSshProfileImport(bytes: ByteArray): SshProfileImport {
    if (bytes.size > SSH_PROFILE_IMPORT_LIMIT) fail(SshProfileImportFailure.SIZE)
    val text = try { bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF") }
    catch (_: CharacterCodingException) { fail(SshProfileImportFailure.UTF8) }
    if (jsonNestingDepth(text) > JSON_MAX_DEPTH) fail(SshProfileImportFailure.FORMAT)
    try {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: fail(SshProfileImportFailure.FORMAT)
        root.allowOnly(setOf("format", "schema", "source", "profiles", "skipped"))
        if (root.string("format") != "netkit-ssh-profiles") fail(SshProfileImportFailure.FORMAT)
        if (root.integer("schema", SshProfileImportFailure.SCHEMA) != 1) fail(SshProfileImportFailure.SCHEMA)
        val profiles = root.rows("profiles")
        val skipped = root.rows("skipped")
        if (profiles.size > MAX_IMPORT_ROWS || skipped.size > MAX_IMPORT_ROWS) fail(SshProfileImportFailure.LIMIT)
        return SshProfileImport(
            profiles.map { element ->
                val row = element as? JsonObject ?: fail(SshProfileImportFailure.FORMAT)
                row.allowOnly(setOf("alias", "group", "host", "port", "username"))
                SshImportProfile(row.string("alias"), row.string("host"),
                    row.integer("port", SshProfileImportFailure.INVALID_PROFILE), row.string("username"), row.optionalString("group"))
            },
            skipped.map { element ->
                val row = element as? JsonObject ?: fail(SshProfileImportFailure.FORMAT)
                row.allowOnly(setOf("alias", "reason"))
                val reason = row.string("reason")
                SshImportSkipped(row.string("alias"), SshImportSkipReason.entries.find { it.name == reason }
                    ?: fail(SshProfileImportFailure.FORMAT))
            }, root.optionalString("source"),
        ).also(::validateIncoming)
    } catch (error: SshProfileImportException) { throw error }
    catch (_: SerializationException) { fail(SshProfileImportFailure.FORMAT) }
    catch (_: IllegalArgumentException) { fail(SshProfileImportFailure.FORMAT) }
}

/** Exact alias match. Local settings survive; a changed endpoint detaches its saved password. */
fun planSshProfileImport(current: SshData, incoming: SshProfileImport, keyRef: String?, generateId: () -> String): SshImportPlan {
    if (!current.valid()) fail(SshProfileImportFailure.INVALID_DATA)
    validateIncoming(incoming)
    if (keyRef != null && !validReference(keyRef)) fail(SshProfileImportFailure.INVALID_REFERENCE)
    val byName = current.profiles.groupBy { it.name }
    val ids = current.profiles.map { it.id }.toMutableSet()
    val added = mutableListOf<SshImportEntry>()
    val updated = mutableListOf<SshImportEntry>()
    val unchanged = mutableListOf<SshImportEntry>()
    for (row in incoming.profiles) {
        val matches = byName[row.alias].orEmpty()
        if (matches.size > 1) fail(SshProfileImportFailure.AMBIGUOUS_ALIAS)
        val before = matches.singleOrNull()
        if (before == null) {
            val id = generateId()
            if (!validReference(id)) fail(SshProfileImportFailure.INVALID_REFERENCE)
            if (!ids.add(id)) fail(SshProfileImportFailure.DUPLICATE_ID)
            added += SshImportEntry(row, null, row.profile(id).copy(
                authentication = if (keyRef == null) SshAuthentication.PASSWORD else SshAuthentication.KEY, keyRef = keyRef))
        } else {
            val targetChanged = before.host != row.host || before.port != row.port || before.username != row.username
            val after = before.copy(host = row.host, port = row.port, username = row.username,
                secretRef = if (targetChanged) null else before.secretRef)
            val entry = SshImportEntry(row, before, after)
            if (before == after) unchanged += entry else updated += entry
        }
    }
    return SshImportPlan(current.profiles.toList(), added.toList(), updated.toList(), unchanged.toList(), incoming.skipped.toList(), incoming.source)
}

/** Compare before applying: never overwrite edits made after the user saw the preview. */
fun applySshImportPlan(current: SshData, plan: SshImportPlan): SshData {
    if (!current.valid()) fail(SshProfileImportFailure.INVALID_DATA)
    if (current.profiles != plan.baselineProfiles) fail(SshProfileImportFailure.STALE_PLAN)
    if (current.profiles.size + plan.newProfiles.size > MAX_IMPORT_ROWS) fail(SshProfileImportFailure.LIMIT)
    val replacements = plan.updatedProfiles.associate { it.after.id to it.after }
    val result = current.copy(profiles = current.profiles.map { replacements[it.id] ?: it } + plan.newProfiles.map { it.after })
    if (!result.valid()) fail(SshProfileImportFailure.INVALID_DATA)
    return result
}
