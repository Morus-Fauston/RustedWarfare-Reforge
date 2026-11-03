package com.rustedwarfare.reforge.pc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PcmAudioDecoderTest {
    @Test fun ogg_crc_sequence_headers_final_page_and_decode_budget_fail_with_specific_source_diagnostics() {
        val source = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs/user-tone.ogg")
        val original = Files.readAllBytes(source); val pages = oggPages(original)
        fun body(page: Int) = page + 27 + (original[page + 26].toInt() and 255)
        fun changed(page: Int, change: (ByteArray, Int) -> Unit) = original.copyOf().also { change(it, page); checksum(it, page) }
        fun intAt(page: Int, relative: Int, value: Int) = changed(page) { bytes, at ->
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at + relative, value)
        }
        fun longAt(page: Int, value: Long) = changed(page) { bytes, at ->
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(at + 6, value)
        }
        val identification = body(0); val comments = body(pages[1]); val setup = comments + (original[pages[1] + 27].toInt() and 255)
        val vendorLength = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(comments + 7)
        val invalid = listOf(
            original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to "CRC",
            original.copyOf(original.size - 1) to "Truncated",
            original.copyOf(pages.last()) to "end-of-stream",
            original + original to "end-of-stream",
            changed(0) { b, at -> b[at + 4] = 1 } to "page",
            changed(0) { b, at -> b[at + 5] = 3 } to "continuation",
            changed(0) { b, at -> b[at + 5] = 0 } to "beginning",
            intAt(pages[1], 18, 99) to "sequence",
            intAt(pages[1], 14, ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(14) xor 1) to "logical",
            changed(pages.last()) { b, at -> b[at + 5] = 0 } to "end-of-stream",
            longAt(pages.last(), Int.MAX_VALUE.toLong()) to "oversized",
            longAt(pages.last(), -2) to "granule",
            changed(0) { b, _ -> b[identification + 11] = 3 } to "identification",
            intAt(0, identification + 12, 12345) to "identification",
            intAt(0, identification + 7, 1) to "identification",
            changed(0) { b, _ -> b[identification + 28] = 0x55 } to "identification",
            changed(0) { b, _ -> b[identification + 29] = 0 } to "identification",
            intAt(pages[1], comments - pages[1] + 11 + vendorLength, Int.MAX_VALUE) to "comment",
            changed(pages[1]) { b, _ -> b[setup] = 4 } to "setup",
            changed(pages.last()) { b, _ -> val offset = body(pages.last()); b[offset] = (b[offset].toInt() or 1).toByte() } to "audio packet",
        )
        for ((bytes, reason) in invalid) {
            val error = assertFailsWith<IllegalArgumentException> { PcmAudioDecoder.decode(bytes, "owned/corrupt.ogg") }
            assertTrue(error.message.orEmpty().contains("RESOURCE_PACK_AUDIO_INVALID: owned/corrupt.ogg"), error.message)
            assertTrue(error.message.orEmpty().contains(reason, ignoreCase = true), error.message)
        }
    }
    @Test fun vorbis_header_pages_must_have_zero_granules_even_with_valid_checksums() {
        val source = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs/user-tone.ogg")
        val original = Files.readAllBytes(source)
        for (granule in listOf(1L, -1L)) {
            val changed = original.copyOf()
            for (page in oggPages(changed).dropLast(1)) {
                ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putLong(page + 6, granule)
                checksum(changed, page)
            }
            val error = assertFailsWith<IllegalArgumentException> { PcmAudioDecoder.decode(changed, "header-position.ogg") }
            assertTrue(error.message.orEmpty().contains("granule"), error.message)
        }
    }
    @Test fun owned_vorbis_is_actually_decoded_to_complete_pcm_with_its_declared_sample_count() {
        for (name in listOf("user-tone.ogg", "long-comment.ogg")) {
            val source = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs/$name")
            val decoded = PcmAudioDecoder.decode(Files.readAllBytes(source), source.toString())
            assertEquals(1, decoded.channels); assertEquals(22050, decoded.sampleRate); assertEquals(3528, decoded.frames)
            val wave = decoded.wavBytes()
            assertEquals(7100, wave.size)
            assertTrue(wave.copyOfRange(44, wave.size).any { it != 0.toByte() }, "The real Vorbis decoder must return audio, not a fabricated silent header")
            assertContentEquals(wave, PcmAudioDecoder.decode(wave, "canonical.wav").wavBytes())
        }
    }
    @Test fun malformed_compressed_truncated_or_oversized_waves_are_not_treated_as_missing_audio() {
        val source = wave(byteArrayOf(0, 0, 1, 0, 2, 0, 3, 0))
        fun intAt(offset: Int, value: Int) = source.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }
        fun shortAt(offset: Int, value: Int) = source.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort()) }
        fun riff(bytes: ByteArray) = bytes.also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, it.size - 8) }
        val duplicateFmt = riff(source.copyOfRange(0, 36) + source.copyOfRange(12, 36) + source.copyOfRange(36, source.size))
        val duplicateData = riff(source + byteArrayOf(100, 97, 116, 97, 2, 0, 0, 0, 0, 0))
        val noData = riff(source.copyOf(44).also { "JUNK".toByteArray().copyInto(it, 36); ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0) })
        val invalid = listOf(source.copyOfRange(0, 30), source.copyOf(source.size - 1), riff(source.copyOf(source.size - 1)),
            source + byteArrayOf(0), intAt(4, 1), intAt(16, 1000000), shortAt(20, 3), shortAt(22, 3),
            intAt(24, 12345), intAt(28, 1), shortAt(32, 1), shortAt(34, 8), intAt(40, 7),
            duplicateFmt, duplicateData, noData, ByteArray(PcmAudioDecoder.MAX_BYTES + 1))
        for (bytes in invalid) {
            val error = assertFailsWith<IllegalArgumentException> { PcmAudioDecoder.decode(bytes, "owned/bad.wav") }
            assertTrue(error.message.orEmpty().contains("RESOURCE_PACK_AUDIO_INVALID: owned/bad.wav"), error.message)
        }
        val metadata = byteArrayOf(74, 85, 78, 75, 3, 0, 0, 0, 97, 98, 99, 0)
        val withJunk = riff(source.copyOfRange(0, 36) + metadata + source.copyOfRange(36, source.size))
        assertContentEquals(source, PcmAudioDecoder.decode(withJunk, "clip.WAV").wavBytes())
        val stereo = PcmAudioDecoder.decode(wave(byteArrayOf(0, 0, 1, 0, 2, 0, 3, 0), 2, 44100), "stereo.wav")
        assertEquals(2, stereo.channels); assertEquals(44100, stereo.sampleRate); assertEquals(2, stereo.frames)
    }
    @Test fun valid_pcm16_wave_is_fully_decoded_and_frozen_as_the_same_canonical_wave() {
        val pcm = byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte(), 0x34, 0x12)
        val source = wave(pcm)
        val decoded = PcmAudioDecoder.decode(source, "owned/clip.wav")
        assertEquals(1, decoded.channels)
        assertEquals(8000, decoded.sampleRate)
        assertEquals(4, decoded.frames)
        assertContentEquals(source, decoded.wavBytes())
        source.fill(0)
        assertContentEquals(pcm, decoded.wavBytes().copyOfRange(44, 52), "Caller mutation cannot alter fixed decoded PCM")
    }

    private fun wave(pcm: ByteArray, channels: Int = 1, rate: Int = 8000): ByteArray =
        ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(channels.toShort()); putInt(rate); putInt(rate * channels * 2)
            putShort((channels * 2).toShort()); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()

    private fun oggPages(bytes: ByteArray): List<Int> {
        val pages = mutableListOf<Int>(); var offset = 0
        while (offset < bytes.size) {
            pages += offset
            val count = bytes[offset + 26].toInt() and 255
            offset += 27 + count + (0 until count).sumOf { bytes[pages.last() + 27 + it].toInt() and 255 }
        }
        assertEquals(bytes.size, offset)
        return pages
    }

    // Independent bit-at-a-time Xiph CRC only constructs semantic negative inputs.
    // The original positive fixture was produced by FFmpeg, not this checksum helper.
    private fun checksum(bytes: ByteArray, page: Int) {
        val count = bytes[page + 26].toInt() and 255
        val end = page + 27 + count + (0 until count).sumOf { bytes[page + 27 + it].toInt() and 255 }
        (page + 22..page + 25).forEach { bytes[it] = 0 }
        var crc = 0
        for (index in page until end) {
            crc = crc xor ((bytes[index].toInt() and 255) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(page + 22, crc)
    }
}
