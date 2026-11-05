package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import java.nio.file.Path
import kotlin.test.*

class PcPlaybackSoundFactoryTest {
    private val sound = PreparedResourceSound(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("owned.wav"),
        1, 22050, 10, 64, "a".repeat(64))
    @Test fun missing_device_is_explicit_before_loading_and_after_device_loss_even_if_backend_returns_zero() {
        var available = false; var loads = 0; var plays = 0; var closes = 0
        val factory = PcPlaybackSoundFactory({ available }, PlaybackSoundFactory {
            loads++
            object : PlaybackSound {
                override fun play(volume: Float, pitch: Float): Long { plays++; return 0L }
                override fun stop() {}
                override fun close() { closes++ }
            }
        })
        assertTrue(assertFailsWith<IllegalStateException> { factory.load(sound) }.message.orEmpty().contains("PC_AUDIO_DEVICE_UNAVAILABLE"))
        assertEquals(0, loads)
        available = true
        factory.load(sound).use { loaded ->
            assertEquals(0L, loaded.play(.3f, 1f)); assertEquals(1, plays)
            available = false
            assertTrue(assertFailsWith<IllegalStateException> { loaded.play(.3f, 1f) }.message.orEmpty().contains("PC_AUDIO_DEVICE_UNAVAILABLE"))
            assertEquals(1, plays)
        }
        assertEquals(1, closes)
    }
}
