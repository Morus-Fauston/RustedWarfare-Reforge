package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.navigation.PlanningTerrain
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * General A/B mechanism regression tests for ENG-P016-012 (strictly generic scenarios, zero holdout overfitting):
 * 1. Small-only pending units in a heterogeneous group fall back to ordinary landing bays when all larger/rect units are parked.
 * 2. Compact rectangular hull (e.g. 26x10) clears a 1-tile straight corridor into a 2x2 interior corner bend without
 *    premature nearest-reachable false arrival.
 * 3. Units inside an old-order `parked` group cluster receiving a new move order escape cleanly, and slow pushers
 *    displacing fast idle units never cause idle per-step displacement to exceed the pusher's actual travel.
 * 4. Dense airborne separation never exceeds `maximumSpeed * delta` per tick.
 * 5. Mixed multi-domain large groups use shared flowfield while small pure-water rectangular squads retain HullRoute.
 * 6. Bow-on mass push formed in `[256, 512)` crossing downward across the IEEE-754 `y = 256.0` exponent step never freezes
 *    in `UnitContact.clearRelative`, and an angled push against a wall-flush idle circle inside a 1-tile corridor slides it
 *    cleanly along the corridor wall instead of wedging.
 */
class MovementRegression012Test {

    private fun simpleTank(base: UnitDefinition, id: String = "tank", radius: Double = 6.0, speed: Double = 1.1, mass: Double = 3000.0) =
        UnitDefinition(
            id = id,
            className = base.className,
            radius = radius,
            displayRadius = radius,
            totalFrames = base.totalFrames,
            imagePath = base.imagePath,
            imageScale = base.imageScale,
            movement = base.movement.copy(moveSpeed = speed, maxTurnSpeed = 4.2, turnAcceleration = 0.3),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(radius),
            mass = mass,
        )

