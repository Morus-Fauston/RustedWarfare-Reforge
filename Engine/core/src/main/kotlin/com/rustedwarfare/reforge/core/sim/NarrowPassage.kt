package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class PassageEntry(
    val passage: NarrowPassage,
    val side: Int,
    val terrainType: MovementType,
    val commandId: String,
    val waitingSince: Long,
    val admitted: Boolean,
    val entered: Boolean,
    val surveyWidth: Double?,
    val terrain: PlanningTerrain,
    val wasWaitingAtMouth: Boolean = false,
)

/**
 * Narrow-passage opposing-traffic admission (ENG-P016-009C).
 *
 * Strictly scoped to **true single-lane opposing contention** where two mutually visible opposing flows
 * cannot fit side-by-side (`own.width + other.width > passage.width`):
 * - Uses [WorldSpatialIndex] locally around candidate units and passage mouths instead of rasterizing
 *   whole-map paths or scanning all moving units across the map.
 * - Never inspects `landingMember`, group size, or same-direction traffic; unopposed / same-direction
 *   flows (e.g., 100-unit stone matrix) receive `passageEntry == null` and zero survey interference.
 */
internal class PassageAdmission(definitions: Collection<UnitDefinition>) {
    private val twoHullWidth: Double
    private val longestHull: Double

    init {
        val shapes = definitions.filter { definition ->
            definition.building == null &&
                UnitLocomotion.routeTypes(definition).any { it != MovementType.NONE && it != MovementType.AIR }
        }.map { it.movementCollision }
        twoHullWidth = 2.0 * (shapes.maxOfOrNull(::travelWidth) ?: 0.0)
        longestHull = shapes.maxOfOrNull(::travelLength) ?: 0.0
    }

    private data class Approach(val unit: MutableUnit, val entry: PassageEntry, val width: Double)

    private val geometry = WeakHashMap<PlanningTerrain, KnownPassages>()
    private val priority = compareBy<Approach> { !it.entry.entered }
        .thenBy { !it.entry.admitted }
        .thenBy { it.entry.waitingSince }
        .thenBy { it.unit.updateKey }

