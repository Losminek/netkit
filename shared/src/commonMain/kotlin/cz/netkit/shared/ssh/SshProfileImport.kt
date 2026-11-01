package cz.netkit.shared.ssh

import cz.netkit.shared.json.JSON_MAX_DEPTH
import cz.netkit.shared.json.jsonNestingDepth
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
data class SshImportProfile(val alias: String, val host: String, val port: Int, val username: String, val group: String? = null,
    // null means v1: preserve local settings; v2/legacy always carry all three settings.
    val authentication: SshAuthentication? = null, val startupCommand: String = "", val keepaliveSeconds: Int = 30)
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
private fun SshImportProfile.profile(id: String) = SshProfile(id, alias, host, port, username, authentication ?: SshAuthentication.PASSWORD,
    startupCommand = startupCommand, keepaliveSeconds = keepaliveSeconds)

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
        if (!root.containsKey("format")) return decodeLegacyProfiles(root).also(::validateIncoming)
        root.allowOnly(setOf("format", "schema", "source", "profiles", "skipped"))
        if (root.string("format") != "netkit-ssh-profiles") fail(SshProfileImportFailure.FORMAT)
        val schema = root.integer("schema", SshProfileImportFailure.SCHEMA)
        if (schema !in 1..2) fail(SshProfileImportFailure.SCHEMA)
        val profiles = root.rows("profiles")
        val skipped = root.rows("skipped")
        if (profiles.size > MAX_IMPORT_ROWS || skipped.size > MAX_IMPORT_ROWS) fail(SshProfileImportFailure.LIMIT)
        return SshProfileImport(
            profiles.map { element ->
                val row = element as? JsonObject ?: fail(SshProfileImportFailure.FORMAT)
                row.allowOnly(setOf("alias", "group", "host", "port", "username") +
                    if (schema == 2) setOf("authentication", "startupCommand", "keepaliveSeconds") else emptySet())
                SshImportProfile(row.string("alias"), row.string("host"),
                    row.integer("port", SshProfileImportFailure.INVALID_PROFILE), row.string("username"), row.optionalString("group"),
                    authentication = if (schema == 2) row.authentication() else null,
                    startupCommand = if (schema == 2) row.string("startupCommand") else "",
                    keepaliveSeconds = if (schema == 2) row.integer("keepaliveSeconds", SshProfileImportFailure.INVALID_PROFILE) else 30)
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

private fun JsonObject.authentication(): SshAuthentication =
    (get("authentication") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { value ->
        SshAuthentication.entries.find { it.name == value }
    } ?: fail(SshProfileImportFailure.INVALID_PROFILE)

/** Only the credential-free export of the old serializer, never a device backup. */
private fun decodeLegacyProfiles(root: JsonObject): SshProfileImport {
    root.allowOnly(setOf("schema", "profiles", "hostKeys", "keys", "rejectedHostKeys"))
    if (!root.containsKey("schema")) fail(SshProfileImportFailure.FORMAT)
    if (root.integer("schema", SshProfileImportFailure.SCHEMA) != 1) fail(SshProfileImportFailure.SCHEMA)
    for (field in listOf("hostKeys", "keys", "rejectedHostKeys")) {
        if (root.rows(field).isNotEmpty()) fail(SshProfileImportFailure.FORMAT)
    }
    val rows = root.rows("profiles")
    if (rows.size > MAX_IMPORT_ROWS) fail(SshProfileImportFailure.LIMIT)
    return SshProfileImport(rows.map { element ->
        val row = element as? JsonObject ?: fail(SshProfileImportFailure.FORMAT)
        row.allowOnly(setOf("id", "name", "host", "port", "username", "authentication",
            "secretRef", "keyRef", "startupCommand", "keepaliveSeconds"))
        for (field in listOf("secretRef", "keyRef")) {
            if (row.containsKey(field) && row[field] != JsonNull) fail(SshProfileImportFailure.FORMAT)
        }
        row.string("id") // Ignore the old identity; the planner generates a new local one.
        SshImportProfile(row.string("name"), row.string("host"),
            row.integer("port", SshProfileImportFailure.INVALID_PROFILE), row.string("username"),
            authentication = row.authentication(), startupCommand = row.string("startupCommand"),
            keepaliveSeconds = row.integer("keepaliveSeconds", SshProfileImportFailure.INVALID_PROFILE))
    }, emptyList())
}

/** Exact alias match. v1 preserves local settings; v2/legacy replace portable settings; a changed endpoint detaches its saved password. */
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
                authentication = row.authentication ?: if (keyRef == null) SshAuthentication.PASSWORD else SshAuthentication.KEY,
                keyRef = keyRef.takeIf { row.authentication == null || row.authentication == SshAuthentication.KEY }))
        } else {
            val targetChanged = before.host != row.host || before.port != row.port || before.username != row.username
            val authentication = row.authentication ?: before.authentication
            val authenticationChanged = authentication != before.authentication
            val after = before.copy(host = row.host, port = row.port, username = row.username,
                authentication = authentication,
                startupCommand = if (row.authentication == null) before.startupCommand else row.startupCommand,
                keepaliveSeconds = if (row.authentication == null) before.keepaliveSeconds else row.keepaliveSeconds,
                secretRef = if (targetChanged || authenticationChanged && authentication != SshAuthentication.PASSWORD) null else before.secretRef,
                keyRef = if (authenticationChanged && authentication != SshAuthentication.KEY) null else before.keyRef)
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
