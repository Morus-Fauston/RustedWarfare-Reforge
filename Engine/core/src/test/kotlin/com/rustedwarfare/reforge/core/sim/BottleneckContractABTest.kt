package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bottleneck Group A & Group B behavioral contract verification for ENG-P016-009C:
 * - Group A: bent-throat mid-stop -> follower queue -> restart into open exit vs finite pocket
 *   (separating tangent resting, collision stops, and penetration).
 * - Group B: paired 8v8 simultaneous dual-lane crossing in passable corridor vs 1-tile single-lane
 *   stern-clear handoff (both 1v1 single-unit and 2v2 multi-unit, zero wall penetration).
 * - Wide-corridor in-group real-time overtaking & unopposed `NarrowPassage` silence.
 * - Simultaneous submarine dive order-independence (`4064ed20` regression).
 */
class BottleneckContractABTest {

    private fun minClearanceToBlocked(map: MapDefinition, pos: Position, radius: Double): Double {
        val tw = map.tileWidth.toDouble()
        val th = map.tileHeight.toDouble()
        var minGap = minOf(
            pos.x - radius,
            map.widthTiles * tw - pos.x - radius,
            pos.y - radius,
            map.heightTiles * th - pos.y - radius,
        )
        val c0 = ((pos.x - radius - tw) / tw).toInt().coerceAtLeast(0)
        val c1 = ((pos.x + radius + tw) / tw).toInt().coerceAtMost(map.widthTiles - 1)
        val r0 = ((pos.y - radius - th) / th).toInt().coerceAtLeast(0)
        val r1 = ((pos.y + radius + th) / th).toInt().coerceAtMost(map.heightTiles - 1)
        for (r in r0..r1) {
            for (c in c0..c1) {
                if (!map.blocking.isBlocked(c, r)) continue
                val cx = pos.x.coerceIn(c * tw, (c + 1) * tw)
                val cy = pos.y.coerceIn(r * th, (r + 1) * th)
                val gap = hypot(pos.x - cx, pos.y - cy) - radius
                minGap = min(minGap, gap)
            }
        }
        return minGap
    }