    fun waitingUnits(
        units: Collection<MutableUnit>,
        spatialIndex: WorldSpatialIndex,
        step: Step,
        terrainFor: (MutableUnit, MovementType) -> PlanningTerrain,
        knows: (MutableUnit, MutableUnit) -> Boolean,
        survey: DriverPassageSurvey,
        budget: PathWorkBudget,
        entranceTracker: MutableMap<String, PassageEntry> = HashMap(),
    ): Set<String> {
        if (twoHullWidth <= 0.0) return emptySet()
        val eligibleById = HashMap<String, MutableUnit>()
        for (u in units) {
            if (isEligibleMover(u)) {
                eligibleById[u.unitId] = u
            } else {
                if (u.passageEntry != null) u.passageEntry = null
                entranceTracker.remove(u.unitId)
            }
        }
        if (entranceTracker.isNotEmpty()) {
            val iter = entranceTracker.entries.iterator()
            while (iter.hasNext()) {
                val (id, prev) = iter.next()
                val u = eligibleById[id]
                if (u == null || u.originMoveId != prev.commandId) iter.remove()
            }
        }
        if (eligibleById.isEmpty()) return emptySet()

        val ruledOut = HashMap<String, Double>()
        val ownTerrain = HashMap<String, PlanningTerrain>(eligibleById.size)
        val proposals = LinkedHashMap<String, Approach>()
        for (unit in units) {
            if (eligibleById[unit.unitId] == null) continue
            val type = UnitLocomotion.movement(unit).type
            val terrain = terrainFor(unit, type)
            ownTerrain[unit.unitId] = terrain
            val allowSurvey = unit.passageEntry != null ||
                entranceTracker[unit.unitId]?.surveyWidth != null ||
                hasNearbyOpposingMover(unit, spatialIndex, step.delta, knows)
            val next = approach(
                unit = unit,
                terrain = terrain,
                type = type,
                step = step,
                survey = survey,
                budget = budget,
                entranceTracker = entranceTracker,
                ruledOut = ruledOut,
                allowSurvey = allowSurvey,
            )
            if (next != null) {
                proposals[unit.unitId] = next
            } else {
                entranceTracker.remove(unit.unitId)
            }
        }
        if (proposals.isEmpty()) {
            for (u in units) if (u.passageEntry != null) u.passageEntry = null
            return emptySet()
        }

        val identified = proposals.values.groupBy { it.entry.passage }
        val additional = ArrayList<Approach>()
        for ((passage, leaders) in identified) {
            val entry = leaders.first().entry
            val (minX, maxX, minY, maxY) = passageSearchBounds(passage, entry.terrain, step.delta)
            for (layer in listOf(SpatialLayer.SURFACE, SpatialLayer.SUBMERGED)) {
                val localUnits = spatialIndex.queryBounds(layer, minX, maxX, minY, maxY)
                for (i in 0 until localUnits.size) {
                    val unit = localUnits[i]
                    if (eligibleById[unit.unitId] == null) continue
                    if (proposals[unit.unitId]?.entry?.passage == passage) continue
                    val type = UnitLocomotion.movement(unit).type
                    if (type == entry.terrainType && (entry.surveyWidth == null || ruledOut[unit.unitId] == entry.surveyWidth)) continue
                    val sharedTerrain = if (type == entry.terrainType) {
                        ownTerrain.getOrPut(unit.unitId) { terrainFor(unit, type) }
                    } else null
                    if (entry.surveyWidth != null && sharedTerrain === entry.terrain &&
                        !withinSurveyReach(unit, passage, sharedTerrain, step.delta)
                    ) continue
                    val width = travelWidth(unit.definition.movementCollision)
                    if (leaders.none { other ->
                            width + other.width > passage.width &&
                                UnitContact.shareLayer(unit, other.unit) &&
                                knows(unit, other.unit) &&
                                knows(other.unit, unit)
                        }
                    ) continue
                    val next = approach(
                        unit = unit,
                        terrain = sharedTerrain ?: terrainFor(unit, entry.terrainType),
                        type = entry.terrainType,
                        step = step,
                        survey = survey,
                        budget = budget,
                        entranceTracker = entranceTracker,
                        required = entry,
                        allowSurvey = true,
                    ) ?: continue
                    additional.add(next)
                }
            }
        }

        for (unit in units) if (unit.passageEntry != null) unit.passageEntry = null
        val waiting = HashSet<String>()
        val byPassage = (proposals.values + additional).groupBy { it.entry.passage }
        for (approaches in byPassage.values) {
            val hasOpposingSingleLaneContention = approaches.size >= 2 && approaches.any { a ->
                approaches.any { b ->
                    a.entry.side != b.entry.side &&
                        a.width + b.width > a.entry.passage.width &&
                        UnitContact.shareLayer(a.unit, b.unit) &&
                        knows(a.unit, b.unit) &&
                        knows(b.unit, a.unit)
                }
            }

            for (own in approaches) {
                if (hasOpposingSingleLaneContention) {
                    val blocked = approaches.any { other ->
                        other.entry.side != own.entry.side &&
                            own.width + other.width > own.entry.passage.width &&
                            UnitContact.shareLayer(own.unit, other.unit) &&
                            knows(own.unit, other.unit) &&
                            knows(other.unit, own.unit) &&
                            priority.compare(other, own) < 0
                    }
                    val updated = own.entry.copy(
                        admitted = !blocked,
                        wasWaitingAtMouth = own.entry.wasWaitingAtMouth || (blocked && !own.entry.entered),
                    )
                    if (own.unit.passageEntry == null || blocked) {
                        own.unit.passageEntry = updated
                        entranceTracker[own.unit.unitId] = updated
                    }
                    if (blocked) waiting += own.unit.unitId
                } else if (own.entry.wasWaitingAtMouth && !own.entry.entered) {
                    // Preserve mouth re-alignment for a mover that side-stepped while waiting,
                    // until its hull enters the passage cleanly.
                    val updated = own.entry.copy(admitted = true, wasWaitingAtMouth = true)
                    if (own.unit.passageEntry == null) {
                        own.unit.passageEntry = updated
                        entranceTracker[own.unit.unitId] = updated
                    }
                } else {
                    // Unopposed / same-direction flow: keep `unit.passageEntry == null` (zero locomotion interference)
                    // while passively remembering `waitingSince` and `entered` in `entranceTracker` in case an
                    // opposing mover starts or arrives on a subsequent step.
                    entranceTracker[own.unit.unitId] = own.entry.copy(admitted = false, wasWaitingAtMouth = false)
                }
            }
        }
        return waiting
    }

    private fun isEligibleMover(u: MutableUnit): Boolean =
        u.alive && u.carriedBy == null && u.attachment == null && u.birthRemaining == 0f &&
            u.originMoveId != null && u.pathPoints.isNotEmpty() &&
            u.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE &&
            UnitLocomotion.movement(u).type.let { type -> type != MovementType.AIR && type != MovementType.NONE }

