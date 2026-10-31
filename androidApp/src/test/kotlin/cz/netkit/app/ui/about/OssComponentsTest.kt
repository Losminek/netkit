package cz.netkit.app.ui.about

import java.io.File
import kotlin.test.*

/**
 * Keeps About > Open-source licences in step with the build. Two sources are checked because each
 * covers what the other cannot:
 * - `androidApp/build.gradle.kts` + `gradle/libs.versions.toml` (and `shared` commonMain): the
 *   direct runtime dependencies as the build really declares them, with their catalog versions;
 *   always current, but blind to transitive libraries.
 * - `docs/project-license-inventory.tsv`: every coordinate of `debugRuntimeClasspath` (transitive
 *   AndroidX, Okio, JSpecify…); complete, but a snapshot refreshed by hand when the graph changes.
 * Unit tests run with the module directory as the working directory.
 */
class OssComponentsTest {
    private val module = File(".").canonicalFile
    private val root = module.parentFile
    private val assets = File(module, "src/main/assets/$OSS_LICENSES_DIR")
    private val components: List<OssComponent> by lazy {
        assertNotNull(parseOssComponents(File(assets, OSS_COMPONENTS_FILE).readText()), "components.json parses")
    }

    @Test fun componentsAreWellFormedAndTextsExist() {
        assertTrue(components.size >= 10)
        assertEquals(components.size, components.map { it.id }.toSet().size, "unique ids")
        for (component in components) {
            assertTrue(Regex("[a-z0-9-]+").matches(component.id), component.id)
            assertTrue(component.name.isNotBlank() && component.license.isNotBlank() && component.copyright.isNotBlank(), component.id)
            assertTrue(component.texts.isNotEmpty(), component.id)
            for (text in component.texts) assertTrue(File(assets, text).isFile, "${component.id}: $text exists")
        }
        val referenced = components.flatMap { it.texts }.toSet()
        val shipped = assets.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".txt") }.toSet()
        assertEquals(shipped, referenced, "every shipped licence text is listed, nothing missing")
    }

    @Test fun licenceTextsMatchTheLicence() {
        for (component in components) {
            val expected = when {
                component.license == "Apache-2.0" -> "Apache-2.0.txt"
                component.license == "MPL-2.0" -> "MPL-2.0.txt"
                component.license == "OFL-1.1" -> "OFL.txt"
                else -> null
            } ?: continue
            assertTrue(component.texts.any { it.endsWith(expected) }, "${component.id} carries $expected")
        }
    }

    @Test fun everyDirectRuntimeDependencyIsListed() {
        val catalog = libraries()
        val versions = versions()
        val shared = File(root, "shared/build.gradle.kts").readText()
        val sharedMain = shared.substringAfter("commonMain.dependencies {", "").substringBefore("}")
        assertTrue(sharedMain.isNotBlank(), "shared commonMain dependencies found")
        val declared = directRuntimeAliases(File(module, "build.gradle.kts").readText()) + directRuntimeAliases(sharedMain)
        assertTrue("okhttp" in declared && "jsch" in declared && "compose-bom" in declared, "parser sees the build: $declared")
        for (alias in declared) {
            val library = assertNotNull(catalog[alias], "$alias is in libs.versions.toml")
            val component = components.firstOrNull { alias in it.catalog }
            assertNotNull(component, "$alias (${library.first}) has an entry in components.json")
            library.second?.let { ref ->
                val version = assertNotNull(versions[ref], "version $ref")
                assertTrue(component.version.orEmpty().contains(version), "${component.id} shows $alias $version, has ${component.version}")
            }
        }
        val known = catalog.keys
        for (component in components) for (alias in component.catalog) assertTrue(alias in known, "${component.id}: unknown alias $alias")
    }

    @Test fun everyRuntimeArtifactOfTheInventoryIsCovered() {
        val rows = File(root, "docs/project-license-inventory.tsv").readLines().drop(1).map { it.split('\t') }
        val runtime = rows.filter { it.size > 4 && "debugRuntimeClasspath" in it[4].split(',') }.map { "${it[0]}:${it[1]}" }
        assertTrue(runtime.size > 50, "inventory has the runtime graph")
        val patterns = components.flatMap { it.artifacts }
        for (coordinate in runtime) {
            assertTrue(patterns.any { matches(it, coordinate) }, "$coordinate is covered by an artifacts pattern")
        }
    }

    @Test fun copiedTextsAreIdenticalToTheirSources() {
        assertEquals(File(root, "NOTICE").readText(), File(assets, "NOTICE.txt").readText())
        assertEquals(File(root, "LICENSE").readText(), File(assets, "Apache-2.0.txt").readText())
        for ((asset, source) in listOf("jsch-LICENSE.txt" to "jsch-META-INF_LICENSE.txt", "jzlib-LICENSE.txt" to "jsch-META-INF_LICENSE.JZlib.txt",
            "jbcrypt-LICENSE.txt" to "jsch-META-INF_LICENSE.jBCrypt.txt")) {
            assertEquals(File(root, "docs/licenses/$source").readText(), File(assets, asset).readText(), asset)
        }
        // The Bouncy Castle licence is HTML upstream; the asset is the same words without markup.
        val html = File(root, "docs/licenses/bouncycastle-LICENSE.html").readText().replace(Regex("<[^>]+>"), " ")
        assertEquals(words(html), words(File(assets, "bouncycastle-LICENSE.txt").readText()))
        assertTrue(File(assets, "MPL-2.0.txt").readText().startsWith("Mozilla Public License Version 2.0"))
    }

    @Test fun paragraphsReflowButKeepListsAndWords() {
        val text = "Copyright (c) X\nAll rights reserved.\n\nRedistribution is\npermitted:\n\n  1. Keep the\n     notice.\n  2. Binary too.\n\n\n"
        assertEquals(listOf("Copyright (c) X All rights reserved.", "Redistribution is permitted:", "1. Keep the notice.\n2. Binary too."),
            licenseParagraphs(text))
        assertEquals(listOf("Title\n=====\nNext line joined"), licenseParagraphs("Title\n=====\nNext line\njoined"))
        assertEquals(listOf("-----\nCopyright X All rights reserved."), licenseParagraphs("-----\nCopyright X\nAll rights reserved."))
        for (name in components.flatMap { it.texts }.distinct()) {
            val original = File(assets, name).readText()
            assertEquals(words(original), words(licenseParagraphs(original).joinToString(" ")), "$name keeps every word")
        }
    }

    @Test fun malformedComponentFileIsNotACrash() {
        assertNull(parseOssComponents("{"))
        assertNull(parseOssComponents("""{"components":[{"id":"x"}]}"""))
        assertNull(parseOssComponents("""{"components":[],"unexpected":1}"""))
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** `group:artifact` against `g:a`, `g:*`, `g:prefix-*` or `prefix.*` (any group below). */
    private fun matches(pattern: String, coordinate: String): Boolean {
        val (group, artifact) = coordinate.split(':')
        val groupPattern = pattern.substringBefore(':')
        val artifactPattern = if (':' in pattern) pattern.substringAfter(':') else "*"
        val groupOk = if (groupPattern.endsWith(".*")) group.startsWith(groupPattern.removeSuffix("*")) else group == groupPattern
        val artifactOk = if (artifactPattern.endsWith("*")) artifact.startsWith(artifactPattern.removeSuffix("*")) else artifact == artifactPattern
        return groupOk && artifactOk
    }

    /** Catalog aliases of `implementation(libs.x.y)` and `implementation(platform(libs.x))`, as `x-y`. */
    private fun directRuntimeAliases(script: String): Set<String> =
        Regex("""^\s*implementation\((?:platform\()?libs\.([A-Za-z0-9.]+)\)""", RegexOption.MULTILINE)
            .findAll(script).map { it.groupValues[1].replace('.', '-') }.toSet()

    /** alias -> (module, version.ref or null) from `[libraries]`. */
    private fun libraries(): Map<String, Pair<String, String?>> = section("libraries").mapNotNull { line ->
        val match = Regex("""^([A-Za-z0-9-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"(?:\s*,\s*version\.ref\s*=\s*"([^"]+)")?\s*}""").find(line)
            ?: return@mapNotNull null
        match.groupValues[1] to (match.groupValues[2] to match.groupValues[3].ifEmpty { null })
    }.toMap()

    private fun versions(): Map<String, String> = section("versions").mapNotNull { line ->
        Regex("""^([A-Za-z0-9-]+)\s*=\s*"([^"]+)"""").find(line)?.let { it.groupValues[1] to it.groupValues[2] }
    }.toMap()

    private fun section(name: String): List<String> {
        val lines = File(root, "gradle/libs.versions.toml").readLines()
        val start = lines.indexOf("[$name]")
        assertTrue(start >= 0, "[$name] in libs.versions.toml")
        return lines.drop(start + 1).takeWhile { !it.startsWith("[") }
    }
}
