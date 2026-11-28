package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.navigation.UnitLocomotion
import com.rustedwarfare.reforge.core.navigation.VerticalPoseController
import kotlin.math.abs

/**
 * Parameterized submersible vertical pose controller (ENG-P017-006):
 * - Native `SubmarineProfile.NATIVE` retains strict weapon/profile qualification via [supports].
 * - Any Phase VI-B custom submarine carrying `movement.submersible != null` runs purely on its
 *   [SubmersibleMotionProfile] parameters with zero unit-ID branching.
 */
internal object SubmarineMotion : VerticalPoseController {
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

    fun isSubmersible(definition: UnitDefinition): Boolean =
        definition.submarine != null || (definition.amphibiousJet == null && definition.movement.submersible != null)

    fun effectiveProfile(definition: UnitDefinition): SubmersibleMotionProfile? =
        if (definition.amphibiousJet != null) null else definition.effectiveSubmersible

    fun maxVerticalRate(definition: UnitDefinition): Float {
        val profile = effectiveProfile(definition) ?: return 0.08f
        return maxOf(0.08f, profile.fastVerticalRate.toFloat(), profile.fineVerticalRate.toFloat())
    }

    override fun advancePose(
        unit: MutableUnit,
        map: MapDefinition,
        delta: Double,
        acceptsHeight: (Double) -> Boolean,
    ): String? = if (advance(unit, delta, acceptsHeight, map)) "submarine-threshold-crossed" else null

    fun advance(
        unit: MutableUnit,
        delta: Double,
        acceptsHeight: (Double) -> Boolean,
        map: MapDefinition? = null,
    ): Boolean {
        val profile = effectiveProfile(unit.definition) ?: return false
        if (!unit.alive) return false
        val elapsed = delta.toFloat()
        var surfaceIntent = unit.submarineSurfaceIntent
        if (!surfaceIntent && profile.diveRequiresOverWater && map != null && !AmphibiousJetMotion.overWater(unit, map)) {
            surfaceIntent = true
            unit.submarineSurfaceIntent = true
        }
        val desired = (if (surfaceIntent) profile.surfaceHeight else profile.submergedHeight).toFloat()
        val rate = if (abs(unit.height.toFloat() - desired) > 2f) profile.fastVerticalRate.toFloat() else profile.fineVerticalRate.toFloat()
        val verticalSpeed = towards(unit.submarineVerticalSpeed, rate, profile.verticalAcceleration.toFloat() * elapsed)
        val height = towards(unit.height.toFloat(), desired, verticalSpeed * elapsed).toDouble()
        require(height.isFinite() && verticalSpeed.isFinite()) { "Submarine depth must remain finite" }
        if (!acceptsHeight(height)) return false
        unit.height = height
        unit.submarineVerticalSpeed = verticalSpeed
        val surface = !UnitLocomotion.isSubmerged(unit)
        val crossed = surface != unit.submarineSurfacePresentation
        unit.submarineSurfacePresentation = surface
        return crossed
    }

    private fun towards(value: Float, target: Float, amount: Float): Float = when {
        value > target + amount -> value - amount
        value < target - amount -> value + amount
        else -> target
    }
}
