package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ProjectileDamageModifiers
import com.rustedwarfare.reforge.core.content.ShieldProfile

/** Reads and updates only the shield held by the unit being protected. */
internal object UnitShield {
    fun advance(unit: MutableUnit, delta: Float) {
        val profile = unit.definition.shield ?: return
        restore(unit, profile, profile.regeneration * delta)
        val faded = unit.shieldDisplayDamage - unit.shieldDisplayDamage * .02f * delta
        unit.shieldDisplayDamage = maxOf(0f, faded - 4f * delta).coerceAtMost(50f)
    }

    fun restoreFromAttachment(parent: MutableUnit, amount: Float) =
        restore(parent, checkNotNull(parent.definition.shield), amount)

    private fun restore(unit: MutableUnit, profile: ShieldProfile, amount: Float) {
        require(!amount.isNaN() && amount >= 0f) { "Shield restoration must be nonnegative" }
        // Compare in Double to avoid overflow at the limit without changing Float rounding below it.
        unit.shield = if (unit.shield.toDouble() + amount.toDouble() >= profile.maximum) profile.maximum
            else (unit.shield + amount).coerceIn(0f, profile.maximum)
    }

    fun afterConversion(unit: MutableUnit, next: ShieldProfile?): Float = next?.let {
        if (it.startAtZero) unit.shield.coerceIn(0f, it.maximum)
        else unit.definition.shield?.let { previous -> unit.shield / previous.maximum * it.maximum } ?: it.maximum
    } ?: 0f

    fun damage(unit: MutableUnit, amount: Double, modifiers: ProjectileDamageModifiers, onAbsorbed: () -> Unit): Double {
        val before = unit.shield.toDouble()
        val cost = amount * modifiers.shieldDamage
        val absorbed = minOf(before, cost)
        val deflected = if (before <= 0.0) 0.0 else
            (if (before < cost) before else amount) * modifiers.shieldDeflection
        unit.shield = (unit.shield - absorbed).toFloat()
        if (absorbed > 0.0) {
            unit.shieldDisplayDamage += absorbed.toFloat()
            onAbsorbed()
        }
        return maxOf(0.0, amount - deflected)
    }
}
