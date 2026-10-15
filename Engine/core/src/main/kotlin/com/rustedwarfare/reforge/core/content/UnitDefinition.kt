package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections

class UnitDefinition(
    val id: String,
    val className: String,
    val radius: Double,
    val displayRadius: Double,
    val totalFrames: Int,
    val imagePath: Path,
    val imageScale: Double,
    val movement: MovementProfile,
    retainedUnconsumed: Set<String>,
) {
    val retainedUnconsumed: Set<String> = Collections.unmodifiableSet(LinkedHashSet(retainedUnconsumed))
}

data class MovementProfile(
    val type: MovementType,
    val moveSpeed: Double,
    val moveAccelerationSpeed: Double,
    val moveDecelerationSpeed: Double,
    val maxTurnSpeed: Double,
    val turnAcceleration: Double,
    val slidingMode: Boolean,
    val ignoringBody: Boolean,
)

enum class MovementType { LAND }
