package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.math.*
import kotlin.test.*

class OrdinaryWeaponContractTest {
    private fun closeInput(): Scenario = CombatTestContent.scenario().let { base -> base.copy(units = base.units.map {
        if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it }) }

    @Test
    fun real_first_manual_shot_matches_the_independent_literal_sampler_witness_and_preserves_same_target_offsets() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(closeInput())).context
        try {
            context.submit(AttackAction("attack", "p1", "a", "b", context.world))
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            val bullet = first.projectiles.single { it.sourceUnitId == "a" }
            assertEquals(Position(-3.0, 5.0), bullet.offset)
            assertEquals(.70900005f, bullet.scatter)
            val weapon = checkNotNull(first.combat).weapons.single { it.unitId == "a" }
            assertEquals(10, weapon.randomCounter)
            assertEquals(1.0, weapon.offset.x)
            assertEquals(74.0, first.units.single { it.unitId == "a" }.cooldown)
            val born = first.projectileEvents.single { it.kind == "born" && it.projectile.sourceUnitId == "a" }.projectile
            assertEquals(20.0, hypot(born.position.x - 110.0, born.position.y - 210.0), 1e-10)
            context.submit(AttackAction("same-target", "p1", "a", "b", context.world))
            val second = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(weapon.offset, checkNotNull(second.combat).weapons.single { it.unitId == "a" }.offset)
            assertEquals(10, checkNotNull(second.combat).weapons.single { it.unitId == "a" }.randomCounter)
            assertEquals(73.0, second.units.single { it.unitId == "a" }.cooldown)
            assertTrue(second.projectileEvents.none { it.kind == "born" && it.projectile.sourceUnitId == "a" })
            context.submit(StopAction("stop", "p1", "a", context.world))
            context.submit(MoveAction("move", "p1", "a", Position(110.0, 210.0), context.world))
            assertEquals(73.0, context.observe().units.single { it.unitId == "a" }.cooldown)
        } finally { context.close() }
    }

    @Test
    fun strict_range_and_aim_residual_gates_use_real_automatic_fire() {
        for ((range, heading, fires) in listOf(Triple(130.0, 90.0, false), Triple(129.99, 90.0, true),
            Triple(110.0, 85.0, false), Triple(110.0, 85.001, true))) {
            val base = closeInput()
            val original = base.unitDefinitions.getValue("c_tank")
            val fixedTurret = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement,
                original.retainedUnconsumed, original.fogOfWarSightRange,
                checkNotNull(original.combat).copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
                unitDefinitions = mapOf("c_tank" to fixedTurret), units = base.units.map {
                    if (it.unitId == "a") it.copy(heading = heading) else it.copy(position = Position(110.0 + range, 210.0), heading = 90.0)
                }))).context
            try {
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(fires, frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "a" },
                    "range=$range heading=$heading")
            } finally { context.close() }
        }
    }

    @Test
    fun trusted_pivot_and_muzzle_show_literal_recoil_without_image_scale_shortening() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(closeInput())).context
        try {
            val recoil = mapOf(0L to -.5, 3L to -2.0, 6L to -8.0 / 7.0, 10L to 0.0)
            for (step in 0L..10L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                val expected = recoil[step] ?: continue
                val unit = frame.units.single { it.unitId == "a" }
                val weapon = checkNotNull(frame.combat).weapons.single { it.unitId == "a" }
                val pivot = assertNotNull(weapon.pivot)
                val muzzle = assertNotNull(weapon.muzzle)
                val theta = Math.toRadians(unit.turretHeading)
                val signedRecoil = (pivot.x - unit.position.x) * sin(theta) - (pivot.y - unit.position.y) * cos(theta)
                assertEquals(expected, signedRecoil, 1e-10)
                assertEquals(20.0, hypot(muzzle.x - pivot.x, muzzle.y - pivot.y), 1e-10)
            }
        } finally { context.close() }
    }

    @Test
    fun same_step_attack_stop_order_finishes_before_any_far_pursuit_or_fire() {
        for (attackLast in listOf(false, true)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(CombatTestContent.scenario())).context
            try {
                val attack = AttackAction("attack", "p1", "a", "b", context.world, 0)
                val stop = StopAction("stop", "p1", "a", context.world, 0)
                val order = if (attackLast) listOf(stop, attack) else listOf(attack, stop)
                order.forEach { assertIs<ActionResult.Accepted>(context.submit(it)) }
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(order.map { it.actionId }, frame.actionResults.map { it.actionId })
                val unit = frame.units.single { it.unitId == "a" }
                assertEquals(if (attackLast) "b" else null, unit.attackTargetId)
                assertEquals(attackLast, unit.position.x > 110.0)
                assertTrue(frame.projectiles.none { it.sourceUnitId == "a" })
            } finally { context.close() }
        }
    }
}
