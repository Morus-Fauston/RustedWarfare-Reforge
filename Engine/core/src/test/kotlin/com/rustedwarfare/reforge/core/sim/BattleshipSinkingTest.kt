package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BattleshipSinkingTest {
    @Test fun real_shell_damage_kills_the_hull_then_water_sinks_in_float_order_while_a_bridge_only_corpse_stays_up() {
        for (bridge in listOf(false, true)) WaterTestScene.withMap(bridge) { map ->
            val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
            val ship = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                root.resolve("naval-production/ini/production-package"))).value.units.getValue("heavyBattleship")
            // The actual loaded hull and 4000 HP, explicitly unarmed to isolate incoming ordinary shell damage.
            val victim = UnitDefinition(ship.id, ship.className, ship.radius, ship.displayRadius, ship.totalFrames,
                ship.imagePath, ship.imageScale, ship.movement, ship.retainedUnconsumed, 0,
                durability = DurabilityProfile(4000.0, ship.combat!!.deadImagePath))
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 210.0)).copy(
                unitDefinitions = mapOf("attacker" to ship, "victim" to victim),
                units = listOf(UnitSpawn("attacker", "p1", "attacker", Position(500.0, 210.0), 90.0),
                    UnitSpawn("victim", "p2", "victim", Position(610.0, 210.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("attacker" to 8L, "victim" to 9L), 10L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val initial = context.observe()
                var q = 0L
                var damaged = false
                while (context.observe().units.single { it.unitId == "victim" }.alive && q < 5000L) {
                    val frame = context.advance(Step(q++, 1.0, "sim-tick"))
                    damaged = damaged || frame.events.any { it.kind == "unit-damaged" && it.unitId == "victim" }
                }
                val death = context.observe().units.single { it.unitId == "victim" }
                assertTrue(damaged)
                assertFalse(death.alive)
                assertEquals(0.0, death.health)
                assertEquals(0.0, death.height)
                assertEquals(WaterPose(false, 0f, false), death.waterPose)
                val heightBits = listOf(0xba51b717.toInt(), 0xbb1d4951.toInt(), 0xbb9d4951.toInt(), 0xbc03126e.toInt())
                val speedBits = listOf(0x3a51b717, 0x3ad1b717, 0x3b1d4951, 0x3b51b717)
                repeat(160) { index ->
                    val corpse = context.advance(Step(q++, 1.0, "sim-tick")).units.single { it.unitId == "victim" }
                    val pose = assertNotNull(corpse.waterPose)
                    if (bridge) {
                        assertEquals(0.0, corpse.height)
                        assertEquals(WaterPose(false, 0f, true), pose)
                    } else {
                        if (index < 4) {
                            assertEquals(heightBits[index], corpse.height.toFloat().toRawBits(), "height q${index+1}")
                            assertEquals(speedBits[index], pose.sinkSpeed.toRawBits(), "speed q${index+1}")
                        }
                        if (index >= 157) {
                            assertEquals(0xc120c7da.toInt(), corpse.height.toFloat().toRawBits())
                            assertEquals(0x3e016ef6, pose.sinkSpeed.toRawBits())
                        }
                        assertEquals(index >= 158, pose.settled)
                    }
                    assertEquals(death.position, corpse.position)
                    assertEquals(0.0, corpse.speed)
                }
                assertTrue(initial.units.single { it.unitId == "victim" }.alive)
                assertEquals(0.0, death.height, "Previously published death frame remains unchanged")
            } finally { context.close() }
        }
    }
}
