package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HeavyMechRetargetTest {
    @Test fun no_legal_retarget_keeps_the_dead_reference_instead_of_selecting_ground_friendly_or_distant_bodies() {
        val heavy = HeavyMechTestScene.declared()
        val source = HelicopterCombatScene.unit()
        fun body(health: Double, flying: Boolean) = UnitDefinition("target", source.className, 1.0, 1.0,
            source.totalFrames, source.imagePath, source.imageScale,
            if (flying) source.movement.copy(flight = FlightProfile(20.0, 0.0, 0.0, 20.0))
            else MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(health, null), rotor = source.rotor.takeIf { flying })
        for (kind in listOf("absent", "ground", "friendly", "distant")) {
            val spawns = listOf(UnitSpawn("heavy", "p1", heavy.id, Position(200.0, 200.0), 90.0),
                UnitSpawn("first", "p2", "fragile", Position(450.0, 200.0), 270.0)) +
                if (kind == "absent") emptyList() else listOf(UnitSpawn("candidate",
                    if (kind == "friendly") "p1" else "p2", "candidate",
                    Position(if (kind == "distant") 700.0 else 470.0, 200.0), 270.0))
            val context = HeavyMechTestScene.open(HeavyMechTestScene.scenario(unit = heavy, heading = 90.0).copy(
                unitDefinitions = mapOf(heavy.id to heavy, "fragile" to body(150.0, true), "candidate" to body(1000.0, kind != "ground")),
                units = spawns, combatContract = CombatContract(12345, -1,
                    spawns.mapIndexed { i, unit -> unit.unitId to i + 8L }.toMap(), 20L)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "heavy", "first", context.world)))
                var second: Long? = null
                var diedAt: Long? = null
                for (q in 0L..159L) {
                    val observed = context.advance(Step(q, 1.0, "sim-tick"))
                    observed.projectileEvents.firstOrNull { it.kind == "born" && it.projectile.sourceWeaponName == "3" }
                        ?.let { second = it.projectile.objectId }
                    if (!observed.units.single { it.unitId == "first" }.alive) { diedAt = q; break }
                }
                val q = assertNotNull(diedAt, kind)
                val id = assertNotNull(second, kind)
                val later = context.advance(Step(q + 1, 1.0, "sim-tick"))
                assertEquals("first", later.projectiles.single { it.objectId == id }.targetUnitId, kind)
                assertTrue(later.projectileEvents.none { it.kind == "target-ended" && it.projectile.objectId == id }, kind)
            } finally { context.close() }
        }
    }

    @Test fun the_second_ordinary_missile_retargets_after_the_first_missile_kills_the_shared_target() {
        val heavy = HeavyMechTestScene.declared()
        val source = HelicopterCombatScene.unit()
        fun passive(health: Double) = UnitDefinition("air", source.className, 1.0, 1.0,
            source.totalFrames, source.imagePath, source.imageScale,
            source.movement.copy(flight = FlightProfile(20.0, 0.0, 0.0, 20.0)), emptySet(),
            durability = DurabilityProfile(health, null), rotor = source.rotor)
        val scene = HeavyMechTestScene.scenario(unit = heavy, heading = 90.0).copy(
            unitDefinitions = mapOf(heavy.id to heavy, "fragile" to passive(150.0), "durable" to passive(1000.0)),
            units = listOf(UnitSpawn("heavy", "p1", heavy.id, Position(200.0, 200.0), 90.0),
                UnitSpawn("first", "p2", "fragile", Position(450.0, 200.0), 270.0),
                UnitSpawn("next", "p2", "durable", Position(470.0, 200.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L, "first" to 9L, "next" to 10L), 11L))
        val context = HeavyMechTestScene.open(scene)
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "heavy", "first", context.world)))
            var second: Long? = null
            var deathStep: Long? = null
            for (q in 0L..159L) {
                val observed = context.advance(Step(q, 1.0, "sim-tick"))
                observed.projectileEvents.firstOrNull { it.kind == "born" && it.projectile.sourceWeaponName == "3" }
                    ?.let {
                        assertEquals("first", it.projectile.targetUnitId)
                        second = it.projectile.objectId
                    }
                if (!observed.units.single { it.unitId == "first" }.alive) { deathStep = q; break }
            }
            val diedAt = assertNotNull(deathStep)
            val projectileId = assertNotNull(second)
            val redirected = context.advance(Step(diedAt + 1, 1.0, "sim-tick"))
            assertEquals("next", redirected.projectiles.single { it.objectId == projectileId }.targetUnitId)
            val healthAfterFirst = assertNotNull(redirected.units.single { it.unitId == "next" }.health)
            var hit = false
            for (q in diedAt + 2..diedAt + 100) {
                val observed = context.advance(Step(q, 1.0, "sim-tick"))
                if (observed.projectileEvents.any { it.kind == "impact" && it.projectile.objectId == projectileId }) {
                    assertTrue(assertNotNull(observed.units.single { it.unitId == "next" }.health) < healthAfterFirst)
                    hit = true
                    break
                }
            }
            assertTrue(hit, "The redirected missile must hit the surviving AIR target")
        } finally { context.close() }
    }
}
