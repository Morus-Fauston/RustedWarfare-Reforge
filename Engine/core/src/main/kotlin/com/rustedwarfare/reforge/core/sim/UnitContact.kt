package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.TransportProfile
import kotlin.math.abs
import kotlin.math.hypot

/** Physical contacts use the current authoritative poses, independently of route knowledge. */
internal object UnitContact {
    private enum class Layer { SURFACE, SUBMERGED }
    private data class Contact(val fraction: Double, val blocker: MutableUnit)
    private data class SideShift(val unit: MutableUnit, val position: Position)

    class YieldRequest(val collisionMap: MapDefinition, val stepIndex: Long, val canYield: (MutableUnit) -> Boolean)

    private fun layer(unit: MutableUnit, height: Double = unit.height): Layer? = when {
        !unit.alive || unit.carriedBy != null || unit.attachment != null || unit.definition.building != null -> null
        CombatTargetQuery.isUnderwater(unit, height) -> Layer.SUBMERGED
        unit.definition.movement.type == MovementType.AIR &&
            (height >= 4.0 || unit.definition.movement.flight == null && unit.amphibiousState == null &&
                unit.definition.transport != TransportProfile.NATIVE_DROPSHIP) -> null
        else -> Layer.SURFACE
    }

    fun shareLayer(first: MutableUnit, second: MutableUnit): Boolean =
        layer(first)?.let { it == layer(second) } == true

    fun posesOverlap(first: MovementCollisionShape, at: Position, heading: Double,
                     second: MovementCollisionShape, other: Position, otherHeading: Double): Boolean {
        val offset = subtract(at, other)
        val reach = boundingRadius(first) + boundingRadius(second)
        return abs(offset.x) <= reach && abs(offset.y) <= reach &&
            Body(first, heading).overlaps(Body(second, otherHeading), offset)
    }

    fun poseFits(shape: MovementCollisionShape, at: Position, heading: Double, map: HullTerrainGrid): Boolean {
        val inside = when (shape) {
            is MovementCollisionShape.Circle -> CircularTerrain(shape.radius).contains(map, at)
            is MovementCollisionShape.Rectangle -> RectangularTerrain(shape, heading).contains(map, at)
        }
        return inside && !overlapsTerrain(Body(shape, heading), at, map)
    }

    fun inContact(unit: MutableUnit, other: MutableUnit): Boolean {
        val layer = layer(unit) ?: return false
        if (layer(other) != layer) return false
        val a = Body(unit.definition.movementCollision, unit.heading)
        val b = Body(other.definition.movementCollision, other.heading)
        val roundoff = 4.0 * Math.ulp(maxOf(abs(unit.position.x), abs(unit.position.y),
            abs(other.position.x), abs(other.position.y), a.reach, b.reach))
        return a.clearance(b, subtract(unit.position, other.position)) <= roundoff
    }

    fun turn(unit: MutableUnit, shape: MovementCollisionShape.Rectangle, from: Double, requested: Double,
             units: Collection<MutableUnit>): Double {
        if (from == requested) return from
        val layer = layer(unit) ?: return requested
        val radius = boundingRadius(shape)
        var heading = requested
        for (other in units) {
            if (unit === other || layer(other) != layer) continue
            val offset = subtract(other.position, unit.position)
            val otherShape = other.definition.movementCollision
            val reach = radius + boundingRadius(otherShape)
            if (abs(offset.x) > reach || abs(offset.y) > reach) continue
            heading = when (otherShape) {
                is MovementCollisionShape.Circle -> RectangularTurn.againstCircle(shape, otherShape.radius, offset, from, heading)
                is MovementCollisionShape.Rectangle -> RectangularTurn.againstRectangle(shape, otherShape, negate(offset), other.heading, from, heading)
            }
            if (heading == from) return from
        }
        return heading
    }

    fun translate(unit: MutableUnit, requested: Position, units: Collection<MutableUnit>,
                  yielding: YieldRequest? = null): Position {
        val contact = translationContact(unit, requested, units) ?: return requested
        if (yielding != null && yielding.canYield(contact.blocker)) {
            val shift = sideShift(unit, requested, contact.blocker, units, yielding.collisionMap)
            if (shift != null) {
                shift.unit.position = shift.position
                shift.unit.lastYieldStep = yielding.stepIndex
                shift.unit.lastYieldTo = unit.unitId to checkNotNull(unit.originMoveId)
                return requested
            }
        }
        val start = unit.position
        if (contact.fraction == 0.0) return start
        val drift = subtract(requested, start)
        return Position(start.x + drift.x * contact.fraction, start.y + drift.y * contact.fraction)
    }

