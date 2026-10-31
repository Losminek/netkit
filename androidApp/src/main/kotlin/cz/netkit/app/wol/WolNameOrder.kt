package cz.netkit.app.wol

import java.text.Collator
import java.util.Locale

/** One comparator per controller; case-insensitive Czech collation, including the digraph ch. */
internal fun czechWolNameComparator(): Comparator<String> {
    val collator = Collator.getInstance(Locale.forLanguageTag("cs")).apply {
        strength = Collator.SECONDARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    return Comparator { left, right -> collator.compare(left, right) }
}
