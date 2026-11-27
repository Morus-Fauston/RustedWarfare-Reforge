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
import kotlin.test.assertTrue

/**
 * Contract & behavior tests for ENG-P016-007:
 * 1. Wide-corridor / open-field 0-stop real-time oblique overtaking (fast scout overtakes slow heavy without bumping/60-tick wait)
 * 2. Tiered reciprocal responsibility (agile small bears 0.75..0.90 yield against giant slow heavy; heavy stays smooth; no crush damage)
 * 3. Single-lane narrow bridge outside lateral clearing (waiting side steps out of outlet lane so exiting traffic clears cleanly)
 * 4. Combat stand-ground (0 yield when stationary firing) & ranged rear-row arc fanning + no backing away from visible enemy target
 * 5. Independent AIR-layer lightweight 2D Boids soft separation (cruising/rallying air flock spreads cleanly, landing aircraft unaffected)
 */
class MixedOvertakeAndAirSeparationTest {

    private fun makeCircleUnit(
        base: UnitDefinition,
        id: String,
        radius: Double,
        speed: Double,
        turnSpeed: Double,
        mass: Double,
        combat: CombatProfile? = null,
    ): UnitDefinition = UnitDefinition(
        id = id,
        className = base.className,
        radius = radius,
        displayRadius = radius,
        totalFrames = base.totalFrames,
        imagePath = base.imagePath,
        imageScale = base.imageScale,
        movement = base.movement.copy(
            type = MovementType.LAND,
            moveSpeed = speed,
            maxTurnSpeed = turnSpeed,
            turnAcceleration = max(0.2, turnSpeed * 0.1),
        ),
        retainedUnconsumed = emptySet(),
        combat = combat,
        movementCollision = MovementCollisionShape.Circle(radius),
        mass = mass,
    )

    @Test
    fun fast_agile_scout_overtakes_slow_heavy_on_wide_road_without_bumping_or_replan_stall() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val fastScout = makeCircleUnit(tankBase, "scout", radius = 6.0, speed = 1.65, turnSpeed = 5.5, mass = 1200.0)
        val slowHeavy = makeCircleUnit(tankBase, "heavy", radius = 14.0, speed = 0.55, turnSpeed = 1.6, mass = 18000.0)