    private fun translationContact(unit: MutableUnit, requested: Position, units: Collection<MutableUnit>,
                                   shifted: SideShift? = null): Contact? {
        val start = unit.position
        if (start == requested) return null
        val layer = layer(unit) ?: return null
        val body = Body(unit.definition.movementCollision, unit.heading)
        val drift = subtract(requested, start)
        var fraction = 1.0
        var blocker: MutableUnit? = null
        for (other in units) {
            if (unit === other || layer(other) != layer) continue
            val otherCenter = if (shifted?.unit === other) shifted.position else other.position
            val offset = subtract(start, otherCenter)
            val reach = body.reach + boundingRadius(other.definition.movementCollision)
            if (abs(offset.x) > reach + abs(drift.x) || abs(offset.y) > reach + abs(drift.y)) continue
            val limit = body.entry(Body(other.definition.movementCollision, other.heading), offset, drift, fraction)
            if (limit < fraction) {
                fraction = limit
                blocker = other
                if (fraction == 0.0) break
            }
        }
        return blocker?.let { Contact(fraction, it) }
    }

    private fun sideShift(mover: MutableUnit, requested: Position, blocker: MutableUnit,
                          units: Collection<MutableUnit>, map: MapDefinition): SideShift? {
        val drift = subtract(requested, mover.position)
        val length = hypot(drift.x, drift.y)
        val side = Position(-drift.y / length, drift.x / length)
        val moverBody = Body(mover.definition.movementCollision, mover.heading)
        val blockerShape = blocker.definition.movementCollision
        val blockerBody = Body(blockerShape, blocker.heading)
        val width = moverBody.radiusAlong(side) + blockerBody.radiusAlong(side)
        val offset = dot(subtract(blocker.position, mover.position), side)
        val maximumShift = minOf(map.tileWidth, map.tileHeight).toDouble()
        val motion = UnitLocomotion.movement(blocker).type
        val surfaceTerrain = if (motion != MovementType.AIR) motion
            else if (blocker.flightLandedInWater) MovementType.WATER else MovementType.LAND
        val grid = HullTerrainGrid.authoritative(map, surfaceTerrain)
        val contactLayer = layer(blocker)
        fun position(delta: Double) =
            Position(blocker.position.x + side.x * delta, blocker.position.y + side.y * delta)

        fun legalShift(delta: Double): SideShift? {
            val end = position(delta)
            val terrainClear = when (blockerShape) {
                is MovementCollisionShape.Circle -> if (blockerShape.radius > 0.0)
                    CircularTerrain(blockerShape.radius).resolve(grid, blocker.position, end) == end
                else MapBounds.contains(grid.width, grid.height, end, 0.0) &&
                    TerrainMotion.resolve(map, blocker.position, end, movementType = surfaceTerrain) == end
                is MovementCollisionShape.Rectangle -> RectangularTerrain(blockerShape, blocker.heading).resolve(grid, blocker.position, end) == end
            }
            if (!terrainClear || overlapsTerrain(blockerBody, end, grid)) return null
            if (translationContact(blocker, end, units) != null || units.any { other ->
                other !== blocker && layer(other) == contactLayer &&
                    blockerBody.overlaps(Body(other.definition.movementCollision, other.heading), subtract(end, other.position))
            }) return null
            val shift = SideShift(blocker, end)
            return shift.takeIf { translationContact(mover, requested, units, it) == null }
        }

        val fullClearance = listOf(width - offset, -width - offset).sortedBy { abs(it) }
        // Leave clearance for the current direction where the stopped unit can stay there.
        for (delta in fullClearance) {
            if (delta != 0.0 && abs(delta) <= maximumShift) legalShift(delta)?.let { return it }
        }
        fun clearsActualStep(delta: Double): Boolean {
            val fromBlocker = subtract(mover.position, position(delta))
            return !moverBody.overlaps(blockerBody, fromBlocker) &&
                moverBody.entry(blockerBody, fromBlocker, drift, 1.0) == 1.0
        }
        val shorter = fullClearance.mapNotNull { projected ->
            val boundary = projected.coerceIn(-maximumShift, maximumShift)
            if (boundary == 0.0 || !clearsActualStep(boundary)) return@mapNotNull null
            var blockedFraction = 0.0
            var clearFraction = 1.0
            repeat(48) {
                val middle = (blockedFraction + clearFraction) / 2.0
                if (clearsActualStep(boundary * middle)) clearFraction = middle
                else blockedFraction = middle
            }
            boundary * clearFraction
        }.sortedBy { abs(it) }
        for (delta in shorter) {
            legalShift(delta)?.let { return it }
        }
        return null
    }

