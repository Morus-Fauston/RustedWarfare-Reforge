package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.navigation.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.*

/**
 * Contract test suite for ENG-P017-010 (Cohort Escort Coordinator & Explicit Alt Cohort Move):
 * 1. Island naval contrast: normal right-click shortcuts gunboats through the narrow south strait,
 *    while explicit `cohortEscort = true` (`Alt`) binds gunboats to the battleship's wide north channel.
 * 2. Reversed vanguard overtake escort: 5 fast fragile light units start ahead of 5 slow heavy units;
 *    under `Alt` escort, heavies overtake to lead the wavefront while lights pace behind with zero
 *    false-positive 2s red stalls and zero stall replans (while genuine wall-trapped units still turn red).
 * 3. Cross-layer air-ground escort + immediate dynamic decoupling on death or re-order.
 * 4. Disconnected strictest-clearance fallback split without infinite deadlock.
 * 5. Narrow-throat single-file suspension + post-exit regrouping + zero bias on homogeneous squads.
 * 6. Rectangular naval hull + circular gunboat `Alt` escort around island.
 */
class CohortEscortAndAltMoveTest {

    private fun derivedDefs(): Map<String, UnitDefinition> =
        ScenarioCatalog.deriveBenchmarkDefinitions(TestContentFactory.openScenario().unitDefinitions)

