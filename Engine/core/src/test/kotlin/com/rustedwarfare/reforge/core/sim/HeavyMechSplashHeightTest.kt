package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HeavyMechSplashHeightTest {
    @Test fun ordinary_area_uses_the_distinct_four_five_and_underwater_height_boundaries() {
        for (height in listOf(4.0, 4.999, 5.0, -2.0, -2.001)) {
            val original = HeavyMechTestScene.declared()
            val combat = original.combat!!
            // Explicit owned height instrument. The normal heavy remains a LAND unit with default height0.
            val heavy = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
                original.fogOfWarSightRange, combat = combat.copy(namedWeapons = WeaponCatalog(combat.namedWeapons!!.ordered.map {
                    it.copy(canShoot = it.name == "2", canAttackLandUnits = height < 0.0,
                        canAttackFlyingUnits = height >= 0.0)
                })), mech = original.mech)
            val heli = HelicopterCombatScene.unit()
            val still = MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false)
            fun body(flightHeight: Double?) = UnitDefinition("instrument", heli.className, 1.0, 1.0,
                heli.totalFrames, heli.imagePath, heli.imageScale,
                if (flightHeight == null) still else heli.movement.copy(flight = FlightProfile(flightHeight, 0.0, 0.0, flightHeight)),
                emptySet(), durability = DurabilityProfile(1000.0, null), rotor = heli.rotor.takeIf { flightHeight != null })
            val definitions = mapOf(heavy.id to heavy, "primary" to body(height.takeIf { it >= 0.0 }),
                "ground" to body(null), "low-air" to body(3.999), "air-four" to body(4.0))
            val target = Position(450.0, 200.0)
            val spawns = listOf(UnitSpawn("heavy", "p1", heavy.id, Position(200.0, 200.0), 90.0, height),
                UnitSpawn("primary", "p2", "primary", target, 270.0, if (height < 0.0) height else 0.0),
                UnitSpawn("ground", "p2", "ground", target, 270.0),
                UnitSpawn("low-air", "p2", "low-air", target, 270.0),
                UnitSpawn("air-four", "p2", "air-four", target, 270.0),
                UnitSpawn("deep", "p2", "ground", target, 270.0, -5.001),
                UnitSpawn("minus-five", "p2", "ground", target, 270.0, -5.0))
            val context = HeavyMechTestScene.open(HeavyMechTestScene.scenario(unit = heavy, heading = 90.0).copy(
                unitDefinitions = definitions, units = spawns,
                combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, unit -> unit.unitId to i + 8L }.toMap(), 30L)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("fire", "p1", "heavy", "primary", context.world)))
                var impact: Observation? = null
                for (q in 0L..159L) {
                    val observed = context.advance(Step(q, 1.0, "sim-tick"))
                    if (observed.projectileEvents.any { it.kind == "impact" }) { impact = observed; break }
                }
                val observed = assertNotNull(impact, "height=$height")
                val shot = observed.projectileEvents.single { it.kind == "impact" }.projectile
                assertEquals(height, shot.height, 1e-6)
                assertEquals(target, shot.position)
                val health = observed.units.associate { it.unitId to it.health }
                val flyingArea = height >= 5.0
                assertEquals(if (height >= 4.0 && !flyingArea) 800.0 else 760.0, health["primary"], "primary at $height")
                assertEquals(if (flyingArea) 960.0 else 1000.0, health["air-four"], "AIR category threshold4 at $height")
                for (id in listOf("low-air", "ground", "minus-five"))
                    assertEquals(if (flyingArea) 1000.0 else 960.0, health[id], "$id at $height")
                assertEquals(if (!flyingArea && height < -2.0) 960.0 else 1000.0, health["deep"], "strict underwater gates at $height")
            } finally { context.close() }
        }
    }
}