    fun routeAround(unit: MutableUnit, occupants: List<MutableUnit>): RouteOccupancy? {
        val contactLayer = layer(unit) ?: return null
        val obstacles = occupants.filter { layer(it) == contactLayer }
        if (obstacles.isEmpty()) return null
        val shape = unit.definition.movementCollision
        return RouteOccupancy { start, target, before, after ->
            val drift = subtract(target, start)
            val moving = Body(shape, after)
            obstacles.all { occupant ->
                val fixedShape = occupant.definition.movementCollision
                val offset = subtract(start, occupant.position)
                val heading = if (shape !is MovementCollisionShape.Rectangle || before == after) after
                    else when (fixedShape) {
                        is MovementCollisionShape.Circle -> RectangularTurn.againstCircle(shape, fixedShape.radius,
                            negate(offset), before, after)
                        is MovementCollisionShape.Rectangle -> RectangularTurn.againstRectangle(shape, fixedShape,
                            offset, occupant.heading, before, after)
                    }
                heading == after && moving.entry(Body(fixedShape, occupant.heading), offset, drift, 1.0) == 1.0
            }
        }
    }

    fun canChangeHeight(unit: MutableUnit, requested: Double, units: Collection<MutableUnit>,
                        map: MapDefinition, buildings: BuildingTerrain?): Boolean {
        val current = layer(unit)
        val target = layer(unit, requested)
        if (current == target) return true
        val pose = unit.position
        val body = Body(unit.definition.movementCollision, unit.heading)
        // A large step between air and submerged depth still needs clearance at the surface.
        val crossesSurface = current == null && target == Layer.SUBMERGED || target == null && current == Layer.SUBMERGED
        if (target == null && !crossesSurface) return true
        for (other in units) {
            if (other === unit) continue
            val otherLayer = layer(other) ?: continue
            if (otherLayer != target && !(crossesSurface && otherLayer == Layer.SURFACE)) continue
            val offset = subtract(pose, other.position)
            val shape = other.definition.movementCollision
            val reach = body.reach + boundingRadius(shape)
            if (abs(offset.x) > reach || abs(offset.y) > reach) continue
            if (body.overlaps(Body(shape, other.heading), offset)) return false
        }
        val buildingCells = buildings?.cells.orEmpty()
        if (current != null && (target != Layer.SURFACE || buildingCells.isEmpty())) return true
        val movement = if (unit.amphibiousState == null) MovementType.LAND else MovementType.WATER
        val grid = HullTerrainGrid.authoritative(buildings?.collisionMap ?: map, movement)
        return !overlapsTerrain(body, pose, grid) { x, y -> current == null || (x to y) in buildingCells }
    }

    private fun overlapsTerrain(body: Body, center: Position, grid: HullTerrainGrid,
                                includeCell: (Int, Int) -> Boolean = { _, _ -> true }): Boolean {
        val cell = Body(MovementCollisionShape.Rectangle(grid.tileHeight.toDouble(), grid.tileWidth.toDouble()), 0.0)
        return grid.anyBlocked(center.x - body.reach, center.x + body.reach, center.y - body.reach, center.y + body.reach) { x, y ->
            val offset = Position(center.x - (x + 0.5) * grid.tileWidth, center.y - (y + 0.5) * grid.tileHeight)
            includeCell(x, y) && body.overlaps(cell, offset)
        }
    }

    fun boundingRadius(shape: MovementCollisionShape): Double = when (shape) {
        is MovementCollisionShape.Circle -> shape.radius
        is MovementCollisionShape.Rectangle -> hypot(shape.length / 2.0, shape.width / 2.0)
    }

    private class Body(private val shape: MovementCollisionShape, heading: Double) {
        val reach = boundingRadius(shape)
        private val forward = ScreenPlane.displacement(heading, 1.0)
        private val right = Position(-forward.y, forward.x)

        fun entry(other: Body, offset: Position, drift: Position, limit: Double): Double {
            val rect = shape as? MovementCollisionShape.Rectangle
            val otherRect = other.shape as? MovementCollisionShape.Rectangle
            if (rect != null && otherRect != null) return rectangleEntry(other, rect, otherRect, offset, drift, limit)
            val rounding = 4.0 * Math.ulp(maxOf(reach, other.reach))
            return when {
                rect != null -> sweepCircle(local(negate(offset)), local(negate(drift)),
                    other.reach - rounding, rect, limit)
                otherRect != null -> sweepCircle(other.local(offset), other.local(drift),
                    reach - rounding, otherRect, limit)
                else -> sweepCircle(offset, drift, reach + other.reach - rounding, null, limit)
            }
        }

