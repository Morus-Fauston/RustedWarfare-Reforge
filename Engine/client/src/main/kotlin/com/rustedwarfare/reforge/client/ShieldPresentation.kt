package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.UnitObservation

/** Default shield appearance derived exclusively from the visible durability DTO. */
object ShieldPresentation {
    data class Image<T>(val resource: T, val opacity: Float)

    /** Resolve a shield against the images actually loaded for this scene. */
    fun <T> image(unit: UnitObservation, images: Map<String, T>): Image<T>? {
        val opacity = opacity(unit) ?: return null
        val image = images[unit.typeId] ?: return null
        return Image(image, opacity)
    }

    fun opacity(unit: UnitObservation): Float? {
        val shield = unit.shield ?: return null
        if (!unit.alive || shield.current <= 0f) return null
        return (.09f + .4f * (shield.current / shield.maximum) +
            .5f * minOf(shield.displayDamage, 50f) / 50f).coerceAtMost(1f)
    }
}
