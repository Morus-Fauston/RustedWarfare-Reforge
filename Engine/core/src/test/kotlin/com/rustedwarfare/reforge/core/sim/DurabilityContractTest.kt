package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Small unarmed typed target exercises durability, not the pending native factory spatial contract. */
class DurabilityContractTest {
    private fun scenario(hp: Double = 25.0): Scenario {
        val base = CombatTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val target = UnitDefinition("durable_target", tank.className, tank.radius, tank.displayRadius,
            tank.totalFrames, tank.imagePath, tank.imageScale,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(hp, checkNotNull(tank.combat).deadImagePath))
        return base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units.map { if (it.unitId == "b") it.copy(typeId = target.id, position = Position(135.5, 210.0)) else it })
    }

    @Test
    fun unarmed_durability_is_a_real_damage_target_without_a_dummy_weapon() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario())).context
        try {
            val before = context.observe()
            assertEquals(25.0, before.units.single { it.unitId == "b" }.health)
            assertEquals(25.0, before.units.single { it.unitId == "b" }.maxHealth)
            assertTrue(checkNotNull(before.combat).weapons.none { it.unitId == "b" })
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("real-hit", "p1", "a", "b", context.world)))
            var after = context.observe()
            for (step in 0L..20L) {
                after = context.advance(Step(step, 1.0, "sim-tick"))
                if (!after.units.single { it.unitId == "b" }.alive) break
            }
            assertEquals(0.0, after.units.single { it.unitId == "b" }.health)
            assertFalse(after.units.single { it.unitId == "b" }.alive)
            assertEquals(1, after.events.count { it.kind == "unit-died" && it.unitId == "b" })
            assertTrue(after.projectileEvents.any { it.kind == "impact" && it.projectile.targetUnitId == "b" && it.damage == 25.0 })
            assertEquals(25.0, before.units.single { it.unitId == "b" }.health)
        } finally { context.close() }
    }

    @Test
    fun immobile_target_checks_control_before_its_movement_or_weapon_capability() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario())).context
        try {
            val enemyMove = assertIs<ActionResult.Rejected>(context.submit(MoveAction("enemy", "p1", "b", Position(200.0, 200.0), context.world)))
            assertEquals("not-owner", enemyMove.error.code)
            val ownMove = assertIs<ActionResult.Rejected>(context.submit(MoveAction("own", "p2", "b", Position(200.0, 200.0), context.world)))
            assertEquals("immobile-unit", ownMove.error.code)
            val ownAttack = assertIs<ActionResult.Rejected>(context.submit(AttackAction("unarmed", "p2", "b", "a", context.world)))
            assertEquals("cannot-attack", ownAttack.error.code)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p2", "b", context.world)))
            assertNull(context.observe().units.single { it.unitId == "b" }.moveTarget)
        } finally { context.close() }
    }

    @Test
    fun invalid_or_conflicting_life_definition_is_rejected_before_world_publication() {
        for (hp in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = SimulationContext.initialize(scenario(hp))
            if (result is InitResult.Accepted) result.context.close()
            assertEquals("unsupported-durability", assertIs<InitResult.Rejected>(result).error.code)
        }
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val conflict = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            original.fogOfWarSightRange, original.combat, durability = DurabilityProfile(211.0, checkNotNull(original.combat).deadImagePath))
        assertEquals("unsupported-durability", assertIs<InitResult.Rejected>(
            SimulationContext.initialize(base.copy(unitDefinitions = mapOf(original.id to conflict)))).error.code)
    }
}
