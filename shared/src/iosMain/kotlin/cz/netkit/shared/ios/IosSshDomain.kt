package cz.netkit.shared.ios

import cz.netkit.shared.json.JSON_MAX_DEPTH
import cz.netkit.shared.json.jsonNestingDepth
import cz.netkit.shared.ssh.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

enum class IosSshDataFailure { SIZE, UTF8, FORMAT, SCHEMA, INVALID_DATA, CONFLICT }
data class IosSshDataResult(val data: SshData?, val failure: IosSshDataFailure?)
data class IosSshImportResult(val imported: SshProfileImport?, val failure: SshProfileImportFailure?)
data class IosSshPlanResult(val plan: SshImportPlan?, val failure: SshProfileImportFailure?)
data class IosSshImportApplyResult(val data: SshData?, val failure: SshProfileImportFailure?)
data class IosSshEndpointChange(val profileId: String, val before: String, val after: String)
enum class IosSshExportFailure { DUPLICATE_NAMES, TOO_LARGE, INVALID_DATA }
data class IosSshExportResult(val bytes: ByteArray?, val failure: IosSshExportFailure?, val duplicateNames: List<String> = emptyList())
enum class IosSshImportField { HOST, PORT, USERNAME, AUTHENTICATION, STARTUP_COMMAND, KEEPALIVE_SECONDS }
data class IosSshFieldChange(val field: IosSshImportField, val before: String, val after: String)
data class IosSshImportPreview(val before: SshProfile?, val after: SshProfile,
    val changes: List<IosSshFieldChange>, val startupWarning: Boolean, val needsKeySelection: Boolean)

/** Synchronous value operations. Swift serializes metadata transactions and writes atomically. */
class IosSshDomain {
    private val json = Json { encodeDefaults = true }
    fun emptyData(): SshData = SshData()

    fun profile(id: String, name: String, host: String, port: Int, username: String,
        authentication: SshAuthentication, secretRef: String?, keyRef: String?, startupCommand: String,
        keepaliveSeconds: Int): SshProfile? = SshProfile(id, name, host, port, username, authentication,
            secretRef, keyRef, startupCommand, keepaliveSeconds).takeIf { it.valid() }