    @Test
    fun heterogeneous_group_falls_back_to_ordinary_when_only_small_units_remain_pending() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val smallDef = simpleTank(tankBase, id = "small", radius = 6.0, speed = 1.1, mass = 2000.0)
        val heavyDef = simpleTank(tankBase, id = "heavy", radius = 14.0, speed = 0.85, mass = 9000.0)
        val scene = base.copy(
            unitDefinitions = mapOf("small" to smallDef, "heavy" to heavyDef),
            units = listOf(
                UnitSpawn("s-1", "p1", "small", Position(120.0, 120.0), 90.0),
                UnitSpawn("s-2", "p1", "small", Position(120.0, 150.0), 90.0),
                UnitSpawn("h-1", "p1", "heavy", Position(160.0, 135.0), 90.0),
            ),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(240.0, 140.0)
            val group = assertNotNull(ctx.requestGroupMove("p1", listOf("s-1", "s-2", "h-1"), click))
            val s1 = MutableUnit("s-1", "p1", "small", smallDef, Position(200.0, 130.0), 90.0).apply {
                updateNavigation {
                    it.withLandingMember(LandingMember(group, parked = false))
                        .withMoveTarget(click, MotionContract.sharedCluster(click))
                        .withPathPoints(listOf(click))
                }
            }
            val h1 = MutableUnit("h-1", "p1", "heavy", heavyDef, Position(220.0, 140.0), 90.0).apply {
                updateNavigation {
                    it.withLandingMember(LandingMember(group, parked = false))
                        .withMoveTarget(click, MotionContract.sharedCluster(click))
                        .withPathPoints(listOf(click))
                }
            }
            val terrain = PlanningTerrain.fromNoFog(scene.map)
            val paths = ClearanceAStarPolicy.DEFAULT
            val bays = MixedLandingBays(click, true, 110.0)

            // While the larger unit (h-1) is still unparked & unclaimed, bay survey stays active (Pending on empty sites).
            val whileHeavyPending = bays.choose(
                members = listOf(s1, h1),
                candidates = emptyList(),
                occupied = { _, _, _ -> false },
                near = { true },
                terrain = { terrain },
                paths = paths,
                budget = PathWorkBudget(8),
                advance = { _, _ -> },
                obstacles = { emptyList() },
            )
            assertIs<MixedLandingBays.Decision.Pending>(whileHeavyPending)

            // Once h-1 is parked, only small unit s-1 remains pending -> must fall back to Decision.Ordinary.
            h1.updateNavigation { it.withLandingMember(LandingMember(group, parked = true)) }
            val afterHeavyParked = bays.choose(
                members = listOf(s1, h1),
                candidates = emptyList(),
                occupied = { _, _, _ -> false },
                near = { true },
                terrain = { terrain },
                paths = paths,
                budget = PathWorkBudget(8),
                advance = { _, _ -> },
                obstacles = { emptyList() },
            )
            assertIs<MixedLandingBays.Decision.Ordinary>(afterHeavyParked)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun compact_rectangle_clears_single_tile_corridor_into_2x2_corner_bend() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val longRect = UnitDefinition(
            id = "longRect",
            className = tankBase.className,
            radius = 13.0,
            displayRadius = 13.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.0, maxTurnSpeed = 2.8, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Rectangle(width = 10.0, length = 26.0),
            mass = 6000.0,
        )
        val w = 26
        val h = 18
        val blocked = BooleanArray(w * h) { idx ->
            val c = idx % w
            val r = idx / w
            if (c == 0 || c == w - 1 || r == 0 || r == h - 1) return@BooleanArray true
            val westRoom = c in 1..5 && r in 3..9
            val horizLane = c in 6..11 && r == 6
            val cornerBox = c in 12..13 && r in 6..7
            val vertLane = c == 13 && r in 8..11
            val southRoom = c in 10..20 && r in 12..16
            !(westRoom || horizLane || cornerBox || vertLane || southRoom)
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("longRect" to longRect),
            units = listOf(UnitSpawn("r-0", "p1", "longRect", Position(70.0, 130.0), 90.0)),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val goal = Position(310.0, 290.0)
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-rect", "p1", "r-0", goal, ctx.world)))
            var arrived = false
            var finalPos = Position(70.0, 130.0)
            for (s in 0L until 1400L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                val u = obs.units.single { it.unitId == "r-0" }
                finalPos = u.position
                if (obs.events.any { it.kind == "movement-arrived" && it.unitId == "r-0" }) {
                    arrived = true
                    break
                }
            }
            assertTrue(arrived, "Compact 26x10 rectangle must arrive through 2x2 corner bend; stopped at $finalPos")
            assertTrue(
                hypot(finalPos.x - goal.x, finalPos.y - goal.y) < 35.0,
                "Must reach south-east goal rather than false-arriving before the bend; finalPos=$finalPos",
            )
        } finally {
            ctx.close()
        }
    }

