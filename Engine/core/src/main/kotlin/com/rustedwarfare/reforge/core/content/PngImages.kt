package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.content.internal.readPngMetadata

data class PngImageDimensions(val width: Int, val height: Int)

/** Reuses the content PNG validator on the exact bytes already captured by a caller. */
object PngImages {
    // Existing trusted base atlases include RGB8. User import deliberately keeps the default admission rules.
    fun inspect(bytes: ByteArray, allowRgb: Boolean = false): PngImageDimensions =
        readPngMetadata(bytes, allowIndexed = true, allowGrayAlpha = true, allowRgb = allowRgb)
        .let { PngImageDimensions(it.width, it.height) }
}
