package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.sim.UnitSpawn
import java.util.Collections

/** Checked package-owned start for a local match. */
class ContentPackOpening internal constructor(
    val map: MapDefinition, val credits: Long, val opponentCredits: Long, units: List<UnitSpawn>,
) {
    val units: List<UnitSpawn> = Collections.unmodifiableList(ArrayList(units))
}