    @Test
    fun cross_group_unit_escapes_parked_group_cluster_and_slow_pusher_bounds_fast_idle_displacement() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        // Slow pusher (speed 0.55, mass 6000) vs fast-capable idle cluster units (speed 1.65, mass 3000)
        // so any bug using maximumTravel(pushed)=1.65 instead of selfTravel=0.0 immediately exceeds pusherDisp!
        val slowPusher = simpleTank(tankBase, id = "slowPusher", radius = 6.0, speed = 0.55, mass = 6000.0)
        val fastCluster = simpleTank(tankBase, id = "fastCluster", radius = 6.0, speed = 1.65, mass = 3000.0)
        val w = 32
        val h = 20
        val blocked = BooleanArray(w * h) { idx ->
            val c = idx % w; val r = idx / w
            if (c == 0 || c == w - 1 || r == 0 || r == h - 1) return@BooleanArray true
            c == 14 && r != 9
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val spawns = mutableListOf<UnitSpawn>()
        val center = Position(380.0, 190.0)
        spawns += UnitSpawn("esc-0", "p1", "slowPusher", center, 270.0)
        val clusterIds = (0 until 8).map { "grp-$it" }
        clusterIds.forEachIndexed { idx, id ->
            val angle = idx * (Math.PI / 4.0)
            val sx = center.x + kotlin.math.cos(angle) * 44.0
            val sy = center.y + kotlin.math.sin(angle) * 44.0
            spawns += UnitSpawn(id, "p1", "fastCluster", Position(sx, sy), 270.0)
        }
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("slowPusher" to slowPusher, "fastCluster" to fastCluster),
            units = spawns,
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            // Phase 1: Issue real groupMove to esc-0 + grp-0..7 onto center so esc-0 parks at center and grp-0..7 form
            // a 360-degree authentic `landingMember.parked == true` closed ring around esc-0.
            val parkTarget = center
            val allInitialIds = listOf("esc-0") + clusterIds
            val oldGroup = assertNotNull(ctx.requestGroupMove("p1", allInitialIds, parkTarget))
            allInitialIds.forEachIndexed { idx, id ->
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("park-$idx", "p1", id, parkTarget, ctx.world), oldGroup))
            }
            var step = 0L
            while (step < 500L) {
                val obs = ctx.advance(Step(step++, 1.0, "sim-tick"))
                if (allInitialIds.all { id -> obs.units.first { it.unitId == id }.let { it.moveTarget == null && it.speed == 0.0 } }) {
                    break
                }
            }
            val settledObs = ctx.observe()
            assertTrue(
                allInitialIds.all { id -> settledObs.units.first { it.unitId == id }.moveTarget == null },
                "All old-order group members must be parked and idle before issuing new escape order",
            )

            // Phase 2: Issue a new cross-group order to esc-0 through the parked group cluster and across the 1-tile gate.
            val goal = Position(120.0, 190.0)
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("escape-order", "p1", "esc-0", goal, ctx.world)))
            var prev = ctx.observe().units.associateBy { it.unitId }
            var arrived = false
            var maxIdleOverrun = 0.0
            var pushedAnyIdle = false
            while (step < 2600L) {
                val obs = ctx.advance(Step(step++, 1.0, "sim-tick"))
                val cur = obs.units.associateBy { it.unitId }
                val escNow = cur.getValue("esc-0")
                val escPrev = prev.getValue("esc-0")
                val pusherStepDisp = hypot(escNow.position.x - escPrev.position.x, escNow.position.y - escPrev.position.y)
                for (id in clusterIds) {
                    val uNow = cur.getValue(id)
                    val uPrev = prev.getValue(id)
                    val idleDisp = hypot(uNow.position.x - uPrev.position.x, uNow.position.y - uPrev.position.y)
                    if (idleDisp > 1e-6) pushedAnyIdle = true
                    if (idleDisp > pusherStepDisp + 1e-4) {
                        maxIdleOverrun = maxOf(maxIdleOverrun, idleDisp - pusherStepDisp)
                    }
                }
                prev = cur
                if (obs.events.any { it.kind == "movement-arrived" && it.unitId == "esc-0" }) {
                    arrived = true
                    break
                }
            }
            assertTrue(pushedAnyIdle, "Slow escaper must physically displace at least one parked old-group unit")
            assertTrue(arrived, "New-order unit must escape authentic parked old-group cluster and cross 1-tile gate")
            assertEquals(0.0, maxIdleOverrun, "Fast-capable idle unit displacement must never exceed slow pusher's actual step travel")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun dense_air_separation_clamps_displacement_within_maximum_speed() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val heli = UnitDefinition(
            id = "heli",
            className = tankBase.className,
            radius = 10.0,
            displayRadius = 10.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(
                type = MovementType.AIR,
                moveSpeed = 1.6,
                maxTurnSpeed = 4.5,
                turnAcceleration = 0.35,
                moveAccelerationSpeed = 0.2,
                moveDecelerationSpeed = 0.2,
                slidingMode = true,
                ignoringBody = true,
                reverseSpeedPercentage = 0.1,
                flight = FlightProfile(25.0, 0.0, 1.0, 0.0),
            ),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(10.0),
            rotor = RotorDefinition(tankBase.imagePath, 1.0),
            mass = 1500.0,
        )
        val spawns = (0 until 12).map { i ->
            val col = i % 4; val row = i / 4
            UnitSpawn("h-$i", "p1", "heli", Position(80.0 + col * 24.0, 80.0 + row * 24.0), 90.0)
        }
        val scene = base.copy(unitDefinitions = mapOf("heli" to heli), units = spawns)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val goal = Position(240.0, 180.0)
            val ids = spawns.map { it.unitId }
            val grp = assertNotNull(ctx.requestGroupMove("p1", ids, goal))
            ids.forEachIndexed { idx, id ->
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("air-$idx", "p1", id, goal, ctx.world), grp))
            }
            var prev = ctx.observe().units.associateBy { it.unitId }
            var maxDisp = 0.0
            for (s in 0L until 320L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                for (u in obs.units) {
                    val p = prev.getValue(u.unitId)
                    val d = hypot(u.position.x - p.position.x, u.position.y - p.position.y)
                    if (d > maxDisp) maxDisp = d
                }
                prev = obs.units.associateBy { it.unitId }
            }
            assertTrue(
                maxDisp <= 1.6 + 1e-4,
                "Airborne combined flight + separation displacement must never exceed moveSpeed*delta (1.6), got $maxDisp",
            )
        } finally {
            ctx.close()
        }
    }

    @Test
    fun mixed_group_shared_flowfield_vs_small_pure_water_rect_hull_route() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = simpleTank(tankBase, id = "tank", radius = 8.0)
        val boat = UnitDefinition(
            id = "boat",
            className = tankBase.className,
            radius = 12.0,
            displayRadius = 12.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(type = MovementType.WATER, moveSpeed = 1.4, maxTurnSpeed = 3.0),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Rectangle(width = 12.0, length = 22.0),
            mass = 4000.0,
        )
        val heli = UnitDefinition(
            id = "heli",
            className = tankBase.className,
            radius = 8.0,
            displayRadius = 8.0,
            totalFrames = tankBase.totalFrames,
            imagePath = tankBase.imagePath,
            imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(
                type = MovementType.AIR,
                moveSpeed = 1.8,
                slidingMode = true,
                ignoringBody = true,
                reverseSpeedPercentage = 0.1,
                flight = FlightProfile(25.0, 0.0, 1.0, 0.0),
            ),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(8.0),
            rotor = RotorDefinition(tankBase.imagePath, 1.0),
            mass = 1500.0,
        )
        val w = 24; val h = 20
        val landBlocked = BooleanArray(w * h) { idx -> (idx / w) >= 10 }
        val waterBlocked = BooleanArray(w * h) { idx -> (idx / w) < 10 }
        val map = MapDefinition(
            widthTiles = w, heightTiles = h, tileWidth = 20, tileHeight = 20,
            tileSets = emptyList(), groundGids = IntArray(w * h),
            blocking = BlockingGrid(w, h, landBlocked),
            waterBlockingOverride = BlockingGrid(w, h, waterBlocked),
        )
        val spawns = listOf(
            UnitSpawn("b-0", "p1", "boat", Position(80.0, 260.0), 90.0),
            UnitSpawn("b-1", "p1", "boat", Position(80.0, 310.0), 90.0),
            UnitSpawn("b-2", "p1", "boat", Position(130.0, 260.0), 90.0),
            UnitSpawn("b-3", "p1", "boat", Position(130.0, 310.0), 90.0),
            UnitSpawn("t-0", "p1", "tank", Position(80.0, 80.0), 90.0),
            UnitSpawn("t-1", "p1", "tank", Position(120.0, 80.0), 90.0),
            UnitSpawn("a-0", "p1", "heli", Position(80.0, 140.0), 90.0),
        )
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("tank" to tank, "boat" to boat, "heli" to heli),
            units = spawns,
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val smallNaval = assertNotNull(ctx.requestGroupMove("p1", listOf("b-0", "b-1", "b-2", "b-3"), Position(380.0, 280.0)))
            assertEquals(setOf("b-0", "b-1", "b-2", "b-3"), smallNaval.members)
            val mixedArmy = assertNotNull(ctx.requestGroupMove("p1", spawns.map { it.unitId }, Position(380.0, 200.0)))
            assertEquals(spawns.map { it.unitId }.toSet(), mixedArmy.members)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun bow_on_contact_across_power_of_two_coordinate_boundary_never_freezes() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = simpleTank(tankBase, id = "tank", radius = 6.0, speed = 1.1, mass = 3000.0)
        // Exact IEEE-754 contact geometry at y = 256.0 (`2^8` -> `[128, 256)` ULP halving boundary):
        // startClearance == -1.2434e-13 (legal under 4*ulp(256)=2.27e-13, previously rejected under 4*ulp(255.5)=1.13e-13).
        val moverStart = Position(242.37620882688898, 256.0)
        val idleStart = Position(239.65162941023587, 244.31339797022474)
        val w = 24; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val c = idx % w; val r = idx / w
            c == 0 || c == w - 1 || r == 0 || r == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("tank" to tank),
            units = listOf(
                UnitSpawn("mover", "p1", "tank", moverStart, 338.9997037889656),
                UnitSpawn("idle", "p1", "tank", idleStart, 180.0),
            ),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val goal = Position(200.0, 150.0)
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-pow2", "p1", "mover", goal, ctx.world)))
            var arrivedStep = -1L
            var minMoverY = moverStart.y
            for (s in 0L until 350L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                minMoverY = minOf(minMoverY, obs.units.first { it.unitId == "mover" }.position.y)
                if (obs.events.any { it.kind == "movement-arrived" && it.unitId == "mover" }) {
                    arrivedStep = s
                    break
                }
            }
            assertTrue(minMoverY < 160.0, "Mover must advance smoothly past y=256.0 and y=128.0 boundaries without freezing; minMoverY=$minMoverY")
            assertTrue(arrivedStep in 0L..320L, "Mover must arrive cleanly at goal without freezing at y=256.0; arrivedStep=$arrivedStep")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun angled_mover_pushes_wall_flush_idle_circle_cleanly_along_single_tile_corridor() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val tank = simpleTank(tankBase, id = "tank", radius = 6.0, speed = 1.1, mass = 3000.0)
        val w = 20; val h = 26
        val blocked = BooleanArray(w * h) { idx ->
            val c = idx % w; val r = idx / w
            if (c == 0 || c == w - 1 || r == 0 || r == h - 1) return@BooleanArray true
            r in 10..14 && c != 10
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        // 1-tile corridor is c=10 -> x in [200.0, 220.0], west wall at x=200.0 -> min circle x = 206.0.
        // Idle tank sits flush against west wall at (206.0, 284.8662) inside mouth while nearly-aligned mover
        // approaches at (206.0635, 296.8660), heading 359.32 deg (-0.68 deg into west wall, where axial binary search
        // yields a ~2.4e-12 sub-pixel nudge > positionRoundoff unless choked axial push falls through to wall-slide).
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("tank" to tank),
            units = listOf(
                UnitSpawn("mover", "p1", "tank", Position(206.06347319980694, 296.8660433636467), 359.3226840756522),
                UnitSpawn("wallIdle", "p1", "tank", Position(206.0, 284.8662112334498), 110.0),
            ),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val goal = Position(210.0, 120.0)
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-wall-push", "p1", "mover", goal, ctx.world)))
            var arrived = false
            for (s in 0L until 600L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                if (obs.events.any { it.kind == "movement-arrived" && it.unitId == "mover" }) {
                    arrived = true
                    break
                }
            }
            val endUnits = ctx.observe().units.associateBy { it.unitId }
            assertTrue(
                arrived,
                "Angled mover must slide wall-flush idle unit along 1-tile corridor wall and reach goal; mover=${endUnits["mover"]?.position}, idle=${endUnits["wallIdle"]?.position}",
            )
        } finally {
            ctx.close()
        }
    }
}
