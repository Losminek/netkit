package cz.netkit.shared.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class JsonLimitsTest {
    @Test fun countsContainersButNotScalars() {
        for (text in listOf("0", "null", "true", "\"text\"")) assertEquals(0, jsonNestingDepth(text))
        assertEquals(1, jsonNestingDepth("{}"))
        assertEquals(1, jsonNestingDepth("[]"))
        assertEquals(4, jsonNestingDepth("""{"a":[{"b":[]}],"c":{}}"""))
    }

    @Test fun ignoresQuotedBracketsAndEscapesWithoutHidingFollowingContainers() {
        for (slashes in 0..4) {
            val quoted = JsonPrimitive("\\".repeat(slashes) + "\"" + "[{".repeat(40) + "\\").toString()
            val text = """{"text":$quoted,"after":[{}]}"""
            assertEquals(3, jsonNestingDepth(text))
            assertIs<JsonObject>(Json.parseToJsonElement(text))
        }
        assertEquals(1, jsonNestingDepth("""{"text":"\u005b\u007b\u0022"}"""))
        assertEquals(2, jsonNestingDepth("""{"endsWithSlash":"\\","after":[]}"""))
    }

    @Test fun maximumDepthReallyParsesAsATree() {
        for (objects in listOf(false, true)) {
            val text = nestedJson(JSON_MAX_DEPTH, objects)
            assertEquals(JSON_MAX_DEPTH, jsonNestingDepth(text))
            var node = Json.parseToJsonElement(text)
            repeat(JSON_MAX_DEPTH) {
                node = if (objects) assertIs<JsonObject>(node).getValue("x") else assertIs<JsonArray>(node).single()
            }
            assertEquals(JsonPrimitive(0), node)
        }
    }

    @Test fun hundredThousandLevelsUseNoRecursiveScanner() {
        for (objects in listOf(false, true)) assertEquals(100_000, jsonNestingDepth(nestedJson(100_000, objects)))
        assertEquals(100_000, jsonNestingDepth("[".repeat(100_000)))
    }
}

internal fun nestedJson(depth: Int, objects: Boolean): String =
    if (objects) "{\"x\":".repeat(depth) + "0" + "}".repeat(depth)
    else "[".repeat(depth) + "0" + "]".repeat(depth)
