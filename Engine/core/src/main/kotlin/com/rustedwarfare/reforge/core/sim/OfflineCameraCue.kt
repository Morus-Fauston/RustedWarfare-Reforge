package com.rustedwarfare.reforge.core.sim

sealed interface OfflineCameraMotion {
    data class Locate(val center: Position) : OfflineCameraMotion
    data class Pan(val center: Position, val durationTicks: Int) : OfflineCameraMotion
    data class Follow(val unitId: String) : OfflineCameraMotion
    data object TargetUnavailable : OfflineCameraMotion
}

data class OfflineCameraCue(val stepIndex: Long, val playerId: String, val motion: OfflineCameraMotion)
