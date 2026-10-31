package cz.netkit.shared.json

/**
 * Bound untrusted JSON before either tree or typed decoding. The deepest current wire schema
 * needs 4 levels (NDT7 locate: root/results/result/urls or location); 16 leaves extension room
 * and preserves the existing latest.json limit without approaching thousands of stack frames.
 * This complements, rather than replaces, each input's byte/character limit.
 */
const val JSON_MAX_DEPTH = 16

/**
 * Deepest `[`/`{` nesting outside string literals, in O(text.length) time and O(1) extra space.
 * Escaped quotes/backslashes do not end a string. This is not a JSON validator: unbalanced or
 * otherwise malformed input is left to the parser. Call before parsing untrusted text.
 */
fun jsonNestingDepth(text: String): Int {
    var depth = 0
    var deepest = 0
    var inString = false
    var escaped = false
    for (char in text) {
        when {
            escaped -> escaped = false
            inString -> when (char) { '\\' -> escaped = true; '"' -> inString = false }
            char == '"' -> inString = true
            char == '[' || char == '{' -> { depth++; if (depth > deepest) deepest = depth }
            char == ']' || char == '}' -> depth--
        }
    }
    return deepest
}
