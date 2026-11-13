package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ResourceId
import java.nio.file.Files
import kotlin.test.*

class NuclearBaseSoundTest {
    @Test fun owned_nuclear_launch_and_explosion_resolve_to_distinct_fixed_pcm() {
        val ready = PcBaseSoundResources(Files.createTempDirectory("nuclear-base-sound-")).prepare()
        val sounds = listOf("nuke-launch", "nuke-explode").map { name ->
            val event = assertNotNull(ready.events[ResourceId.parse("reforge:events/$name")])
            val sound = ready.sounds.getValue(event.sound)
            assertTrue(Files.isRegularFile(sound.path))
            assertEquals(22050, sound.sampleRate)
            assertEquals(1, sound.channels)
            assertTrue(sound.frames >= 4410)
            sound.sha256
        }
        assertEquals(2, sounds.toSet().size)
        ready.verify()
    }
}
