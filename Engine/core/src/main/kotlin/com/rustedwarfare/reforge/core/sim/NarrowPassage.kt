package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

internal data class PassageEntry(val passage: NarrowPassage, val side: Int, val terrainType: MovementType,
                                 val commandId: String, val waitingSince: Long, val admitted: Boolean,
                                 val entered: Boolean)

internal class PassageAdmission(definitions: Collection<UnitDefinition>) {
    // Registered dimensions do not expose which opposing units are present under fog.
    private val twoHullWidth: Double
    private val longestHull: Double

    init {
        val shapes = definitions.filter { definition -> definition.building == null &&
            UnitLocomotion.routeTypes(definition).any { it != MovementType.NONE && it != MovementType.AIR }
        }.map { it.movementCollision }
        twoHullWidth = 2.0 * (shapes.maxOfOrNull(::travelWidth) ?: 0.0)
        longestHull = shapes.maxOfOrNull(::travelLength) ?: 0.0
    }

    private data class Approach(val unit: MutableUnit, val entry: PassageEntry, val width: Double)
    // Memoized geometry is purely derived from an immutable authorized terrain snapshot.
    private val geometry = WeakHashMap<PlanningTerrain, KnownPassages>()
    private val priority = compareBy<Approach> { !it.entry.entered }.thenBy { !it.entry.admitted }
        .thenBy { it.entry.waitingSince }.thenBy { it.unit.updateKey }

    fun waitingUnits(units: Collection<MutableUnit>, step: Step,
                     terrainFor: (MutableUnit, MovementType) -> PlanningTerrain,
                     knows: (MutableUnit, MutableUnit) -> Boolean): Set<String> {
        val moving = units.filter {
            it.alive && it.carriedBy == null && it.attachment == null && it.birthRemaining == 0f &&
                it.originMoveId != null && it.pathPoints.isNotEmpty() && it.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE &&
                UnitLocomotion.movement(it).type.let { type -> type != MovementType.AIR && type != MovementType.NONE }
        }
        val proposals = linkedMapOf<String, Approach>()
        for (unit in moving) {
            val type = UnitLocomotion.movement(unit).type
            val next = approach(unit, terrainFor(unit, type), type, step) ?: continue
            proposals[unit.unitId] = next
        }
        val identified = proposals.values.toList()
        for (unit in moving) {
            if (unit.unitId in proposals) continue
            // A hover route, for example, can traverse a water passage without having
            // a chokepoint in its own mask. Verify the same geometry in its owner's
            // knowledge and check its actual route before joining the waiting group.
            for (other in identified) {
                if (!UnitContact.shareLayer(unit, other.unit) ||
                    !knows(unit, other.unit) || !knows(other.unit, unit)) continue
                val entry = other.entry
                val type = entry.terrainType
                if (UnitLocomotion.movement(unit).type == type) continue
                val next = approach(unit, terrainFor(unit, type), type, step, entry.passage) ?: continue
                proposals[unit.unitId] = next
                break
            }
        }

        for (unit in units) unit.passageEntry = null
        val waiting = HashSet<String>()
        for (approaches in proposals.values.groupBy { it.entry.passage }.values) for (own in approaches) {
            // Entered units retain priority over entrance queues. If both sides
            // are already inside, the same priority also selects which mover
            // yields its own propulsion while being pushed back with its order.
            val blocked = approaches.any { other ->
                other.entry.side != own.entry.side && own.width + other.width > own.entry.passage.width &&
                    UnitContact.shareLayer(own.unit, other.unit) && knows(own.unit, other.unit) &&
                    knows(other.unit, own.unit) && priority.compare(other, own) < 0 &&
                    !independentLandings(own.unit, other.unit)
            }
            own.unit.passageEntry = own.entry.copy(admitted = !blocked)
            if (blocked) waiting += own.unit.unitId
        }
        return waiting
    }

    private fun independentLandings(a: MutableUnit, b: MutableUnit): Boolean {
        val member = a.landingMember ?: return false
        if (b.landingMember?.group !== member.group) return false
        val aSite = member.claimed ?: return false
        val bSite = b.landingMember?.claimed ?: return false
        return a.pathPoints.singleOrNull() == aSite && b.pathPoints.singleOrNull() == bSite &&
            landingApproachesSeparate(a, aSite, b, bSite)
    }

