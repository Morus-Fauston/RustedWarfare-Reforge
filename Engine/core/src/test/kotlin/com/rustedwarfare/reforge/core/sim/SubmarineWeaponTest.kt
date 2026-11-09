package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineWeaponTest {
    @Test fun physical_depth_selects_range_and_launch_profile_without_rewriting_existing_shots() {
        withSubmarineCombat(-8.0, 200.0) { context ->
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "sub", "target", context.world)))
            val row = context.advance(Step(0L, 1.0, "sim-tick"))
            assertTrue(row.projectiles.isEmpty(), "200 is outside submerged range 180")
            assertTrue(row.units.single { it.unitId == "sub" }.tasks.any { it.kind == UnitTaskKind.ATTACK })
            assertNotNull(row.units.single { it.unitId == "sub" }.moveTarget)
        }
        withSubmarineCombat(-8.0, 150.0) { context ->
            val row = context.advance(Step(0L, 1.0, "sim-tick"))
            val born = row.projectileEvents.single { it.kind == "born" }.projectile
            assertEquals(250.0, born.remainingLife)
            assertNull(born.ballistic)
            assertNull(born.missile)
            assertEquals(-8.0, born.height)
            assertEquals(1.3, row.projectiles.single().position.x - born.position.x, 1e-5)
            assertTrue(row.soundTriggers.isEmpty(), "Torpedo has no surface launch sound")
            for (q in 1L..130L) context.advance(Step(q, 1.0, "sim-tick"))
            assertEquals(958.0, context.observe().units.single { it.unitId == "target" }.health)
        }
        withSubmarineCombat(-1.0, 200.0) { context ->
            val row = context.advance(Step(0L, 1.0, "sim-tick"))
            val born = row.projectileEvents.single { it.kind == "born" }.projectile
            assertEquals(190.0, born.remainingLife)
            assertEquals(2f, assertNotNull(born.ballistic).speed)
            assertNull(born.missile, "Surface ballistic does not inherit native missile acceleration or retargeting")
            assertEquals(born.position, row.projectiles.single().position, "Climb gate prevents initial planar advance")
            assertEquals(.8f, row.soundTriggers.single().volume)
            assertTrue(row.units.single { it.unitId == "sub" }.height < -1.0)
            val oldId = born.objectId
            val next = context.advance(Step(1L, 1.0, "sim-tick"))
            val retained = next.projectiles.single { it.objectId == oldId }
            assertEquals(188.0, retained.remainingLife)
            assertEquals(2f, assertNotNull(retained.ballistic).speed)
        }
    }
}

internal fun withSubmarineCombat(height: Double, distance: Double, run: (SimulationContext) -> Unit) =
    WaterTestScene.withMap { map -> withSubmarinePackage { root ->
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
        val sub = pack.units.getValue("attackSubmarine")
        val target = UnitDefinition("target", "OwnedSurfaceTarget", 6.0, 6.0, 1, sub.imagePath, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(1000.0, null))
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = mapOf("attackSubmarine" to sub, "target" to target),
            units = listOf(UnitSpawn("sub", "p1", "attackSubmarine", Position(500.0, 200.0), 90.0, height),
                UnitSpawn("target", "p2", "target", Position(500.0 + distance, 200.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("sub" to 8L, "target" to 9L), 10L))
        val initialized = SimulationContext.initialize(input)
        val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try { run(context) } finally { context.close() }
    } }
