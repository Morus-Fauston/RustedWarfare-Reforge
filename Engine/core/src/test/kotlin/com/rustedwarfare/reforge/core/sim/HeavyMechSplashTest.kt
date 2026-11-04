package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HeavyMechSplashTest {
    @Test fun ordinary_air_splash_uses_center_distance_falloff_and_excludes_friends_and_ground_units() {
        val original = HeavyMechTestScene.declared()
        val combat = assertNotNull(original.combat)
        val heavy = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            original.fogOfWarSightRange, combat = combat.copy(namedWeapons = WeaponCatalog(
                assertNotNull(combat.namedWeapons).ordered.map { it.copy(canShoot = it.name == "2") })), mech = original.mech)
        val heli = HelicopterCombatScene.unit()
        // Radius1 fixes the ordinary aim offset to zero. These are controlled mechanism bodies, not original units.
        val air = UnitDefinition("air", heli.className, 1.0, 1.0, heli.totalFrames, heli.imagePath, heli.imageScale,
            heli.movement.copy(flight = FlightProfile(20.0, 0.0, 0.0, 20.0)), emptySet(),
            durability = DurabilityProfile(1000.0, null), rotor = heli.rotor)
        val ground = UnitDefinition("ground", heli.className, 1.0, 1.0, heli.totalFrames, heli.imagePath, heli.imageScale,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(1000.0, null))
        val spawns = listOf(UnitSpawn("heavy", "p1", heavy.id, Position(200.0, 200.0), 90.0),
            UnitSpawn("primary", "p2", "air", Position(450.0, 200.0), 270.0),
            UnitSpawn("half", "p2", "air", Position(467.5, 200.0), 270.0),
            UnitSpawn("edge", "p2", "air", Position(485.0, 200.0), 270.0),
            UnitSpawn("outside", "p2", "air", Position(485.01, 200.0), 270.0),
            UnitSpawn("own", "p1", "air", Position(450.0, 210.0), 270.0),
            UnitSpawn("ally", "p3", "air", Position(450.0, 190.0), 270.0),
            UnitSpawn("ground", "p2", "ground", Position(450.0, 200.0), 270.0))
        val context = HeavyMechTestScene.open(HeavyMechTestScene.scenario(unit = heavy, heading = 90.0).copy(
            unitDefinitions = mapOf(heavy.id to heavy, "air" to air, "ground" to ground),
            players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.NONE, "red"),
                Player("p3", PlayerControl.NONE, "blue")), units = spawns,
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, unit -> unit.unitId to i + 8L }.toMap(), 30L)))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "heavy", "primary", context.world)))
            var impact: Observation? = null
            for (q in 0L..159L) {
                val observed = context.advance(Step(q, 1.0, "sim-tick"))
                if (observed.projectileEvents.any { it.kind == "impact" }) { impact = observed; break }
            }
            val hit = assertNotNull(impact)
            assertEquals(Position(450.0, 200.0), hit.projectileEvents.single { it.kind == "impact" }.projectile.position)
            val health = hit.units.associate { it.unitId to it.health }
            assertEquals(760.0, health["primary"])
            assertEquals(976.0, health["half"])
            assertEquals(996.0, health["edge"])
            for (id in listOf("outside", "own", "ally", "ground")) assertEquals(1000.0, health[id], id)
        } finally { context.close() }
    }
}
