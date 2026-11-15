package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.OfflineCameraMotion
import com.rustedwarfare.reforge.core.sim.Position

internal class OfflineCameraController(private val map: MapProjection, private val evidence: ClientEvidence = ClientEvidence.NONE) {
    private sealed interface CameraControl {
        val motion: String
        data class Pan(val start: Position, val destination: Position, val started: Long, val duration: Int) : CameraControl {
            override val motion: String get() = "pan"
        }
        data class Follow(val unitId: String) : CameraControl {
            override val motion: String get() = "follow"
        }
    }

    private var active: CameraControl? = null
    private var step = -1L
    val followsUnit: Boolean get() = active is CameraControl.Follow

    fun advance(stepIndex: Long, motions: List<OfflineCameraMotion>, authorizedPosition: (String) -> Position?) {
        step = stepIndex
        for (motion in motions) {
            stop("replacement")
            when (motion) {
                is OfflineCameraMotion.Locate -> {
                    map.centerOn(motion.center)
                    record("applied", "locate", null, null)
                }
                is OfflineCameraMotion.Pan -> {
                    active = CameraControl.Pan(map.camera.center, motion.center, step, motion.durationTicks)
                    record("started", "pan", null, null)
                }
                is OfflineCameraMotion.Follow -> {
                    val point = authorizedPosition(motion.unitId)
                    if (point == null) {
                        record("ended", "follow", "target-unavailable", null)
                    } else {
                        active = CameraControl.Follow(motion.unitId)
                        map.centerOn(point)
                        record("started", "follow", null, motion.unitId)
                    }
                }
                OfflineCameraMotion.TargetUnavailable -> record("ended", "follow", "target-unavailable", null)
            }
        }
        when (val control = active) {
            is CameraControl.Pan -> {
                val time = ((step - control.started).toDouble() / control.duration).coerceIn(0.0, 1.0)
                val weight = time * time * (3.0 - 2.0 * time)
                map.centerOn(Position(control.start.x + (control.destination.x - control.start.x) * weight,
                    control.start.y + (control.destination.y - control.start.y) * weight))
                if (time == 1.0) stop("completed") else if (step % 15 == 0L) record("sample", control.motion, null, null)
            }
            is CameraControl.Follow -> {
                val point = authorizedPosition(control.unitId)
                if (point == null) stop("target-unavailable") else {
                    map.centerOn(point)
                    if (step % 15 == 0L) record("sample", control.motion, null, control.unitId)
                }
            }
            null -> Unit
        }
    }

    fun takeOver(reason: String) = stop(reason)

    private fun stop(reason: String) {
        val control = active ?: return
        active = null
        record("ended", control.motion, reason, null)
    }

    private fun record(phase: String, motion: String, reason: String?, target: String?) =
        evidence.recordWhenEnabled("camera-script") {
            mapOf("phase" to phase, "motion" to motion, "reason" to reason, "targetUnitId" to target,
                "stepIndex" to step, "center" to map.camera.center.fields(), "zoom" to map.camera.zoom)
        }
}
