package com.rustedwarfare.reforge.core.content.internal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

internal data class PngMetadata(val width: Int, val height: Int)

/** Native fixtures opt into their source encoding; turret inputs remain RGBA8. */
internal fun readPngMetadata(path: Path, allowIndexed: Boolean = false, allowGrayAlpha: Boolean = false): PngMetadata {
    require(Files.size(path) <= 16L * 1024 * 1024) { "PNG read bound" }
    return DataInputStream(ByteArrayInputStream(Files.readAllBytes(path))).use { input ->
        require(input.readLong() == -8552249625308161526L) { "PNG signature" }
        var width = 0; var height = 0; var ended = false; var colorType = 6; var paletteEntries = 0
        val compressed = ByteArrayOutputStream()
        while (input.available() > 0) {
            val length = input.readInt()
            require(length >= 0 && length <= input.available() - 8) { "PNG chunk length" }
            val type = ByteArray(4); input.readFully(type)
            val data = ByteArray(length); input.readFully(data)
            val crc = input.readInt().toLong() and 0xffffffffL
            require(crc == CRC32().apply { update(type); update(data) }.value) { "PNG CRC" }
            val name = String(type, Charsets.US_ASCII)
            if (width == 0) {
                require(name == "IHDR" && length == 13) { "PNG IHDR" }
                DataInputStream(ByteArrayInputStream(data)).use { h ->
                    width = h.readInt(); height = h.readInt()
                    val depth = h.readUnsignedByte(); colorType = h.readUnsignedByte()
                    require(width > 0 && height > 0 && depth == 8 &&
                        (colorType == 6 || allowIndexed && colorType == 3 || allowGrayAlpha && colorType == 4) &&
                        h.readUnsignedByte() == 0 && h.readUnsignedByte() == 0 && h.readUnsignedByte() == 0) { "Expected noninterlaced RGBA8" }
                }
            } else require(name != "IHDR") { "Duplicate PNG header" }
            when (name) {
                "PLTE" -> if (colorType == 3) {
                    require(paletteEntries == 0 && compressed.size() == 0 && length in 3..768 && length % 3 == 0) { "PNG palette" }
                    paletteEntries = length / 3
                }
                "tRNS" -> if (colorType == 3) require(paletteEntries > 0 && length in 1..paletteEntries) { "PNG palette alpha" }
                "IDAT" -> {
                    require(colorType != 3 || paletteEntries > 0) { "PNG missing palette" }
                    compressed.write(data)
                }
                "IEND" -> { require(length == 0 && input.available() == 0); ended = true }
            }
            if (ended) break
        }
        val row = width.toLong() * (when (colorType) { 3 -> 1L; 4 -> 2L; else -> 4L }) + 1L
        val expected = Math.multiplyExact(row, height.toLong())
        require(ended && compressed.size() > 0 && expected <= 16L * 1024 * 1024) { "PNG size or end" }
        InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { pixels ->
            if (colorType == 3) {
                var previous = ByteArray(width)
                repeat(height) {
                    val filter = pixels.read(); require(filter in 0..4) { "PNG row filter" }
                    val decoded = ByteArray(width)
                    for (x in decoded.indices) {
                        val raw = pixels.read(); require(raw >= 0) { "PNG truncated" }
                        val left = if (x == 0) 0 else decoded[x - 1].toInt() and 255
                        val above = previous[x].toInt() and 255
                        val upperLeft = if (x == 0) 0 else previous[x - 1].toInt() and 255
                        val predictor = when (filter) {
                            1 -> left
                            2 -> above
                            3 -> (left + above) / 2
                            4 -> {
                                val p = left + above - upperLeft
                                val a = kotlin.math.abs(p - left); val b = kotlin.math.abs(p - above)
                                val c = kotlin.math.abs(p - upperLeft)
                                if (a <= b && a <= c) left else if (b <= c) above else upperLeft
                            }
                            else -> 0
                        }
                        val index = (raw + predictor) and 255
                        require(index < paletteEntries) { "PNG palette index" }
                        decoded[x] = index.toByte()
                    }
                    previous = decoded
                }
                require(pixels.read() == -1) { "PNG overrun" }
                return@use
            }
            val buffer = ByteArray(4096); var count = 0L
            while (true) {
                val read = pixels.read(buffer); if (read < 0) break
                for (i in 0 until read) if ((count + i) % row == 0L)
                    require((buffer[i].toInt() and 255) <= 4) { "PNG row filter" }
                count += read; require(count <= expected) { "PNG overrun" }
            }
            require(count == expected) { "PNG truncated" }
        }
        PngMetadata(width, height)
    }
}
