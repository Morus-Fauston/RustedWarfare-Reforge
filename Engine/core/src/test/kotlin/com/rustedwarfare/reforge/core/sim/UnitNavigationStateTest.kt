package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UnitNavigationStateTest {
    private fun sampleUnit(id: String = "u1"): MutableUnit {
        val def = TestContentFactory.openScenario().unitDefinitions.getValue("tank")
        return MutableUnit(id, "p1", "tank", def, Position(100.0, 100.0), 90.0)
    }

    @Test
    fun group_reservation_holds_single_track_landing_and_clears_atomically() {
        val world = WorldHandle("nav-test")
        val group = GroupMoveRequest(world, 1L, "p1", Position(200.0, 200.0), setOf("u1"))
        val landing = LandingMember(group)

        var state = UnitNavigationState.EMPTY
        assertNull(state.groupReservation)
        assertNull(state.landingMember)

        state = state.withLandingMember(landing)
        assertSame(landing, state.landingMember)
        assertSame(group, state.groupReservation?.group)
        assertTrue(state.groupReservation?.active == true)

        state = state.copy(
            moveTarget = Position(200.0, 200.0),
            nearestMoveTarget = Position(210.0, 200.0),
            nearestMoveIsGroup = true,
            originMoveId = "act-1",
            originAtStep = 5L,
            lastPlannedKnowledgeRevision = 9L,
            lastPlannedBuildingRevision = 4L,
            nearTargetTime = 12.0,
            stalledRouteTicks = 7L,
            hardContactLimitedTicks = 3L,
            routeTranslationClipped = true,
            followsManeuverRoute = true,
            maneuverTurnWaypoint = Position(150.0, 150.0),
            lastManeuverContactRepair = 6L,
            routeOccupantIds = listOf("u2"),
            localProgressTime = 4.5,
        )

        val paused = state.pauseRoute()
        assertNull(paused.moveTarget)
        assertTrue(paused.pathPoints.isEmpty())
        assertNull(paused.groupReservation)
        assertNull(paused.landingMember)
        assertNull(paused.nearestMoveTarget)
        assertFalse(paused.nearestMoveIsGroup)
        assertNull(paused.originMoveId)
        assertNull(paused.originAtStep)
        assertEquals(-1L, paused.lastPlannedKnowledgeRevision)
        assertEquals(-1L, paused.lastPlannedBuildingRevision)
        assertEquals(0.0, paused.nearTargetTime)
        assertNull(paused.localMotion)
        assertNull(paused.routeContinuation)
        assertEquals(0L, paused.stalledRouteTicks)
        assertEquals(0L, paused.hardContactLimitedTicks)
        assertFalse(paused.routeTranslationClipped)
        assertFalse(paused.followsManeuverRoute)
        assertNull(paused.maneuverTurnWaypoint)
        assertNull(paused.lastManeuverContactRepair)
        assertTrue(paused.routeOccupantIds.isEmpty())
        assertEquals(0.0, paused.localProgressTime)
    }

    @Test
    fun waypoint_transitions_are_immutable_and_do_not_mutate_prior_snapshots() {
        val raw = mutableListOf(Position(120.0, 100.0), Position(160.0, 100.0), Position(200.0, 100.0))
        val adopted = UnitNavigationState.EMPTY.adoptRoute(
            points = raw,
            knowledgeRevision = 4L,
            buildingRevision = 2L,
            occupantIds = listOf("u2"),
        )
        raw.add(Position(999.0, 999.0))
        assertEquals(3, adopted.pathPoints.size)
        assertEquals(1L, adopted.adoptedRouteRevision)
        assertFailsWith<UnsupportedOperationException> {
            (adopted.pathPoints as MutableList<Position>).add(Position(0.0, 0.0))
        }

        val prepended = adopted.prependWaypoints(listOf(Position(110.0, 105.0)), resetLocalSegment = true)
        assertEquals(4, prepended.pathPoints.size)
        assertEquals(Position(110.0, 105.0), prepended.pathPoints.first())
        assertEquals(3, adopted.pathPoints.size)

        val replaced = prepended.replaceFrontWaypoint(Position(112.0, 102.0), resetLocalSegment = true)
        assertEquals(Position(112.0, 102.0), replaced.pathPoints.first())
        assertEquals(Position(110.0, 105.0), prepended.pathPoints.first())

        val popped = replaced.popFrontWaypoint(clearLocalSegment = true, maneuverTurnWaypoint = Position(112.0, 102.0))
        assertEquals(3, popped.pathPoints.size)
        assertEquals(Position(120.0, 100.0), popped.pathPoints.first())
        assertEquals(Position(112.0, 102.0), popped.maneuverTurnWaypoint)
        assertEquals(4, replaced.pathPoints.size)
    }

    @Test
    fun mark_arrived_clears_route_and_preserves_parked_landing_and_arrival_metadata() {
        val world = WorldHandle("arrive-test")
        val group = GroupMoveRequest(world, 2L, "p1", Position(220.0, 100.0), setOf("u1"))
        val parked = LandingMember(group, parked = true)
        val arrival = MoveArrival(
            originalTarget = Position(220.0, 100.0),
            stoppedAt = Position(219.5, 100.0),
            distance = 0.5,
            group = true,
            stepIndex = 40L,
        )
        val moving = UnitNavigationState.EMPTY.adoptRoute(listOf(Position(220.0, 100.0)), knowledgeRevision = 1L).copy(
            moveTarget = Position(220.0, 100.0),
            stalledRouteTicks = 5L,
            routeTranslationClipped = true,
        )
        val arrived = moving.markArrived(parkedLanding = parked, arrival = arrival)
        assertNull(arrived.moveTarget)
        assertTrue(arrived.pathPoints.isEmpty())
        assertEquals(0L, arrived.stalledRouteTicks)
        assertFalse(arrived.routeTranslationClipped)
        assertSame(parked, arrived.landingMember)
        assertSame(group, arrived.groupReservation?.group)
        assertEquals(arrival, arrived.moveArrival)
    }

    @Test
    fun step_state_fork_shares_navigation_reference_in_o1_and_isolates_rollback() {
        val unit = sampleUnit()
        val points = listOf(Position(140.0, 100.0), Position(180.0, 100.0))
        unit.adoptRoute(points, logicalTarget = Position(180.0, 100.0), knowledgeRevision = 3L)
        unit.moveTarget = Position(180.0, 100.0)
        val originalNav = unit.nav
        val state = StepState(
            units = linkedMapOf(unit.unitId to unit),
            spatialIndex = WorldSpatialIndex().apply { syncAll(listOf(unit)) },
        )

        val forked = state.fork(PathSearchRollback())
        val forkedUnit = forked.units.getValue(unit.unitId)
        assertNotSame(unit, forkedUnit)
        assertSame(originalNav, forkedUnit.nav)

        forkedUnit.nav = forkedUnit.nav.popFrontWaypoint(clearLocalSegment = true)
        assertEquals(1, forkedUnit.pathPoints.size)
        assertEquals(2, state.units.getValue(unit.unitId).pathPoints.size)
        assertSame(originalNav, state.units.getValue(unit.unitId).nav)
    }

    @Test
    fun mutable_unit_setters_defensively_freeze_lists_and_pause_preserves_terrain_history() {
        val unit = sampleUnit()
        val rawPath = mutableListOf(Position(120.0, 100.0))
        val rawOccupants = mutableListOf("u2")
        unit.pathPoints = rawPath
        unit.routeOccupantIds = rawOccupants
        rawPath.add(Position(999.0, 999.0))
        rawOccupants.add("u3")
        assertEquals(1, unit.pathPoints.size)
        assertEquals(1, unit.routeOccupantIds.size)

        unit.terrainCorrectionTime = 0.75
        unit.terrainClearSteps = 2
        unit.pauseRoute()
        assertEquals(0.75, unit.terrainCorrectionTime)
        assertEquals(2, unit.terrainClearSteps)
    }

    @Test
    fun mark_arrived_with_null_arrival_preserves_prior_arrival() {
        val world = WorldHandle("arrive-owner-test")
        val group = GroupMoveRequest(world, 3L, "p1", Position(240.0, 100.0), setOf("u1"))
        val parked = LandingMember(group, parked = true)
        val prior = MoveArrival(
            originalTarget = Position(200.0, 100.0),
            stoppedAt = Position(199.0, 100.0),
            distance = 1.0,
            group = false,
            stepIndex = 20L,
        )
        val moving = UnitNavigationState.EMPTY.copy(
            moveTarget = Position(240.0, 100.0),
            moveArrival = prior,
        )
        val arrived = moving.markArrived(parkedLanding = parked, arrival = null)
        assertSame(parked, arrived.landingMember)
        assertSame(group, arrived.groupReservation?.group)
        assertEquals(prior, arrived.moveArrival)
    }
}
