package com.rustedwarfare.reforge.core.sim

import kotlin.math.sqrt
import kotlin.random.Random

/** Frozen trusted-host event; never part of PlayerObservation or a playback packet. */
data class SoundTriggerObservation(val eventId: String, val position: Position, val volume: Float, val pitch: Float = 1f)

data class SoundListener(val center: Position, val halfWidth: Float, val zoom: Float = 1f,
    val masterVolume: Float = 1f, val gameVolume: Float = 1f, val enabled: Boolean = true) {
    internal fun validate(width: Double, height: Double) {
        // A height-fitted portrait map can have a world viewport wider than the map itself.
        require(center.x.isFinite() && center.y.isFinite() && center.x in 0.0..width && center.y in 0.0..height &&
            halfWidth.isFinite() && halfWidth > 0f && zoom.isFinite() && zoom > 0f && zoom <= 4f &&
            masterVolume.isFinite() && masterVolume in 0f..1f && gameVolume.isFinite() && gameVolume in 0f..1f) {
            "SOUND_LISTENER_INVALID: Expected an in-map camera, finite viewport, supported zoom and bounded volumes"
        }
    }
}

/** Deliberately has no coordinate, distance, source unit, projectile ID or seed. */
data class AuthorizedSound(val eventId: String, val volume: Float, val pitch: Float)

class AuthorizedSoundBatch internal constructor(val world: WorldHandle, val stepIndex: Long, val serial: Long,
    sounds: List<AuthorizedSound>) {
    val sounds: List<AuthorizedSound> = immutable(sounds)
}

/** Owner-thread authority; listening consumes a committed step even when no sound passes. */
internal class SoundAuthority {
    private var world: WorldHandle? = null
    private val consumed = mutableMapOf<String, Long>()
    private val serials = mutableMapOf<String, Long>()

    fun consume(currentWorld: WorldHandle, player: String, committed: Observation?, listener: SoundListener): AuthorizedSoundBatch {
        if (world !== currentWorld) { world = currentWorld; consumed.clear(); serials.clear() }
        val step = committed?.stepIndex ?: -1L
        if (committed == null || consumed[player]?.let { it >= step } == true)
            return AuthorizedSoundBatch(currentWorld, step, serials[player] ?: 0L, emptyList())
        consumed[player] = step
        val serial = Math.addExact(serials[player] ?: 0L, 1L).also { serials[player] = it }
        val sounds = if (!listener.enabled || listener.masterVolume * listener.gameVolume < .01f) emptyList() else
            committed.soundTriggers.mapNotNull { trigger ->
                var base = trigger.volume
                if (listener.zoom < .5f) base *= 4f * listener.zoom * listener.zoom
                val centerX = listener.center.x.toFloat().toInt().toFloat()
                val centerY = listener.center.y.toFloat().toInt().toFloat()
                val dx = centerX - trigger.position.x.toFloat(); val dy = centerY - trigger.position.y.toFloat()
                val distanceSquared = dx * dx + dy * dy
                val radius = 1.72f * listener.halfWidth
                if (distanceSquared > radius * radius) return@mapNotNull null
                val distance = sqrt(distanceSquared)
                val attenuation = if (distance <= listener.halfWidth) 1f else 1f - (distance - listener.halfWidth) / listener.halfWidth
                val amplitude = attenuation * base
                if (amplitude <= .05f) return@mapNotNull null
                // Presentation randomness is independent of the simulator's deterministic counters.
                AuthorizedSound(trigger.eventId, minOf(amplitude, 1f) * listener.masterVolume * listener.gameVolume,
                    trigger.pitch + (Random.Default.nextFloat() * .14f - .07f))
            }
        return AuthorizedSoundBatch(currentWorld, step, serial, sounds)
    }
}