    private fun approach(unit: MutableUnit, terrain: PlanningTerrain, type: MovementType, step: Step,
                         required: NarrowPassage? = null): Approach? {
        val tile = maxOf(terrain.tileWidth, terrain.tileHeight).toDouble()
        if (twoHullWidth <= minOf(terrain.tileWidth, terrain.tileHeight)) return null
        val shape = unit.definition.movementCollision
        val width = travelWidth(shape)
        if (width <= 0.0) return null
        val command = checkNotNull(unit.originMoveId)
        val previous = unit.passageEntry?.takeIf { it.commandId == command && it.terrainType == type }
        val halfX = axialHalf(shape, unit.heading, true)
        val halfY = axialHalf(shape, unit.heading, false)
        if (previous?.entered == true && (required == null || previous.passage == required)) {
            val passage = previous.passage
            // The route can turn away from the opening before the stern and its exit path clear.
            val outlet = passage.mouths[1 - previous.side]
            if (passage.overlaps(unit.position, halfX, halfY) ||
                outlet.overlapsOutlet(unit.position, halfX, halfY, maxOf(tile, travelLength(shape)) / 2.0))
                return Approach(unit, previous, width)
        }

        val movement = UnitLocomotion.movement(unit)
        val travel = UnitLocomotion.effectiveMaximumSpeed(unit, movement) * step.delta
        // A long hull needs space to clear its stern and reach an outside grid join before turning.
        val turnRoom = if (longestHull > tile) longestHull + tile else tile
        val gate = turnRoom + travelLength(shape) / 2.0 + travel
        val horizon = gate + tile + maxOf(halfX, halfY)
        val increment = minOf(terrain.tileWidth, terrain.tileHeight) / 2.0
        val known = geometry.getOrPut(terrain) { KnownPassages(twoHullWidth) }
        val checked = HashSet<NarrowPassage>()
        var from = unit.position
        var distance = 0.0
        for (target in unit.pathPoints) {
            val dx = target.x - from.x
            val dy = target.y - from.y
            val length = hypot(dx, dy)
            if (length == 0.0) continue
            val limit = minOf(length, horizon - distance)
            val samples = ceil(limit / increment).toInt().coerceAtLeast(1)
            val back = if (distance == 0.0) maxOf(halfX, halfY) else 0.0
            val backSamples = ceil(back / increment).toInt()
            for (index in -backSamples..samples) {
                val offset = if (index < 0) maxOf(index * increment, -back) else limit * index / samples
                val fraction = offset / length
                val point = Position(from.x + dx * fraction, from.y + dy * fraction)
                val passage = known.at(terrain, point) ?: continue
                if (!checked.add(passage) || (required?.let { it != passage } ?: (passage.width < width))) continue
                val inside = passage.overlaps(unit.position, halfX, halfY)
                val side = passage.entryAlong(unit.position, unit.pathPoints, inside) ?: continue
                if (!inside && passage.mouths[side].depth(unit.position) !in 0.0..gate) continue
                val entry = previous?.takeIf { it.passage == passage && it.side == side }
                    ?: PassageEntry(passage, side, type, command, step.stepIndex, admitted = false, entered = false)
                return Approach(unit, entry.copy(entered = inside), width)
            }
            distance += length
            if (distance >= horizon) break
            from = target
        }
        return null
    }

    private fun travelWidth(shape: MovementCollisionShape): Double = when (shape) {
        is MovementCollisionShape.Circle -> shape.radius * 2.0
        is MovementCollisionShape.Rectangle -> shape.width
    }

    private fun travelLength(shape: MovementCollisionShape): Double = when (shape) {
        is MovementCollisionShape.Circle -> shape.radius * 2.0
        is MovementCollisionShape.Rectangle -> maxOf(shape.length, shape.width)
    }

    private fun axialHalf(shape: MovementCollisionShape, heading: Double, horizontal: Boolean): Double = when (shape) {
        is MovementCollisionShape.Circle -> shape.radius
        is MovementCollisionShape.Rectangle -> {
            val forward = ScreenPlane.displacement(heading, 1.0)
            val along = if (horizontal) forward.x else forward.y
            val across = if (horizontal) forward.y else forward.x
            (abs(along) * shape.length + abs(across) * shape.width) / 2.0
        }
    }
}
