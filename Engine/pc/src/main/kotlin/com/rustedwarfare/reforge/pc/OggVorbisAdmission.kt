package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.backends.lwjgl3.audio.OggInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Strict single-stream container admission plus the existing libGDX Vorbis decoder. */
internal object OggVorbisAdmission {
    // Xiph Ogg framing CRC, not the reflected PNG/ZIP polynomial.
    private val crcTable = IntArray(256) { entry ->
        var value = entry shl 24
        repeat(8) { value = (value shl 1) xor if (value < 0) 0x04c11db7 else 0 }
        value
    }

    fun decode(bytes: ByteArray, source: String, supportedRates: Set<Int>): PcmAudio {
        fun bad(reason: String): Nothing = throw IllegalArgumentException("RESOURCE_PACK_AUDIO_INVALID: $source: $reason")
        fun u32(data: ByteArray, offset: Int) = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        fun header(packet: ByteArray, type: Int) = packet.size >= 7 && packet[0].toInt() == type &&
            String(packet, 1, 6, Charsets.US_ASCII) == "vorbis"
        var offset = 0; var serial: Long? = null; var sequence = 0L; var ended = false
        var channels = 0; var rate = 0; var packetCount = 0; var lastGranule = 0L; var frames = 0L
        val packet = ByteArrayOutputStream()
        while (offset < bytes.size) {
            checkVisualCancellation()
            if (ended || bytes.size - offset < 27 || String(bytes, offset, 4, Charsets.US_ASCII) != "OggS" || bytes[offset + 4] != 0.toByte())
                bad("Expected a complete supported Ogg page")
            val flags = bytes[offset + 5].toInt() and 255
            if (flags and 0xf8 != 0 || (flags and 1 != 0) != (packet.size() > 0)) bad("Invalid Ogg continuation/flags")
            if ((sequence == 0L) != (flags and 2 != 0)) bad("Expected exactly one Ogg beginning-of-stream page")
            val thisSerial = u32(bytes, offset + 14)
            if (serial == null) serial = thisSerial else if (thisSerial != serial) bad("Multiple Ogg logical streams are unsupported")
            if (u32(bytes, offset + 18) != sequence++) bad("Ogg page sequence is incomplete")
            val segments = bytes[offset + 26].toInt() and 255
            val body = offset + 27 + segments
            if (body > bytes.size) bad("Truncated Ogg segment table")
            val size = (0 until segments).sumOf { bytes[offset + 27 + it].toInt() and 255 }
            val end = body + size
            if (end > bytes.size) bad("Truncated Ogg page data")
            var crc = 0
            for (index in offset until end) {
                val value = if (index in offset + 22..offset + 25) 0 else bytes[index].toInt() and 255
                crc = (crc shl 8) xor crcTable[((crc ushr 24) xor value) and 255]
            }
            if ((crc.toLong() and 0xffffffffL) != u32(bytes, offset + 22)) bad("Ogg page CRC mismatch")
            var position = body
            val packetsBefore = packetCount
            for (segment in 0 until segments) {
                val length = bytes[offset + 27 + segment].toInt() and 255
                packet.write(bytes, position, length); position += length
                if (length == 255) continue
                val value = packet.toByteArray(); packet.reset()
                when (packetCount) {
                    0 -> {
                        if (sequence != 1L || segments != 1 || end - offset != 58 || !header(value, 1) || value.size != 30 || u32(value, 7) != 0L)
                            bad("Expected the Vorbis I identification header alone on its first page")
                        channels = value[11].toInt() and 255
                        val sampleRate = u32(value, 12)
                        val sizes = value[28].toInt() and 255
                        if (channels !in 1..2 || sampleRate > Int.MAX_VALUE || sampleRate.toInt() !in supportedRates ||
                            sizes and 15 !in 6..13 || sizes ushr 4 !in (sizes and 15)..13 || value[29].toInt() and 1 == 0)
                            bad("Unsupported or malformed Vorbis identification format")
                        rate = sampleRate.toInt()
                    }
                    1 -> {
                        if (!header(value, 3)) bad("Missing Vorbis comment header")
                        var cursor = 7
                        fun length(): Int {
                            if (value.size - cursor < 4) bad("Truncated Vorbis comment fields")
                            val length = u32(value, cursor); cursor += 4
                            if (length > value.size - cursor) bad("Truncated Vorbis comment text")
                            return length.toInt()
                        }
                        val vendorLength = length()
                        cursor += vendorLength
                        if (value.size - cursor < 4) bad("Missing Vorbis comment count")
                        val count = u32(value, cursor); cursor += 4
                        if (count > (value.size - cursor) / 4) bad("Invalid Vorbis comment count")
                        repeat(count.toInt()) { val commentLength = length(); cursor += commentLength }
                        if (cursor >= value.size || value[cursor].toInt() and 1 == 0) bad("Missing Vorbis comment framing bit")
                    }
                    2 -> {
                        if (!header(value, 5) || value.size < 8 || position != end) bad("Missing Vorbis setup header or invalid header/audio page boundary")
                    }
                    else -> if (value.isEmpty() || value[0].toInt() and 1 != 0) bad("Invalid Vorbis audio packet")
                }
                packetCount++
            }
            val granule = ByteBuffer.wrap(bytes, offset + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (packetCount <= 3 && granule != if (packetCount > packetsBefore) 0L else -1L)
                bad("Invalid Vorbis header granule position")
            if (granule < -1 || granule >= 0 && granule < lastGranule) bad("Invalid Ogg granule order")
            if (granule >= 0) lastGranule = granule
            ended = flags and 4 != 0
            if (ended) {
                if (end != bytes.size || packet.size() != 0 || packetCount < 4 || granule <= 0 || channels == 0 ||
                    granule > (PcmAudioDecoder.MAX_BYTES - 44L) / (channels * 2)) bad("Incomplete or oversized Vorbis end-of-stream")
                frames = granule
            }
            offset = end
        }
        if (!ended) bad("Missing Ogg end-of-stream page")
        val pcm = try {
            OggInputStream(ByteArrayInputStream(bytes)).use { decoder ->
                if (decoder.channels != channels || decoder.sampleRate != rate) bad("Decoded Vorbis format differs from identification")
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    checkVisualCancellation()
                    val count = decoder.read(buffer)
                    if (count <= 0) break
                    if (output.size().toLong() + count + 44 > PcmAudioDecoder.MAX_BYTES) bad("Decoded PCM exceeds the existing 16 MiB output bound")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            if (failure is IllegalArgumentException && failure.message.orEmpty().startsWith("RESOURCE_PACK_AUDIO_INVALID")) throw failure
            bad("Cannot decode Vorbis audio: ${failure.message}")
        }
        val expected = (frames * channels * 2).toInt()
        if (pcm.size < expected || pcm.size % (channels * 2) != 0) bad("Decoded Vorbis sample frames are incomplete")
        // The EOS granule counts real samples; remove codec padding after that position.
        val samples = pcm.copyOf(expected)
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) for (index in samples.indices step 2) {
            val low = samples[index]; samples[index] = samples[index + 1]; samples[index + 1] = low
        }
        return PcmAudio(channels, rate, samples)
    }
}
