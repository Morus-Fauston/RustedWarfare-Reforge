package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.sim.Position
import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

internal data class AfterburnTrailDrawing(val center: Position, val scale: Float, val alpha: Float,
    val heading: Double, val images: UnitAppearance)

/** Retained movement effects consume only the authorized player observation. */
internal class AfterburnTrailPresentation {
    private data class Particle(val unitId: String, val center: Position, val heading: Double,
        val born: Double, val images: UnitAppearance)
    private var identity: Pair<String, String>? = null
    private var previousTime: Double? = null
    private val emitters = mutableMapOf<String, Double>()
    private val particles = mutableListOf<Particle>()

    fun project(view: PlayerObservation, resources: SandboxRenderResources): List<AfterburnTrailDrawing> {
        val now = view.simulationTime
        val key = view.worldId to view.playerId
        if (identity != key || previousTime?.let { now < it } == true) {
            identity = key; previousTime = null; emitters.clear(); particles.clear()
        }
        val delta = previousTime?.let { now - it } ?: 0.0
        previousTime = now
        val visible = view.units.map { it.unitId }.toSet()
        particles.removeAll { now - it.born >= 30.0 || it.unitId !in visible }
        val moving = view.units.filter { it.alive && it.typeId == "aaBeamGunship_afterburn" && it.speed != 0.0 }
        emitters.keys.retainAll(moving.map { it.unitId }.toSet())
        if (delta > 0.0) for (unit in moving) {
            val images = resources.units.getValue(unit.typeId)
            require(images.afterburnTrailImagePath != null) { "Accelerated flight requires a trail image" }
            val elapsed = (emitters[unit.unitId] ?: 0.0) + delta
            emitters[unit.unitId] = elapsed % 4.0
            // Retain only the live visual interval when the renderer skipped simulation samples.
            var age = elapsed % 4.0
            if (elapsed < 4.0) continue
            while (age < minOf(elapsed, 30.0)) {
                val angle = Math.toRadians(unit.heading)
                val velocity = unit.flightPose?.velocity ?: Position(0.0, 0.0)
                for (side in listOf(10.0, -10.0)) {
                    val center = Position(unit.position.x - velocity.x * age + cos(angle) * side - sin(angle) * 22.0,
                        unit.position.y - velocity.y * age + sin(angle) * side + cos(angle) * 22.0 - unit.height)
                    particles += Particle(unit.unitId, center, unit.heading, now - age, images)
                }
                age += 4.0
            }
        }
        return Collections.unmodifiableList(particles.map { particle ->
            val age = now - particle.born
            val angle = Math.toRadians(particle.heading)
            AfterburnTrailDrawing(Position(particle.center.x - sin(angle) * .2 * age,
                particle.center.y + cos(angle) * .2 * age), (.7 + .7 * age / 30.0).toFloat(),
                (.7 * minOf(1.0, age / 6.0) * (1.0 - age / 30.0)).toFloat(), particle.heading, particle.images)
        })
    }
}
