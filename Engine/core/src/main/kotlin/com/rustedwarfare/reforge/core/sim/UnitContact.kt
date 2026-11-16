package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.TransportProfile
import kotlin.math.abs
import kotlin.math.hypot

/** Physical contacts use the current authoritative poses, independently of route knowledge. */
internal object UnitContact {
    // ENG-P014-003 starting value for the planned playtest.
    private const val PROVISIONAL_PUSH_MASS_RATIO = 8.0

    private enum class Layer { SURFACE, SUBMERGED }
    private data class Contact(val fraction: Double, val blocker: MutableUnit)
    data class Adjustment(val unit: MutableUnit, val position: Position)
    data class Translation(val position: Position, val displaced: Adjustment? = null, val legacyYield: Boolean = false)

    class YieldRequest(val collisionMap: MapDefinition, val stepIndex: Long, val unitsById: Map<String, MutableUnit>,
                       val canYield: (MutableUnit) -> Boolean)

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

    /** Per-step settlement in the simulation's existing motion update order. */
    class Translations(private val delta: Double) {
        private val startingPositions = mutableMapOf<String, Position>()
        private val maximumSpeeds = mutableMapOf<String, Double>()
        private val paired = mutableSetOf<String>()
        private val pushLimits = mutableMapOf<String, Double>()

        fun resolve(unit: MutableUnit, requested: Position, units: Collection<MutableUnit>, map: MapDefinition,
                    unitsById: Map<String, MutableUnit>, canPush: ((MutableUnit) -> Boolean)?,
                    yielding: YieldRequest? = null): Translation {
            if (unit.position == requested) return Translation(requested)
            val origin = startingPositions.getOrPut(unit.unitId) { unit.position }
            val ownLimit = pushLimits[unit.unitId]?.let { maxOf(maximumTravel(unit), it) }
            val target = if (ownLimit == null) requested else
                lastLegalPoint(unit.position, requested) { distance(origin, it) <= ownLimit + positionRoundoff(origin, it) }
            val collision = translationContact(unit, target, units) ?: return Translation(target)
            val clipped = pointAt(unit.position, target, collision.fraction)
            val blocker = collision.blocker
            if (yielding != null && yielding.canYield(blocker)) {
                sideShift(unit, target, blocker, units, yielding)?.let { return Translation(target, it, legacyYield = true) }
            }
            if (canPush == null || unit.unitId in paired || blocker.unitId in paired)
                return Translation(clipped)
            val moverMass = unit.definition.mass?.takeIf { it.isFinite() && it > 0.0 } ?: return Translation(clipped)
            val blockerMass = blocker.definition.mass?.takeIf { it.isFinite() && it > 0.0 } ?: return Translation(clipped)
            if (blockerMass / moverMass > PROVISIONAL_PUSH_MASS_RATIO || !canPush(blocker)) return Translation(clipped)
            val scale = maxOf(moverMass, blockerMass)
            val moverWeight = moverMass / scale
            val transfer = (1.0 - collision.fraction) * (moverWeight / (moverWeight + blockerMass / scale))
            val drift = subtract(target, unit.position)
            val desiredBlocker = Position(blocker.position.x + drift.x * transfer, blocker.position.y + drift.y * transfer)
            val nextContact = translationContact(blocker, desiredBlocker, units, ignored = unit)
            val blockerTarget = pointAt(blocker.position, desiredBlocker, nextContact?.fraction ?: 1.0)
            if (blockerTarget == blocker.position) return Translation(clipped)
            val blockerOrigin = startingPositions.getOrPut(blocker.unitId) { blocker.position }
            val blockerTravel = maximumTravel(blocker)
            val shape = blocker.definition.movementCollision
            val motion = UnitLocomotion.movement(blocker).type
            val terrain = if (motion != MovementType.AIR) motion
                else if (blocker.flightLandedInWater) MovementType.WATER else MovementType.LAND
            val grid = HullTerrainGrid.authoritative(map, terrain)
            val body = Body(shape, blocker.heading)

            fun moverEnd(blockerEnd: Position): Position = Position(
                clipped.x + (blockerEnd.x - blocker.position.x),
                clipped.y + (blockerEnd.y - blocker.position.y),
            )

            val blockedByLayer = layer(blocker)
            val actualBlocker = lastLegalPoint(blocker.position, blockerTarget) { candidate ->
                val moverEnd = moverEnd(candidate)
                val limit = maxOf(blockerTravel, distance(origin, moverEnd))
                distance(blockerOrigin, candidate) <= limit + positionRoundoff(blockerOrigin, candidate) &&
                    UnitAttachments.containsAdjustedSlots(unitsById, blocker, map, candidate) &&
                    clearsTerrain(blocker, shape, body, candidate, map, terrain, grid) &&
                    translationContact(blocker, candidate, units, ignored = unit) == null &&
                    units.none { other ->
                        other !== blocker && other !== unit && layer(other) == blockedByLayer &&
                            body.overlaps(Body(other.definition.movementCollision, other.heading), subtract(candidate, other.position))
                    } &&
                    translationContact(unit, moverEnd, units, Adjustment(blocker, candidate)) == null
            }
            if (actualBlocker == blocker.position) return Translation(clipped)
            val actualMover = moverEnd(actualBlocker)
            paired += unit.unitId
            paired += blocker.unitId
            pushLimits[blocker.unitId] = distance(origin, actualMover)
            return Translation(actualMover, Adjustment(blocker, actualBlocker))
        }

