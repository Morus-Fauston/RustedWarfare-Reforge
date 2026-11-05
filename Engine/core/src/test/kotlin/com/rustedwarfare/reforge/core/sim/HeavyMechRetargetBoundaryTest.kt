package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HeavyMechRetargetBoundaryTest {
    private fun scene(candidate: Position, parentArc: Double? = null): Scenario {
        val source = HeavyMechTestScene.declared()
        val combat = source.combat!!
        // Collinear owned mechanism fixture: only the mounts change, not missile timing/damage/speed.
        val heavy = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed,
            source.fogOfWarSightRange, combat = combat.copy(namedWeapons = WeaponCatalog(combat.namedWeapons!!.ordered.map {
                it.copy(mount = TurretMount(), limitingAngle = if (it.name == "1") parentArc else it.limitingAngle)
            })), mech = source.mech)
        val heli = HelicopterCombatScene.unit()
        fun passive(health: Double) = UnitDefinition("instrument", heli.className, 1.0, 1.0,
            heli.totalFrames, heli.imagePath, heli.imageScale,
            heli.movement.copy(flight = FlightProfile(20.0, 0.0, 0.0, 20.0)), emptySet(),
            durability = DurabilityProfile(health, null), rotor = heli.rotor)
        return HeavyMechTestScene.scenario(unit = heavy, heading = 90.0).copy(
            unitDefinitions = mapOf(heavy.id to heavy, "fragile" to passive(150.0), "durable" to passive(1000.0)),
            units = listOf(UnitSpawn("heavy", "p1", heavy.id, Position(200.0, 200.0), 90.0),
                UnitSpawn("first", "p2", "fragile", Position(450.0, 200.0), 270.0),
                UnitSpawn("next", "p2", "durable", candidate, 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L, "first" to 9L, "next" to 10L), 11L))
    }

    private data class Witness(val deathTick: Long, val beforeShot: ProjectileObservation)

    @Test fun preferred_candidate_can_override_an_earlier_nearer_candidate_and_a_later_nearer_one_can_override_it() {
        val witness = witness()
        val centerX = witness.beforeShot.position.x + 15.0
        for (preferredFirst in listOf(true, false)) {
            val preferred = if (preferredFirst) "a-preferred" else "z-preferred"
            val nearer = if (preferredFirst) "z-nearer" else "a-nearer"
            val base = scene(Position(700.0, 200.0))
            val spawns = base.units.filter { it.unitId != "next" } + listOf(
                UnitSpawn(preferred, "p2", "durable", Position(centerX + 110.0, 200.0), 270.0),
                UnitSpawn(nearer, "p2", "durable", Position(centerX + 50.0, 200.0), 270.0))
            val context = HeavyMechTestScene.open(base.copy(units = spawns,
                combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L, "first" to 9L, preferred to 10L, nearer to 11L), 12L)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("first", "p1", "heavy", "first", context.world)))
                for (q in 0L until witness.deathTick - 1) context.advance(Step(q, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("prefer", "p1", "heavy", preferred, context.world)))
                context.advance(Step(witness.deathTick - 1, 1.0, "sim-tick"))
                val shot = context.observe().projectiles.single { it.sourceWeaponName == "3" }
                assertEquals("first", shot.targetUnitId)
                val observed = context.advance(Step(witness.deathTick, 1.0, "sim-tick"))
                assertFalse(observed.units.single { it.unitId == "first" }.alive)
                assertEquals(if (preferredFirst) nearer else preferred,
                    observed.projectiles.single { it.objectId == shot.objectId }.targetUnitId)
            } finally { context.close() }
        }
    }

    @Test fun missiles_keep_the_dead_target_reference_when_their_actual_source_has_died_before_impact() {
        val witness = witness()
        val base = scene(Position(470.0, 200.0))
        val source = base.unitDefinitions.getValue("mechHeavyMissile")
        val still = MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false)
        val killer = UnitDefinition("killer", source.className, 1.0, 1.0, 1, source.imagePath, 1.0, still, emptySet(),
            combat = CombatProfile(10000.0, 500.0, 1.0, 0.0, 360.0, 0.0,
                ProjectileProfile(2500.0, 70.0, 1000.0), null, null, 1.0, recoil = WeaponRecoil(0.0, 4.0, 7.0)))
        val dummy = UnitDefinition("dummy", source.className, 1.0, 1.0, 1, source.imagePath, 1.0, still, emptySet(),
            durability = DurabilityProfile(1000000000.0, null))
        val spawns = base.units + listOf(UnitSpawn("killer", "p2", killer.id, Position(600.0, 200.0), 180.0),
            UnitSpawn("dummy", "p1", dummy.id, Position(600.0, 400.0), 0.0))
        val context = HeavyMechTestScene.open(base.copy(unitDefinitions = base.unitDefinitions + mapOf(killer.id to killer, dummy.id to dummy),
            units = spawns, players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L, "first" to 9L, "next" to 10L, "killer" to 11L, "dummy" to 12L), 13L)))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("first", "p1", "heavy", "first", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("hold", "p2", "killer", "dummy", context.world)))
            for (q in 0L until witness.deathTick - 4) context.advance(Step(q, 1.0, "sim-tick"))
            val shot = context.observe().projectiles.single { it.sourceUnitId == "heavy" && it.sourceWeaponName == "3" }
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("kill-source", "p2", "killer", "heavy", context.world)))
            for (q in witness.deathTick - 4 until witness.deathTick) context.advance(Step(q, 1.0, "sim-tick"))
            assertFalse(context.observe().units.single { it.unitId == "heavy" }.alive)
            val observed = context.advance(Step(witness.deathTick, 1.0, "sim-tick"))
            assertFalse(observed.units.single { it.unitId == "first" }.alive)
            assertTrue(observed.units.single { it.unitId == "next" }.alive)
            assertEquals("first", observed.projectiles.single { it.objectId == shot.objectId }.targetUnitId)
        } finally { context.close() }
    }

    private fun witness(): Witness {
        val context = HeavyMechTestScene.open(scene(Position(700.0, 200.0)))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "heavy", "first", context.world)))
            var previous = context.observe()
            for (q in 0L..159L) {
                val observed = context.advance(Step(q, 1.0, "sim-tick"))
                if (!observed.units.single { it.unitId == "first" }.alive) {
                    val shot = previous.projectiles.single { it.sourceWeaponName == "3" }
                    assertEquals("first", shot.targetUnitId)
                    assertEquals(90.0, shot.heading)
                    assertEquals(200.0, shot.position.y)
                    return Witness(q, shot)
                }
                previous = observed
            }
            error("The real first missile must kill the fragile target")
        } finally { context.close() }
    }

    @Test fun strict_radius_forward_search_center_and_parent_arc_apply_to_the_original_missile() {
        val witness = witness()
        val shot = witness.beforeShot
        // The observed pre-impact projectile locates the input fixture. The literal oracles below
        // come from the contract: forward=15, strict radius<120, and original parent arc retained.
        val centerX = shot.position.x + 15.0
        data class Case(val name: String, val candidate: Position, val expected: Boolean, val arc: Double? = null)
        val cases = listOf(
            Case("exact-120", Position(centerX, 320.0), false),
            Case("inside-120", Position(centerX, 319.999), true),
            Case("forward-only", Position(shot.position.x + 134.99, 200.0), true),
            Case("behind-unshifted-radius", Position(shot.position.x - 119.99, 200.0), false),
            Case("parent-arc", Position(centerX, 319.0), false, 1.0))
        for (case in cases) {
            val context = HeavyMechTestScene.open(scene(case.candidate, case.arc))
            try {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "heavy", "first", context.world)))
                for (q in 0L until witness.deathTick) context.advance(Step(q, 1.0, "sim-tick"))
                val before = context.observe().projectiles.single { it.sourceWeaponName == "3" }
                assertEquals(shot, before, "${case.name}: candidate must not alter the original shot before its target dies")
                val observed = context.advance(Step(witness.deathTick, 1.0, "sim-tick"))
                assertFalse(observed.units.single { it.unitId == "first" }.alive, case.name)
                val redirected = observed.projectiles.single { it.objectId == shot.objectId }
                assertEquals(if (case.expected) "next" else "first", redirected.targetUnitId, case.name)
            } finally { context.close() }
        }
    }
}
