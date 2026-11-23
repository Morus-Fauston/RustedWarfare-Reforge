package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit & integration verification for [LocomotionModule] (ENG-P016-006):
 * - Dimensionless scaling across tiny scouts and giant experimentals
 * - Full 2D ORCA + static wall 3D LP minimal-penetration fallback (zero wall penetration, non-zero progress)
 * - Fog-of-war strict isolation (unseen enemies never trigger avoidance)
 * - Open-field 25v25 head-on dual-lane right-hand crossing (0 deadlock, high arrival)
 * - Wide-corridor opposed crossing (8v8 dual lane inside 3-tile corridor)
 * - Large same-direction group natural lateral spread in open field
 * - Slow-turn rectangular hull stability (no tail-into-wall clipping, no high-frequency head jitter)
 */
class LocomotionModuleTest {

    @Test
    fun dimensionless_scale_adapts_proportionally_from_small_scout_to_giant_experimental() {
        val base = TestContentFactory.openScenario().unitDefinitions.getValue("tank")
        val tinyScout = TestContentFactory.unitWith(
            base,
            radius = 5.0,
            movement = base.movement.copy(moveSpeed = 1.6, maxTurnSpeed = 6.0, moveDecelerationSpeed = 0.25),
        )
        val giantExp = TestContentFactory.unitWith(
            base,
            radius = 28.0,
            movement = base.movement.copy(moveSpeed = 0.55, maxTurnSpeed = 0.8, moveDecelerationSpeed = 0.08),
        )
        val scoutUnit = MutableUnit("scout", "p1", "scout", tinyScout, Position(100.0, 100.0), 0.0)
        val expUnit = MutableUnit("exp", "p1", "exp", giantExp, Position(100.0, 100.0), 0.0)

        val scoutScale = LocomotionModule.KinematicScale.from(scoutUnit, tinyScout.movement, tinyScout.movement.moveSpeed)
        val expScale = LocomotionModule.KinematicScale.from(expUnit, giantExp.movement, giantExp.movement.moveSpeed)

        assertTrue(expScale.perceptionRadius > scoutScale.perceptionRadius * 2.5,
            "Giant experimental perception ${expScale.perceptionRadius} should scale far above tiny scout ${scoutScale.perceptionRadius}")
        assertTrue(expScale.comfortSpacing > scoutScale.comfortSpacing * 3.0,
            "Giant comfort spacing ${expScale.comfortSpacing} should scale with radius above ${scoutScale.comfortSpacing}")
        assertTrue(expScale.timeHorizon > scoutScale.timeHorizon,
            "Slower/heavier giant should have longer reaction horizon tau: ${expScale.timeHorizon} vs ${scoutScale.timeHorizon}")
        assertTrue(scoutScale.perceptionRadius >= 4.5 * scoutScale.hardRadius)
        assertTrue(expScale.perceptionRadius >= 4.5 * expScale.hardRadius)
    }

