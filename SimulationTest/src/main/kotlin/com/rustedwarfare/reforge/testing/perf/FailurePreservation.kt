package com.rustedwarfare.reforge.testing.perf

/** A failed attempt to preserve evidence must never replace or silently hide the first failure. */
internal inline fun preserveFailure(primary: Throwable, write: () -> Unit) {
    try { write() } catch (secondary: Throwable) {
        if (secondary !== primary) primary.addSuppressed(secondary)
    }
}