    /**
     * Local spatial pre-filter via [WorldSpatialIndex]: checks whether any mutually visible active mover
     * on the same contact layer within passage-contention reach has a non-codirectional travel vector.
     */
    private fun hasNearbyOpposingMover(
        unit: MutableUnit,
        spatialIndex: WorldSpatialIndex,
        delta: Double,
        knows: (MutableUnit, MutableUnit) -> Boolean,
    ): Boolean {
        val contactLayer = UnitContact.layer(unit) ?: return false
        val layer = SpatialLayer.fromContact(contactLayer)
        val ownAim = unit.pathPoints.firstOrNull() ?: unit.moveTarget ?: return false
        val ownGoal = unit.moveTarget ?: ownAim
        val ax = ownAim.x - unit.position.x
        val ay = ownAim.y - unit.position.y
        val gx = ownGoal.x - unit.position.x
        val gy = ownGoal.y - unit.position.y
        val ownLen = hypot(gx, gy)
        val dirX = if (ownLen > 1e-6) gx / ownLen else 0.0
        val dirY = if (ownLen > 1e-6) gy / ownLen else 0.0
        val aimLen = hypot(ax, ay)
        val aimX = if (aimLen > 1e-6) ax / aimLen else dirX
        val aimY = if (aimLen > 1e-6) ay / aimLen else dirY

        val maxSpd = UnitLocomotion.effectiveMaximumSpeed(unit, UnitLocomotion.movement(unit))
        val safeDelta = if (delta.isFinite()) delta.coerceIn(0.0, 10.0) else 1.0
        val reach = max(320.0, longestHull * 3.5 + maxSpd * safeDelta * 4.0 + 180.0)
        var found = false
        spatialIndex.forEachInBoundsUnordered(
            layer = layer,
            minX = unit.position.x - reach,
            maxX = unit.position.x + reach,
            minY = unit.position.y - reach,
            maxY = unit.position.y + reach,
        ) { other ->
            if (other === unit || !isEligibleMover(other)) return@forEachInBoundsUnordered false
            if (!UnitContact.shareLayer(unit, other)) return@forEachInBoundsUnordered false
            if (!knows(unit, other) || !knows(other, unit)) return@forEachInBoundsUnordered false
            val oGoal = other.moveTarget ?: other.pathPoints.lastOrNull() ?: return@forEachInBoundsUnordered false
            val ogx = oGoal.x - other.position.x
            val ogy = oGoal.y - other.position.y
            val oglen = hypot(ogx, ogy)
            val oDirX = if (oglen > 1e-6) ogx / oglen else 0.0
            val oDirY = if (oglen > 1e-6) ogy / oglen else 0.0
            val oAim = other.pathPoints.firstOrNull() ?: oGoal
            val oax = oAim.x - other.position.x
            val oay = oAim.y - other.position.y
            val oalen = hypot(oax, oay)
            val oAimX = if (oalen > 1e-6) oax / oalen else oDirX
            val oAimY = if (oalen > 1e-6) oay / oalen else oDirY
            // Either overall command directions or immediate approach segments are non-codirectional (dot < 0.35),
            // which covers straight head-on bridges AND L-shaped bent single-lane passages (90-deg mouths).
            val goalDot = dirX * oDirX + dirY * oDirY
            val aimDot = aimX * oAimX + aimY * oAimY
            if (goalDot < 0.35 || aimDot < 0.35) {
                found = true
                return@forEachInBoundsUnordered true
            }
            false
        }
        return found
    }

    private data class BoxBounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

    private fun passageSearchBounds(passage: NarrowPassage, terrain: PlanningTerrain, delta: Double): BoxBounds {
        val m0 = passage.mouths[0]
        val m1 = passage.mouths[1]
        val tile = max(terrain.tileWidth, terrain.tileHeight).toDouble()
        val safeDelta = if (delta.isFinite()) delta.coerceIn(0.0, 10.0) else 1.0
        val pad = longestHull * 2.5 + tile * 4.5 + max(4.0, safeDelta) * 6.0
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (m in listOf(m0, m1)) {
            if (m.horizontal) {
                minX = min(minX, m.plane - pad)
                maxX = max(maxX, m.plane + pad)
                minY = min(minY, m.low - pad)
                maxY = max(maxY, m.high + pad)
            } else {
                minX = min(minX, m.low - pad)
                maxX = max(maxX, m.high + pad)
                minY = min(minY, m.plane - pad)
                maxY = max(maxY, m.plane + pad)
            }
        }
        return BoxBounds(minX, maxX, minY, maxY)
    }

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

