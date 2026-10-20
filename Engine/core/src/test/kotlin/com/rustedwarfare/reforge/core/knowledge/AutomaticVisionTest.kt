package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import com.rustedwarfare.reforge.core.path.PathSearchReason
import kotlin.test.*
import kotlin.math.hypot

class AutomaticVisionTest {
    private fun scenario(position: Position = Position(30.0, 30.0), sight: Int = 1): Scenario {
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 1.0, 1.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 20.0, 20.0, 20.0, 4.1, 0.25, false, false), emptySet(), sight)
        return Scenario("av-test", MapDefinition(5, 5, 20, 20, emptyList(), IntArray(25), BlockingGrid(5, 5, BooleanArray(25))),
            mapOf("tank" to unit), listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1", "p1", "tank", position, 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
    }

    @Test fun av01_initial_circle_uses_centers_and_nonpositive_sources_contribute_nothing() {
        for ((position, sight, expected) in listOf(
            Triple(Position(30.0, 30.0), 1, setOf(1 to 1, 0 to 1, 2 to 1, 1 to 0, 1 to 2)),
            Triple(Position(10.0, 10.0), 1, setOf(0 to 0, 1 to 0, 0 to 1)),
            Triple(Position(30.0, 30.0), 0, emptySet()),
            Triple(Position(30.0, 30.0), -1, emptySet()),
        )) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario(position, sight))).context
            try {
                val view = context.visionSnapshot(context.world, "p1")
                assertEquals(expected, view.basicCells.map { it.column to it.row }.toSet())
                assertEquals(view.basicCells, view.effectiveCells)
                assertEquals(expected, context.knowledgeSnapshot(context.world, "p1").cells.mapIndexedNotNull { index, cell ->
                    if (cell.visible) index % 5 to index / 5 else null
                }.toSet())
            } finally { context.close() }
        }
    }

    @Test fun av06_movement_discovers_then_next_step_replans_and_stops_at_unreachable_wall() {
        val base = scenario(Position(10.0, 110.0))
        val input = base.copy(map = MapDefinition(1, 7, 20, 20, emptyList(), IntArray(7),
            BlockingGrid(1, 7, BooleanArray(7) { it == 3 })))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertNull(context.knowledgeSnapshot(context.world, "p1").cell(0, 3).lastKnownBlocked)
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1", Position(10.0, 30.0), context.world)))
            context.drainPathDiagnostics(context.world)
            val discovered = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(Position(10.0, 90.0), discovered.units.single().position)
            assertEquals(CellKnowledge(true, true, 0), context.knowledgeSnapshot(context.world, "p1").cell(0, 3))
            assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
            val stopped = context.advance(Step(1, 1.0, "sim-tick"))
            assertNull(stopped.units.single().moveTarget)
            assertTrue(stopped.units.single().pathPoints.isEmpty())
            assertEquals(Position(10.0, 90.0), stopped.units.single().position)
            assertTrue(stopped.events.any { it.kind == "path-unreachable" })
            assertEquals(PathSearchReason.KNOWLEDGE_REPLAN, context.drainPathDiagnostics(context.world).single().reason)
            context.advance(Step(2, 1.0, "sim-tick"))
            assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
        } finally { context.close() }
    }

    @Test fun av09_memory_keeps_ground_identity_but_hardcore_forgets_on_losing_vision() {
        for (retention in TerrainRetention.entries) {
            val base = scenario(Position(10.0, 110.0))
            val input = base.copy(map = MapDefinition(1, 7, 20, 20, emptyList(), intArrayOf(1, 2, 3, 4, 5, 6, 42),
                BlockingGrid(1, 7, BooleanArray(7))), automaticVision =
                KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertEquals(42, context.knowledgeSnapshot(context.world, "p1").cell(0, 6).lastKnownGroundGid)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1", Position(10.0, 30.0), context.world)))
                context.advance(Step(0, 1.0, "sim-tick"))
                val hidden = context.knowledgeSnapshot(context.world, "p1").cell(0, 6)
                assertFalse(hidden.visible)
                assertEquals(if (retention == TerrainRetention.MEMORY) 42 else null, hidden.lastKnownGroundGid)
                assertEquals(if (retention == TerrainRetention.MEMORY) false else null, hidden.lastKnownBlocked)
            } finally { context.close() }
        }
    }

    @Test fun av12_automatic_world_rejects_external_projection_and_old_world_capabilities() {
        val input = scenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val oldWorld = context.world
        fun code(expected: String, block: () -> Unit) = assertEquals(expected, assertFailsWith<KnowledgeException>(block = block).code)
        try {
            val before = context.knowledgeSnapshot(oldWorld, "p1")
            code("automatic-vision") { context.acceptVisibleTerrain(oldWorld, "p1", listOf(VisibleTerrainCell(4, 4, true))) }
            assertEquals(before.revision, context.knowledgeSnapshot(oldWorld, "p1").revision)
            assertEquals(before.cells, context.knowledgeSnapshot(oldWorld, "p1").cells)
            code("already-enabled") { context.enableKnowledge(oldWorld, input.automaticVision!!) }
            context.close()
            code("closed") { context.visionSnapshot(oldWorld, "p1") }
            assertIs<InitResult.Accepted>(context.reopen(input.copy(units = emptyList())))
            code("stale-world") { context.visionSnapshot(oldWorld, "p1") }
            assertTrue(context.visionSnapshot(context.world, "p1").effectiveCells.isEmpty())
            assertTrue(context.knowledgeSnapshot(context.world, "p1").cells.all { it == CellKnowledge(false, null) })
            code("unknown-player") { context.visionSnapshot(context.world, "missing") }
        } finally { context.close() }
    }

    @Test fun sh04_invalid_team_and_automatic_rules_are_rejected_before_publishing_a_world() {
        val input = scenario()
        for (team in listOf("", " ", "team/a", "队伍")) {
            val result = assertIs<InitResult.Rejected>(SimulationContext.initialize(input.copy(
                players = listOf(Player("p1", PlayerControl.LOCAL, team)))))
            assertEquals("invalid-id", result.error.code)
        }
        val badRules = KnowledgeRules(TerrainRetention.HARDCORE_FORGET, PathInformationSource.ORIGINAL_COMPATIBILITY, false)
        assertEquals("incompatible-rules", assertIs<InitResult.Rejected>(SimulationContext.initialize(input.copy(automaticVision = badRules))).error.code)
        val badMap = MapDefinition(5, 5, 20, 20, emptyList(), IntArray(24), BlockingGrid(5, 5, BooleanArray(25)))
        assertEquals("invalid-map", assertIs<InitResult.Rejected>(SimulationContext.initialize(input.copy(map = badMap))).error.code)
    }

    @Test fun pv01_player_view_filters_hidden_units_intent_events_results_and_global_revision() {
        val base = scenario()
        val tank = base.unitDefinitions.getValue("tank")
        val blind = UnitDefinition("blind", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 0)
        val input = base.copy(unitDefinitions = base.unitDefinitions + ("blind" to blind),
            players = listOf(Player("p1", PlayerControl.LOCAL, "a"), Player("p2", PlayerControl.LOCAL, "a"), Player("p3", PlayerControl.LOCAL, "b")),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 30.0), 0.0),
                UnitSpawn("u2", "p2", "tank", Position(50.0, 30.0), 180.0),
                UnitSpawn("u3", "p3", "tank", Position(90.0, 90.0), 270.0),
                UnitSpawn("u0", "p1", "blind", Position(90.0, 10.0), 0.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val initial = context.observePlayer(context.world, "p1")
            assertEquals(setOf("u1", "u2"), initial.units.map { it.unitId }.toSet())
            assertNotNull(initial.knowledge)
            assertNotNull(initial.vision)
            context.submit(MoveAction("ally-move", "p2", "u2", Position(50.0, 70.0), context.world))
            context.submit(MoveAction("own-move", "p1", "u1", Position(30.0, 70.0), context.world))
            val before = context.observePlayer(context.world, "p1")
            assertEquals(Position(30.0, 70.0), before.units.single { it.unitId == "u1" }.moveTarget)
            val ally = before.units.single { it.unitId == "u2" }
            assertNull(ally.moveTarget)
            assertTrue(ally.pathPoints.isEmpty())
            assertEquals(listOf("own-move"), before.actionResults.map { it.actionId })
            val hostBefore = context.observe().sampleId
            context.submit(MoveAction("hidden-move", "p3", "u3", Position(70.0, 90.0), context.world))
            context.submit(SelectAction("hidden-error", "p3", "missing-unit", context.world))
            assertNotEquals(hostBefore, context.observe().sampleId)
            val after = context.observePlayer(context.world, "p1")
            assertEquals(before.sampleId, after.sampleId)
            assertEquals(before.units, after.units)
            assertEquals(before.events, after.events)
            assertEquals(before.actionResults, after.actionResults)
            assertEquals("not-visible", assertIs<ActionResult.Rejected>(context.submit(SelectAction("guess-hidden", "p1", "u3", context.world))).error.code)
            assertEquals("not-visible", assertIs<ActionResult.Rejected>(context.submit(SelectAction("guess-blind", "p1", "u0", context.world))).error.code)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(MoveAction("steal", "p1", "u2", Position(50.0, 70.0), context.world))).error.code)
            val all = context.observePlayer(context.world, "p3")
            assertTrue(all.actionResults.none { it.actionId in setOf("own-move", "guess-hidden", "steal") })
            assertEquals("unknown-player", assertFailsWith<KnowledgeException> { context.observePlayer(context.world, "missing") }.code)
            context.advance(Step(0, 1.0, "sim-tick"))
            val committed = context.observePlayer(context.world, "p1")
            context.submit(StopAction("hidden-stop-after-commit", "p3", "u3", context.world))
            val afterHiddenStop = context.observePlayer(context.world, "p1")
            assertEquals(committed.sampleId, afterHiddenStop.sampleId)
            assertEquals(committed.events, afterHiddenStop.events)
            assertEquals(committed.actionResults, afterHiddenStop.actionResults)
        } finally { context.close() }
    }

    @Test fun pv02_same_step_own_actions_publish_distinct_stable_sample_ids() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario())).context
        try {
            val samples = mutableListOf(context.observePlayer(context.world, "p1"))
            val actions = listOf(
                MoveAction("move", "p1", "u1", Position(30.0, 70.0), context.world),
                StopAction("stop", "p1", "u1", context.world),
                SelectAction("select", "p1", "u1", context.world),
                SelectAction("rejected", "p1", "missing", context.world))
            for (action in actions) {
                context.submit(action)
                val sample = context.observePlayer(context.world, "p1")
                assertEquals(action.actionId, sample.actionResults.last().actionId)
                assertEquals(sample, context.observePlayer(context.world, "p1"))
                assertNotEquals(samples.last().sampleId, sample.sampleId)
                samples += sample
            }
            assertEquals(samples.size, samples.map { it.sampleId }.toSet().size)
        } finally { context.close() }
    }

    @Test fun pv03_visible_stationary_stop_is_published_without_exposing_other_players_results() {
        val input = scenario().copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 30.0), 0.0),
                UnitSpawn("u2", "p2", "tank", Position(50.0, 30.0), 0.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            for (committed in listOf(false, true)) {
                if (committed) context.advance(Step(0, 1.0, "sim-tick"))
                val before = context.observePlayer(context.world, "p1")
                context.submit(StopAction("stop-$committed", "p2", "u2", context.world))
                val after = context.observePlayer(context.world, "p1")
                assertEquals(before.units, after.units)
                assertEquals(before.events + Event("movement-cancelled", "u2"), after.events)
                assertTrue(after.actionResults.isEmpty())
                assertNotEquals(before.sampleId, after.sampleId)
                assertEquals(after, context.observePlayer(context.world, "p1"))
            }
        } finally { context.close() }
    }

    @Test fun sh01_current_basic_vision_is_shared_only_with_the_same_nonnull_team_and_never_grants_control() {
        val base = scenario()
        val first = setOf(1 to 1, 0 to 1, 2 to 1, 1 to 0, 1 to 2)
        val second = setOf(4 to 1, 3 to 1, 5 to 1, 4 to 0, 4 to 2)
        for ((sharing, team1, team2) in listOf(Triple(true, "a", "a"), Triple(false, "a", "a"),
            Triple(true, null, null), Triple(true, "a", "b"))) {
            val players = mutableListOf(Player("p1", PlayerControl.LOCAL, team1), Player("p2", PlayerControl.NONE, team2),
                Player("p3", PlayerControl.NONE, "c"), Player("p4", PlayerControl.NONE, team1))
            val spawns = mutableListOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 30.0), 0.0),
                UnitSpawn("u2", "p2", "tank", Position(90.0, 30.0), 0.0), UnitSpawn("u3", "p3", "tank", Position(150.0, 30.0), 0.0))
            val input = base.copy(map = MapDefinition(9, 5, 20, 20, emptyList(), IntArray(45), BlockingGrid(9, 5, BooleanArray(45))),
                players = players, units = spawns, automaticVision = base.automaticVision!!.copy(shareAlliedVision = sharing))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            players.clear()
            spawns.clear()
            try {
                fun cells(player: String, basic: Boolean = false): Set<Pair<Int, Int>> {
                    val view = context.visionSnapshot(context.world, player)
                    return (if (basic) view.basicCells else view.effectiveCells).map { it.column to it.row }.toSet()
                }
                val allied = sharing && team1 != null && team1 == team2
                assertEquals(first, cells("p1", true))
                assertEquals(second, cells("p2", true))
                assertEquals(if (allied) first + second else first, cells("p1"))
                assertEquals(if (allied) first + second else second, cells("p2"))
                assertTrue(cells("p3").all { it.first >= 6 })
                assertTrue(cells("p4", true).isEmpty())
                assertEquals(if (sharing && team1 != null) (if (allied) first + second else first) else emptySet(), cells("p4"))
                assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(MoveAction("steal", "p1", "u2", Position(90.0, 70.0), context.world))).error.code)
                assertEquals("actor-not-controlled", assertIs<ActionResult.Rejected>(context.submit(MoveAction("uncontrolled", "p2", "u2", Position(90.0, 70.0), context.world))).error.code)
            } finally { context.close() }
        }
    }

    @Test fun sh03_shared_ground_is_retained_or_forgotten_when_the_ally_leaves_and_history_is_not_shared() {
        val base = scenario()
        for (retention in TerrainRetention.entries) {
            val input = base.copy(map = MapDefinition(7, 5, 20, 20, emptyList(), IntArray(35) { if (it == 4) 42 else 1 },
                BlockingGrid(7, 5, BooleanArray(35))), players = listOf(Player("p1", PlayerControl.LOCAL, "a"), Player("p2", PlayerControl.LOCAL, "a")),
                units = listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 30.0), 0.0), UnitSpawn("u2", "p2", "tank", Position(90.0, 30.0), 180.0)),
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, true))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertEquals(CellKnowledge(true, false, 42), context.knowledgeSnapshot(context.world, "p1").cell(4, 0))
                context.submit(MoveAction("ally-away", "p2", "u2", Position(90.0, 90.0), context.world))
                context.advance(Step(0, 1.0, "sim-tick"))
                assertFalse(VisionCell(4, 0) in context.visionSnapshot(context.world, "p1").effectiveCells)
                assertEquals(if (retention == TerrainRetention.MEMORY) CellKnowledge(false, false, 42) else CellKnowledge(false, null),
                    context.knowledgeSnapshot(context.world, "p1").cell(4, 0))
            } finally { context.close() }
        }
    }

    @Test fun av07_one_move_discovers_a_wall_and_automatically_routes_around_it_to_arrival() {
        val base = scenario(Position(10.0, 50.0))
        val original = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition("tank", original.className, 1.0, 1.0, 1, original.imagePath, 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet(), 1)
        val input = base.copy(map = MapDefinition(7, 5, 20, 20, emptyList(), IntArray(35),
            BlockingGrid(7, 5, BooleanArray(35) { it == 17 })), unitDefinitions = mapOf("tank" to tank),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(10.0, 50.0), 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.enablePathDiagnostics(context.world)
            assertNull(context.knowledgeSnapshot(context.world, "p1").cell(3, 2).lastKnownBlocked)
            context.submit(MoveAction("only-move", "p1", "u1", Position(130.0, 50.0), context.world))
            context.drainPathDiagnostics(context.world)
            var replans = 0
            var discoveredAt: Long? = null
            var arrived = false
            for (step in 0L until 600L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val unit = observation.units.single()
                val samples = context.drainPathDiagnostics(context.world)
                replans += samples.count { it.reason == PathSearchReason.KNOWLEDGE_REPLAN }
                if (discoveredAt == null && context.knowledgeSnapshot(context.world, "p1").cell(3, 2).lastKnownBlocked == true) {
                    discoveredAt = step
                    assertTrue(samples.none { it.reason == PathSearchReason.KNOWLEDGE_REPLAN })
                }
                val separation = hypot(unit.position.x - unit.position.x.coerceIn(60.0, 80.0),
                    unit.position.y - unit.position.y.coerceIn(40.0, 60.0))
                println("AV07,$step,${unit.position.x},${unit.position.y},${unit.speed},${unit.heading},${unit.movementState},${unit.moveTarget},${unit.pathPoints},replans=$replans")
                assertTrue(separation > tank.radius, "Circle overlaps/touches real wall at step $step")
                assertTrue(samples.all { it.actionId == "only-move" })
                if (unit.moveTarget == null) {
                    assertTrue(observation.events.any { it.kind == "movement-arrived" }, "Stopped without arriving at step $step")
                    arrived = true
                    break
                }
            }
            assertNotNull(discoveredAt)
            assertEquals(1, replans)
            assertTrue(arrived, "One automatic replan must arrive within 600 explicit steps")
        } finally { context.close() }
    }

    @Test fun av02_av03_continuous_centers_huge_ranges_and_walls_do_not_change_the_circle_rule() {
        for ((position, radius, expected) in listOf(
            Triple(Position(40.0, 30.0), 1, setOf(1 to 1, 2 to 1)),
            Triple(Position(30.0, 30.0), Int.MAX_VALUE, (0..4).flatMap { row -> (0..4).map { it to row } }.toSet()),
        )) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario(position, radius))).context
            try { assertEquals(expected, context.visionSnapshot(context.world, "p1").basicCells.map { it.column to it.row }.toSet()) }
            finally { context.close() }
        }
        val base = scenario(sight = 2)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(map = MapDefinition(5, 5, 20, 20,
            emptyList(), IntArray(25), BlockingGrid(5, 5, BooleanArray(25) { it == 7 || it == 11 }))))).context
        try {
            assertEquals(setOf(1 to 1, 0 to 1, 2 to 1, 1 to 0, 1 to 2, 3 to 1, 1 to 3, 0 to 0, 2 to 0, 0 to 2, 2 to 2),
                context.visionSnapshot(context.world, "p1").basicCells.map { it.column to it.row }.toSet())
            assertEquals(true, context.knowledgeSnapshot(context.world, "p1").cell(2, 1).lastKnownBlocked)
            assertTrue(context.knowledgeSnapshot(context.world, "p1").cell(3, 1).visible)
        } finally { context.close() }
    }

    @Test fun av08_hidden_truth_pair_has_identical_player_inputs_with_a_sensitive_no_fog_negative_control() {
        fun open(blocked: Boolean, automatic: Boolean): SimulationContext {
            val base = scenario()
            return assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
                map = MapDefinition(5, 5, 20, 20, emptyList(), IntArray(25) { if (it == 24 && blocked) 42 else 1 },
                    BlockingGrid(5, 5, BooleanArray(25) { it == 24 && blocked })),
                automaticVision = if (automatic) base.automaticVision else null))).context
        }
        val hiddenWall = open(true, true)
        val hiddenOpen = open(false, true)
        val truthWall = open(true, false)
        val truthOpen = open(false, false)
        try {
            for (context in listOf(hiddenWall, hiddenOpen, truthWall, truthOpen)) {
                context.submit(MoveAction("same-move", "p1", "u1", Position(90.0, 90.0), context.world))
            }
            val wallView = hiddenWall.observePlayer(hiddenWall.world, "p1")
            val openView = hiddenOpen.observePlayer(hiddenOpen.world, "p1")
            assertEquals(wallView.sampleId, openView.sampleId)
            assertEquals(wallView.units, openView.units)
            assertEquals(wallView.events, openView.events)
            assertEquals(wallView.actionResults, openView.actionResults)
            assertEquals(wallView.vision!!.effectiveCells, openView.vision!!.effectiveCells)
            assertEquals(wallView.knowledge!!.cells, openView.knowledge!!.cells)
            assertEquals(CellKnowledge(false, null), wallView.knowledge.cell(4, 4))
            assertNotEquals(truthWall.observe().units.single().pathPoints, truthOpen.observe().units.single().pathPoints,
                "The same hidden truth mutation must be detected when deliberately using the NoFog truth adapter")
            assertEquals(listOf(Position(90.0, 90.0)), wallView.units.single().pathPoints)
            assertTrue(truthWall.observe().units.single().pathPoints.isEmpty())
        } finally { listOf(hiddenWall, hiddenOpen, truthWall, truthOpen).forEach { it.close() } }
    }

    @Test fun av10_vision_uses_all_final_positions_regardless_of_unit_sort_order() {
        val base = scenario()
        for ((leftId, rightId) in listOf("a" to "z", "z" to "a")) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
                map = MapDefinition(7, 5, 20, 20, emptyList(), IntArray(35), BlockingGrid(7, 5, BooleanArray(35))),
                players = listOf(Player("p1", PlayerControl.LOCAL, "a"), Player("p2", PlayerControl.LOCAL, "a")),
                units = listOf(UnitSpawn(leftId, "p1", "tank", Position(30.0, 70.0), 0.0),
                    UnitSpawn(rightId, "p2", "tank", Position(90.0, 70.0), 0.0)),
                automaticVision = base.automaticVision!!.copy(shareAlliedVision = true)))).context
            try {
                context.submit(MoveAction("left", "p1", leftId, Position(30.0, 10.0), context.world))
                context.submit(MoveAction("right", "p2", rightId, Position(90.0, 10.0), context.world))
                context.advance(Step(0, 1.0, "sim-tick"))
                val expected = setOf(1 to 2, 0 to 2, 2 to 2, 1 to 1, 1 to 3, 4 to 2, 3 to 2, 5 to 2, 4 to 1, 4 to 3)
                for (player in listOf("p1", "p2")) assertEquals(expected,
                    context.visionSnapshot(context.world, player).effectiveCells.map { it.column to it.row }.toSet())
                assertEquals(1L, context.visionSnapshot(context.world, "p1").epoch)
            } finally { context.close() }
        }
    }

    @Test fun av11_invalid_step_preserves_views_knowledge_and_pending_work_for_a_valid_retry() {
        val base = scenario()
        val tank = base.unitDefinitions.getValue("tank")
        val fast = UnitDefinition("fast", tank.className, 1.0, 1.0, 1, tank.imagePath, 1.0,
            tank.movement.copy(moveSpeed = Double.MAX_VALUE, moveAccelerationSpeed = Double.MAX_VALUE,
                moveDecelerationSpeed = Double.MAX_VALUE), emptySet(), 0)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600) { it == 217 })),
            unitDefinitions = mapOf("tank" to tank, "fast" to fast), units = listOf(
                UnitSpawn("a", "p1", "tank", Position(110.0, 150.0), 90.0),
                UnitSpawn("z", "p1", "fast", Position(110.0, 110.0), 90.0))))).context
        try {
            context.enablePathDiagnostics(context.world)
            context.submit(MoveAction("normal", "p1", "a", Position(250.0, 150.0), context.world))
            context.submit(MoveAction("fast", "p1", "z", Position(250.0, 110.0), context.world))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(true, context.knowledgeSnapshot(context.world, "p1").cell(7, 7).lastKnownBlocked)
            context.drainPathDiagnostics(context.world)
            context.submit(SelectAction("queued-select", "p1", "a", context.world, atStep = 1))
            val host = context.observe()
            val player = context.observePlayer(context.world, "p1")
            val knowledge = context.knowledgeSnapshot(context.world, "p1")
            val vision = context.visionSnapshot(context.world, "p1")
            // D-083 blocks the old first-step extreme displacement; no mid-step overflow is reachable here.
            assertFailsWith<IllegalArgumentException> { context.advance(Step(1, 0.5, "sim-tick")) }
            assertSame(host, context.observe())
            assertEquals(knowledge.revision, context.knowledgeSnapshot(context.world, "p1").revision)
            assertEquals(knowledge.cells, context.knowledgeSnapshot(context.world, "p1").cells)
            assertEquals(vision.epoch, context.visionSnapshot(context.world, "p1").epoch)
            assertEquals(vision.effectiveCells, context.visionSnapshot(context.world, "p1").effectiveCells)
            assertEquals(player.sampleId, context.observePlayer(context.world, "p1").sampleId)
            assertEquals(player.actionResults, context.observePlayer(context.world, "p1").actionResults)
            assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
            context.submit(StopAction("stop-fast", "p1", "z", context.world))
            val recovered = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(2.0, recovered.simulationTime)
            assertTrue(recovered.actionResults.any { it.actionId == "queued-select" })
            val searches = context.drainPathDiagnostics(context.world)
            assertEquals(listOf("a"), searches.map { it.unitId })
            assertEquals(com.rustedwarfare.reforge.core.path.PathSearchReason.KNOWLEDGE_REPLAN, searches.single().reason)
            assertEquals(vision.epoch + 1L, context.visionSnapshot(context.world, "p1").epoch)
        } finally { context.close() }
    }

    @Test fun av10_heading_180_preserves_actual_double_boundary_without_epsilon() {
        val base = scenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 30.0), 180.0))))).context
        try {
            context.submit(MoveAction("down", "p1", "u1", Position(30.0, 90.0), context.world))
            assertEquals(Position(30.000000000000004, 50.0), context.advance(Step(0, 1.0, "sim-tick")).units.single().position)
            assertEquals(setOf(1 to 1, 1 to 2, 2 to 2, 1 to 3),
                context.visionSnapshot(context.world, "p1").basicCells.map { it.column to it.row }.toSet())
            assertFalse(context.knowledgeSnapshot(context.world, "p1").cell(0, 2).visible)
        } finally { context.close() }
    }

    @Test fun pv02_no_fog_view_remains_all_visible_without_other_players_intent_and_checks_world_capability_first() {
        val base = scenario()
        val input = base.copy(automaticVision = null, players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            units = base.units + UnitSpawn("u2", "p2", "tank", Position(90.0, 90.0), 270.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val foreign = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val world = context.world
        try {
            assertEquals(world.id, foreign.world.id)
            assertEquals("stale-world", assertFailsWith<KnowledgeException> { context.observePlayer(foreign.world, "missing") }.code)
            context.submit(MoveAction("other", "p2", "u2", Position(70.0, 90.0), world))
            val view = context.observePlayer(world, "p1")
            assertNull(view.knowledge)
            assertNull(view.vision)
            assertEquals(setOf("u1", "u2"), view.units.map { it.unitId }.toSet())
            assertNull(view.units.single { it.unitId == "u2" }.moveTarget)
            assertTrue(view.units.single { it.unitId == "u2" }.pathPoints.isEmpty())
            assertTrue(view.actionResults.isEmpty())
            assertFailsWith<UnsupportedOperationException> { (view.units as MutableList<UnitObservation>).clear() }
            context.close()
            assertEquals("closed", assertFailsWith<KnowledgeException> { context.observePlayer(foreign.world, "missing") }.code)
            assertIs<InitResult.Accepted>(context.reopen(input))
            assertEquals("stale-world", assertFailsWith<KnowledgeException> { context.observePlayer(world, "p1") }.code)
        } finally { context.close(); foreign.close() }
    }

    @Test fun pv03_scheduled_select_rechecks_current_visibility_when_applied() {
        val base = scenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 30.0), 0.0),
                UnitSpawn("u2", "p2", "tank", Position(50.0, 30.0), 0.0))))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select-later", "p1", "u2", context.world, atStep = 1)))
            context.submit(MoveAction("away", "p1", "u1", Position(30.0, 10.0), context.world))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "u2" })
            val result = context.advance(Step(1, 1.0, "sim-tick")).actionResults.single { it.actionId == "select-later" }
            assertEquals("not-visible", assertIs<ActionResult.Rejected>(result).error.code)
        } finally { context.close() }
    }
}
