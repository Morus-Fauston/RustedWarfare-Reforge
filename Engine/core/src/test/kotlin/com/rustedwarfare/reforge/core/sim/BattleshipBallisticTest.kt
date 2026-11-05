package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BattleshipBallisticTest {
    @Test
    fun custom_missile_uses_speed_for_height_accelerates_on_the_vertical_first_step_and_hits_air() = WaterTestScene.withMap { map ->
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ship = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("naval-production/ini/production-package"))).value.units.getValue("heavyBattleship")
        val helicopter = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("helicopter-production/ini/package"))).value.units.getValue("helicopter")
        // Explicit unarmed AIR target with real finite flight/rotor capabilities and fixed height20.
        val air = UnitDefinition("air-target", helicopter.className, helicopter.radius, helicopter.displayRadius, helicopter.totalFrames,
            helicopter.imagePath, helicopter.imageScale, helicopter.movement.copy(moveSpeed = 0.0,
                moveAccelerationSpeed = 0.0, moveDecelerationSpeed = 0.0, maxTurnSpeed = 0.0, turnAcceleration = 0.0,
                flight = FlightProfile(20.0, 0.0, 0.0, 20.0)), emptySet(), 0,
            durability = DurabilityProfile(1000.0, null), rotor = helicopter.rotor)
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = mapOf("ship" to ship, "air" to air),
            units = listOf(UnitSpawn("ship", "p1", "ship", Position(500.0, 200.0), 90.0),
                UnitSpawn("air", "p2", "air", Position(650.0, 200.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("ship" to 8L, "air" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.submit(AttackAction("anti-air", "p1", "ship", "air", context.world))
            // Fixed before implementation from original f.a(F)V offsets1651..2012, not product output.
            val heights = listOf(0x3e99999a, 0x3f333334, 0x3f99999a, 0x3fe66667, 0x40200000, 0x40533334, 0x40800000, 0x40800000)
            val speeds = listOf(0x3ecccccd, 0x3f000000, 0x3f19999a, 0x3f333334, 0x3f4cccce, 0x3f666668, 0x3f800001, 0x3f8cccce)
            val impacts = mutableListOf<String?>()
            repeat(170) { step ->
                val sample = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                val states = sample.combat!!.weapons.filter { it.unitId == "ship" }.associateBy { it.weaponName }
                for (name in listOf("1", "2", "3")) assertNull(states.getValue(name).targetUnitId)
                if (step < 8) {
                    val shot = sample.projectiles.single { it.sourceWeaponName == "4" }
                    val ballistic = assertNotNull(shot.ballistic)
                    assertEquals(heights[step], shot.height.toFloat().toRawBits(), "height step$step")
                    assertEquals(speeds[step], ballistic.speed.toRawBits(), "speed step$step")
                    assertEquals(step >= 6, ballistic.reachedApex)
                    if (step == 0) assertEquals(sample.projectileEvents.single {
                        it.kind == "born" && it.projectile.sourceWeaponName == "4" }.projectile.position, shot.position)
                }
                impacts += sample.projectileEvents.filter { it.kind == "impact" }.map { it.projectile.sourceWeaponName }
            }
            assertEquals(listOf("4", "5"), impacts.sortedBy { it })
            assertEquals(900.0, context.observe().units.single { it.unitId == "air" }.health)
        } finally { context.close() }
    }
}
