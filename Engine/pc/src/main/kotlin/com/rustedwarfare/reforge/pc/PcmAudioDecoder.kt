package com.rustedwarfare.reforge.pc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical, immutable PCM data; the rendering backend receives only its fixed WAV output. */
internal class PcmAudio(val channels: Int, val sampleRate: Int, pcm: ByteArray) {
    private val samples = pcm.copyOf()
    val frames: Int = samples.size / (channels * 2)
    fun wavBytes(): ByteArray = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + samples.size)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(channels.toShort())
        putInt(sampleRate); putInt(sampleRate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(samples.size); put(samples)
    }.array()
}

/** PC-only, bounded admission. Decoding is preparation evidence, never evidence of actual playback. */
internal object PcmAudioDecoder {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val rates = setOf(8000, 16000, 22050, 24000, 32000, 44100, 48000)

    fun decode(bytes: ByteArray, source: String): PcmAudio {
        fun bad(reason: String): Nothing = throw IllegalArgumentException("RESOURCE_PACK_AUDIO_INVALID: $source: $reason")
        checkVisualCancellation()
        if (bytes.size > MAX_BYTES) bad("Audio exceeds the existing 16 MiB input bound")
        if (source.endsWith(".ogg", true)) return OggVorbisAdmission.decode(bytes, source, rates)
        if (!source.endsWith(".wav", true)) bad("Unsupported audio codec")
        fun text(offset: Int, length: Int) = String(bytes, offset, length, Charsets.US_ASCII)
        fun u16(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun u32(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        if (bytes.size < 44 || text(0, 4) != "RIFF" || text(8, 4) != "WAVE" || u32(4) != bytes.size.toLong() - 8)
            bad("Expected a complete RIFF/WAVE file with its exact declared length")
        var offset = 12; var channels = 0; var rate = 0; var data: ByteArray? = null; var formatSeen = false
        while (offset < bytes.size) {
            if (bytes.size - offset < 8) bad("Truncated WAV chunk header")
            val kind = text(offset, 4); val size = u32(offset + 4)
            val start = offset + 8; val end = start.toLong() + size
            val padded = end + (size and 1L)
            if (padded > bytes.size || size > MAX_BYTES) bad("Truncated or oversized WAV chunk $kind")
            when (kind) {
                "fmt " -> {
                    if (formatSeen) bad("Duplicate fmt chunk")
                    formatSeen = true
                    if (size != 16L && !(size == 18L && u16(start + 16) == 0)) bad("Expected PCM fmt length16 or18 with no extension")
                    if (u16(start) != 1 || u16(start + 14) != 16) bad("Only signed little-endian PCM16 WAV is supported")
                    channels = u16(start + 2); val sampleRate = u32(start + 4)
                    if (channels !in 1..2 || sampleRate > Int.MAX_VALUE || sampleRate.toInt() !in rates)
                        bad("Unsupported channel count or sample rate")
                    rate = sampleRate.toInt()
                    if (u16(start + 12) != channels * 2 || u32(start + 8) != rate.toLong() * channels * 2)
                        bad("WAV byteRate/blockAlign disagree with PCM format")
                }
                "data" -> {
                    if (!formatSeen || data != null) bad("Expected one data chunk after fmt")
                    if (size <= 0 || size % (channels * 2) != 0L) bad("Expected complete nonempty PCM sample frames")
                    if (size + 44 > MAX_BYTES) bad("Decoded PCM exceeds the existing 16 MiB output bound")
                    data = bytes.copyOfRange(start, end.toInt())
                }
            }
            offset = padded.toInt()
        }
        return PcmAudio(channels, rate, data ?: bad("Missing WAV data chunk"))
    }
}
