package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class MechAppearanceTest {
    @Test
    fun a_real_mech_death_with_none_wreck_does_not_draw_the_living_body_as_a_corpse() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("mech-production/ini")
        val mech = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value.units.getValue("mechGun")
        val combat = assertNotNull(mech.combat)
        val target = UnitDefinition(mech.id, mech.className, mech.radius, mech.displayRadius, mech.totalFrames,
            mech.imagePath, mech.imageScale, mech.movement, mech.retainedUnconsumed, mech.fogOfWarSightRange,
            combat = combat.copy(maxHp = 23.0, namedWeapons = WeaponCatalog(assertNotNull(combat.namedWeapons).ordered.map { it.copy(canShoot = false) })),
            mech = mech.mech)
        val scene = ControlledScenario.load(root).copy(scenarioId = "p008-mech-drawing-death",
            unitDefinitions = mapOf("mech" to mech, "target" to target),
            units = listOf(UnitSpawn("a", "p1", "mech", Position(100.0, 100.0), 90.0),
                UnitSpawn("b", "p2", "target", Position(200.0, 100.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))
        val appearance = SandboxRenderResources.from(scene).units.getValue("target")
        assertNull(appearance.deadImagePath)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertTrue(appearance.isDrawn(context.observe().units.single { it.unitId == "b" }))
            var dead: UnitObservation? = null
            for (step in 0L..100L) {
                val unit = context.advance(Step(step, 1.0, "sim-tick")).units.single { it.unitId == "b" }
                if (!unit.alive) { dead = unit; break }
            }
            assertNotNull(dead)
            assertNotNull(dead.mechPose)
            assertFalse(appearance.isDrawn(dead), "NONE is not a request to reuse the living mech sprite")
        } finally { context.close() }
    }
}