        // 5-tile wide (100u) straight horizontal corridor: ample room to overtake cleanly
        val w = 48; val h = 15
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y <= 4 || y >= 10
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = listOf(
            UnitSpawn("heavy-lead", "p1", "heavy", Position(220.0, 150.0), 90.0),
            UnitSpawn("scout-1", "p1", "scout", Position(130.0, 150.0), 90.0),
            UnitSpawn("scout-2", "p1", "scout", Position(100.0, 150.0), 90.0),
        )
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("scout" to fastScout, "heavy" to slowHeavy),
            units = units,
        )
        val init = SimulationContext.initialize(scene)
        val ctx = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-h", "p1", "heavy-lead", Position(840.0, 150.0), ctx.world)))
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-s1", "p1", "scout-1", Position(860.0, 140.0), ctx.world)))
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-s2", "p1", "scout-2", Position(860.0, 160.0), ctx.world)))

            var scout1OvertakeStep: Long? = null
            var scout2OvertakeStep: Long? = null
            var scoutBlockedEvents = 0
            var heavyMaxYDev = 0.0
            var scout1MinSpeedDuringPass = Double.POSITIVE_INFINITY
            for (s in 0L until 700L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                val hUnit = obs.units.first { it.unitId == "heavy-lead" }
                val s1 = obs.units.first { it.unitId == "scout-1" }
                val s2 = obs.units.first { it.unitId == "scout-2" }
                heavyMaxYDev = max(heavyMaxYDev, abs(hUnit.position.y - 150.0))
                if (s in 40L..250L && s1.moveTarget != null) {
                    scout1MinSpeedDuringPass = min(scout1MinSpeedDuringPass, s1.speed)
                }
                if (scout1OvertakeStep == null && s1.position.x > hUnit.position.x + 25.0) scout1OvertakeStep = s
                if (scout2OvertakeStep == null && s2.position.x > hUnit.position.x + 25.0) scout2OvertakeStep = s
                scoutBlockedEvents += obs.events.count { it.kind == "movement-blocked" && it.unitId?.startsWith("scout-") == true }
            }
            println("overtake: s1Step=$scout1OvertakeStep s2Step=$scout2OvertakeStep blocked=$scoutBlockedEvents heavyMaxYDev=$heavyMaxYDev s1MinSpd=$scout1MinSpeedDuringPass")
            assertTrue(scout1OvertakeStep != null && scout1OvertakeStep < 320L,
                "Fast scout-1 must overtake slow heavy quickly via 2D ORCA, step=$scout1OvertakeStep")
            assertTrue(scout2OvertakeStep != null && scout2OvertakeStep < 360L,
                "Fast scout-2 must overtake slow heavy quickly via 2D ORCA, step=$scout2OvertakeStep")
            assertTrue(scoutBlockedEvents == 0,
                "Zero hard-contact bumping expected during wide-corridor overtake, got $scoutBlockedEvents")
            assertTrue(heavyMaxYDev < 12.0,
                "Heavy lead trajectory should remain smooth while nimble scouts yield around it (dev=$heavyMaxYDev)")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun tiered_reciprocal_responsibility_assigns_high_yield_to_agile_small_vs_heavy_giant_and_zero_crush_damage() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val small = makeCircleUnit(tankBase, "small", radius = 6.0, speed = 1.6, turnSpeed = 5.5, mass = 1200.0)
        val giant = makeCircleUnit(tankBase, "giant", radius = 18.0, speed = 0.55, turnSpeed = 1.2, mass = 45000.0)
        val smallUnit = MutableUnit("s", "p1", "small", small, Position(100.0, 100.0), 90.0)
        val giantUnit = MutableUnit("g", "p1", "giant", giant, Position(150.0, 100.0), 270.0)
        val giantSnap = FrozenSpatialUnit(
            unitId = "g", updateKey = "g", ownerPlayerId = "p1",
            position = giantUnit.position, heading = 270.0, speed = 0.55,
            flightVelocity = Position(0.0, 0.0), hardRadius = 18.0, bufferRadius = 20.0,
            layer = SpatialLayer.SURFACE, moving = true, nativeTransportPlacement = false,
        )
        val smallSnap = FrozenSpatialUnit(
            unitId = "s", updateKey = "s", ownerPlayerId = "p1",
            position = smallUnit.position, heading = 90.0, speed = 1.6,
            flightVelocity = Position(0.0, 0.0), hardRadius = 6.0, bufferRadius = 8.0,
            layer = SpatialLayer.SURFACE, moving = true, nativeTransportPlacement = false,
        )
        val smallResp = LocomotionModule.computeResponsibility(smallUnit, 6.0, giantSnap, giantUnit, peerAnchored = false)
        val giantResp = LocomotionModule.computeResponsibility(giantUnit, 18.0, smallSnap, smallUnit, peerAnchored = false)
        println("responsibility: small=$smallResp giant=$giantResp")
        assertTrue(smallResp in 0.75..0.90, "Agile small unit must bear 0.75..0.90 yield responsibility against giant, got $smallResp")
        assertTrue(giantResp in 0.10..0.25, "Giant slow unit must bear 0.10..0.25 yield responsibility against small, got $giantResp")

        // Verify crossing simulation causes zero HP loss (no unintended crush damage)
        val w = 36; val h = 20
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("small" to small, "giant" to giant),
            units = listOf(
                UnitSpawn("s1", "p1", "small", Position(140.0, 200.0), 90.0),
                UnitSpawn("g1", "p1", "giant", Position(420.0, 200.0), 270.0),
            ),
        )
        val init = SimulationContext.initialize(scene)
        val ctx = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("ms", "p1", "s1", Position(520.0, 200.0), ctx.world)))
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("mg", "p1", "g1", Position(120.0, 200.0), ctx.world)))
            var endObs: Observation? = null
            var giantMaxDev = 0.0
            var smallMaxDev = 0.0
            for (step in 0L until 700L) {
                val obs = ctx.advance(Step(step, 1.0, "sim-tick"))
                endObs = obs
                giantMaxDev = max(giantMaxDev, abs(obs.units.first { it.unitId == "g1" }.position.y - 200.0))
                smallMaxDev = max(smallMaxDev, abs(obs.units.first { it.unitId == "s1" }.position.y - 200.0))
            }
            val end = checkNotNull(endObs)
            assertEquals(2, end.units.size, "Both units must survive crossing with zero crush deaths")
            assertTrue(smallMaxDev > giantMaxDev,
                "Small agile unit must yield laterally more than giant (smallDev=$smallMaxDev, giantDev=$giantMaxDev)")
            assertTrue(end.units.all { it.moveTarget == null }, "Both small and giant must arrive cleanly")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun single_lane_narrow_bridge_waiting_unit_clears_mouth_outlet_laterally_so_exiting_traffic_passes() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        // Radius 8 => diameter 16, twoHullWidth = 32 > 1-tile bridge width 20 => single-lane narrow bridge!
        val tank = makeCircleUnit(tankBase, "tank", radius = 8.0, speed = 1.2, turnSpeed = 4.5, mass = 3000.0)
        val w = 36; val h = 21
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else if (x in 14..21) y != 10 // 1-tile wide bridge at row 10 (y in 200..220)
            else false
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        // Eastbound unit starts closer to (or entering) the west mouth; Westbound unit sits right outside the east mouth!
        val scene = base.copy(
            map = map,
            players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies")),
            unitDefinitions = mapOf("tank" to tank),
            units = listOf(
                UnitSpawn("east-1", "p1", "tank", Position(295.0, 210.0), 90.0), // inside/entering west mouth
                UnitSpawn("west-1", "p2", "tank", Position(465.0, 210.0), 270.0), // directly outside east mouth centerline
            ),
        )
        val init = SimulationContext.initialize(scene)
        val ctx = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("me", "p1", "east-1", Position(580.0, 210.0), ctx.world)))
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("mw", "p2", "west-1", Position(140.0, 210.0), ctx.world)))
            var westMaxLateralWhileWaiting = 0.0
            var endObs: Observation? = null
            for (s in 0L until 900L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                endObs = obs
                val wUnit = obs.units.first { it.unitId == "west-1" }
                val eUnit = obs.units.first { it.unitId == "east-1" }
                if (eUnit.position.x < 460.0) {
                    westMaxLateralWhileWaiting = max(westMaxLateralWhileWaiting, abs(wUnit.position.y - 210.0))
                }
            }
            val end = checkNotNull(endObs)
            val eEnd = end.units.first { it.unitId == "east-1" }
            val wEnd = end.units.first { it.unitId == "west-1" }
            println("narrow bridge: westLateralClear=$westMaxLateralWhileWaiting east=${eEnd.position} west=${wEnd.position}")
            assertTrue(westMaxLateralWhileWaiting >= 12.0,
                "Waiting unit outside narrow bridge mouth must shift laterally clear of outlet (>=12u), got $westMaxLateralWhileWaiting")
            assertTrue(eEnd.moveTarget == null && eEnd.position.x > 550.0, "Eastbound unit must exit bridge and arrive")
            assertTrue(wEnd.moveTarget == null && wEnd.position.x < 170.0, "Westbound unit must cross after east exits and arrive")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun combat_firing_front_line_holds_ground_while_rear_ranged_units_fan_along_range_arc() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val gun = CombatProfile(
            maxHp = 400.0,
            attackRange = 120.0,
            shootDelay = 30.0,
            turretSize = 10.0,
            turretTurnSpeed = 8.0,
            turretTurnAcceleration = 1.0,
            projectile = ProjectileProfile(
                directDamage = 5.0,
                life = 40.0,
                speed = 8.0,
            ),
            turretImagePath = null,
            deadImagePath = null,
            turretImageScale = 1.0,
        )
        val shooter = makeCircleUnit(tankBase, "shooter", radius = 8.0, speed = 1.2, turnSpeed = 4.5, mass = 3000.0, combat = gun)
        val targetDummy = makeCircleUnit(tankBase, "dummy", radius = 10.0, speed = 0.4, turnSpeed = 1.0, mass = 10000.0, combat = gun.copy(maxHp = 5000.0, attackRange = 10.0, projectile = gun.projectile.copy(directDamage = 0.0)))

        val w = 36; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        // Front shooter already in firing range of enemy at (460, 240); 4 rear shooters start in a single column behind it.
        val units = listOf(
            UnitSpawn("front-0", "p1", "shooter", Position(355.0, 240.0), 90.0),
            UnitSpawn("rear-1", "p1", "shooter", Position(315.0, 240.0), 90.0),
            UnitSpawn("rear-2", "p1", "shooter", Position(285.0, 240.0), 90.0),
            UnitSpawn("rear-3", "p1", "shooter", Position(255.0, 240.0), 90.0),
            UnitSpawn("rear-4", "p1", "shooter", Position(225.0, 240.0), 90.0),
            UnitSpawn("enemy-1", "p2", "dummy", Position(460.0, 240.0), 270.0),
        )
        val scene = base.copy(
            map = map,
            players = listOf(Player("p1", PlayerControl.LOCAL, "t1"), Player("p2", PlayerControl.LOCAL, "t2")),
            unitDefinitions = mapOf("shooter" to shooter, "dummy" to targetDummy),
            units = units,
            combatContract = CombatContract(12345, -1, units.mapIndexed { idx, u -> u.unitId to (idx + 1L) }.toMap(), 100L),
        )
        val init = SimulationContext.initialize(scene)
        val ctx = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            for (id in listOf("front-0", "rear-1", "rear-2", "rear-3", "rear-4")) {
                assertIs<ActionResult.Accepted>(ctx.submit(AttackAction("atk-$id", "p1", id, "enemy-1", ctx.world)))
            }
            var frontMaxDrift = 0.0
            var endObs: Observation? = null
            for (s in 0L until 360L) {
                val obs = ctx.advance(Step(s, 1.0, "sim-tick"))
                endObs = obs
                val f0 = obs.units.first { it.unitId == "front-0" }
                frontMaxDrift = max(frontMaxDrift, hypot(f0.position.x - 355.0, f0.position.y - 240.0))
            }
            val end = checkNotNull(endObs)
            val p1Units = end.units.filter { it.ownerPlayerId == "p1" }
            val ySpan = p1Units.maxOf { it.position.y } - p1Units.minOf { it.position.y }
            val inRangeCount = p1Units.count { hypot(it.position.x - 460.0, it.position.y - 240.0) <= 122.0 }
            println("combat arc: frontDrift=$frontMaxDrift ySpan=$ySpan inRange=$inRangeCount/5")
            assertTrue(frontMaxDrift <= 2.0, "Stationary firing front unit must hold ground cleanly (drift=$frontMaxDrift)")
            assertTrue(ySpan >= 28.0, "Rear ranged units must fan laterally along range arc (>=28u), got $ySpan")
            assertEquals(5, inRangeCount, "All 5 ranged units must enter firing range without queueing dead behind front-0")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun air_layer_lightweight_soft_separation_spreads_rallying_air_flock_without_single_pixel_stacking() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val heli = HelicopterCombatScene.unit()
        val w = 36; val h = 24
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, BooleanArray(w * h)))
        val units = (0 until 8).map { i ->
            UnitSpawn("air-$i", "p1", "helicopter", Position(140.0 + (i % 4) * 18.0, 220.0 + (i / 4) * 18.0), 90.0)
        }
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("helicopter" to heli),
            units = units,
            combatContract = CombatContract(12345, -1, units.mapIndexed { idx, u -> u.unitId to (idx + 1L) }.toMap(), 100L),
        )
        val init = SimulationContext.initialize(scene)
        val ctx = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            val rally = Position(500.0, 240.0)
            for (i in 0 until 8) {
                assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("fly-$i", "p1", "air-$i", rally, ctx.world)))
            }
            var endObs: Observation? = null
            for (s in 0L until 380L) {
                endObs = ctx.advance(Step(s, 1.0, "sim-tick"))
            }
            val end = checkNotNull(endObs)
            val airPos = end.units.map { it.position }
            var minPairDist = Double.POSITIVE_INFINITY
            for (i in airPos.indices) {
                for (j in i + 1 until airPos.size) {
                    minPairDist = min(minPairDist, hypot(airPos[i].x - airPos[j].x, airPos[i].y - airPos[j].y))
                }
            }
            val maxDistFromRally = airPos.maxOf { hypot(it.x - rally.x, it.y - rally.y) }
            println("air flock: minPairDist=$minPairDist maxDistFromRally=$maxDistFromRally")
            // Helicopter radius = 15u (diameter 30u); 8 choppers in a clean non-overlapping cluster
            // must maintain >= 28u pair spacing and remain compactly clustered around the rally click.
            assertTrue(minPairDist >= 28.0,
                "Airborne flock rallying on same click must separate cleanly via AIR-layer soft Boids (>=28u for r=15 choppers), got $minPairDist")
            assertTrue(maxDistFromRally <= 160.0,
                "Airborne flock should stay clustered around rally point (<=160u), got $maxDistFromRally")
        } finally {
            ctx.close()
        }
    }
}
