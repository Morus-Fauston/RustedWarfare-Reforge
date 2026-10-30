package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.content.internal.readPngMetadata
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class PngContentTest {
    @Test fun valid_header_never_publishes_metadata_for_a_corrupted_or_truncated_image() {
        val file = Files.createTempFile("content-png-invalid-", ".png")
        try {
            ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", file.toFile())
            val original = Files.readAllBytes(file)
            val damagedCrc = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            for (bytes in listOf(damagedCrc, original.copyOf(24), original.copyOf(original.size - 12))) {
                Files.write(file, bytes)
                assertFails { readPngMetadata(file) }
            }
        } finally { Files.delete(file) }
    }

    @Test fun indexed_encoding_remains_an_explicit_reader_permission() {
        val file = Files.createTempFile("content-png-indexed-", ".png")
        try {
            ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_BYTE_INDEXED), "png", file.toFile())
            assertFailsWith<IllegalArgumentException> { readPngMetadata(file) }
            val metadata = readPngMetadata(file, allowIndexed = true)
            assertEquals(3, metadata.width)
            assertEquals(2, metadata.height)
        } finally { Files.delete(file) }
    }

    @Test fun fully_validated_image_returns_its_dimensions_without_a_graphics_context() {
        val file = Files.createTempFile("content-png-metadata-", ".png")
        try {
            ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", file.toFile())
            val metadata = readPngMetadata(file)
            assertEquals(3, metadata.width)
            assertEquals(2, metadata.height)
        } finally { Files.delete(file) }
    }
}
