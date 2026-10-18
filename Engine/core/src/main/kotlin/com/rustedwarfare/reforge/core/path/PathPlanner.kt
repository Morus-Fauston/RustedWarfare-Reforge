package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.*
import java.util.Collections

/** Public host/test seam over the existing experimental A*, not a second algorithm. */
object PathPlanner {
    fun findPath(terrain: PlanningTerrain, start: Position, target: Position, radius: Double): List<Position>? {
        if(!start.x.isFinite() || !start.y.isFinite() || !target.x.isFinite() || !target.y.isFinite()) throw PlanningException("invalid-coordinate", "Planning coordinates must be finite")
        if(!radius.isFinite() || radius<0.0) throw PlanningException("invalid-radius", "Planning radius must be finite and nonnegative")
        val points=ClearanceAStarPolicy(CenterClearance(PlanningCircleTerrain),TileCenters,PlanningCircleTerrain)
            .findPath(terrain,start,target,radius) ?: return null
        return Collections.unmodifiableList(ArrayList(points))
    }
}
