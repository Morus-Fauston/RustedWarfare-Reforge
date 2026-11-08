package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class ShieldCombatTest {
    @Test fun shield_hit_display_decays_independently_while_real_shield_recovers() = withShieldDuel(40.0) { context ->
        val hit = firstShieldHit(context)
        assertEquals(40f, hit.units.single { it.unitId == "heavy" }.shield?.displayDamage)
        val next = context.advance(Step(hit.stepIndex + 1, 1.0, "sim-tick"))
        val shield = assertNotNull(next.units.single { it.unitId == "heavy" }.shield)
        assertEquals(35.2f, shield.displayDamage)
        assertEquals(460.08f, shield.current)
        var frame = next
        for (step in next.stepIndex + 1..hit.stepIndex + 600) frame = context.advance(Step(step, 1.0, "sim-tick"))
        assertEquals(500f, frame.units.single { it.unitId == "heavy" }.shield?.current)
        assertEquals(0f, frame.units.single { it.unitId == "heavy" }.shield?.displayDamage)
    }
    @Test fun an_empty_shield_recovers_on_the_next_alive_update_without_a_hit_delay() = withShieldDuel(540.0) { context ->
        val hit = firstShieldHit(context)
        val target = hit.units.single { it.unitId == "heavy" }
        assertEquals(0f, target.shield?.current)
        assertEquals(2460.0, target.health, "Only the forty points beyond the initial shield reach HP")
        val next = context.advance(Step(hit.stepIndex + 1, 1.0, "sim-tick"))
        assertEquals(.08f, next.units.single { it.unitId == "heavy" }.shield?.current)
        assertEquals(2460.0, next.units.single { it.unitId == "heavy" }.health)
    }

    @Test fun exact_shield_damage_does_not_reach_health_and_lethal_overflow_does_not_regenerate() {
        for ((damage, hp) in listOf(500.0 to 2500.0, 3000.0 to 0.0)) withShieldDuel(damage) { context ->
            val hit = firstShieldHit(context)
            val unit = hit.units.single { it.unitId == "heavy" }
            assertEquals(0f, unit.shield?.current)
            assertEquals(hp, unit.health)
            if (!unit.alive) {
                val next = context.advance(Step(hit.stepIndex + 1, 1.0, "sim-tick"))
                assertEquals(0f, next.units.single { it.unitId == "heavy" }.shield?.current)
            }
        }
    }
    @Test fun authorized_observation_reports_initial_and_spent_shield_separately_from_health_damage() = withShieldDuel(40.0) { context ->
        val initial = context.observePlayer(context.world, "p1").units.single { it.unitId == "heavy" }
        assertEquals(500f, initial.shield?.current)
        assertEquals(500f, initial.shield?.maximum)
        val hit = firstShieldHit(context)
        val unit = context.observePlayer(context.world, "p1").units.single { it.unitId == "heavy" }
        assertEquals(460f, unit.shield?.current)
        assertEquals(0.0, hit.projectileEvents.single { it.kind == "impact" && it.projectile.sourceUnitId == "probe" }.damage)
        assertTrue(hit.events.any { it.kind == "shield-damaged" && it.unitId == "heavy" })
        assertEquals(500f, initial.shield?.current, "Previously published shield state is immutable")
    }
    @Test fun an_actual_hit_spends_full_shield_before_health() = withShieldDuel(40.0) { context ->
        assertIs<ActionResult.Accepted>(context.submit(AttackAction("fire", "p1", "probe", "heavy", context.world)))
        val hit = firstShieldHit(context)
        assertEquals(2500.0, hit.units.single { it.unitId == "heavy" }.health,
            "The loaded representative's 500 shield absorbs this ordinary 40 hit")
    }
}

internal fun firstShieldHit(context: SimulationContext): Observation {
    // Normal target offset and turret alignment may take several updates.
    for (step in 0L..59L) {
        val frame = context.advance(Step(step, 1.0, "sim-tick"))
        if (frame.projectileEvents.any { it.kind == "impact" && it.projectile.sourceUnitId == "probe" }) return frame
    }
    fail("The controlled probe must actually hit through the normal weapon path")
}

internal fun withShieldDuel(damage: Double, transform: (Scenario) -> Scenario = { it }, check: (SimulationContext) -> Unit) {
    val heavy = HeavyMechTestScene.declared()
    val source = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
    val combat = checkNotNull(source.combat).copy(shootDelay = 10000.0,
        projectile = ProjectileProfile(damage, 60.0, 1000.0), namedWeapons = null)
    val probe = UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
        source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed,
        source.fogOfWarSightRange, combat)
    val input = HeavyMechTestScene.scenario(unit = heavy).copy(unitDefinitions = mapOf(heavy.id to heavy, probe.id to probe),
        units = listOf(UnitSpawn("probe", "p1", probe.id, Position(100.0, 200.0), 90.0),
            UnitSpawn("heavy", "p2", heavy.id, Position(200.0, 200.0), 270.0)),
        combatContract = CombatContract(12345, -1, mapOf("probe" to 8L, "heavy" to 9L), 10L))
    val context = HeavyMechTestScene.open(transform(input))
    try { check(context) } finally { context.close() }
}
