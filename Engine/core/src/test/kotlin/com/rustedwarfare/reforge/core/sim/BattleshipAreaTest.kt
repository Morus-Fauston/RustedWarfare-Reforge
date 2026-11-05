package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BattleshipAreaTest {
    @Test fun the_real_main_shell_damages_surface_neighbors_but_excludes_air_deep_water_owners_and_allies() = WaterTestScene.withMap { map ->
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("naval-production/ini/production-package"))).value
        val original = pack.units.getValue("heavyBattleship")
        val combat = original.combat!!
        val ship = UnitDefinition(original.id, original.className, original.radius, original.displayRadius, original.totalFrames,
            original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed, 0,
            combat = combat.copy(namedWeapons = WeaponCatalog(combat.namedWeapons!!.ordered.map { it.copy(canShoot = it.name == "1") })))
        // The radius1 surface instrument makes the original target-offset integer bound zero.
        val target = UnitDefinition("surface", "OwnedSurfaceInstrument", 1.0, 1.0, 1, original.imagePath, 1.0,
            MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), 0,
            durability = DurabilityProfile(1000.0, null))
        val heli = pack.units.getValue("helicopter")
        val air = UnitDefinition("air", heli.className, heli.radius, heli.displayRadius, heli.totalFrames,
            heli.imagePath, heli.imageScale, heli.movement.copy(moveSpeed = 0.0, moveAccelerationSpeed = 0.0,
                moveDecelerationSpeed = 0.0, maxTurnSpeed = 0.0, turnAcceleration = 0.0,
                flight = FlightProfile(20.0, 0.0, 0.0, 20.0)), emptySet(), 0,
            durability = DurabilityProfile(1000.0, null), rotor = heli.rotor)
        val point = Position(650.0, 200.0)
        val spawns = listOf(UnitSpawn("ship", "p1", "ship", Position(500.0, 200.0), 90.0),
            UnitSpawn("center", "p2", "surface", point, 0.0),
            UnitSpawn("neighbor", "p2", "surface", Position(670.0, 200.0), 0.0),
            UnitSpawn("air", "p2", "air", point, 0.0),
            UnitSpawn("sub", "p2", "sub", point, 0.0, -8.0),
            UnitSpawn("own", "p1", "surface", point, 0.0), UnitSpawn("ally", "p3", "surface", point, 0.0))
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = mapOf("ship" to ship, "surface" to target, "air" to air,
                "sub" to pack.units.getValue("navalUnderwaterTarget")), units = spawns,
            players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.NONE, "red"), Player("p3", PlayerControl.NONE, "blue")),
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, spawn -> spawn.unitId to i + 8L }.toMap(), 15L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("main", "p1", "ship", "center", context.world)))
            var impact: Observation? = null
            for (q in 0L..99L) {
                val frame = context.advance(Step(q, 1.0, "sim-tick"))
                if (frame.projectileEvents.any { it.kind == "impact" }) { impact = frame; break }
            }
            val frame = assertNotNull(impact)
            val shot = frame.projectileEvents.single { it.kind == "impact" }.projectile
            assertEquals("1", shot.sourceWeaponName)
            assertEquals(point, assertNotNull(shot.ground).targetPosition)
            val health = frame.units.associate { it.unitId to it.health }
            assertEquals(890.0, health["center"])
            assertEquals(927.8888854980469, health["neighbor"])
            for (id in listOf("air", "sub", "own", "ally")) assertEquals(1000.0, health[id], id)
        } finally { context.close() }
    }
}
