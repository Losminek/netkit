package cz.netkit.shared.lan

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** A fixed worker pool avoids allocating a coroutine for every host/port in a range. */
suspend fun <T> probeBounded(targets: List<T>, parallel: Int, probe: suspend (T) -> Unit) = coroutineScope {
    require(parallel in 1..64)
    repeat(minOf(parallel,targets.size)) { worker ->
        launch {
            var index=worker
            while (index < targets.size) {
                currentCoroutineContext().ensureActive()
                probe(targets[index])
                index += parallel
            }
        }
    }
}