    @Test
    fun lp_3d_fallback_preserves_static_wall_under_multi_unit_pinch_and_avoids_wall_penetration() {
        // Unit pressed against a top static wall (normal = (0, +1), cannot move up: vy >= 0)
        // while 3 dynamic neighbor half-planes contradict each other and push upward (vy <= -0.5).
        val wall = LocomotionModule.HalfPlane(
            point = LocomotionModule.Vec2(0.0, 0.0),
            normal = LocomotionModule.Vec2(0.0, 1.0), // vy >= 0 (hard static wall)
        )
        val dynPushUpLeft = LocomotionModule.HalfPlane(
            point = LocomotionModule.Vec2(-0.6, -0.5),
            normal = LocomotionModule.Vec2(-0.6, -0.8).normalized(),
        )
        val dynPushUpRight = LocomotionModule.HalfPlane(
            point = LocomotionModule.Vec2(0.6, -0.5),
            normal = LocomotionModule.Vec2(0.6, -0.8).normalized(),
        )
        val dynPinchFromBelow = LocomotionModule.HalfPlane(
            point = LocomotionModule.Vec2(0.0, -0.4),
            normal = LocomotionModule.Vec2(0.0, -1.0), // wants vy <= -0.4, directly conflicts with wall vy >= 0
        )
        val lines = listOf(wall, dynPushUpLeft, dynPushUpRight, dynPinchFromBelow)
        val res = LocomotionModule.solveLinearProgram(
            lines = lines,
            numFixed = 1,
            maxSpeed = 1.2,
            prefVelocity = LocomotionModule.Vec2(1.0, 0.0),
        )
        assertTrue(res.used3dFallback, "Conflicting dynamic constraints against static wall must invoke 3D LP fallback")
        assertTrue(wall.signedMargin(res.velocity) >= -1e-5,
            "3D LP fallback MUST preserve hard static wall constraint: vy=${res.velocity.y}")
        assertTrue(res.velocity.length() <= 1.2 + 1e-5,
            "Solved speed ${res.velocity.length()} must stay within maxSpeed")
        assertTrue(abs(res.velocity.x) < 1e-4,
            "Under symmetric left/right pinch against wall, minimax 3D LP optimum must center at vx=0, got ${res.velocity}")

        // Orthogonal wall-tangent tie case: wall vy >= 0 vs parallel dynamic pinch vy <= -0.4 (all vx share equal 0.4 penetration).
        // Must project prefVelocity (+1.0, 0.0) onto the wall tangent rather than snapping to -maxSpeed (-1.2, 0.0).
        val parallelRes = LocomotionModule.solveLinearProgram(
            lines = listOf(wall, dynPinchFromBelow),
            numFixed = 1,
            maxSpeed = 1.2,
            prefVelocity = LocomotionModule.Vec2(1.0, 0.0),
        )
        assertTrue(parallelRes.used3dFallback, "Parallel opposing wall + dynamic line must invoke 3D LP fallback")
        assertTrue(wall.signedMargin(parallelRes.velocity) >= -1e-5,
            "Parallel 3D LP fallback must preserve hard wall: ${parallelRes.velocity}")
        assertTrue(parallelRes.velocity.x > 0.9,
            "Parallel wall-tangent tie must preserve forward prefVelocity (+x > 0.9), got ${parallelRes.velocity}")
    }