        fun maximumSpeed(unit: MutableUnit, movement: MovementProfile): Double =
            maximumSpeeds.getOrPut(unit.unitId) { UnitLocomotion.effectiveMaximumSpeed(unit, movement) }

        private fun maximumTravel(unit: MutableUnit): Double =
            maximumSpeed(unit, UnitLocomotion.movement(unit)) * delta
    }

    private fun pointAt(start: Position, end: Position, fraction: Double): Position = when (fraction) {
        0.0 -> start
        1.0 -> end
        else -> Position(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
    }

    private fun lastLegalPoint(start: Position, end: Position, legal: (Position) -> Boolean): Position {
        if (start == end || legal(end)) return end
        var clear = 0.0
        var blocked = 1.0
        repeat(48) {
            val fraction = (clear + blocked) * .5
            if (legal(pointAt(start, end, fraction))) clear = fraction else blocked = fraction
        }
        return pointAt(start, end, clear)
    }

    private fun distance(a: Position, b: Position): Double = hypot(a.x - b.x, a.y - b.y)

    private fun positionRoundoff(a: Position, b: Position): Double =
        4.0 * Math.ulp(maxOf(abs(a.x), abs(a.y), abs(b.x), abs(b.y)))

    private fun translationContact(unit: MutableUnit, requested: Position, units: Collection<MutableUnit>,
                                   shifted: Adjustment? = null, ignored: MutableUnit? = null): Contact? {
        val start = unit.position
        if (start == requested) return null
        val layer = layer(unit) ?: return null
        val body = Body(unit.definition.movementCollision, unit.heading)
        val drift = subtract(requested, start)
        var fraction = 1.0
        var blocker: MutableUnit? = null
        for (other in units) {
            if (unit === other || other === ignored || layer(other) != layer) continue
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
                          units: Collection<MutableUnit>, yielding: YieldRequest): Adjustment? {
        val map = yielding.collisionMap
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

        fun legalShift(delta: Double): Adjustment? {
            val end = position(delta)
            if (!UnitAttachments.containsAdjustedSlots(yielding.unitsById, blocker, map, end)) return null
            if (!clearsTerrain(blocker, blockerShape, blockerBody, end, map, surfaceTerrain, grid)) return null
            if (translationContact(blocker, end, units) != null || units.any { other ->
                other !== blocker && layer(other) == contactLayer &&
                    blockerBody.overlaps(Body(other.definition.movementCollision, other.heading), subtract(end, other.position))
            }) return null
            val shift = Adjustment(blocker, end)
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

    private fun clearsTerrain(unit: MutableUnit, shape: MovementCollisionShape, body: Body, target: Position,
                              map: MapDefinition, terrain: MovementType, grid: HullTerrainGrid): Boolean {
        val traversed = when (shape) {
            is MovementCollisionShape.Circle -> if (shape.radius > 0.0)
                CircularTerrain(shape.radius).resolve(grid, unit.position, target) == target
            else MapBounds.contains(grid.width, grid.height, target, 0.0) &&
                TerrainMotion.resolve(map, unit.position, target, movementType = terrain) == target
            is MovementCollisionShape.Rectangle ->
                RectangularTerrain(shape, unit.heading).resolve(grid, unit.position, target) == target
        }
        return traversed && !overlapsTerrain(body, target, grid)
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

    fun boundingRadius(shape: MovementCollisionShape): Double = shape.boundingRadius

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
