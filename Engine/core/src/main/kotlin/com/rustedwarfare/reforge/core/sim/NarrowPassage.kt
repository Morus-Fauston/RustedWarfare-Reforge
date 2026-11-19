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
                                 val entered: Boolean, val surveyWidth: Double?, val terrain: PlanningTerrain)

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
                     knows: (MutableUnit, MutableUnit) -> Boolean, survey: DriverPassageSurvey,
                     budget: PathWorkBudget): Set<String> {
        val moving = units.filter {
            it.alive && it.carriedBy == null && it.attachment == null && it.birthRemaining == 0f &&
                it.originMoveId != null && it.pathPoints.isNotEmpty() && it.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE &&
                UnitLocomotion.movement(it).type.let { type -> type != MovementType.AIR && type != MovementType.NONE }
        }
        val ruledOut = HashMap<String, Double>()
        val ownTerrain = HashMap<String, PlanningTerrain>(moving.size)
        val proposals = linkedMapOf<String, Approach>()
        for (unit in moving) {
            val type = UnitLocomotion.movement(unit).type
            val terrain = terrainFor(unit, type)
            ownTerrain[unit.unitId] = terrain
            val next = approach(unit, terrain, type, step, survey, budget, ruledOut = ruledOut) ?: continue
            proposals[unit.unitId] = next
        }
        val identified = proposals.values.groupBy { it.entry.passage }
        val additional = mutableListOf<Approach>()
        for ((passage, leaders) in identified) {
            val entry = leaders.first().entry
            for (unit in moving) {
                if (proposals[unit.unitId]?.entry?.passage == passage) continue
                val type = UnitLocomotion.movement(unit).type
                if (type == entry.terrainType && (entry.surveyWidth == null || ruledOut[unit.unitId] == entry.surveyWidth)) continue
                val sharedTerrain = if (type == entry.terrainType) ownTerrain[unit.unitId] else null
                if (entry.surveyWidth != null && sharedTerrain === entry.terrain &&
                    !withinSurveyReach(unit, passage, sharedTerrain, step.delta)) continue
                val width = travelWidth(unit.definition.movementCollision)
                if (leaders.none { other -> width + other.width > passage.width && UnitContact.shareLayer(unit, other.unit) &&
                        knows(unit, other.unit) && knows(other.unit, unit) }) continue
                val next = approach(unit, sharedTerrain ?: terrainFor(unit, entry.terrainType), entry.terrainType, step,
                    survey, budget, entry) ?: continue
                additional += next
            }
        }

        for (unit in units) unit.passageEntry = null
        val waiting = HashSet<String>()
        for (approaches in (proposals.values + additional).groupBy { it.entry.passage }.values) for (own in approaches) {
            // Entered units retain priority over entrance queues. If both sides
            // are already inside, the same priority also selects which mover
            // yields its own propulsion while being pushed back with its order.
            val blocked = approaches.any { other ->
                other.entry.side != own.entry.side && own.width + other.width > own.entry.passage.width &&
                    UnitContact.shareLayer(own.unit, other.unit) && knows(own.unit, other.unit) &&
                    knows(other.unit, own.unit) && priority.compare(other, own) < 0 &&
                    !independentLandings(own.unit, other.unit)
            }
            if (own.unit.passageEntry == null || blocked) own.unit.passageEntry = own.entry.copy(admitted = !blocked)
            if (blocked) waiting += own.unit.unitId
        }
        return waiting
    }

    /** Supplemental surveys have two parallel mouths and a straight throat. A
     * vehicle outside this box cannot sample it within the approach horizon. */
    private fun withinSurveyReach(unit: MutableUnit, passage: NarrowPassage, terrain: PlanningTerrain, delta: Double): Boolean {
        val mouth = passage.mouths[0]
        val far = passage.mouths[1]
        val tile = maxOf(terrain.tileWidth, terrain.tileHeight).toDouble()
        val travel = UnitLocomotion.effectiveMaximumSpeed(unit, UnitLocomotion.movement(unit)) * delta
        val reach = longestHull * 2.0 + tile * 3.0 + travel
        val along = if (mouth.horizontal) unit.position.x else unit.position.y
        val across = if (mouth.horizontal) unit.position.y else unit.position.x
        return along >= minOf(mouth.plane, far.plane) - reach && along <= maxOf(mouth.plane, far.plane) + reach &&
            across >= mouth.low - reach && across <= mouth.high + reach
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
                         survey: DriverPassageSurvey, budget: PathWorkBudget, required: PassageEntry? = null,
                         ruledOut: MutableMap<String, Double>? = null): Approach? {
        val tile = maxOf(terrain.tileWidth, terrain.tileHeight).toDouble()
        if (twoHullWidth <= minOf(terrain.tileWidth, terrain.tileHeight)) return null
        val shape = unit.definition.movementCollision
        val width = travelWidth(shape)
        if (width <= 0.0) return null
        val command = checkNotNull(unit.originMoveId)
        val previous = unit.passageEntry?.takeIf { it.commandId == command && it.terrainType == type && it.terrain === terrain }
        val halfX = axialHalf(shape, unit.heading, true)
        val halfY = axialHalf(shape, unit.heading, false)
        if (previous?.entered == true && (required == null || previous.passage == required.passage)) {
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
        // A large registered hull can merge several nearby openings into one area
        // with many mouths. Also resolve the single-file geometry for these drivers.
        val driverWidth = 2.0 * width
        val localWidth = if (required != null) required.surveyWidth else driverWidth.takeIf {
            unit.landingMember != null && shape is MovementCollisionShape.Circle &&
                it < twoHullWidth && it > minOf(terrain.tileWidth, terrain.tileHeight)
        }
        var masked = false
        var lastColumn = Int.MIN_VALUE
        var lastRow = Int.MIN_VALUE
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
                // With the same knowledge the other driver can test this exact gate,
                // without discovering the same throat again from a different tile.
                val column = kotlin.math.floor((from.x + dx * fraction) / terrain.tileWidth).toInt()
                val row = kotlin.math.floor((from.y + dy * fraction) / terrain.tileHeight).toInt()
                if (column == lastColumn && row == lastRow) continue
                lastColumn = column
                lastRow = row
                val shared = required?.takeIf { it.terrain === terrain }
                val tileInfo = if (shared == null && required?.surveyWidth == null) known.tileAt(terrain, column, row) else null
                val global = tileInfo?.passage
                val passage = when {
                    shared != null -> shared.passage.takeIf { it.contains(column, row) }
                    global != null -> { masked = true; global }
                    localWidth == null -> null
                    required == null && tileInfo?.wideBay == true -> null
                    else -> survey.at(terrain, column, row, localWidth, budget)
                } ?: continue
                if (!checked.add(passage) || (required?.let { it.passage != passage } ?: (passage.width < width))) continue
                val inside = passage.overlaps(unit.position, halfX, halfY)
                val side = passage.entryAlong(unit.position, unit.pathPoints, inside) ?: continue
                if (!inside && passage.mouths[side].depth(unit.position) !in 0.0..gate) continue
                val entry = previous?.takeIf { it.passage == passage && it.side == side }
                    ?: PassageEntry(passage, side, type, command, step.stepIndex, admitted = false, entered = false,
                        surveyWidth = localWidth.takeIf { global == null }, terrain = terrain)
                return Approach(unit, entry.copy(entered = inside), width)
            }
            distance += length
            if (distance >= horizon) break
            from = target
        }
        // Every sampled tile was ruled out for this width on the driver's own
        // terrain. Another vehicle of that size cannot reveal an untested gate.
        if (localWidth != null && !masked && !survey.shortOfBudget) ruledOut?.put(unit.unitId, localWidth)
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
