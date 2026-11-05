package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.PreparedVisualAssets
import java.nio.file.Path

/** One current selection, held only in this process. No GL objects or disk-provided trust. */
internal class PcAuditedVisualReuse {
    private data class Audit(val key: Any, val prepared: PreparedVisualAssets)
    private var current: Audit? = null

    fun prepare(key: Any?, roots: List<Path>,
        audit: (() -> Unit, PcFrozenResourceInputs?) -> PreparedVisualAssets): PreparedVisualAssets {
        val previous = current
        current = null // Failure/cancellation cannot leave a reusable partial request.
        checkVisualCancellation()
        if (key == null) return audit(::checkVisualCancellation, null)
        if (previous != null && previous.key == key) {
            previous.prepared.verifyForPublication()
            checkVisualCancellation()
            current = previous
            return previous.prepared
        }
        PcFrozenResourceInputs.capture(roots).use { inputs ->
            val prepared = audit(inputs::verifyCurrent, inputs)
            inputs.verifyCurrent()
            checkVisualCancellation()
            current = Audit(key, prepared)
            return prepared
        }
    }
}