    private fun approach(
        unit: MutableUnit,
        terrain: PlanningTerrain,
        type: MovementType,
        step: Step,
        survey: DriverPassageSurvey,
        budget: PathWorkBudget,
        entranceTracker: Map<String, PassageEntry>,
        required: PassageEntry? = null,
        ruledOut: MutableMap<String, Double>? = null,
        allowSurvey: Boolean = true,
    ): Approach? {
        val tile = maxOf(terrain.tileWidth, terrain.tileHeight).toDouble()
        val minTile = minOf(terrain.tileWidth, terrain.tileHeight).toDouble()
        if (twoHullWidth <= minTile) return null
        val shape = unit.definition.movementCollision
        val width = travelWidth(shape)
        if (width <= 0.0) return null
        val command = checkNotNull(unit.originMoveId)
        val previous = (unit.passageEntry ?: entranceTracker[unit.unitId])?.takeIf {
            it.commandId == command && it.terrainType == type && it.terrain === terrain
        }
        val halfX = axialHalf(shape, unit.heading, true)
        val halfY = axialHalf(shape, unit.heading, false)
        if (previous?.entered == true && (required == null || previous.passage == required.passage)) {
            val passage = previous.passage
            val outlet = passage.mouths[1 - previous.side]
            if (passage.overlaps(unit.position, halfX, halfY) ||
                outlet.overlapsOutlet(unit.position, halfX, halfY, maxOf(tile, travelLength(shape)) / 2.0)
            ) {
                return Approach(unit, previous, width)
            }
        }

        val movement = UnitLocomotion.movement(unit)
        val travel = UnitLocomotion.effectiveMaximumSpeed(unit, movement) * step.delta
        val turnRoom = if (longestHull > tile) longestHull + tile else tile
        val gate = turnRoom + travelLength(shape) / 2.0 + travel
        val horizon = gate + tile + maxOf(halfX, halfY)
        val increment = minTile / 2.0
        val known = geometry.getOrPut(terrain) { KnownPassages(twoHullWidth) }
        val driverWidth = 2.0 * width
        val localWidth = if (!allowSurvey) null else if (required != null) required.surveyWidth else driverWidth.takeIf {
            shape is MovementCollisionShape.Circle && it < twoHullWidth && it > minTile
        }
        var masked = false
        var lastColumn = Int.MIN_VALUE
        var lastRow = Int.MIN_VALUE
        var checked: HashSet<NarrowPassage>? = null
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
                    global != null -> {
                        masked = true
                        global
                    }
                    localWidth == null -> null
                    required == null && tileInfo?.wideBay == true -> null
                    else -> survey.at(terrain, column, row, localWidth, budget)
                } ?: continue
                val seen = checked ?: HashSet<NarrowPassage>(4).also { checked = it }
                if (!seen.add(passage) || (required?.let { it.passage != passage } ?: (passage.width < width))) continue
                val inside = passage.overlaps(unit.position, halfX, halfY)
                val side = passage.entryAlong(unit.position, unit.pathPoints, inside) ?: continue
                if (!inside && passage.mouths[side].depth(unit.position) !in 0.0..gate) continue
                val entry = previous?.takeIf { it.passage == passage && it.side == side }
                    ?: PassageEntry(
                        passage = passage,
                        side = side,
                        terrainType = type,
                        commandId = command,
                        waitingSince = step.stepIndex,
                        admitted = false,
                        entered = false,
                        surveyWidth = localWidth.takeIf { global == null },
                        terrain = terrain,
                    )
                return Approach(unit, entry.copy(entered = inside), width)
            }
            distance += length
            if (distance >= horizon) break
            from = target
        }
        if (previous != null && previous.wasWaitingAtMouth && (required == null || previous.passage == required.passage)) {
            val passage = previous.passage
            val inside = passage.overlaps(unit.position, halfX, halfY)
            val mouth = passage.mouths[previous.side]
            val depth = mouth.depth(unit.position)
            val across = if (mouth.horizontal) unit.position.y else unit.position.x
            val stagePad = maxOf(tile * 2.5, width * 2.5)
            if (inside || (depth in -width..(gate + tile * 2.5) && across in (mouth.low - stagePad)..(mouth.high + stagePad))) {
                return Approach(unit, previous.copy(entered = inside), width)
            }
        }
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