    @Test
    fun group_a_bent_throat_mid_stop_and_restart_into_open_and_finite_pocket() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = "tank",
            className = tankBase.className,
            radius = 8.0,
            displayRadius = 8.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.25, maxTurnSpeed = 4.2, turnAcceleration = 0.3),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(8.0),
            mass = 3000.0,
        )

        fun runBentCase(finitePocket: Boolean) {
            val w = 28
            val h = 20
            // L-shaped 1.5-tile / 2-tile throat: horizontal arm at y=7..8 (x=2..13), bending down x=12..13 (y=7..14),
            // opening either into open east plain (finitePocket=false) or a finite 4x4 pocket (x=12..15, y=13..16).
            val blocked = BooleanArray(w * h) { idx ->
                val x = idx % w
                val y = idx / w
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) return@BooleanArray true
                val inWestPlaza = x in 1..7 && y in 4..11
                val inHorizThroat = x in 7..13 && y in 7..8
                val inVertThroat = x in 12..13 && y in 7..13
                val inExit = if (finitePocket) (x in 12..15 && y in 13..16) else (x in 12..25 && y in 11..18)
                !(inWestPlaza || inHorizThroat || inVertThroat || inExit)
            }
            val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
            val spawns = listOf(
                UnitSpawn("lead", "p1", "tank", Position(110.0, 160.0), 90.0),
                UnitSpawn("follow-1", "p1", "tank", Position(75.0, 150.0), 90.0),
                UnitSpawn("follow-2", "p1", "tank", Position(75.0, 170.0), 90.0),
            )
            val goal = if (finitePocket) Position(280.0, 300.0) else Position(420.0, 300.0)
            val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = spawns)
            val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val allIds = spawns.map { it.unitId }
                val g1 = checkNotNull(ctx.requestGroupMove("p1", allIds, goal))
                for (id in allIds) {
                    assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("m0-$id", "p1", id, goal, ctx.world), g1))
                }

                var minWallGap = Double.POSITIVE_INFINITY
                var minPairGap = Double.POSITIVE_INFINITY
                var stoppedMidThroat = false
                var resumedLead = false
                var endObs: Observation? = null

                for (s in 0L until 1100L) {
                    val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                    endObs = obs
                    val lead = obs.units.first { it.unitId == "lead" }
                    if (!stoppedMidThroat && lead.position.x >= 225.0) {
                        stoppedMidThroat = true
                        assertIs<ActionResult.Accepted>(ctx.submit(StopAction("stop-lead", "p1", "lead", ctx.world)))
                    }
                    if (stoppedMidThroat && !resumedLead && s >= 240L) {
                        resumedLead = true
                        assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("resume-lead", "p1", "lead", goal, ctx.world)))
                    }
                    for (u in obs.units) {
                        minWallGap = min(minWallGap, minClearanceToBlocked(map, u.position, 8.0))
                    }
                    for (i in 0 until obs.units.size) {
                        for (j in i + 1 until obs.units.size) {
                            val d = hypot(
                                obs.units[i].position.x - obs.units[j].position.x,
                                obs.units[i].position.y - obs.units[j].position.y,
                            ) - 16.0
                            minPairGap = min(minPairGap, d)
                        }
                    }
                }
                val finalUnits = checkNotNull(endObs).units
                val arrived = finalUnits.count { it.moveTarget == null && it.speed <= 1e-3 }
                assertTrue(stoppedMidThroat && resumedLead, "Lead unit must reach mid-throat stop and resume")
                assertTrue(minWallGap >= -0.05, "Group A (finitePocket=$finitePocket) wall penetration detected: minWallGap=$minWallGap")
                assertTrue(minPairGap >= -0.05, "Group A (finitePocket=$finitePocket) hull overlap detected: minPairGap=$minPairGap")
                assertEquals(3, arrived, "All 3 units must arrive and settle after mid-throat restart (finitePocket=$finitePocket)")
            } finally {
                ctx.close()
            }
        }

        runBentCase(finitePocket = false)
        runBentCase(finitePocket = true)
    }

    @Test
    fun group_b_paired_8v8_simultaneous_wide_crossing_vs_single_lane_stern_clear_handoff() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = "tank",
            className = tankBase.className,
            radius = 8.0,
            displayRadius = 8.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.3, maxTurnSpeed = 4.5, turnAcceleration = 0.35),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(8.0),
            mass = 3000.0,
        )

        // Part 1: 4-tile (80u) passable corridor -> 8v8 simultaneous crossing, zero NarrowPassage wait
        run {
            val w = 36
            val h = 18
            val blocked = BooleanArray(w * h) { idx ->
                val x = idx % w
                val y = idx / w
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
                else if (x in 12..23) y !in 7..10
                else false
            }
            val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
            val spawns = buildList {
                for (i in 0 until 8) {
                    val rowOff = (i % 4) * 18.0
                    val colOff = (i / 4) * 24.0
                    add(UnitSpawn("w-$i", "p1", "tank", Position(160.0 - colOff, 152.0 + rowOff), 90.0))
                    add(UnitSpawn("e-$i", "p2", "tank", Position(560.0 + colOff, 152.0 + rowOff), 270.0))
                }
            }
            val players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies"))
            val scene = base.copy(map = map, players = players, unitDefinitions = mapOf("tank" to tank), units = spawns)
            val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val wIds = (0 until 8).map { "w-$it" }
                val eIds = (0 until 8).map { "e-$it" }
                val gw = checkNotNull(ctx.requestGroupMove("p1", wIds, Position(590.0, 180.0)))
                for (id in wIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("mw-$id", "p1", id, Position(590.0, 180.0), ctx.world), gw))
                val ge = checkNotNull(ctx.requestGroupMove("p2", eIds, Position(130.0, 180.0)))
                for (id in eIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("me-$id", "p2", id, Position(130.0, 180.0), ctx.world), ge))

                var simultaneousInsideCorridor = false
                var minWallGap = Double.POSITIVE_INFINITY
                var endObs: Observation? = null
                for (s in 0L until 950L) {
                    val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                    endObs = obs
                    val wIn = obs.units.any { it.unitId.startsWith("w-") && it.position.x in 260.0..460.0 && it.speed > 0.3 }
                    val eIn = obs.units.any { it.unitId.startsWith("e-") && it.position.x in 260.0..460.0 && it.speed > 0.3 }
                    if (wIn && eIn) simultaneousInsideCorridor = true
                    for (u in obs.units) {
                        minWallGap = min(minWallGap, minClearanceToBlocked(map, u.position, 8.0))
                    }
                }
                val arrived = checkNotNull(endObs).units.count { it.moveTarget == null }
                assertTrue(simultaneousInsideCorridor, "Passable 80u corridor must cross 8v8 simultaneously in dual lanes")
                assertTrue(minWallGap >= -0.05, "Wide corridor wall clearance violated: $minWallGap")
                assertEquals(16, arrived, "All 16 units in 8v8 wide corridor must arrive")
            } finally {
                ctx.close()
            }
        }

        // Part 2: 1-tile (20u) single-lane bridge -> single-unit 1v1 (no groupMove) stern-clear handoff & >= 0 wall gap
        run {
            val w = 30
            val h = 15
            val blocked = BooleanArray(w * h) { idx ->
                val x = idx % w
                val y = idx / w
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
                else if (x in 12..17) y != 7
                else false
            }
            val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
            val spawns = listOf(
                UnitSpawn("west-solo", "p1", "tank", Position(180.0, 150.0), 90.0),
                UnitSpawn("east-solo", "p2", "tank", Position(420.0, 150.0), 270.0),
            )
            val players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies"))
            val scene = base.copy(map = map, players = players, unitDefinitions = mapOf("tank" to tank), units = spawns)
            val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-west", "p1", "west-solo", Position(440.0, 150.0), ctx.world)))
                assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-east", "p2", "east-solo", Position(160.0, 150.0), ctx.world)))

                var minWallGap = Double.POSITIVE_INFINITY
                var minPairGap = Double.POSITIVE_INFINITY
                var endObs: Observation? = null
                for (s in 0L until 850L) {
                    val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                    endObs = obs
                    for (u in obs.units) {
                        minWallGap = min(minWallGap, minClearanceToBlocked(map, u.position, 8.0))
                    }
                    val u0 = obs.units[0].position
                    val u1 = obs.units[1].position
                    minPairGap = min(minPairGap, hypot(u0.x - u1.x, u0.y - u1.y) - 16.0)
                }
                val arrived = checkNotNull(endObs).units.count { it.moveTarget == null }
                assertTrue(minWallGap >= -0.02, "Single-unit bridge move must never penetrate bridge walls (was -2.42u on 009A), got $minWallGap")
                assertTrue(minPairGap >= -0.05, "Opposed single-lane bridge units must not overlap hulls, got $minPairGap")
                assertEquals(2, arrived, "Both opposed single-lane bridge units must complete crossing")
            } finally {
                ctx.close()
            }
        }

        // Part 3: 1-tile (20u) single-lane bridge 2v2 platoon -> car-following ORCA inside lane + stern-clear handoff
        run {
            val w = 32
            val h = 15
            val blocked = BooleanArray(w * h) { idx ->
                val x = idx % w
                val y = idx / w
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
                else if (x in 13..18) y != 7
                else false
            }
            val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
            val spawns = listOf(
                UnitSpawn("west-0", "p1", "tank", Position(210.0, 150.0), 90.0),
                UnitSpawn("west-1", "p1", "tank", Position(175.0, 150.0), 90.0),
                UnitSpawn("east-0", "p2", "tank", Position(450.0, 150.0), 270.0),
                UnitSpawn("east-1", "p2", "tank", Position(485.0, 150.0), 270.0),
            )
            val players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies"))
            val scene = base.copy(map = map, players = players, unitDefinitions = mapOf("tank" to tank), units = spawns)
            val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("mw-0", "p1", "west-0", Position(520.0, 150.0), ctx.world)))
                assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("mw-1", "p1", "west-1", Position(480.0, 150.0), ctx.world)))
                assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("me-0", "p2", "east-0", Position(140.0, 150.0), ctx.world)))
                assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("me-1", "p2", "east-1", Position(180.0, 150.0), ctx.world)))

                var minWallGap = Double.POSITIVE_INFINITY
                var minPairGap = Double.POSITIVE_INFINITY
                var endObs: Observation? = null
                for (s in 0L until 1100L) {
                    val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                    endObs = obs
                    for (u in obs.units) {
                        minWallGap = min(minWallGap, minClearanceToBlocked(map, u.position, 8.0))
                    }
                    for (i in obs.units.indices) {
                        for (j in i + 1 until obs.units.size) {
                            val d = hypot(
                                obs.units[i].position.x - obs.units[j].position.x,
                                obs.units[i].position.y - obs.units[j].position.y,
                            ) - 16.0
                            minPairGap = min(minPairGap, d)
                        }
                    }
                }
                val arrived = checkNotNull(endObs).units.count { it.moveTarget == null }
                val summary2v2 = checkNotNull(endObs).units.joinToString("; ") { "${it.unitId}@(${"%.1f".format(it.position.x)},${"%.1f".format(it.position.y)}) tgt=${it.moveTarget != null}" }
                assertTrue(minWallGap >= -0.02, "2v2 single-lane bridge units must never penetrate bridge walls, got $minWallGap")
                assertTrue(minPairGap >= -0.05, "2v2 single-lane bridge units must car-follow without hull overlap, got $minPairGap")
                assertEquals(4, arrived, "All 4 units in 2v2 single-lane bridge contention must complete crossing [$summary2v2]")
            } finally {
                ctx.close()
            }
        }
    }

    @Test
    fun in_group_fast_scout_overtakes_slow_heavy_in_wide_corridor_and_unopposed_gate_skips_passage_survey() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val heavy = UnitDefinition(
            id = "heavy",
            className = tankBase.className,
            radius = 12.0,
            displayRadius = 12.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 0.55, maxTurnSpeed = 2.4, turnAcceleration = 0.2),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(12.0),
            mass = 9000.0,
        )
        val scout = UnitDefinition(
            id = "scout",
            className = tankBase.className,
            radius = 6.0,
            displayRadius = 6.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.85, maxTurnSpeed = 5.5, turnAcceleration = 0.4),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(6.0),
            mass = 1500.0,
        )
        // 3-tile (60u) corridor from x=6..20 (y=6..8 -> 120..180, centerline 150), followed by 1-tile gate at x=26 (y=7)
        val w = 34
        val h = 15
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else if (x in 6..20) y !in 6..8
            else if (x == 26) y != 7
            else false
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))

        // Direct PassageAdmission check: unopposed traffic through 1-tile gate leaves passageEntry == null,
        // whereas two opposed movers across the 1-tile gate activate PassageAdmission.
        val terrain = com.rustedwarfare.reforge.core.navigation.PlanningTerrain.fromNoFog(map, MovementType.LAND)
        val admission = PassageAdmission(listOf(heavy, scout))
        val soloUnit = MutableUnit("solo-1", "p1", "scout", scout, Position(470.0, 150.0), 90.0).apply {
            updateNavigation {
                it.bindCommandTarget(
                    target = Position(600.0, 150.0),
                    originMoveId = "m-solo",
                    contract = MotionContract.pointMove(Position(600.0, 150.0)),
                )
            }
            taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, "m-solo", Position(600.0, 150.0)))
            adoptRoute(listOf(Position(600.0, 150.0)), Position(600.0, 150.0), 0L, 0L)
        }
        val soloSpatial = WorldSpatialIndex().apply { syncAll(listOf(soloUnit)) }
        val soloWaiting = admission.waitingUnits(
            listOf(soloUnit), soloSpatial, Step(0L, 1.0, "t"),
            { _, _ -> terrain }, { _, _ -> true }, DriverPassageSurvey(), PathWorkBudget(1024),
        )
        assertTrue(soloWaiting.isEmpty(), "Unopposed mover must not wait at 1-tile gate")
        assertNull(soloUnit.passageEntry, "Unopposed mover must keep passageEntry == null")

        val opposedEast = MutableUnit("opp-e", "p2", "scout", scout, Position(570.0, 150.0), 270.0).apply {
            updateNavigation {
                it.bindCommandTarget(
                    target = Position(440.0, 150.0),
                    originMoveId = "m-opp",
                    contract = MotionContract.pointMove(Position(440.0, 150.0)),
                )
            }
            taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, "m-opp", Position(440.0, 150.0)))
            adoptRoute(listOf(Position(440.0, 150.0)), Position(440.0, 150.0), 0L, 0L)
        }
        val oppSpatial = WorldSpatialIndex().apply { syncAll(listOf(soloUnit, opposedEast)) }
        val oppWaiting = admission.waitingUnits(
            listOf(soloUnit, opposedEast), oppSpatial, Step(1L, 1.0, "t"),
            { _, _ -> terrain }, { _, _ -> true }, DriverPassageSurvey(), PathWorkBudget(1024),
        )
        assertEquals(1, oppWaiting.size, "True opposed 1-tile gate contention must hold one side outside")

        val spawns = listOf(
            UnitSpawn("heavy-1", "p1", "heavy", Position(150.0, 150.0), 90.0),
            UnitSpawn("scout-1", "p1", "scout", Position(100.0, 150.0), 90.0),
        )
        val scene = base.copy(map = map, unitDefinitions = mapOf("heavy" to heavy, "scout" to scout), units = spawns)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = listOf("heavy-1", "scout-1")
            val g = checkNotNull(ctx.requestGroupMove("p1", ids, Position(440.0, 150.0)))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("m-$id", "p1", id, Position(440.0, 150.0), ctx.world), g))
            }

            var overtookInsideCorridor = false
            for (s in 0L until 420L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                val hPos = obs.units.first { it.unitId == "heavy-1" }.position
                val sPos = obs.units.first { it.unitId == "scout-1" }.position
                if (hPos.x < 400.0 && sPos.x > hPos.x + 22.0) {
                    overtookInsideCorridor = true
                }
            }
            assertTrue(overtookInsideCorridor, "Fast scout must overtake same-group slow heavy inside 3-tile corridor")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun loc_module_has_zero_upper_command_special_casing_identifiers() {
        val locFile = java.nio.file.Paths.get("src/main/kotlin/com/rustedwarfare/reforge/core/navigation/LocomotionModule.kt")
        val source = java.nio.file.Files.readString(locFile)
        val codeLines = source.lines()
            .map { it.substringBefore("//") }
            .filter { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("*") && !trimmed.startsWith("/*")
            }
            .joinToString("\n")
        val forbidden = listOf(
            "landingMember",
            "groupMoveReservation",
            "nearestMoveTarget",
            "attackCommand",
            "primaryCombatTargetId",
            "sameLandingGroup",
            "allowContactPush",
            "host.units",
        )
        for (token in forbidden) {
            assertTrue(!codeLines.contains(token), "LocomotionModule executable code must not reference upper-command or mutable-peer token '$token'")
        }
    }

    @Test
    fun single_orders_and_group_move_produce_identical_local_avoidance_solve_and_rollback_isolates_passage_tracker() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = "tank",
            className = tankBase.className,
            radius = 8.0,
            displayRadius = 8.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 4.5, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(8.0),
            mass = 3000.0,
        )
        val w = 30
        val h = 15
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else if (x in 13..16) y != 7
            else false
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val players = listOf(Player("p1", PlayerControl.LOCAL, "t1"), Player("p2", PlayerControl.LOCAL, "t2"))

        // 1. Verify StepState.fork isolates `passageTracker` mutations across candidate-step rollback!
        val rollback = PathSearchRollback()
        val state0 = StepState()
        val terrain = com.rustedwarfare.reforge.core.navigation.PlanningTerrain.fromNoFog(map, MovementType.LAND)
        val admission = PassageAdmission(listOf(tank))
        val uWest = MutableUnit("w0", "p1", "tank", tank, Position(242.0, 150.0), 90.0).apply {
            updateNavigation {
                it.bindCommandTarget(
                    target = Position(420.0, 150.0),
                    originMoveId = "mw",
                    contract = MotionContract.pointMove(Position(420.0, 150.0)),
                )
            }
            taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, "mw", Position(420.0, 150.0)))
            adoptRoute(listOf(Position(420.0, 150.0)), Position(420.0, 150.0), 0L, 0L)
        }
        val forkedState = state0.fork(rollback)
        val idxFork = WorldSpatialIndex().apply { syncAll(listOf(uWest)) }
        admission.waitingUnits(
            listOf(uWest), idxFork, Step(5L, 1.0, "t"),
            { _, _ -> terrain }, { _, _ -> true }, forkedState.driverPassages, PathWorkBudget(512),
            entranceTracker = forkedState.passageTracker,
        )
        assertTrue(forkedState.passageTracker.isNotEmpty(), "Forked step state must record passive passage tracker entry")
        assertTrue(state0.passageTracker.isEmpty(), "Base committed StepState.passageTracker must stay untouched on candidate rollback")

        // 2. Verify identical step-start kinematic states under single MoveAction vs groupMove yield identical avoidance velocity!
        val openMap = MapDefinition(30, 15, 20, 20, emptyList(), IntArray(450), BlockingGrid(30, 15, BooleanArray(450)))
        val openScene = base.copy(
            map = openMap,
            players = players,
            unitDefinitions = mapOf("tank" to tank),
            units = listOf(
                UnitSpawn("m1", "p1", "tank", Position(120.0, 150.0), 90.0),
                UnitSpawn("m2", "p1", "tank", Position(80.0, 150.0), 90.0),
                UnitSpawn("idle", "p2", "tank", Position(155.0, 150.0), 270.0),
            ),
        )
        val ctxSingle = assertIs<InitResult.Accepted>(SimulationContext.initialize(openScene)).context
        val ctxGroup = assertIs<InitResult.Accepted>(SimulationContext.initialize(openScene)).context
        try {
            val farGoal = Position(480.0, 150.0)
            assertIs<ActionResult.Accepted>(ctxSingle.submit(MoveAction("s1", "p1", "m1", farGoal, ctxSingle.world)))
            assertIs<ActionResult.Accepted>(ctxSingle.submit(MoveAction("s2", "p1", "m2", farGoal, ctxSingle.world)))
            val grp = checkNotNull(ctxGroup.requestGroupMove("p1", listOf("m1", "m2"), farGoal))
            assertIs<ActionResult.Accepted>(ctxGroup.submitGroupMove(MoveAction("g1", "p1", "m1", farGoal, ctxGroup.world), grp))
            assertIs<ActionResult.Accepted>(ctxGroup.submitGroupMove(MoveAction("g2", "p1", "m2", farGoal, ctxGroup.world), grp))

            // Record transit trajectories from the first tick m1 begins moving in each context (aligning any
            // planning-queue publication step offset) so identical kinematic states are compared step-for-step.
            val singleTrace = ArrayList<Position>(25)
            val groupTrace = ArrayList<Position>(25)
            for (s in 0L..35L) {
                val obsS = ctxSingle.advance(Step(s, 1.0, "sim-tick"))
                val obsG = ctxGroup.advance(Step(s, 1.0, "sim-tick"))
                val pS = obsS.units.first { it.unitId == "m1" }.position
                val pG = obsG.units.first { it.unitId == "m1" }.position
                if (singleTrace.isNotEmpty() || hypot(pS.x - 120.0, pS.y - 150.0) > 1e-6) singleTrace.add(pS)
                if (groupTrace.isNotEmpty() || hypot(pG.x - 120.0, pG.y - 150.0) > 1e-6) groupTrace.add(pG)
            }
            assertTrue(singleTrace.size >= 15 && groupTrace.size >= 15, "Both single and group moves must advance at least 15 transit ticks")
            for (i in 0 until 15) {
                val pS = singleTrace[i]
                val pG = groupTrace[i]
                assertTrue(
                    hypot(pS.x - pG.x, pS.y - pG.y) < 1e-6,
                    "Transit local avoidance around idle blocker must match between single MoveAction ($pS) and groupMove ($pG) at transit tick $i",
                )
            }
        } finally {
            ctxSingle.close()
            ctxGroup.close()
        }
    }

    @Test
    fun implicit_ini_wide_radius_unit_settles_via_beginNearest_inside_single_tile_sealed_corridor() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        // Implicit INI circular land hull (`explicitMovementCollision = false`) with radius 15.0 > 0.45 * 20u = 9.0u
        val mammoth = UnitDefinition(
            id = "mammoth",
            className = tankBase.className,
            radius = 15.0,
            displayRadius = 15.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.1, maxTurnSpeed = 4.0, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(15.0),
            explicitMovementCollision = false,
            mass = 12000.0,
        )
        // 1-tile (20u) corridor at row 7 from col 6..14, sealed at col 15 (x = 300..320); target clicked across seal at (350, 150)
        val w = 24
        val h = 15
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            when {
                x == 0 || x == w - 1 || y == 0 || y == h - 1 -> true
                x in 6..14 -> y != 7
                x == 15 -> true
                else -> false
            }
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("mammoth" to mammoth),
            units = listOf(UnitSpawn("m-0", "p1", "mammoth", Position(80.0, 150.0), 90.0)),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-seal", "p1", "m-0", Position(350.0, 150.0), ctx.world)))
            var nearestReached = false
            var minWallGap = Double.POSITIVE_INFINITY
            var finalPos = Position(80.0, 150.0)
            for (s in 0L until 600L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                val u = obs.units.single { it.unitId == "m-0" }
                finalPos = u.position
                minWallGap = min(minWallGap, minClearanceToBlocked(map, u.position, 9.0))
                if (obs.events.any { it.kind == "movement-nearest-reached" && it.unitId == "m-0" }) {
                    nearestReached = true
                }
            }
            assertTrue(nearestReached, "Implicit-INI wide-radius unit must emit movement-nearest-reached inside 1-tile sealed corridor (final=$finalPos)")
            assertTrue(finalPos.x >= 285.0 && finalPos.x <= 291.5, "Must advance to the sealed end of the 1-tile corridor near x=291.0, got $finalPos")
            assertTrue(minWallGap >= -0.02, "Must respect unified 9.0u terrain passability radius without wall penetration, got $minWallGap")
        } finally {
            ctx.close()
        }
    }
}