        fun overlaps(other: Body, offset: Position): Boolean {
            return clearance(other, offset) < -4.0 * Math.ulp(maxOf(reach, other.reach))
        }

        fun clearance(other: Body, offset: Position): Double {
            val rect = shape as? MovementCollisionShape.Rectangle
            val otherRect = other.shape as? MovementCollisionShape.Rectangle
            if (rect != null && otherRect != null) {
                return listOf(forward, right, other.forward, other.right).maxOf { axis ->
                    abs(dot(offset, axis)) - project(rect, axis) - other.project(otherRect, axis)
                }
            }
            return when {
                rect != null -> roundedRectangleClearance(local(offset), rect.length / 2.0, rect.width / 2.0, other.reach)
                otherRect != null -> roundedRectangleClearance(other.local(offset), otherRect.length / 2.0, otherRect.width / 2.0, reach)
                else -> hypot(offset.x, offset.y) - (reach + other.reach)
            }
        }

        private fun local(point: Position) = Position(dot(point, forward), dot(point, right))

        private fun project(rect: MovementCollisionShape.Rectangle, axis: Position) =
            abs(dot(axis, forward)) * rect.length / 2.0 + abs(dot(axis, right)) * rect.width / 2.0

        fun radiusAlong(axis: Position): Double = when (shape) {
            is MovementCollisionShape.Circle -> shape.radius
            is MovementCollisionShape.Rectangle -> project(shape, axis)
        }

        private fun rectangleEntry(other: Body, rect: MovementCollisionShape.Rectangle,
                                   otherRect: MovementCollisionShape.Rectangle, offset: Position,
                                   drift: Position, limit: Double): Double {
            var enter = 0.0
            var leave = limit
            var leastOverlap = Double.POSITIVE_INFINITY
            var leastOverlapDoesNotIncrease = false
            var exitsOnAnAxis = false
            for (axis in listOf(forward, right, other.forward, other.right)) {
                val position = dot(offset, axis)
                val projectedDrift = dot(drift, axis)
                val speed = if (abs(projectedDrift) <= 4.0 * Math.ulp(maxOf(abs(drift.x), abs(drift.y)))) 0.0 else projectedDrift
                val reach = project(rect, axis) + other.project(otherRect, axis)
                val interior = reach - 4.0 * Math.ulp(reach)
                val overlap = interior - abs(position)
                val movesOut = speed != 0.0 && (position == 0.0 || (position > 0.0) == (speed > 0.0))
                val overlapDoesNotIncrease = speed == 0.0 || movesOut
                if (overlap < leastOverlap) leastOverlapDoesNotIncrease = overlapDoesNotIncrease
                if (overlap == leastOverlap) leastOverlapDoesNotIncrease = leastOverlapDoesNotIncrease || overlapDoesNotIncrease
                leastOverlap = minOf(overlap, leastOverlap)
                exitsOnAnAxis = exitsOnAnAxis || movesOut
                if (speed == 0.0) {
                    if (overlap <= 0.0) return limit
                    continue
                }
                val low = (-interior - position) / speed
                val high = (interior - position) / speed
                enter = maxOf(enter, minOf(low, high))
                leave = minOf(leave, maxOf(low, high))
                if (enter >= leave) return limit
            }
            return if (leastOverlap > 0.0 && leastOverlapDoesNotIncrease && exitsOnAnAxis) limit else enter
        }
    }

    private fun sweepCircle(start: Position, drift: Position, radius: Double,
                            rect: MovementCollisionShape.Rectangle?, limit: Double): Double {
        // This circle pair only gets farther apart. Avoid a spurious entry at a rounded tangent.
        if (rect == null && dot(start, drift) >= 0.0) return limit
        val halfLength = (rect?.length ?: 0.0) / 2.0
        val halfWidth = (rect?.width ?: 0.0) / 2.0
        return roundRectangleEntry(start, drift, radius.coerceAtLeast(0.0), -halfLength, halfLength, -halfWidth, halfWidth, limit)
    }

    private fun dot(a: Position, b: Position) = a.x * b.x + a.y * b.y
    private fun subtract(a: Position, b: Position) = Position(a.x - b.x, a.y - b.y)
    private fun negate(point: Position) = Position(-point.x, -point.y)
}