    @Test
    fun unseen_fog_enemies_do_not_trigger_local_avoidance() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        // Very short sight range (1 tile = 20u) so enemy 55u ahead is hidden in fog!
        val blindTank = UnitDefinition(
            id = tankBase.id, className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 4.5, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 1,
            movementCollision = MovementCollisionShape.Circle(6.0),
        )
        val w = 30
        val h = 20
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, BooleanArray(w * h)))
        val p1 = Player("p1", PlayerControl.LOCAL, "teamA")
        val p2 = Player("p2", PlayerControl.LOCAL, "teamB")

        fun runTrace(includeHiddenEnemy: Boolean): List<Position> {
            val units = buildList {
                add(UnitSpawn("u1", "p1", "tank", Position(100.0, 200.0), 90.0))
                if (includeHiddenEnemy) {
                    // Placed 65u ahead -> outside sight range 1 tile (20u), inside perception radius if fog were leaked!
                    add(UnitSpawn("e1", "p2", "tank", Position(165.0, 200.0), 270.0))
                }
            }
            val scene = base.copy(
                map = map,
                players = listOf(p1, p2),
                unitDefinitions = mapOf("tank" to blindTank),
                units = units,
                automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
            )
            val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(
                    ctx.submit(MoveAction("go", "p1", "u1", Position(400.0, 200.0), ctx.world))
                )
                val trace = ArrayList<Position>()
                // Check first 20 ticks while enemy is strictly outside p1's 1-tile vision
                for (s in 0L until 20L) {
                    val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                    val u = obs.units.first { it.unitId == "u1" }
                    trace.add(u.position)
                    // Confirm p1 does NOT see e1 yet
                    if (includeHiddenEnemy) {
                        val p1Obs = ctx.observePlayer(ctx.world, "p1")
                        assertTrue(p1Obs.units.none { it.unitId == "e1" },
                            "Enemy should still be hidden in fog at step $s, u1=${u.position}")
                    }
                }
                return trace
            } finally {
                ctx.close()
            }
        }

        val solo = runTrace(includeHiddenEnemy = false)
        val withHidden = runTrace(includeHiddenEnemy = true)
        assertEquals(solo, withHidden, "Hidden fog-of-war enemy must produce bit-identical trajectory (zero leak)")
    }

    @Test
    fun open_field_25v25_head_on_traffic_forms_right_hand_dual_lanes_and_passes_cleanly() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = tankBase.id, className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 4.1, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(), movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val w = 50
        val h = 30
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = buildList {
            for (i in 0 until 25) {
                val row = i % 5; val col = i / 5
                add(UnitSpawn("east-$i", "p1", "tank", Position(240.0 - col * 28.0, 244.0 + row * 28.0), 90.0))
            }
            for (i in 0 until 25) {
                val row = i % 5; val col = i / 5
                add(UnitSpawn("west-$i", "p2", "tank", Position(760.0 + col * 28.0, 244.0 + row * 28.0), 270.0))
            }
        }
        val scene = base.copy(
            map = map,
            players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies")),
            unitDefinitions = mapOf("tank" to tank),
            units = units,
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val eastIds = (0 until 25).map { "east-$it" }
            val westIds = (0 until 25).map { "west-$it" }
            val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, Position(780.0, 300.0)))
            for (id in eastIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("e-$id", "p1", id, Position(780.0, 300.0), ctx.world), gEast))
            val gWest = checkNotNull(ctx.requestGroupMove("p2", westIds, Position(220.0, 300.0)))
            for (id in westIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("w-$id", "p2", id, Position(220.0, 300.0), ctx.world), gWest))

            var maxRightLaneSep = 0.0
            var finalObs: Observation? = null
            for (s in 0L until 1800L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                finalObs = obs
                if (s == 10L || s == 100L || s == 300L) {
                    val wMove = obs.units.count { it.unitId.startsWith("west-") && it.moveTarget != null }
                    val eMove = obs.units.count { it.unitId.startsWith("east-") && it.moveTarget != null }
                    val w0 = obs.units.first { it.unitId == "west-0" }
                    val e0 = obs.units.first { it.unitId == "east-0" }
                    println("  step=$s eMove=$eMove wMove=$wMove e0=@(${e0.position.x.toInt()},${e0.position.y.toInt()})->${e0.moveTarget} w0=@(${w0.position.x.toInt()},${w0.position.y.toInt()})->${w0.moveTarget}")
                }
                // During crossing window (mid-field x in 400..600), measure mean Y of eastbound vs westbound
                if (s in 200L..600L) {
                    val eastY = obs.units.filter { it.unitId.startsWith("east-") }.map { it.position.y }.average()
                    val westY = obs.units.filter { it.unitId.startsWith("west-") }.map { it.position.y }.average()
                    // Right-hand rule in y-down: Eastbound shifts South (+y), Westbound shifts North (-y) => eastY > westY
                    maxRightLaneSep = max(maxRightLaneSep, eastY - westY)
                }
            }
            val end = checkNotNull(finalObs)
            val eastCrossed = end.units.count { it.unitId.startsWith("east-") && it.position.x > 650.0 }
            val westCrossed = end.units.count { it.unitId.startsWith("west-") && it.position.x < 350.0 }
            val arrived = end.units.count { it.moveTarget == null }
            val eastSample = end.units.filter { it.unitId.startsWith("east-") }.take(5).map { "${it.unitId}@(${it.position.x.toInt()},${it.position.y.toInt()})->${it.moveTarget?.x?.toInt()},${it.moveTarget?.y?.toInt()} pts=${it.pathPoints.size}" }
            val westSample = end.units.filter { it.unitId.startsWith("west-") }.take(5).map { "${it.unitId}@(${it.position.x.toInt()},${it.position.y.toInt()})->${it.moveTarget?.x?.toInt()},${it.moveTarget?.y?.toInt()} pts=${it.pathPoints.size}" }
            println("25v25 open: maxRightLaneSep=$maxRightLaneSep eastCrossed=$eastCrossed/25 westCrossed=$westCrossed/25 arrived=$arrived/50")
            println("  east5=$eastSample")
            println("  west5=$westSample")
            assertTrue(maxRightLaneSep >= 12.0,
                "Opposing 25v25 flows should separate onto right-hand lanes (eastY - westY >= 12), got $maxRightLaneSep")
            assertEquals(25, eastCrossed, "All 25 eastbound units must cross cleanly without head-on deadlock")
            assertEquals(25, westCrossed, "All 25 westbound units must cross cleanly without head-on deadlock")
            assertEquals(50, arrived, "Expected all 50/50 units to arrive within 1800 ticks without deadlock")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun wide_corridor_8v8_opposed_traffic_forms_dual_lanes_without_deadlock() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = tankBase.id, className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 4.1, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(), movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val w = 45
        val h = 20
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 14..30) && (y !in 9..11)
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = buildList {
            for (i in 0 until 8) {
                val row = i % 2; val col = i / 2
                add(UnitSpawn("ceast-$i", "p1", "tank", Position(220.0 - col * 26.0, 192.0 + row * 16.0), 90.0))
            }
            for (i in 0 until 8) {
                val row = i % 2; val col = i / 2
                add(UnitSpawn("cwest-$i", "p2", "tank", Position(680.0 + col * 26.0, 192.0 + row * 16.0), 270.0))
            }
        }
        val scene = base.copy(
            map = map,
            players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies")),
            unitDefinitions = mapOf("tank" to tank),
            units = units,
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val eastIds = (0 until 8).map { "ceast-$it" }
            val westIds = (0 until 8).map { "cwest-$it" }
            val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, Position(760.0, 200.0)))
            for (id in eastIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("e-$id", "p1", id, Position(760.0, 200.0), ctx.world), gEast))
            val gWest = checkNotNull(ctx.requestGroupMove("p2", westIds, Position(140.0, 200.0)))
            for (id in westIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("w-$id", "p2", id, Position(140.0, 200.0), ctx.world), gWest))

            var finalObs: Observation? = null
            for (s in 0L until 1800L) {
                finalObs = ctx.advance(Step(s, 1.0, "sim-tick"))
            }
            val end = checkNotNull(finalObs)
            val eastCrossed = end.units.count { it.unitId.startsWith("ceast-") && it.position.x > 640.0 }
            val westCrossed = end.units.count { it.unitId.startsWith("cwest-") && it.position.x < 260.0 }
            val arrived = end.units.count { it.moveTarget == null }
            val unarrived = end.units.filter { it.moveTarget != null }.map { "${it.unitId}@(${it.position.x.toInt()},${it.position.y.toInt()})->(${it.moveTarget?.x?.toInt()},${it.moveTarget?.y?.toInt()}) pts=${it.pathPoints.size} spd=${it.speed}" }
            println("8v8 corridor: eastCrossed=$eastCrossed/8 westCrossed=$westCrossed/8 arrived=$arrived/16")
            println("  unarrived=$unarrived")
            assertEquals(8, eastCrossed, "All 8 eastbound units must clear the 3-tile corridor")
            assertEquals(8, westCrossed, "All 8 westbound units must clear the 3-tile corridor")
            assertEquals(16, arrived, "All 16 corridor units must reach destination without deadlock")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun large_same_direction_group_spreads_laterally_in_open_field_without_single_file_clumping() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = tankBase.id, className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 4.1, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(), movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val w = 50; val h = 30
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        // Start tightly packed in a narrow 2-row strip (y in 292..308, span = 16u) heading East 500u
        val units = (0 until 20).map { i ->
            val row = i % 2; val col = i / 2
            UnitSpawn("g-$i", "p1", "tank", Position(220.0 - col * 15.0, 292.0 + row * 16.0), 90.0)
        }
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = units)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = (0 until 20).map { "g-$it" }
            val group = checkNotNull(ctx.requestGroupMove("p1", ids, Position(760.0, 300.0)))
            for (id in ids) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("m-$id", "p1", id, Position(760.0, 300.0), ctx.world), group))

            var maxLateralSpan = 0.0
            var endObs: Observation? = null
            for (s in 0L until 1000L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                endObs = obs
                if (s in 80L..450L) {
                    val ys = obs.units.map { it.position.y }
                    maxLateralSpan = max(maxLateralSpan, ys.max() - ys.min())
                }
            }
            val arrived = checkNotNull(endObs).units.count { it.moveTarget == null }
            println("open spread: initialSpan=16.0 maxLateralSpan=$maxLateralSpan arrived=$arrived/20")
            assertTrue(maxLateralSpan > 42.0,
                "20-unit tight strip should naturally spread laterally in open field (>42u), got $maxLateralSpan")
            assertEquals(20, arrived, "All 20 units should arrive cleanly")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun approaching_rectangular_hulls_adjust_course_before_hard_contact() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val hull = MovementCollisionShape.Rectangle(length = 26.0, width = 12.0)
        val ship = UnitDefinition(
            id = "ship", className = tank.className, radius = 13.0, displayRadius = 13.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(type = MovementType.WATER, moveSpeed = 1.0, maxTurnSpeed = 2.0),
            retainedUnconsumed = emptySet(), movementCollision = hull, mass = 3000.0,
        )
        val width = 40
        val height = 20
        lateinit var map: MapDefinition
        WaterTestScene.withMap { shore ->
            val water = shore.groundGids.last()
            map = MapDefinition(width, height, 20, 20, shore.tileSets, IntArray(width * height) { water },
                BlockingGrid(width, height, BooleanArray(width * height)))
        }
        val scene = base.copy(map = map, unitDefinitions = mapOf("ship" to ship), units = listOf(
            UnitSpawn("east", "p1", "ship", Position(160.0, 210.0), 90.0),
            UnitSpawn("west", "p1", "ship", Position(480.0, 210.0), 270.0),
        ))
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for ((id, goalX) in listOf("east" to 600.0, "west" to 80.0)) {
                assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("cross-$id", "p1", id, Position(goalX, 210.0), ctx.world)))
            }
            var lateralSeparation = 0.0
            for (step in 0L until 450L) {
                val frame = ctx.advance(Step(step, 1.0, "sim-tick"))
                val east = frame.units.single { it.unitId == "east" }
                val west = frame.units.single { it.unitId == "west" }
                if (west.position.x - east.position.x > 2 * hull.boundingRadius) {
                    lateralSeparation = max(lateralSeparation, abs(east.position.y - west.position.y))
                } else break
            }
            assertTrue(lateralSeparation > 1.0, "Opposing hulls should steer apart before contact: $lateralSeparation")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun slow_turn_rectangular_hull_avoids_tail_into_wall_and_high_frequency_head_jitter() {
        val base = TestContentFactory.openScenario()
        val shipBase = base.unitDefinitions.getValue("tank")
        val rectHull = MovementCollisionShape.Rectangle(length = 26.0, width = 12.0)
        val slowRect = UnitDefinition(
            id = "rect", className = shipBase.className, radius = 13.0, displayRadius = 13.0,
            totalFrames = shipBase.totalFrames, imagePath = shipBase.imagePath, imageScale = shipBase.imageScale,
            movement = shipBase.movement.copy(
                type = MovementType.LAND,
                moveSpeed = 0.9,
                maxTurnSpeed = 1.6,
                turnAcceleration = 0.15,
            ),
            retainedUnconsumed = emptySet(),
            movementCollision = rectHull,
        )
        val w = 36; val h = 24
        // L-shaped corridor: 4 tiles wide (80u) with a 90-degree turn and a parked peer inside the bend
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else !(x in 3..22 && y in 8..13 || x in 17..22 && y in 8..20)
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = listOf(
            UnitSpawn("rect-1", "p1", "rect", Position(120.0, 210.0), 90.0),
            UnitSpawn("peer-1", "p1", "rect", Position(260.0, 235.0), 90.0),
        )
        val scene = base.copy(map = map, unitDefinitions = mapOf("rect" to slowRect), units = units)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(
                ctx.submit(MoveAction("go-rect", "p1", "rect-1", Position(400.0, 360.0), ctx.world))
            )
            val hullGrid = HullTerrainGrid.authoritative(map, MovementType.LAND)
            var prevHeading = 90.0
            var prevSign = 0
            var rapidFlips = 0
            var endObs: Observation? = null
            for (s in 0L until 900L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                endObs = obs
                val r = obs.units.first { it.unitId == "rect-1" }
                assertTrue(UnitContact.poseFits(rectHull, r.position, r.heading, hullGrid),
                    "Rectangular hull clipped terrain wall at step $s pos=${r.position} heading=${r.heading}")
                val dHead = ScreenPlane.difference(r.heading, prevHeading)
                val sign = when {
                    dHead > 0.35 -> 1
                    dHead < -0.35 -> -1
                    else -> 0
                }
                if (sign != 0 && prevSign != 0 && sign != prevSign) {
                    rapidFlips++
                }
                if (sign != 0) prevSign = sign
                prevHeading = r.heading
            }
            val endR = checkNotNull(endObs).units.first { it.unitId == "rect-1" }
            val distToGoal = hypot(endR.position.x - 400.0, endR.position.y - 360.0)
            println("rect turn: rapidFlips=$rapidFlips distToGoal=$distToGoal arrived=${endR.moveTarget == null}")
            assertTrue(rapidFlips <= 12, "Slow-turn rectangular hull should not exhibit high-frequency head jitter (flips=$rapidFlips)")
            assertTrue(distToGoal < 35.0, "Rectangular hull should round the corner past peer and reach goal, dist=$distToGoal")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun same_player_split_squads_head_on_form_right_hand_dual_lanes_and_arrive_all() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = tankBase.id, className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 4.1, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(), movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val w = 44; val h = 28
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = buildList {
            for (i in 0 until 12) {
                val r = i % 3; val c = i / 3
                add(UnitSpawn("p1-east-$i", "p1", "tank", Position(220.0 - c * 28.0, 252.0 + r * 28.0), 90.0))
                add(UnitSpawn("p1-west-$i", "p1", "tank", Position(660.0 + c * 28.0, 252.0 + r * 28.0), 270.0))
            }
        }
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = units)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val eastIds = (0 until 12).map { "p1-east-$it" }
            val westIds = (0 until 12).map { "p1-west-$it" }
            val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, Position(680.0, 280.0)))
            for (id in eastIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("e-$id", "p1", id, Position(680.0, 280.0), ctx.world), gEast))
            val gWest = checkNotNull(ctx.requestGroupMove("p1", westIds, Position(200.0, 280.0)))
            for (id in westIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("w-$id", "p1", id, Position(200.0, 280.0), ctx.world), gWest))

            var maxRightLaneSep = 0.0
            var endObs: Observation? = null
            for (s in 0L until 1400L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                endObs = obs
                if (s in 160L..500L) {
                    val eastY = obs.units.filter { it.unitId.startsWith("p1-east-") }.map { it.position.y }.average()
                    val westY = obs.units.filter { it.unitId.startsWith("p1-west-") }.map { it.position.y }.average()
                    maxRightLaneSep = max(maxRightLaneSep, eastY - westY)
                }
            }
            val end = checkNotNull(endObs)
            val arrived = end.units.count { it.moveTarget == null }
            assertTrue(maxRightLaneSep >= 12.0,
                "Same-player split squads must separate onto right-hand dual lanes (eastY - westY >= 12), got $maxRightLaneSep")
            assertEquals(24, arrived, "All 24 same-player split-squad units must arrive cleanly, got $arrived")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun single_lane_narrow_corridor_and_map_edge_attenuate_lateral_separation_without_wall_scraping() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = tankBase.id, className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 4.5, turnAcceleration = 0.3),
            retainedUnconsumed = emptySet(), movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val openTerrain = PlanningTerrain(20, 20, 20, 20, BooleanArray(400))
        val edgeClearance = LocomotionModule.estimateLocalClearanceWorld(openTerrain, Position(100.0, 10.0))
        assertTrue(edgeClearance <= 20.5, "Map-edge clearance must reflect border distance (<=20.5), got $edgeClearance")

        val w = 30; val h = 15
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else if (x in 10..19) y != 7
            else false
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = (0 until 4).map { i ->
            UnitSpawn("t-$i", "p1", "tank", Position(140.0 - i * 20.0, 150.0), 90.0)
        }
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = units)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = units.map { it.unitId }
            val g = checkNotNull(ctx.requestGroupMove("p1", ids, Position(480.0, 150.0)))
            for (id in ids) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("m-$id", "p1", id, Position(480.0, 150.0), ctx.world), g))
            var maxCorridorLateralDev = 0.0
            var endObs: Observation? = null
            for (s in 0L until 900L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                endObs = obs
                for (u in obs.units) {
                    if (u.position.x in 205.0..395.0) {
                        maxCorridorLateralDev = max(maxCorridorLateralDev, abs(u.position.y - 150.0))
                    }
                }
            }
            val arrived = checkNotNull(endObs).units.count { it.moveTarget == null }
            assertEquals(4, arrived, "All 4 tanks should traverse the 1-tile single-lane passage and arrive")
            assertTrue(maxCorridorLateralDev <= 4.1,
                "Lateral separation must attenuate inside true single-lane passage (maxDev=$maxCorridorLateralDev)")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun locomotion_avoidance_is_bit_deterministic_and_rolls_back_lastAvoidHeadingDeg_across_failed_candidate_step() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = tankBase.id, className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 4.1, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(), movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val w = 36; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = (0 until 6).map { i ->
            UnitSpawn("e-$i", "p1", "tank", Position(180.0 - (i / 2) * 24.0, 220.0 + (i % 2) * 24.0), 90.0)
        } + (0 until 6).map { i ->
            UnitSpawn("w-$i", "p2", "tank", Position(540.0 + (i / 2) * 24.0, 220.0 + (i % 2) * 24.0), 270.0)
        }
        val scene = base.copy(
            map = map,
            players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies")),
            unitDefinitions = mapOf("tank" to tank),
            units = units,
        )

        fun runTrace(injectFault: Boolean): List<List<Any?>> {
            var faultFired = false
            val steering = SteeringPolicy { body, aim, delta, coordinates ->
                if (injectFault && !faultFired && body.nav.lastAvoidHeadingDeg != null) {
                    faultFired = true
                    error("injected-avoid-rollback-fault")
                }
                AcceleratedSteering.turn(body, aim, delta, coordinates)
            }
            val ctx = assertIs<InitResult.Accepted>(SimulationContext(ExperimentalPolicies(steering = steering)).initialize(scene)).context
            try {
                val eIds = (0 until 6).map { "e-$it" }
                val wIds = (0 until 6).map { "w-$it" }
                val gE = checkNotNull(ctx.requestGroupMove("p1", eIds, Position(560.0, 232.0)))
                for (id in eIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("me-$id", "p1", id, Position(560.0, 232.0), ctx.world), gE))
                val gW = checkNotNull(ctx.requestGroupMove("p2", wIds, Position(160.0, 232.0)))
                for (id in wIds) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("mw-$id", "p2", id, Position(160.0, 232.0), ctx.world), gW))

                val trace = ArrayList<List<Any?>>()
                for (s in 0L until 320L) {
                    val tick = Step(s, 1.0, "sim-tick")
                    val obs = try {
                        ctx.advance(tick)
                    } catch (e: IllegalStateException) {
                        if (e.message != "injected-avoid-rollback-fault") throw e
                        ctx.advance(tick)
                    }
                    if (s % 40L == 0L || s == 319L) {
                        trace.add(obs.units.map { listOf(it.unitId, it.position.x, it.position.y, it.heading, it.speed) })
                    }
                }
                assertEquals(injectFault, faultFired, "Fault injection must trigger after lastAvoidHeadingDeg becomes active")
                return trace
            } finally {
                ctx.close()
            }
        }

        val navWithAvoid = UnitNavigationState.EMPTY.copy(lastAvoidHeadingDeg = 125.0)
        assertEquals(null, navWithAvoid.adoptRoute(listOf(Position(100.0, 100.0)), 1L).lastAvoidHeadingDeg)

        val clean = runTrace(injectFault = false)
        val recovered = runTrace(injectFault = true)
        assertEquals(clean, recovered, "Failed candidate step rollback must preserve bit-identical locomotion replay")
    }
}
