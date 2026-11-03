package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import org.lwjgl.openal.ALC10

/** libGDX returns voice 0 without a device; validate the actual PC context separately. */
class PcPlaybackSoundFactory internal constructor(private val available: () -> Boolean,
    private val delegate: PlaybackSoundFactory) : PlaybackSoundFactory {
    constructor() : this({ kotlin.runCatching { ALC10.alcGetCurrentContext() != 0L }.getOrDefault(false) }, GdxPlaybackSoundFactory)
    private fun requireDevice() = check(available()) { "PC_AUDIO_DEVICE_UNAVAILABLE: OpenAL has no current audio context" }
    override fun load(sound: PreparedResourceSound): PlaybackSound {
        requireDevice()
        val loaded = delegate.load(sound)
        return object : PlaybackSound {
            override fun play(volume: Float, pitch: Float): Long { requireDevice(); return loaded.play(volume, pitch) }
            override fun stop() = loaded.stop()
            override fun close() = loaded.close()
        }
    }
}
