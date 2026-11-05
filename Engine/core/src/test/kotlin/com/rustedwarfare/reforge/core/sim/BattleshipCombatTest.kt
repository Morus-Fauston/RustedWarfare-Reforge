package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BattleshipCombatTest {
    @Test
    fun negative_height_on_an_ordinary_land_unit_does_not_grant_the_explicit_underwater_capability() = WaterTestScene.withMap { map ->
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ship = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("naval-production/ini/production-package"))).value.units.getValue("heavyBattleship")
        val ordinary = UnitDefinition("negative-height-control", "OwnedOrdinaryTarget", 6.0, 6.0, 1,
            ship.imagePath, 1.0, MovementProfile(MovementType.LAND, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(1000.0, null))
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = mapOf("ship" to ship, "target" to ordinary),
            units = listOf(UnitSpawn("ship", "p1", "ship", Position(500.0, 200.0), 90.0),
                UnitSpawn("target", "p2", "target", Position(300.0, 200.0), 0.0, -8.0)),
            combatContract = CombatContract(12345, -1, mapOf("ship" to 8L, "target" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("land-control", "p1", "ship", "target", context.world)))
            val sample = context.advance(Step(0, 1.0, "sim-tick"))
            val states = sample.combat!!.weapons.filter { it.unitId == "ship" }.associateBy { it.weaponName }
            assertEquals("target", states.getValue("1").targetUnitId)
            assertEquals("target", states.getValue("2").targetUnitId)
            for (name in listOf("3", "4", "5")) assertNull(states.getValue(name).targetUnitId)
            assertEquals(-8.0, sample.units.single { it.unitId == "target" }.height)
        } finally { context.close() }
    }

    @Test
    fun finite_underwater_target_receives_only_the_real_torpedo_and_loses_sixty_five_health() = WaterTestScene.withMap { map ->
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ship = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("naval-production/ini/production-package"))).value.units.getValue("heavyBattleship")
        val target = UnitDefinition("navalUnderwaterTarget", "OwnedFixedUnderwaterTarget", 6.0, 6.0, 1,
            ship.imagePath, 1.0, MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(1000.0, null), fixedUnderwaterTarget = FixedUnderwaterTargetProfile(-8.0))
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = mapOf("ship" to ship, "target" to target),
            units = listOf(UnitSpawn("ship", "p1", "ship", Position(500.0, 200.0), 90.0),
                UnitSpawn("target", "p2", "target", Position(650.0, 200.0), 0.0, -8.0)),
            combatContract = CombatContract(12345, -1, mapOf("ship" to 8L, "target" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("torpedo", "p1", "ship", "target", context.world)))
            val births = mutableListOf<ProjectileObservation>()
            repeat(170) { step ->
                val sample = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                val states = sample.combat!!.weapons.filter { it.unitId == "ship" }.associateBy { it.weaponName }
                assertEquals("target", states.getValue("3").targetUnitId)
                for (name in listOf("1", "2", "4", "5")) assertNull(states.getValue(name).targetUnitId)
                births += sample.projectileEvents.filter { it.kind == "born" }.map { it.projectile }
            }
            assertEquals(listOf("3"), births.map { it.sourceWeaponName })
            assertEquals(935.0, context.observe().units.single { it.unitId == "target" }.health)
            assertEquals(-8.0, context.observe().units.single { it.unitId == "target" }.height)
        } finally { context.close() }
    }
}
