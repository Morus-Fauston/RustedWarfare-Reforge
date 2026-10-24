package com.rustedwarfare.reforge.pc

internal inline fun preserveFailure(primary: Throwable, write: () -> Unit) {
    try { write() } catch (secondary: Throwable) {
        if (secondary !== primary) primary.addSuppressed(secondary)
    }
}
