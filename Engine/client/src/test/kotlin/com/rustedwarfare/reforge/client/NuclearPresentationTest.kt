package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class NuclearPresentationTest {
    @Test fun real_launch_retains_visible_trails_and_flashes_once_then_clears_every_effect_on_reopen() {
        val scene=NuclearSandboxScene.scenario()
        val c=assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val presentation=NuclearPresentation()
            fun project()=presentation.project(c.observePlayer(c.world,"p1"))
            assertTrue(project().isEmpty())
            var step=0L
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("build","p1","silo","buildNuke",c.world)))
            repeat(3334) { c.advance(Step(step++,1.0,"sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("launch","p1","silo","launchNuke",c.world,target=Position(520.0,180.0))))
            val kinds=mutableSetOf<NuclearEffectKind>();val sounds=mutableSetOf<String>()
            repeat(750) {
                c.advance(Step(step++,1.0,"sim-tick"))
                val effects=project()
                kinds+=effects.map { it.kind }
                sounds+=c.observe().soundTriggers.map { it.eventId }
                assertEquals(effects,project(),"Repeated draws must not emit duplicate particles or flashes")
                assertTrue(effects.all { it.radius>0 && it.alpha in 0f..1f && it.height.isFinite() })
            }
            assertEquals(setOf(NuclearEffectKind.TRAIL,NuclearEffectKind.LAUNCH,NuclearEffectKind.EXPLOSION),kinds)
            assertTrue(sounds.containsAll(setOf("reforge:events/nuke-launch","reforge:events/nuke-explode")))
            c.close();assertIs<InitResult.Accepted>(c.reopen(scene))
            assertTrue(project().isEmpty())
        } finally { c.close() }
    }
}
