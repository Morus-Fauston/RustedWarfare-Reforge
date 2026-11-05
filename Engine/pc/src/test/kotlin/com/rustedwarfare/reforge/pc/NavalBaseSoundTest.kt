package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ResourceId
import java.nio.file.Files
import kotlin.test.*

class NavalBaseSoundTest {
    @Test fun owned_default_audio_resolves_both_naval_events_to_distinct_fixed_pcm_without_an_original_directory() {
        val ready = PcBaseSoundResources(Files.createTempDirectory("naval-base-sound-")).prepare()
        val hashes = listOf("tank-fire", "naval-main-fire", "naval-missile-fire").map { name ->
            val event = ResourceId.parse("reforge:events/$name")
            val mapping = assertNotNull(ready.events[event], "Missing owned fallback event: $name")
            val sound = ready.sounds.getValue(mapping.sound)
            assertTrue(sound.frames > 0 && sound.sampleRate == 22050 && sound.channels == 1)
            assertTrue(Files.isRegularFile(sound.path))
            sound.sha256
        }
        assertEquals(3, hashes.toSet().size)
        ready.verify()
    }
}
