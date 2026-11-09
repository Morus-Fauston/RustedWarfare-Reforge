package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineDeathTest {
    @Test fun a_real_underwater_weapon_kills_the_submarine_then_the_dead_body_sinks_and_clamps_at_minus_ten() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val sub = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("attackSubmarine")
            val shot = ProjectileProfile(300.0, 50.0, 7.0)
            val named = NamedWeaponDefinition("1", "instrument", shot, TurretMount(), 10000.0, 0.0, 360.0, 0.0,
                null, 1.0, canAttackUnderwaterUnits = true)
            val instrument = UnitDefinition("instrument", "OwnedInstrument", 6.0, 6.0, 1, sub.imagePath, 1.0,
                MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                combat = CombatProfile(1000.0, 200.0, 10000.0, 0.0, 360.0, 0.0, shot, null, null, 1.0,
                    namedWeapons = WeaponCatalog(listOf(named))))
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = mapOf("sub" to sub, "instrument" to instrument),
                units = listOf(UnitSpawn("sub", "p1", "sub", Position(500.0, 200.0), 90.0, -8.0),
                    UnitSpawn("zgun", "p2", "instrument", Position(650.0, 200.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("sub" to 8L, "zgun" to 9L), 10L))
            val initialized = SimulationContext.initialize(input)
            val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
            try {
                assertEquals(40L, assertIs<ActionResult.Accepted>(context.submit(
                    SubmarineDepthAction("scheduled-raise", "p1", "sub", true, context.world, 40L))).queuedForStep)
                var tick = 0L
                while (context.observe().units.single { it.unitId == "sub" }.alive && tick < 40L)
                    context.advance(Step(tick++, 1.0, "sim-tick"))
                val dead = context.observe().units.single { it.unitId == "sub" }
                assertFalse(dead.alive)
                assertEquals(0.0, dead.health)
                assertTrue(dead.tasks.isEmpty())
                assertEquals(0.0, dead.speed)
                assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(context.submit(
                    SubmarineDepthAction("raise-dead", "p1", "sub", true, context.world))).error.code)
                val first = context.advance(Step(tick++, 1.0, "sim-tick")).units.single { it.unitId == "sub" }
                assertEquals(.002f, assertNotNull(first.waterPose).sinkSpeed)
                assertEquals((-8f - .002f).toDouble(), first.height)
                repeat(100) {
                    val q = tick++
                    val row = context.advance(Step(q, 1.0, "sim-tick"))
                    if (q == 40L) assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(
                        row.actionResults.single { it.actionId == "scheduled-raise" }).error.code)
                }
                val settled = context.observe().units.single { it.unitId == "sub" }
                assertEquals(-10.0, settled.height)
                assertTrue(assertNotNull(settled.waterPose).settled)
                repeat(10) { context.advance(Step(tick++, 1.0, "sim-tick")) }
                assertEquals(settled, context.observe().units.single { it.unitId == "sub" })
            } finally { context.close() }
        } }
}