    fun decodeData(bytes: ByteArray): IosSshDataResult {
        if (bytes.size > 1_048_576) return invalid(IosSshDataFailure.SIZE)
        val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
            catch (_: CharacterCodingException) { return invalid(IosSshDataFailure.UTF8) }
        if (jsonNestingDepth(text) > JSON_MAX_DEPTH) return invalid(IosSshDataFailure.FORMAT)
        return try {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return invalid(IosSshDataFailure.FORMAT)
            val schema = (root["schema"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (schema != 1) invalid(IosSshDataFailure.SCHEMA)
            else checked(json.decodeFromString(SshData.serializer(), text))
        } catch (_: SerializationException) { invalid(IosSshDataFailure.FORMAT) }
        catch (_: IllegalArgumentException) { invalid(IosSshDataFailure.FORMAT) }
    }

    fun encodeData(data: SshData): ByteArray? = if (!data.valid()) null else
        json.encodeToString(SshData.serializer(), data).encodeToByteArray().takeIf { it.size <= 1_048_576 }

    /** Swift must obtain validated, frozen bytes before presenting the file exporter. */
    fun prepareExport(data: SshData): IosSshExportResult = try {
        IosSshExportResult(encodeSshProfileExport(data), null)
    } catch (error: SshProfileExportException) {
        when (val reason = error.reason) {
            is SshProfileExportError.DuplicateNames -> IosSshExportResult(null, IosSshExportFailure.DUPLICATE_NAMES, reason.names)
            SshProfileExportError.TooLarge -> IosSshExportResult(null, IosSshExportFailure.TOO_LARGE)
        }
    } catch (_: IllegalArgumentException) { IosSshExportResult(null, IosSshExportFailure.INVALID_DATA) }

    fun moveProfile(data: SshData, id: String, delta: Int): IosSshDataResult =
        if (!data.valid()) invalid(IosSshDataFailure.INVALID_DATA) else checked(moveSshProfile(data, id, delta))

    fun needsKeySelection(profile: SshProfile, data: SshData): Boolean = profile.needsKeySelection(data)

    fun upsertProfile(data: SshData, profile: SshProfile): IosSshDataResult {
        if (!data.valid() || !profile.valid()) return invalid(IosSshDataFailure.INVALID_DATA)
        val previous = data.profiles.firstOrNull { it.id == profile.id }
        val targetChanged = previous != null && (previous.host != profile.host || previous.port != profile.port || previous.username != profile.username)
        val saved = if (targetChanged) profile.copy(secretRef = null) else profile
        val updated = if (previous == null) data.profiles + saved else data.profiles.map { if (it.id == profile.id) saved else it }
        return checked(data.copy(profiles = updated))
    }

    fun upsertKey(data: SshData, key: SshKeyInfo): IosSshDataResult {
        if (!data.valid()) return invalid(IosSshDataFailure.INVALID_DATA)
        val updated = if (data.keys.none { it.id == key.id }) data.keys + key else data.keys.map { if (it.id == key.id) key else it }
        return checked(data.copy(keys = updated))
    }

    /** Metadata only: persist the result before deleting unreferenced Keychain material. */
    fun deleteProfile(data: SshData, id: String): IosSshDataResult {
        if (!data.valid()) return invalid(IosSshDataFailure.INVALID_DATA)
        if (data.profiles.none { it.id == id }) return invalid(IosSshDataFailure.CONFLICT)
        return checked(data.copy(profiles = data.profiles.filterNot { it.id == id }))
    }

    /** A key referenced by any profile cannot be removed, regardless of authentication mode. */
    fun deleteKey(data: SshData, id: String): IosSshDataResult {
        if (!data.valid()) return invalid(IosSshDataFailure.INVALID_DATA)
        if (data.keys.none { it.id == id } || data.profiles.any { it.keyRef == id })
            return invalid(IosSshDataFailure.CONFLICT)
        return checked(data.copy(keys = data.keys.filterNot { it.id == id }))
    }

    fun hostDecision(data: SshData, offered: TrustedHostKey): HostKeyDecision =
        hostKeyDecision(data.hostKeys.firstOrNull { it.endpoint == offered.endpoint }, offered)

    /** Unknown-host confirmation cannot replace a different saved key, including a racing insertion. */
    fun trustUnknown(data: SshData, offered: TrustedHostKey): IosSshDataResult {
        if (!data.valid()) return invalid(IosSshDataFailure.INVALID_DATA)
        val previous = data.hostKeys.firstOrNull { it.endpoint == offered.endpoint }
        if (previous != null && previous != offered) return invalid(IosSshDataFailure.CONFLICT)
        return checked(if (previous == null) data.copy(hostKeys = data.hostKeys + offered) else data)
    }

    fun recordRejected(data: SshData, offered: TrustedHostKey): IosSshDataResult = if (!data.valid()) invalid(IosSshDataFailure.INVALID_DATA)
        else checked(data.copy(rejectedHostKeys = (data.rejectedHostKeys.filterNot { it.endpoint == offered.endpoint } + offered).takeLast(500)))

    /** User-confirmed trust reset retains the last rejected key, as on Android. */
    fun forgetHost(data: SshData, endpoint: String): IosSshDataResult = if (!data.valid()) invalid(IosSshDataFailure.INVALID_DATA)
        else checked(data.copy(hostKeys = data.hostKeys.filterNot { it.endpoint == endpoint }))

    fun previousRejected(data: SshData, offered: TrustedHostKey): TrustedHostKey? =
        distinctRejectedHostKey(offered, data.rejectedHostKeys.firstOrNull { it.endpoint == offered.endpoint })

    fun decodeImport(bytes: ByteArray): IosSshImportResult = try {
        IosSshImportResult(decodeSshProfileImport(bytes), null)
    } catch (error: SshProfileImportException) { IosSshImportResult(null, error.reason) }

    fun planImport(current: SshData, imported: SshProfileImport, keyRef: String?, generateId: () -> String): IosSshPlanResult = try {
        IosSshPlanResult(planSshProfileImport(current, imported, keyRef, generateId), null)
    } catch (error: SshProfileImportException) { IosSshPlanResult(null, error.reason) }

    fun applyImport(current: SshData, plan: SshImportPlan): IosSshImportApplyResult = try {
        IosSshImportApplyResult(applySshImportPlan(current, plan), null)
    } catch (error: SshProfileImportException) { IosSshImportApplyResult(null, error.reason) }

    /** Avoid a Cocoa `new`-family getter name at the Swift preview boundary. */
    fun importAddedCount(plan: SshImportPlan): Int = plan.newProfiles.size

    fun importDetachedRefs(plan: SshImportPlan): List<String> = plan.detachedSecretRefs

    /** Values stay unlocalized; Swift supplies CS/EN labels and authentication names. */
    fun importProfilePreviews(plan: SshImportPlan, data: SshData): List<IosSshImportPreview> =
        (plan.newProfiles + plan.updatedProfiles + plan.unchangedProfiles).map { entry ->
            val before = entry.before
            val after = entry.after
            val changes = buildList {
                fun change(field: IosSshImportField, old: String, updated: String) {
                    if (old != updated) add(IosSshFieldChange(field, old, updated))
                }
                if (before != null) {
                    change(IosSshImportField.HOST, before.host, after.host)
                    change(IosSshImportField.PORT, before.port.toString(), after.port.toString())
                    change(IosSshImportField.USERNAME, before.username, after.username)
                    change(IosSshImportField.AUTHENTICATION, before.authentication.name, after.authentication.name)
                    change(IosSshImportField.STARTUP_COMMAND, before.startupCommand, after.startupCommand)
                    change(IosSshImportField.KEEPALIVE_SECONDS, before.keepaliveSeconds.toString(), after.keepaliveSeconds.toString())
                }
            }
            IosSshImportPreview(before, after, changes,
                if (before == null) after.startupCommand.isNotEmpty() else before.startupCommand != after.startupCommand,
                after.needsKeySelection(data))
        }

    fun importChangedEndpoints(plan: SshImportPlan): List<IosSshEndpointChange> =
        plan.updatedProfiles.mapNotNull { entry ->
            val before = entry.before ?: return@mapNotNull null
            val after = entry.after
            if (before.host == after.host && before.port == after.port && before.username == after.username) null
            else IosSshEndpointChange(after.id, "${before.username}@${before.endpoint}", "${after.username}@${after.endpoint}")
        }

    fun kexAlgorithms(): List<String> = SshAlgorithms.kex
    fun hostKeyAlgorithms(): List<String> = SshAlgorithms.hostKeys
    fun cipherAlgorithms(): List<String> = SshAlgorithms.ciphers
    fun macAlgorithms(): List<String> = SshAlgorithms.macs
    private fun checked(data: SshData): IosSshDataResult = if (data.valid()) IosSshDataResult(data, null) else invalid(IosSshDataFailure.INVALID_DATA)
    private fun invalid(failure: IosSshDataFailure) = IosSshDataResult(null, failure)
}
