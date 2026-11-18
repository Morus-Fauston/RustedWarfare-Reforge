package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.path.CenterLineCells
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Publication is candidate state: every occupant, waypoint and terrain cell is resumable. */
internal class OvertakePublication(
    val input: MovePlanningInput,
    private val searched: PlannedMove,
    private val searchedStart: Position,
    private val rejoinOnly: Boolean = false,
) {
    private val member = input.members.single()
    private val body = checkNotNull(member.body)
    private val route = searched.slot?.path.orEmpty()
    private val original = body.pathPoints
    private val aim = original.firstOrNull() ?: member.start
    private val distance = sqrt(ScreenPlane.distanceSquared(member.start, aim))
    private val direction = if (distance == 0.0) Position(0.0, 0.0)
        else Position((aim.x-member.start.x)/distance, (aim.y-member.start.y)/distance)
    private val speed = UnitLocomotion.effectiveMaximumSpeed(body, UnitLocomotion.movement(body))
    private var phase = if (rejoinOnly) 10 else 0
    private var cursor = 0
    private var following = Double.POSITIVE_INFINITY
    private var crossingBlocker = false
    private var spacing = 0.0
    private var front = Double.NEGATIVE_INFINITY
    private var sideIndex = -1
    private var sideOverride: Position? = null
    private var sideSign = 1.0
    private var alternateSideTried = false
    private var requiredLateral = 0.0
    private var sidePrefix = 0.0
    private var release = 0.0
    private var pass: Position? = null
    private var rejoin = -1
    private var proposed = if (rejoinOnly) route.toMutableList() else mutableListOf<Position>()
    private var occupantsById = HashMap<String, MovePlanningOccupant>()
    private var rollback: PathSearchRollback? = null
    var currentOccupantIds: List<String> = emptyList()
        private set
    private var proposedLength = 0.0
    private var originalLength = 0.0
    private var occupantIndex = 0
    private var cellColumn = Int.MIN_VALUE
    private var cellRow = 0
    private var firstColumn = 0
    private var lastColumn = -1
    private var lastRow = -1
    var workUnits = 0
        private set
    var result: PlannedMove? = null
        private set

    fun fork(rollback: PathSearchRollback): OvertakePublication = OvertakePublication(input, searched, searchedStart, rejoinOnly).also { copy ->
        copy.phase=phase; copy.cursor=cursor; copy.following=following; copy.crossingBlocker=crossingBlocker; copy.spacing=spacing; copy.front=front
        copy.sideIndex=sideIndex; copy.sideOverride=sideOverride; copy.sideSign=sideSign; copy.alternateSideTried=alternateSideTried; copy.requiredLateral=requiredLateral; copy.sidePrefix=sidePrefix; copy.release=release; copy.pass=pass; copy.rejoin=rejoin
        copy.proposed=proposed
        copy.occupantsById=occupantsById; copy.rollback=rollback
        copy.currentOccupantIds=currentOccupantIds
        val retained=proposed.size
        rollback.record { if(proposed.size>retained) proposed.subList(retained,proposed.size).clear() }
        copy.proposedLength=proposedLength; copy.originalLength=originalLength
        copy.occupantIndex=occupantIndex; copy.cellColumn=cellColumn; copy.cellRow=cellRow
        copy.firstColumn=firstColumn; copy.lastColumn=lastColumn; copy.lastRow=lastRow
        copy.workUnits=workUnits; copy.result=result
    }

    private fun along(point: Position) =
        (point.x-member.start.x)*direction.x+(point.y-member.start.y)*direction.y

    private fun point(index: Int): Position = if(index==sideIndex && sideOverride!=null) checkNotNull(sideOverride)
        else if (index != 0) route[index] else Position(route[0].x+member.start.x-searchedStart.x, route[0].y+member.start.y-searchedStart.y)

    private fun lateral(point: Position) = -(point.x-member.start.x)*direction.y+(point.y-member.start.y)*direction.x

    private fun keep() {
        // The first grid route can choose the bank side of a convoy. Its
        // widened passing leg may not fit there even when the other side is
        // open. Check that side with the same paid geometry/publication cursor.
        if (sideIndex >= 0 && !alternateSideTried) {
            alternateSideTried = true
            sideSign = -sideSign
            sideOverride = null
            requiredLateral = maxOf(spacing, abs(lateral(point(sideIndex))))
            proposed = mutableListOf()
            proposedLength = 0.0
            originalLength = 0.0
            cellColumn = Int.MIN_VALUE
            cursor = 0
            phase = 9
            return
        }
        result=searched.copy(preserveCurrentRoute=true)
    }

    private fun prepareSide(index: Int, length: Double) {
        sideIndex=index
        sidePrefix=length
        val across=lateral(point(index))
        sideSign=if(across<0.0) -1.0 else 1.0
        requiredLateral=abs(across)
        cursor=0
        phase=9
    }

    private fun finishSide() {
        val before=point(sideIndex)
        val across=sideSign*requiredLateral-lateral(before)
        val lateralPoint=Position(before.x-direction.y*across,before.y+direction.x*across)
        sideOverride=lateralPoint
        val side=checkNotNull(sideOverride)
        val previous=if(sideIndex==0) member.start else point(sideIndex-1)
        val length=sidePrefix-sqrt(ScreenPlane.distanceSquared(previous,before))+sqrt(ScreenPlane.distanceSquared(previous,side))
        val ratio=following/speed
        val minimum=(front+ratio*(length-along(side)))/(1.0-ratio)
        release=minOf(minimum+spacing/(1.0-ratio),distance-member.radius)
        if(release<minimum || release>=distance) { keep(); return }
        val advance=(release-along(side)).coerceAtLeast(0.0)
        pass=if(advance==0.0) null else Position(side.x+direction.x*advance,side.y+direction.y*advance)
        cursor=sideIndex+1
        phase=2
    }

    /** Recheck changed occupancy against a single current snapshot in this step.
     * Unchanged static bodies reuse their paid checks; moving bodies recheck the
     * departure. If a complete fresh check cannot fit even a full allowance,
     * retain the original route rather than publish unchecked connectors.
     */
    fun validateCurrent(current: MovePlanningInput, budget: PathWorkBudget): PlannedMove? {
        val planned=checkNotNull(result)
        if(planned.preserveCurrentRoute || planned.slot==null) return planned
        val fullAllowance=budget.used==0
        val live=current.members.single()
        val currentBody=checkNotNull(live.body)
        val ids=ArrayList<String>()
        fun take(cost: Int): Boolean {
            val old=budget.used
            val allowed=budget.take(cost)
            workUnits+=budget.used-old
            return allowed
        }
        val terrain=live.terrain
        val first=proposed.first()
        if(!MapBounds.contains(terrain.widthTiles.toDouble()*terrain.tileWidth,
                terrain.heightTiles.toDouble()*terrain.tileHeight,live.start,live.radius)) return planned.copy(preserveCurrentRoute=true)
        val c0=(floor((minOf(live.start.x,first.x)-live.radius)/terrain.tileWidth)-1).coerceIn(0.0,terrain.widthTiles.toDouble()).toInt()
        val c1=(floor((maxOf(live.start.x,first.x)+live.radius)/terrain.tileWidth)+1).coerceIn(-1.0,terrain.widthTiles-1.0).toInt()
        val r0=(floor((minOf(live.start.y,first.y)-live.radius)/terrain.tileHeight)-1).coerceIn(0.0,terrain.heightTiles.toDouble()).toInt()
        val r1=(floor((maxOf(live.start.y,first.y)+live.radius)/terrain.tileHeight)+1).coerceIn(-1.0,terrain.heightTiles-1.0).toInt()
        for(row in r0..r1) for(column in c0..c1) {
            if(!take(1)) return if(fullAllowance) planned.copy(preserveCurrentRoute=true) else null
            if(terrain.isBlocked(column,row)) {
                if(!take(8)) return if(fullAllowance) planned.copy(preserveCurrentRoute=true) else null
                val shape = live.pose.shape
                val enters = if (shape is MovementCollisionShape.Circle) {
                    val reach = (shape.radius - 4.0 * Math.ulp(maxOf(terrain.widthTiles.toDouble() * terrain.tileWidth,
                        terrain.heightTiles.toDouble() * terrain.tileHeight, shape.radius))).coerceAtLeast(0.0)
                    roundRectangleEntry(live.start, Position(first.x-live.start.x, first.y-live.start.y), reach,
                        column.toDouble()*terrain.tileWidth, (column+1.0)*terrain.tileWidth,
                        row.toDouble()*terrain.tileHeight, (row+1.0)*terrain.tileHeight, 1.0) < 1.0
                } else CenterLineCells.enters(live.start,first,column,row,terrain.tileWidth,terrain.tileHeight)
                if(enters)
                    return planned.copy(preserveCurrentRoute=true)
            }
        }
        for(occupant in current.occupants) {
            if(!take(1)) return if(fullAllowance) planned.copy(preserveCurrentRoute=true) else null
            // A replacement keeps the group route's pushable-body policy.
            // Only its routing blockers constrain the path; all bodies still
            // constrain endpoint clearance and each actual hard-contact step.
            if (rejoinOnly && !occupant.obstructsRoute) continue
            ids.add(occupant.body.unitId)
            val old=occupantsById[occupant.body.unitId]
            val moving=occupant.body.moveTarget!=null
            val unchangedStatic=!moving && old?.pose==occupant.pose && old.body.moveTarget==null
            val checks=if(moving || unchangedStatic) 1 else proposed.size
            for(index in 0 until checks) {
                // The occupant entry already pays for its first AABB check;
                // subsequent segments each pay for their own broadphase.
                if(index>0 && !take(1)) return if(fullAllowance) planned.copy(preserveCurrentRoute=true) else null
                val from=if(index==0) live.start else proposed[index-1]
                val to=proposed[index]
                val reach=live.radius+occupant.pose.shape.boundingRadius
                val center=occupant.pose.position
                if(center.x<minOf(from.x,to.x)-reach || center.x>maxOf(from.x,to.x)+reach ||
                    center.y<minOf(from.y,to.y)-reach || center.y>maxOf(from.y,to.y)+reach) continue
                if(!take(8)) return if(fullAllowance) planned.copy(preserveCurrentRoute=true) else null
                if(UnitContact.routeAround(currentBody,listOf(occupant.body))?.allows(from,proposed[index],live.pose.heading,live.pose.heading)==false)
                    return planned.copy(preserveCurrentRoute=true)
            }
        }
        currentOccupantIds=ids
        return planned
    }

    /** Each paid quantum does at most one constant-size geometry check or one list entry. */
    fun advance(budget: PathWorkBudget) {
        while (result == null) {
            if (!budget.take()) return
            workUnits += 8
            when (phase) {
                10 -> result = searched
                11 -> {
                    if(cursor<route.size) proposed.add(route[cursor++])
                    else { cursor=0; phase=7 }
                }
                0 -> {
                    if (route.size<2 || speed<=0.0 || distance==0.0) { keep(); continue }
                    if (cursor == input.occupants.size) {
                        if (crossingBlocker) {
                            cursor=0; phase=11; continue
                        }
                        if (!following.isFinite() || following>=speed) { keep(); continue }
                        phase=1; cursor=0; continue
                    }
                    val occupant=input.occupants[cursor++]
                    occupantsById[occupant.body.unitId]=occupant
                    rollback?.record { occupantsById.remove(occupant.body.unitId) }
                    val other=occupant.body
                    if (occupant.obstructsRoute && UnitContact.routeAround(body,listOf(other))?.allows(member.start,aim,member.pose.heading,member.pose.heading)==false) {
                        following=minOf(following,if(other.moveTarget==null) 0.0 else
                            UnitLocomotion.effectiveMaximumSpeed(other,UnitLocomotion.movement(other)))
                        val gap=member.radius+other.definition.movementCollision.boundingRadius
                        // Equal scalar speeds imply following only on the same
                        // corridor. Known crossing intent uses the searched
                        // detour, without the constant-speed convoy prediction.
                        val forward=ScreenPlane.displacement(other.heading,1.0)
                        val intent=occupant.knownRouteAim
                        if(other.moveTarget!=null && intent!=null && (abs(lateral(intent))>gap ||
                                along(intent)<=along(other.position) ||
                                forward.x*direction.x+forward.y*direction.y<=0.0)) crossingBlocker=true
                        spacing=maxOf(spacing,gap); front=maxOf(front,along(other.position)+gap)
                    }
                }
                1 -> {
                    if(cursor==route.size) {
                        prepareSide(0,sqrt(ScreenPlane.distanceSquared(member.start,point(0))))
                        continue
                    }
                    val next=point(cursor)
                    val across=abs(lateral(next))
                    if(across>=spacing) {
                        prepareSide(0,sqrt(ScreenPlane.distanceSquared(member.start,point(0))))
                        requiredLateral=maxOf(requiredLateral,across)
                    } else cursor++
                }
                2 -> {
                    if(cursor==route.size) { keep(); continue }
                    if(along(point(cursor))>=release) { rejoin=cursor; cursor=0; phase=3 }
                    else cursor++
                }
                3 -> {
                    if(cursor<=sideIndex) proposed.add(point(cursor++))
                    else if(cursor==sideIndex+1) {
                        pass?.let(proposed::add); cursor=rejoin; phase=4
                    }
                }
                4 -> {
                    if(cursor<route.size) proposed.add(point(cursor++))
                    else { cursor=0; phase=5 }
                }
                5 -> {
                    if(cursor<proposed.size) {
                        proposedLength+=sqrt(ScreenPlane.distanceSquared(if(cursor==0) member.start else proposed[cursor-1],proposed[cursor]))
                        cursor++
                    } else { cursor=0; phase=6 }
                }
                6 -> {
                    if(cursor<original.size) {
                        originalLength+=sqrt(ScreenPlane.distanceSquared(if(cursor==0) member.start else original[cursor-1],original[cursor]))
                        cursor++
                    } else {
                        if(following>0.0 && proposedLength/speed>originalLength/following) { keep(); continue }
                        cursor=0; phase=7
                    }
                }
                7 -> {
                    if(cursor==proposed.size) {
                        result=searched.copy(slot=checkNotNull(searched.slot).copy(path=proposed)); continue
                    }
                    val from=if(cursor==0) member.start else proposed[cursor-1]
                    val to=proposed[cursor]
                    val terrain=member.terrain
                    if(cellColumn==Int.MIN_VALUE) {
                        if(!MapBounds.contains(terrain.widthTiles.toDouble()*terrain.tileWidth,
                                terrain.heightTiles.toDouble()*terrain.tileHeight,from,member.radius) ||
                            !MapBounds.contains(terrain.widthTiles.toDouble()*terrain.tileWidth,
                                terrain.heightTiles.toDouble()*terrain.tileHeight,to,member.radius)) { keep(); continue }
                        firstColumn=(floor((minOf(from.x,to.x)-member.radius)/terrain.tileWidth)-1).coerceIn(0.0,terrain.widthTiles.toDouble()).toInt()
                        lastColumn=(floor((maxOf(from.x,to.x)+member.radius)/terrain.tileWidth)+1).coerceIn(-1.0,terrain.widthTiles-1.0).toInt()
                        cellColumn=firstColumn
                        cellRow=(floor((minOf(from.y,to.y)-member.radius)/terrain.tileHeight)-1).coerceIn(0.0,terrain.heightTiles.toDouble()).toInt()
                        lastRow=(floor((maxOf(from.y,to.y)+member.radius)/terrain.tileHeight)+1).coerceIn(-1.0,terrain.heightTiles-1.0).toInt()
                    } else if(cellRow<=lastRow) {
                        if(terrain.isBlocked(cellColumn,cellRow) && CenterLineCells.enters(from,to,cellColumn,cellRow,terrain.tileWidth,terrain.tileHeight)) { keep(); continue }
                        if(cellColumn==lastColumn) { cellColumn=firstColumn; cellRow++ } else cellColumn++
                    } else { occupantIndex=0; phase=8 }
                }
                9 -> {
                    if(cursor==input.occupants.size) { finishSide(); continue }
                    val occupant=input.occupants[cursor++]
                    val other=occupant.body
                    val gap=member.radius+occupant.pose.shape.boundingRadius
                    fun include(point: Position) {
                        if(along(point) in -gap..distance+gap)
                            requiredLateral=maxOf(requiredLateral,sideSign*lateral(point)+gap)
                    }
                    // Distant stationary bodies participate in the route search,
                    // but only bodies beside this side lane can widen its clearance.
                    if(occupant.obstructsRoute && (other.moveTarget!=null ||
                            abs(lateral(other.position))<=requiredLateral+gap)) include(other.position)
                    // The next leg of an own unit is known intent. Its final
                    // destination may be across a wall, outside this corridor.
                    // This reserves passing clearance without assigning a slot.
                    occupant.knownRouteAim?.takeIf { abs(lateral(it))<=gap }?.let(::include)
                }
                8 -> {
                    if(occupantIndex==input.occupants.size) { cursor++; cellColumn=Int.MIN_VALUE; phase=7; continue }
                    val other=input.occupants[occupantIndex++].body
                    // Moving bodies remain subject to authoritative contact on every actual step.
                    // Only the departure connector needs their planning-time position.
                    if(cursor==0 || other.moveTarget==null) {
                        val from=if(cursor==0) member.start else proposed[cursor-1]
                        if(UnitContact.routeAround(body,listOf(other))?.allows(from,proposed[cursor],member.pose.heading,member.pose.heading)==false) keep()
                    }
                }
            }
        }
    }
}