    private fun runIslandNaval(cohortEscort: Boolean, maxSteps: Long = 2800L): Pair<List<Observation>, List<PathSearchSample>> {
        val defs = derivedDefs()
        val entry = checkNotNull(ScenarioCatalog.byId("cohort-island-naval-escort"))
        val scene = entry.buildScenario(defs)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        ctx.enablePathDiagnostics(ctx.world)
        val obs = ArrayList<Observation>(maxSteps.toInt() + 1)
        val diag: List<PathSearchSample>
        try {
            val ids = listOf("bs-0") + (0 until 6).map { "gb-$it" }
            val goal = Position(960.0, 480.0)
            val group = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = cohortEscort))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(
                    ctx.submitGroupMove(MoveAction("m-$cohortEscort-$id", "p1", id, goal, ctx.world, cohortEscort = cohortEscort), group),
                )
            }
            obs += ctx.observe()
            for (step in 0L until maxSteps) {
                val frame = ctx.advance(Step(step, 1.0, "sim-tick"))
                obs += frame
                if (step >= 20L && frame.units.all { it.moveTarget == null && abs(it.speed) <= 1e-4 }) break
            }
            diag = ctx.drainPathDiagnostics(ctx.world)
        } finally {
            ctx.close()
        }
        return obs to diag
    }

    @Test
    fun island_naval_contrast_normal_shortcuts_south_while_alt_escorts_battleship_north() {
        val defs = derivedDefs()
        val entry = checkNotNull(ScenarioCatalog.byId("cohort-island-naval-escort"))

        val (normalObs, _) = runIslandNaval(cohortEscort = false)
        val normalSeq = DiagnosticSceneProjector.projectSequence(normalObs, defs, gates = entry.gates)
        val normalSouthExits = normalSeq.gateSummaries.first { it.gateId == "south-narrow-strait" }.sternExitedCount
        val normalNorthExits = normalSeq.gateSummaries.first { it.gateId == "north-wide-channel" }.sternExitedCount
        assertEquals(7, normalSeq.frames.last().arrivedCount, "Normal move must arrive 7/7")
        assertTrue(normalSouthExits >= 5, "Normal move should let gunboats shortcut south strait, got south=$normalSouthExits north=$normalNorthExits")
        assertTrue(normalNorthExits >= 1, "Battleship must still sail north channel, got north=$normalNorthExits")

        val normalGunboatFirstArrive = normalObs.withIndex().drop(20).first { (_, frame) ->
            frame.units.filter { it.unitId.startsWith("gb-") }.all { it.moveTarget == null }
        }.index
        val normalBattleshipArrive = normalObs.withIndex().drop(20).first { (_, frame) ->
            frame.units.single { it.unitId == "bs-0" }.moveTarget == null
        }.index
        assertTrue(
            normalBattleshipArrive - normalGunboatFirstArrive > 300,
            "Normal right-click gunboats should arrive far ahead of battleship (gb=$normalGunboatFirstArrive, bs=$normalBattleshipArrive)",
        )

        val (escortObs, escortDiag) = runIslandNaval(cohortEscort = true)
        val escortSeq = DiagnosticSceneProjector.projectSequence(escortObs, defs, gates = entry.gates)
        val failures = entry.assertOutcome(escortSeq)
        assertTrue(failures.isEmpty(), "Alt island escort assertions failed: $failures")

        // Verify escort gunboats never triggered stall replans while pacing behind battleship.
        val stallReplans = escortDiag.count { it.reason == PathSearchReason.ROUTE_REPAIR }
        assertEquals(0, stallReplans, "Alt escort pacing must not trigger stall replanning, got $stallReplans")

        // Verify arrival cohesion: gunboats arrive alongside battleship, not 400+ ticks ahead.
        val escortGunboatArrive = escortObs.withIndex().drop(20).first { (_, frame) ->
            frame.units.filter { it.unitId.startsWith("gb-") }.all { it.moveTarget == null }
        }.index
        val escortBattleshipArrive = escortObs.withIndex().drop(20).first { (_, frame) ->
            frame.units.single { it.unitId == "bs-0" }.moveTarget == null
        }.index
        assertTrue(
            abs(escortBattleshipArrive - escortGunboatArrive) < 280,
            "Alt escort ships should arrive together (gb=$escortGunboatArrive, bs=$escortBattleshipArrive)",
        )
    }

    @Test
    fun reversed_vanguard_overtake_escort_march_and_zero_false_red_while_real_stall_stays_red() {
        val defs = derivedDefs()
        val entry = checkNotNull(ScenarioCatalog.byId("cohort-vanguard-escort-march"))
        val scene = entry.buildScenario(defs)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        ctx.enablePathDiagnostics(ctx.world)
        val obs = ArrayList<Observation>(entry.defaultSteps + 1)
        val diag: List<PathSearchSample>
        var observedEscortYielding = false
        try {
            entry.issueOrders(ctx)
            obs += ctx.observe()
            for (step in 0L until entry.defaultSteps.toLong()) {
                val frame = ctx.advance(Step(step, 1.0, "sim-tick"))
                obs += frame
                if (frame.units.any { it.unitId.startsWith("light-") && it.cohortEscortYielding }) {
                    observedEscortYielding = true
                }
                if (step >= 20L && frame.units.all { it.moveTarget == null && abs(it.speed) <= 1e-4 }) break
            }
            diag = ctx.drainPathDiagnostics(ctx.world)
        } finally {
            ctx.close()
        }

        assertTrue(observedEscortYielding, "Fast fragile units must enter active cohortEscortYielding while heavy vanguard overtakes")
        val seq = DiagnosticSceneProjector.projectSequence(obs, defs, gates = entry.gates)
        val failures = entry.assertOutcome(seq)
        assertTrue(failures.isEmpty(), "Vanguard escort assertions failed: $failures")
        assertEquals(0, diag.count { it.reason == PathSearchReason.ROUTE_REPAIR }, "Escort pacing must cause zero stall replans")

        // Verify genuine blockage STILL triggers 2s red highlight in DiagnosticSceneProjector!
        val syntheticStalledObs = (0L..140L).map { s ->
            Observation(
                sampleId = "stall-$s",
                worldId = "stall-check",
                scenarioId = "stall-check",
                stepIndex = s,
                simulationTime = s.toDouble(),
                units = listOf(
                    UnitObservation(
                        unitId = "trapped-0",
                        ownerPlayerId = "p1",
                        typeId = "c_tank",
                        position = Position(100.0, 100.0),
                        heading = 90.0,
                        speed = 0.0,
                        angularVelocity = 0.0,
                        moveTarget = Position(400.0, 100.0),
                        pathPoints = emptyList(),
                        movementState = MovementState.MOVING,
                        cohortEscortYielding = false,
                    ),
                    UnitObservation(
                        unitId = "yielding-0",
                        ownerPlayerId = "p1",
                        typeId = "fast_ranged_light",
                        position = Position(140.0, 100.0),
                        heading = 90.0,
                        speed = 0.0,
                        angularVelocity = 0.0,
                        moveTarget = Position(400.0, 100.0),
                        pathPoints = emptyList(),
                        movementState = MovementState.MOVING,
                        cohortEscortYielding = true,
                    ),
                ),
                events = emptyList(),
                actionResults = emptyList(),
            )
        }
        val stallProj = DiagnosticSceneProjector.projectSequence(syntheticStalledObs, defs)
        val lastFrame = stallProj.frames.last()
        val trapped = lastFrame.units.single { it.unitId == "trapped-0" }
        val yielding = lastFrame.units.single { it.unitId == "yielding-0" }
        assertTrue(trapped.stalledHighlight, "Genuinely blocked unit (cohortEscortYielding=false) MUST still turn red after 2s")
        assertFalse(yielding.stalledHighlight, "Actively escort-yielding unit MUST be exempt from 2s red highlight")
    }

    @Test
    fun cross_layer_air_ground_escort_and_immediate_dynamic_decoupling_on_reorder_or_death() {
        val defs = derivedDefs()
        val map = ScenarioCatalog.buildCohortVanguardMap()
        val goal = Position(1080.0, 240.0)
        val spawns = listOf(
            UnitSpawn("tank-0", "p1", "heavy_vanguard", Position(140.0, 220.0), 90.0),
            UnitSpawn("tank-1", "p1", "heavy_vanguard", Position(140.0, 260.0), 90.0),
            UnitSpawn("air-0", "p1", "helicopter", Position(200.0, 190.0), 90.0),
            UnitSpawn("air-1", "p1", "helicopter", Position(200.0, 240.0), 90.0),
            UnitSpawn("air-2", "p1", "helicopter", Position(200.0, 290.0), 90.0),
        )

        // Part A: Alt cross-layer escort paces helicopters with slow ground tanks.
        run {
            val scene = ScenarioCatalog.baseScenario("air-ground-escort", map, defs, spawns)
            val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            val obs = mutableListOf<Observation>()
            try {
                val ids = spawns.map { it.unitId }
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = true))
                for (id in ids) {
                    assertIs<ActionResult.Accepted>(
                        ctx.submitGroupMove(MoveAction("ag-$id", "p1", id, goal, ctx.world, cohortEscort = true), group),
                    )
                }
                obs += ctx.observe()
                for (step in 0L until 2200L) {
                    val frame = ctx.advance(Step(step, 1.0, "sim-tick"))
                    obs += frame
                    if (step >= 20L && frame.units.all { it.moveTarget == null && abs(it.speed) <= 1e-4 }) break
                }
            } finally {
                ctx.close()
            }
            val step500 = obs.getOrNull(500) ?: obs.last()
            val avgTankX = step500.units.filter { it.unitId.startsWith("tank-") }.map { it.position.x }.average()
            val avgAirX = step500.units.filter { it.unitId.startsWith("air-") }.map { it.position.x }.average()
            assertTrue(
                abs(avgAirX - avgTankX) < 160.0,
                "Air escorts must stay paced with ground tanks at step 500 instead of racing ahead (tankX=$avgTankX, airX=$avgAirX)",
            )
            assertTrue(obs.any { f -> f.units.any { it.unitId.startsWith("air-") && it.cohortEscortYielding } },
                "Air units should enter cohortEscortYielding while pacing ground tanks")
            assertTrue(obs.last().units.all { it.moveTarget == null }, "All ${spawns.size} air+ground units must complete move orders")
            val seq = DiagnosticSceneProjector.projectSequence(obs, defs)
            assertEquals(spawns.size, seq.frames.last().arrivedCount, "All ${spawns.size} air+ground units must arrive")
            val tanksFinishedStep = obs.withIndex().drop(20).first { (_, f) ->
                f.units.filter { it.unitId.startsWith("tank-") }.all { it.moveTarget == null }
            }.index.toLong()
            val escortStall = seq.frames.firstOrNull { f ->
                f.stepIndex <= tanksFinishedStep && f.stalledCount > 0
            }
            assertNull(
                escortStall,
                "Zero false-positive 2s red stalls during entire air-ground escort march (<= step $tanksFinishedStep), got step=${escortStall?.stepIndex}",
            )
        }

        // Part B: Dynamic decoupling on re-order/stop of slow ground units -> fast units immediately resume full speed.
        run {
            val scene = ScenarioCatalog.baseScenario("decouple-reorder", map, defs, spawns)
            val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val ids = spawns.map { it.unitId }
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = true))
                for (id in ids) {
                    assertIs<ActionResult.Accepted>(
                        ctx.submitGroupMove(MoveAction("dr-$id", "p1", id, goal, ctx.world, cohortEscort = true), group),
                    )
                }
                var yieldingSeen = false
                for (step in 0L until 200L) {
                    val f = ctx.advance(Step(step, 1.0, "sim-tick"))
                    if (f.units.any { it.unitId == "air-0" && it.cohortEscortYielding }) {
                        yieldingSeen = true
                        // Stop both ground tanks -> air escorts must immediately decouple on the next step!
                        assertIs<ActionResult.Accepted>(ctx.submit(StopAction("stop-t0", "p1", "tank-0", ctx.world)))
                        assertIs<ActionResult.Accepted>(ctx.submit(StopAction("stop-t1", "p1", "tank-1", ctx.world)))
                        val nextFrame = ctx.advance(Step(step + 1L, 1.0, "sim-tick"))
                        val air0Next = nextFrame.units.single { it.unitId == "air-0" }
                        assertFalse(air0Next.cohortEscortYielding, "Air escort must decouple immediately on next step after ground stop")
                        var resumedSpeed = air0Next.speed
                        for (k in 2L..25L) {
                            resumedSpeed = maxOf(resumedSpeed, ctx.advance(Step(step + k, 1.0, "sim-tick")).units.single { it.unitId == "air-0" }.speed)
                        }
                        assertTrue(resumedSpeed >= 1.5, "Decoupled helicopter must resume near full speed (>=1.5), got $resumedSpeed")
                        break
                    }
                }
                assertTrue(yieldingSeen, "Expected air-0 to yield before tank stop")
            } finally {
                ctx.close()
            }
        }

        // Part C: Dynamic decoupling on death of slow vanguard (same-layer land) -> fast escorts immediately resume full speed.
        run {
            val landSpawns = listOf(
                UnitSpawn("heavy-0", "p1", "heavy_vanguard", Position(140.0, 240.0), 90.0),
                UnitSpawn("light-0", "p1", "fast_ranged_light", Position(280.0, 220.0), 90.0),
                UnitSpawn("light-1", "p1", "fast_ranged_light", Position(280.0, 260.0), 90.0),
            )
            val scene = ScenarioCatalog.baseScenario("decouple-death", map, defs, landSpawns)
            val host = OfflineSandboxHost(scene, deploymentEnabled = true, generation = 1L)
            val ctx = host.context
            try {
                val ids = landSpawns.map { it.unitId }
                val group = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = true))
                for (id in ids) {
                    assertIs<ActionResult.Accepted>(
                        ctx.submitGroupMove(MoveAction("dd-$id", "p1", id, goal, ctx.world, cohortEscort = true), group),
                    )
                }
                var decoupled = false
                for (step in 0L until 180L) {
                    host.advance(Step(step, 1.0, "sim-tick"))
                    val f = ctx.observe()
                    if (f.units.any { it.unitId == "light-0" && it.cohortEscortYielding }) {
                        // Destroy heavy-0 via sandbox command; applies on step+1
                        host.submit(SandboxCommandRequest(1L, ctx.world, 1L, "/destroy heavy-0"))
                        host.advance(Step(step + 1L, 1.0, "sim-tick"))
                        val fAfterKill = ctx.observe()
                        assertTrue(fAfterKill.units.none { it.unitId == "heavy-0" && it.alive }, "heavy-0 must be destroyed")
                        host.advance(Step(step + 2L, 1.0, "sim-tick"))
                        val fNext = ctx.observe()
                        assertFalse(
                            fNext.units.single { it.unitId == "light-0" }.cohortEscortYielding,
                            "light-0 must immediately drop cohortEscortYielding after heavy-0 dies",
                        )
                        var maxSpd = 0.0
                        for (k in 3L..35L) {
                            host.advance(Step(step + k, 1.0, "sim-tick"))
                            maxSpd = maxOf(maxSpd, ctx.observe().units.single { it.unitId == "light-0" }.speed)
                        }
                        assertTrue(maxSpd >= 1.4, "Decoupled fast escort must resume near full speed (>=1.4), got $maxSpd")
                        decoupled = true
                        break
                    }
                }
                assertTrue(decoupled, "Expected light-0 to yield then decouple when heavy-0 died")
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun disconnected_strict_corridor_falls_back_to_per_clearance_split_without_deadlock() {
        val defs = derivedDefs()
        val w = 36
        val h = 22
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            // Single 1-tile pinhole wall at x=17..18, y=11: small c_tank (r=6) fits, heavy_vanguard (r=14) CANNOT pass!
            else x in 17..18 && y != 11
        }
        val map = ScenarioCatalog.proceduralMap(w, h, blocked)
        val spawns = listOf(
            UnitSpawn("heavy-0", "p1", "heavy_vanguard", Position(180.0, 220.0), 90.0),
            UnitSpawn("small-0", "p1", "c_tank", Position(220.0, 200.0), 90.0),
            UnitSpawn("small-1", "p1", "c_tank", Position(220.0, 240.0), 90.0),
        )
        val scene = ScenarioCatalog.baseScenario("disconnected-fallback", map, defs, spawns)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = spawns.map { it.unitId }
            val goal = Position(560.0, 220.0)
            val group = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = true))
            assertTrue(
                group.sharedCorridorRadiusByMovement.isEmpty(),
                "When strictest clearance band has no connected path to goal, sharedCorridorRadiusByMovement must stay empty to trigger fallback split",
            )
            for (id in ids) {
                assertIs<ActionResult.Accepted>(
                    ctx.submitGroupMove(MoveAction("df-$id", "p1", id, goal, ctx.world, cohortEscort = true), group),
                )
            }
            var last = ctx.observe()
            for (step in 0L until 900L) {
                last = ctx.advance(Step(step, 1.0, "sim-tick"))
            }
            for (sid in listOf("small-0", "small-1")) {
                val u = last.units.single { it.unitId == sid }
                assertTrue(u.position.x > 460.0, "Fallback split must let $sid cross the 1-tile gate to goal, got ${u.position}")
                assertNull(u.moveTarget, "$sid must finish arriving instead of deadlocking on heavy-0")
            }
        } finally {
            ctx.close()
        }
    }

    @Test
    fun narrow_throat_suspends_escort_for_single_file_then_regroups_and_homogeneous_bias_is_zero() {
        val defs = derivedDefs()
        val w = 44
        val h = 22
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            // 1-tile narrow gate at x=16..18, y=11 connecting two wide open basins.
            else x in 16..18 && y != 11
        }
        val map = ScenarioCatalog.proceduralMap(w, h, blocked)

        // Check homogeneous squad bias == emptyMap()
        val homoSpawns = (0 until 4).map { UnitSpawn("t-$it", "p1", "c_tank", Position(140.0 + it * 24.0, 220.0), 90.0) }
        val homoScene = ScenarioCatalog.baseScenario("homo-bias", map, defs, homoSpawns)
        val homoCtx = assertIs<InitResult.Accepted>(SimulationContext.initialize(homoScene)).context
        try {
            val g = checkNotNull(homoCtx.requestGroupMove("p1", homoSpawns.map { it.unitId }, Position(740.0, 220.0), cohortEscort = true))
            assertTrue(g.cohortEscortBiasByUnit.isEmpty(), "Homogeneous squad must have zero artificial escort bias")
        } finally {
            homoCtx.close()
        }

        // Mixed-speed small units (c_tank + fast_ranged_light) passing 1-tile throat under Alt escort
        val mixedSpawns = listOf(
            UnitSpawn("lead-0", "p1", "fast_ranged_light", Position(295.0, 220.0), 90.0),
            UnitSpawn("lead-1", "p1", "fast_ranged_light", Position(265.0, 220.0), 90.0),
            UnitSpawn("rear-0", "p1", "c_tank", Position(215.0, 220.0), 90.0),
            UnitSpawn("rear-1", "p1", "c_tank", Position(175.0, 220.0), 90.0),
        )
        val scene = ScenarioCatalog.baseScenario("throat-regroup", map, defs, mixedSpawns)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val obs = mutableListOf<Observation>()
        try {
            val ids = mixedSpawns.map { it.unitId }
            val goal = Position(760.0, 220.0)
            val g = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = true))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(
                    ctx.submitGroupMove(MoveAction("tr-$id", "p1", id, goal, ctx.world, cohortEscort = true), g),
                )
            }
            obs += ctx.observe()
            for (step in 0L until 1400L) {
                val f = ctx.advance(Step(step, 1.0, "sim-tick"))
                obs += f
                if (step >= 20L && f.units.all { it.moveTarget == null && abs(it.speed) <= 1e-4 }) break
            }
        } finally {
            ctx.close()
        }
        val seq = DiagnosticSceneProjector.projectSequence(obs, defs)
        assertEquals(4, seq.frames.last().arrivedCount, "All 4 units must cross 1-tile throat single-file and arrive")
        val firstStall = seq.frames.firstOrNull { it.stalledCount > 0 }
        assertNull(
            firstStall,
            "Zero 2s red stalls during narrow-throat escort & regrouping, got step=${firstStall?.stepIndex} stalled=${firstStall?.units?.filter { it.stalledHighlight }?.map { "${it.unitId}@${it.center}" }}",
        )
    }

    @Test
    fun rectangular_naval_hull_and_gunboat_alt_escort_around_island() {
        val base = derivedDefs()
        val bsBase = base.getValue("escort_battleship")
        val rectBs = UnitDefinition(
            id = "rect_battleship", className = bsBase.className, radius = 18.0, displayRadius = 18.0,
            totalFrames = bsBase.totalFrames, imagePath = bsBase.imagePath, imageScale = bsBase.imageScale,
            movement = bsBase.movement, retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = bsBase.combat?.copy(maxHp = 2500.0), durability = DurabilityProfile(2500.0, null), build = bsBase.build,
            movementCollision = MovementCollisionShape.Rectangle(40.0, 18.0), mass = 48000.0,
        )
        val defs = base + ("rect_battleship" to rectBs)
        val map = ScenarioCatalog.buildCohortIslandNavalMap()
        val spawns = listOf(
            UnitSpawn("rbs-0", "p1", "rect_battleship", Position(180.0, 480.0), 90.0),
            UnitSpawn("gb-0", "p1", "escort_gunboat", Position(250.0, 460.0), 90.0),
            UnitSpawn("gb-1", "p1", "escort_gunboat", Position(250.0, 500.0), 90.0),
        )
        val scene = ScenarioCatalog.baseScenario("rect-naval-escort", map, defs, spawns)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val obs = mutableListOf<Observation>()
        try {
            val ids = spawns.map { it.unitId }
            val goal = Position(960.0, 480.0)
            val g = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = true))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(
                    ctx.submitGroupMove(MoveAction("rn-$id", "p1", id, goal, ctx.world, cohortEscort = true), g),
                )
            }
            obs += ctx.observe()
            for (step in 0L until 3200L) {
                val f = ctx.advance(Step(step, 1.0, "sim-tick"))
                obs += f
                if (step >= 20L && f.units.all { it.moveTarget == null && abs(it.speed) <= 1e-4 }) break
            }
        } finally {
            ctx.close()
        }
        val entry = checkNotNull(ScenarioCatalog.byId("cohort-island-naval-escort"))
        val seq = DiagnosticSceneProjector.projectSequence(obs, defs, gates = entry.gates)
        assertEquals(3, seq.frames.last().arrivedCount, "Rectangular battleship + 2 gunboats must arrive 3/3")
        val north = seq.gateSummaries.first { it.gateId == "north-wide-channel" }.sternExitedCount
        val south = seq.gateSummaries.first { it.gateId == "south-narrow-strait" }.sternExitedCount
        assertEquals(3, north, "All 3 ships (incl. rectangular battleship) must transit north channel together")
        assertEquals(0, south, "Zero gunboats may shortcut south strait under Alt escort")
    }

    @Test
    fun unarmed_high_hp_support_trails_armed_vanguard_and_enemy_fog_strips_escort_yielding_and_open_border_keeps_pacing() {
        val base = derivedDefs()
        val tankBase = base.getValue("c_tank")
        // Armed light unit (low HP=200, range=140) vs unarmed high-HP support/engineer (HP=1500, combat=null)
        val armedLight = UnitDefinition(
            id = "armed_light", className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.0), retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = CombatProfile(
                maxHp = 200.0, attackRange = 140.0, shootDelay = 30.0, turretSize = 6.0,
                turretTurnSpeed = 4.0, turretTurnAcceleration = 0.5,
                projectile = ProjectileProfile(directDamage = 10.0, life = 60.0, speed = 4.0),
                turretImagePath = null, deadImagePath = null, turretImageScale = 1.0,
            ),
            durability = DurabilityProfile(200.0, null), build = tankBase.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val unarmedHeavySupport = UnitDefinition(
            id = "unarmed_support", className = tankBase.className, radius = 10.0, displayRadius = 10.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.6), retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = null, durability = DurabilityProfile(1500.0, null), build = tankBase.build,
            movementCollision = MovementCollisionShape.Circle(10.0), mass = 8000.0,
        )
        val defs = base + ("armed_light" to armedLight) + ("unarmed_support" to unarmedHeavySupport)
        val map = ScenarioCatalog.buildCohortVanguardMap()
        // Place units right next to top border wall (y=30 -> row=1, clearanceRings=1!) to verify open border
        // wall does NOT falsely suspend cohort escort pacing!
        val spawns = listOf(
            UnitSpawn("armed-0", "p1", "armed_light", Position(140.0, 32.0), 90.0),
            UnitSpawn("support-0", "p1", "unarmed_support", Position(260.0, 32.0), 90.0),
            UnitSpawn("enemy-obs", "p2", "c_tank", Position(260.0, 110.0), 0.0),
        )
        val scene = ScenarioCatalog.baseScenario(
            id = "unarmed-mix-and-border",
            map = map,
            definitions = defs,
            units = spawns,
            players = listOf(Player("p1", PlayerControl.LOCAL, "team1"), Player("p2", PlayerControl.LOCAL, "team2")),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = listOf("armed-0", "support-0")
            val goal = Position(1080.0, 32.0)
            val group = checkNotNull(ctx.requestGroupMove("p1", ids, goal, cohortEscort = true))
            val armedBias = group.cohortEscortBiasByUnit["armed-0"] ?: 0.0
            val supportBias = group.cohortEscortBiasByUnit["support-0"] ?: 0.0
            assertTrue(
                supportBias > armedBias + 20.0,
                "High-HP unarmed support must receive larger rear escort bias than armed vanguard (support=$supportBias, armed=$armedBias)",
            )
            for (id in ids) {
                assertIs<ActionResult.Accepted>(
                    ctx.submitGroupMove(MoveAction("ub-$id", "p1", id, goal, ctx.world, cohortEscort = true), group),
                )
            }
            var sawOwnYieldAndEnemyStripped = false
            for (step in 0L until 180L) {
                ctx.advance(Step(step, 1.0, "sim-tick"))
                val p1View = ctx.observePlayer(ctx.world, "p1")
                val p2View = ctx.observePlayer(ctx.world, "p2")
                val ownSupport = p1View.units.single { it.unitId == "support-0" }
                val enemyViewOfSupport = p2View.units.firstOrNull { it.unitId == "support-0" }
                if (ownSupport.cohortEscortYielding) {
                    assertNotNull(enemyViewOfSupport, "Enemy observer should see support-0 within sight range")
                    assertFalse(
                        enemyViewOfSupport.cohortEscortYielding,
                        "PlayerObservationProjector MUST strip cohortEscortYielding=false for enemy observers",
                    )
                    sawOwnYieldAndEnemyStripped = true
                    break
                }
            }
            assertTrue(
                sawOwnYieldAndEnemyStripped,
                "Unarmed support marching adjacent to open map border (clearanceRings=1) must still yield for armed vanguard instead of suspending escort",
            )
        } finally {
            ctx.close()
        }
    }
}
