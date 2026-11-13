package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.math.abs

/** Native intent, physical depth and threshold presentation are independent state. */
internal object SubmarineMotion {
    fun submerged(unit: MutableUnit): Boolean = unit.definition.submarine != null && unit.height < -1.0

    fun movement(unit: MutableUnit): MovementProfile = unit.definition.movement.let {
        if (submerged(unit)) it.copy(moveSpeed = .45) else it
    }

    fun supports(definition: UnitDefinition): Boolean = definition.submarine == SubmarineProfile.NATIVE &&
        definition.id == "attackSubmarine" && definition.className == "NativeSubmarine" &&
        definition.movement == MovementProfile(MovementType.WATER, .8, .018, .1, 1.2, .06, false, false, .6) &&
        definition.radius == 15.0 && definition.displayRadius == 13.0 && definition.maximumHealth == 260.0 &&
        definition.build == BuildProfile(800L, .001f) && definition.combat?.let(SubmarineCombat::supports) == true &&
        definition.fixedUnderwaterTarget == null && definition.building == null && definition.production == null &&
        definition.builder == null && definition.construction == null && definition.pointDefense == null &&
        definition.mech == null && definition.rotor == null && definition.income == null &&
        definition.upgrades == null && definition.shield == null && definition.deathExplosion == null &&
        definition.durability == null && !definition.authoredWaterCombat && definition.totalFrames == 1 &&
        definition.imageScale == 1.0 && definition.movement.flight == null && definition.movement.hover == null &&
        definition.directHitTargetKind == DirectHitTargetKind.ORDINARY

    fun advance(unit: MutableUnit, delta: Double): Boolean {
        if (unit.definition.submarine == null || !unit.alive) return false
        val elapsed = delta.toFloat()
        val desired = if (unit.submarineSurfaceIntent) 1f else -8f
        val rate = if (abs(unit.height.toFloat() - desired) > 2f) .08f else .02f
        unit.submarineVerticalSpeed = towards(unit.submarineVerticalSpeed, rate, .003f * elapsed)
        unit.height = towards(unit.height.toFloat(), desired, unit.submarineVerticalSpeed * elapsed).toDouble()
        require(unit.height.isFinite() && unit.submarineVerticalSpeed.isFinite()) { "Submarine depth must remain finite" }
        val surface = unit.height >= -1.0
        val crossed = surface != unit.submarineSurfacePresentation
        unit.submarineSurfacePresentation = surface
        if (crossed) unit.localMotion = null
        return crossed
    }

    private fun towards(value: Float, target: Float, amount: Float): Float = when {
        value > target + amount -> value - amount
        value < target - amount -> value + amount
        else -> target
    }
}
