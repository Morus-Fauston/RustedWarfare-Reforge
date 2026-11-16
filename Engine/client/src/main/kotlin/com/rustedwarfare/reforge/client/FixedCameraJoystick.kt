package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.hypot

internal class FixedCameraJoystick {
    var pointer: Int? = null
        private set
    var direction: Position = neutral
        private set
    val steering: Boolean get() = direction != neutral

    fun press(pointer: Int, x: Double, y: Double): Boolean {
        if (this.pointer != null) return false
        this.pointer = pointer
        return drag(pointer, x, y)
    }

    fun drag(pointer: Int, x: Double, y: Double): Boolean {
        if (this.pointer != pointer) return false
        val radius = hypot(x, y)
        direction = if (!radius.isFinite() || radius <= deadZone) neutral else {
            val strength = (radius.coerceAtMost(1.0) - deadZone) / (1.0 - deadZone)
            Position(x / radius * strength, y / radius * strength)
        }
        return true
    }

    fun release(pointer: Int): Boolean = this.pointer == pointer && cancel()

    fun cancel(): Boolean {
        if (pointer == null) return false
        pointer = null
        direction = neutral
        return true
    }

    fun apply(map: MapProjection, elapsed: Double, takeOver: () -> Unit): Boolean {
        if (!steering || !elapsed.isFinite() || elapsed <= 0.0) return false
        takeOver()
        val before = map.camera.center
        val distance = speed * elapsed.coerceAtMost(.05)
        map.panBy(-direction.x * distance, -direction.y * distance)
        return map.camera.center != before
    }

    companion object {
        const val startingZoom = 2.0
        private const val deadZone = .16
        private const val speed = 280.0
        private val neutral = Position(0.0, 0.0)
    }
}
