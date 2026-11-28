package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.navigation.*
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.*

/**
 * Hard geometric contract tests for ENG-P017-008:
 * 1. Continuous no-rally production (>= 25 tanks, single & twin factories) fans into a wide 2D sheet with zero doorway stalls.
 * 2. Continuous with-rally production (>= 20 tanks) hands off across the legacy y+90 zone without braking to zero.
 * 3. Collinear pushing into an idle column triggers pairwise-normal + lateral buckling into a 2D sheet obeying ADR-009.
 */
class FactoryFanOutAndSheetPushTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fastLandFactoryScenario(
        mapWidth: Int = 50,
        mapHeight: Int = 45,
        factoryPositions: List<Pair<String, Position>> = listOf("f1" to Position(510.0, 210.0)),
    ): Scenario {
        val base = BuildingTestContent.scenario()
        val fillGid = base.map.groundGids.firstOrNull() ?: 1
        val map = MapDefinition(
            mapWidth, mapHeight, 20, 20, base.map.tileSets,
            IntArray(mapWidth * mapHeight) { fillGid },
            BlockingGrid(mapWidth, mapHeight, BooleanArray(mapWidth * mapHeight)),
        )
        val rawTank = base.unitDefinitions.getValue("c_tank")
        val tank = UnitDefinition(
            id = "c_tank",
            className = rawTank.className,
            radius = 6.0,
            displayRadius = 6.0,
            totalFrames = rawTank.totalFrames,
            imagePath = rawTank.imagePath,
            imageScale = rawTank.imageScale,
            movement = rawTank.movement.copy(moveSpeed = 1.15, moveAccelerationSpeed = 0.08, moveDecelerationSpeed = 0.08, maxTurnSpeed = 6.0),
            retainedUnconsumed = emptySet(),
            fogOfWarSightRange = 15,
            combat = rawTank.combat,
            durability = rawTank.durability,
            build = BuildProfile(100L, 0.025f), // 40 ticks per tank -> fast continuous stream
            movementCollision = MovementCollisionShape.Circle(6.0),
            mass = 3000.0,
        )
        val rawFactory = base.unitDefinitions.getValue("landFactory")
        val factory = UnitDefinition(
            id = rawFactory.id,
            className = rawFactory.className,
            radius = rawFactory.radius,
            displayRadius = rawFactory.displayRadius,
            totalFrames = rawFactory.totalFrames,
            imagePath = rawFactory.imagePath,
            imageScale = rawFactory.imageScale,
            movement = rawFactory.movement,
            retainedUnconsumed = rawFactory.retainedUnconsumed,
            fogOfWarSightRange = rawFactory.fogOfWarSightRange,
            combat = rawFactory.combat,
            durability = rawFactory.durability,
            building = rawFactory.building,
            production = checkNotNull(rawFactory.production).let { p ->
                ProductionProfile(listOf("c_tank"), p.exitProfile, p.speedMultiplier, rallyEnabled = false)
            },
            build = rawFactory.build,
            construction = rawFactory.construction,
            directHitTargetKind = rawFactory.directHitTargetKind,
        )
        val ids = factoryPositions.mapIndexed { idx, (id, _) -> id to (10L + idx) }.toMap()
        return base.copy(
            map = map,
            unitDefinitions = mapOf(tank.id to tank, factory.id to factory),
            units = factoryPositions.map { (id, pos) -> UnitSpawn(id, "p1", factory.id, pos, 0.0) },
            combatContract = CombatContract(12345, -1, ids, 100L),
            economyContract = EconomyContract(mapOf("p1" to 200000L, "p2" to 0L)),
        )
    }

    private fun fastAuthoredRallyFactoryScenario(
        mapWidth: Int = 55,
        mapHeight: Int = 45,
        factoryPos: Position = Position(250.0, 210.0),
    ): Scenario {
        val source = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("author-production/json/package")
        val copyRoot = tempDir.resolve("authored-rally-pack")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = copyRoot.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
        val factoryJson = copyRoot.resolve("units/spfactory.json")
        val doc = JsonValue.readJSON(Files.readString(factoryJson)).asObject().set("rally", true)
        Files.writeString(factoryJson, doc.toString())
        val loaded = ContentLoader.loadContentPack(copyRoot)
        val rawDefs = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
        val rawTank = rawDefs.getValue("c_tank")
        val fastTank = UnitDefinition(
            id = rawTank.id,
            className = rawTank.className,
            radius = 6.0,
            displayRadius = 6.0,
            totalFrames = rawTank.totalFrames,
            imagePath = rawTank.imagePath,
            imageScale = rawTank.imageScale,
            movement = rawTank.movement.copy(moveSpeed = 1.15, moveAccelerationSpeed = 0.08, moveDecelerationSpeed = 0.08, maxTurnSpeed = 6.0),
            retainedUnconsumed = emptySet(),
            fogOfWarSightRange = rawTank.fogOfWarSightRange,
            combat = rawTank.combat,
            durability = rawTank.durability,
            build = BuildProfile(100L, 0.025f),
            movementCollision = MovementCollisionShape.Circle(6.0),
            mass = 3000.0,
        )
        val base = TestContentFactory.openScenario()
        val fillGid = base.map.groundGids.firstOrNull() ?: 1
        val map = MapDefinition(
            mapWidth, mapHeight, 20, 20, base.map.tileSets,
            IntArray(mapWidth * mapHeight) { fillGid },
            BlockingGrid(mapWidth, mapHeight, BooleanArray(mapWidth * mapHeight)),
        )
        return base.copy(
            map = map,
            unitDefinitions = rawDefs + (fastTank.id to fastTank),
            units = listOf(UnitSpawn("f1", "p1", "spfactory", factoryPos, 0.0)),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            combatContract = CombatContract(12345, -1, mapOf("f1" to 10L), 100L),
            economyContract = EconomyContract(mapOf("p1" to 200000L, "p2" to 0L)),
        )
    }

    private fun hexNonce(i: Int): String = i.toString(16).padStart(32, '0')

    @Test
    fun no_rally_continuous_production_25_tanks_fans_into_2d_sheet_without_doorway_stall() {
        val factoryPos = Position(510.0, 210.0)
        val scene = fastLandFactoryScenario(factoryPositions = listOf("f1" to factoryPos))
        val init = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            val total = 25
            for (i in 1..total) {
                assertIs<ActionResult.Accepted>(
                    context.submit(ProduceAction("prod-$i", "p1", "f1", "c_tank", hexNonce(i), context.world)),
                )
            }
            var maxConsecutiveDoorwayStop = 0
            val doorwayStopStreak = mutableMapOf<String, Int>()
            val birthStepByUnit = mutableMapOf<String, Long>()
            var step = 0L
            while (step < 3200L) {
                val frame = context.advance(Step(step++, 1.0, "sim-tick"))
                val tanks = frame.units.filter { it.typeId == "c_tank" }
                for (t in tanks) {
                    if (t.unitId !in birthStepByUnit) birthStepByUnit[t.unitId] = step
                    val age = step - birthStepByUnit.getValue(t.unitId)
                    val stoppedInDoorway = age > 35L && t.moveTarget != null &&
                        t.position.y in (factoryPos.y + 36.0)..(factoryPos.y + 72.0) &&
                        abs(t.position.x - factoryPos.x) <= 22.0 &&
                        t.speed == 0.0
                    val streak = if (stoppedInDoorway) (doorwayStopStreak[t.unitId] ?: 0) + 1 else 0
                    doorwayStopStreak[t.unitId] = streak
                    if (streak > maxConsecutiveDoorwayStop) maxConsecutiveDoorwayStop = streak
                }
                if (tanks.size == total && tanks.all { it.moveTarget == null }) break
            }
            val finalObs = context.observe()
            val tanks = finalObs.units.filter { it.typeId == "c_tank" }
            assertEquals(total, tanks.size, "All 25 tanks must be produced")
            val activeTanks = tanks.filter { it.moveTarget != null }
            assertTrue(
                activeTanks.isEmpty(),
                "All 25 no-rally tanks must settle cleanly; active=${activeTanks.size} " +
                    "activePos=${activeTanks.map { "(%.1f,%.1f,s=%.2f)".format(it.position.x, it.position.y, it.speed) }} " +
                    "settledPos=${tanks.filter { it.moveTarget == null }.map { "(%.1f,%.1f)".format(it.position.x, it.position.y) }}",
            )
            assertTrue(
                maxConsecutiveDoorwayStop < 15,
                "Continuous no-rally tanks must not stall inside doorway: maxConsecutiveDoorwayStop=$maxConsecutiveDoorwayStop",
            )
            // Zero hard overlap
            for (i in tanks.indices) for (j in i + 1 until tanks.size) {
                val d = hypot(tanks[i].position.x - tanks[j].position.x, tanks[i].position.y - tanks[j].position.y)
                assertTrue(d >= 12.0 - 1e-4, "Zero hard overlap in settled fan: $d between ${tanks[i].unitId} and ${tanks[j].unitId}")
            }
            val minX = tanks.minOf { it.position.x }
            val maxX = tanks.maxOf { it.position.x }
            val minY = tanks.minOf { it.position.y }
            val maxY = tanks.maxOf { it.position.y }
            val spanX = maxX - minX
            val spanY = maxY - minY
            val leftCount = tanks.count { it.position.x < factoryPos.x - 10.0 }
            val rightCount = tanks.count { it.position.x > factoryPos.x + 10.0 }
            assertTrue(spanX >= 60.0, "Settled fan must widen laterally into a 2D sheet, not a 1D line: spanX=$spanX spanY=$spanY")
            assertTrue(leftCount >= 5 && rightCount >= 5, "Fan must spread across both wings: left=$leftCount right=$rightCount")
            assertTrue(spanY <= max(spanX * 2.2, 120.0), "Cluster must not degenerate into a narrow vertical snake: spanX=$spanX spanY=$spanY")

            // Verify player re-order cleanly detaches a produced unit from the factory staging cluster.
            val sampleUnit = tanks.maxBy { it.position.x }
            assertIs<ActionResult.Accepted>(
                context.submit(MoveAction("player-retarget", "p1", sampleUnit.unitId, Position(820.0, 260.0), context.world)),
            )
            repeat(400) { context.advance(Step(step++, 1.0, "sim-tick")) }
            val moved = context.observe().units.single { it.unitId == sampleUnit.unitId }
            assertTrue(moved.position.x > 700.0, "Player command must cleanly detach unit from factory fan cluster: ${moved.position}")
        } finally {
            context.close()
        }
    }

    @Test
    fun twin_factories_continuous_production_30_tanks_fans_into_wide_2d_sheet() {
        val f1 = Position(370.0, 210.0)
        val f2 = Position(610.0, 210.0)
        val scene = fastLandFactoryScenario(mapWidth = 55, mapHeight = 45, factoryPositions = listOf("f1" to f1, "f2" to f2))
        val init = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            val perFactory = 15
            for (i in 1..perFactory) {
                assertIs<ActionResult.Accepted>(
                    context.submit(ProduceAction("f1-$i", "p1", "f1", "c_tank", hexNonce(200 + i), context.world)),
                )
                assertIs<ActionResult.Accepted>(
                    context.submit(ProduceAction("f2-$i", "p1", "f2", "c_tank", hexNonce(300 + i), context.world)),
                )
            }
            var step = 0L
            while (step < 3200L) {
                val frame = context.advance(Step(step++, 1.0, "sim-tick"))
                val tanks = frame.units.filter { it.typeId == "c_tank" }
                if (tanks.size == perFactory * 2 && tanks.all { it.moveTarget == null }) break
            }
            val tanks = context.observe().units.filter { it.typeId == "c_tank" }
            assertEquals(perFactory * 2, tanks.size)
            assertTrue(tanks.all { it.moveTarget == null }, "All 30 twin-factory tanks must settle cleanly; active=${tanks.count { it.moveTarget != null }}")
            for (i in tanks.indices) for (j in i + 1 until tanks.size) {
                val d = hypot(tanks[i].position.x - tanks[j].position.x, tanks[i].position.y - tanks[j].position.y)
                assertTrue(d >= 12.0 - 1e-4, "Zero hard overlap across twin-factory fans: $d")
            }
            val leftCluster = tanks.filter { it.position.x < 490.0 }
            val rightCluster = tanks.filter { it.position.x >= 490.0 }
            assertTrue(leftCluster.size >= 10 && rightCluster.size >= 10, "Both factory fans must populate cleanly: L=${leftCluster.size} R=${rightCluster.size}")
            val leftSpanX = leftCluster.maxOf { it.position.x } - leftCluster.minOf { it.position.x }
            val rightSpanX = rightCluster.maxOf { it.position.x } - rightCluster.minOf { it.position.x }
            assertTrue(leftSpanX >= 45.0 && rightSpanX >= 45.0, "Both twin factory fans must spread laterally: L=$leftSpanX R=$rightSpanX")
        } finally {
            context.close()
        }
    }

    @Test
    fun with_rally_continuous_production_20_tanks_hands_off_without_braking_trough() {
        val factoryPos = Position(250.0, 210.0)
        val rallyPos = Position(760.0, 460.0)
        val scene = fastAuthoredRallyFactoryScenario(factoryPos = factoryPos)
        val init = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            assertIs<ActionResult.Accepted>(
                context.submit(UnitAction("set-rally", "p1", "f1", "setRally", context.world, target = rallyPos)),
            )
            val total = 20
            for (i in 1..total) {
                assertIs<ActionResult.Accepted>(
                    context.submit(ProduceAction("prod-$i", "p1", "f1", "c_tank", hexNonce(100 + i), context.world)),
                )
            }
            val minSpeedInLegacyHandoffZone = mutableMapOf<String, Double>()
            var step = 0L
            while (step < 3200L) {
                val frame = context.advance(Step(step++, 1.0, "sim-tick"))
                val tanks = frame.units.filter { it.typeId == "c_tank" }
                for (t in tanks) {
                    // Legacy handoff braking zone: y in [factory.y + 55, factory.y + 115] around old y+90 stop point
                    if (t.position.y in (factoryPos.y + 55.0)..(factoryPos.y + 115.0) &&
                        hypot(t.position.x - rallyPos.x, t.position.y - rallyPos.y) > 120.0
                    ) {
                        val prev = minSpeedInLegacyHandoffZone[t.unitId] ?: Double.POSITIVE_INFINITY
                        minSpeedInLegacyHandoffZone[t.unitId] = min(prev, t.speed)
                    }
                }
                if (tanks.size == total && tanks.all { it.moveTarget == null }) break
            }
            val finalObs = context.observe()
            val tanks = finalObs.units.filter { it.typeId == "c_tank" }
            assertEquals(total, tanks.size)
            assertTrue(
                tanks.all { it.moveTarget == null },
                "All 20 rally tanks must arrive and settle cleanly; active=${tanks.count { it.moveTarget != null }}",
            )
            // Every tank must settle near the rally point with zero overlap
            assertTrue(
                tanks.all { hypot(it.position.x - rallyPos.x, it.position.y - rallyPos.y) <= 130.0 },
                "All 20 tanks must settle around rally $rallyPos",
            )
            for (i in tanks.indices) for (j in i + 1 until tanks.size) {
                val d = hypot(tanks[i].position.x - tanks[j].position.x, tanks[i].position.y - tanks[j].position.y)
                assertTrue(d >= 12.0 - 1e-4, "Zero hard overlap at rally cluster: $d")
            }
            // Zero braking trough across the legacy y + 90 handoff zone!
            assertTrue(minSpeedInLegacyHandoffZone.size >= 15, "Must sample exit handoff speeds across stream")
            val zeroTroughs = minSpeedInLegacyHandoffZone.values.count { it < 0.35 }
            assertEquals(
                0,
                zeroTroughs,
                "Continuous rally stream must hand off smoothly without braking trough at y+90; minSpeeds=${minSpeedInLegacyHandoffZone.values.sorted().take(8)}",
            )
        } finally {
            context.close()
        }
    }

    @Test
    fun collinear_axis_push_into_idle_column_buckles_into_2d_sheet_with_zero_overlap() {
        val base = TestContentFactory.openScenario()
        // Place a solid backstop wall at row >= 20 (y >= 400) behind the idle column so the 10 pushers
        // genuinely compress the 10-unit collinear idle column instead of sliding it as an unbounded stick.
        val blocked = BooleanArray(45 * 45) { idx -> (idx / 45) >= 20 }
        val map = MapDefinition(45, 45, 20, 20, emptyList(), IntArray(45 * 45), BlockingGrid(45, 45, blocked))
        val rawTank = base.unitDefinitions.getValue("tank")
        val tank = UnitDefinition(
            id = "tank",
            className = rawTank.className,
            radius = 6.0,
            displayRadius = 6.0,
            totalFrames = rawTank.totalFrames,
            imagePath = rawTank.imagePath,
            imageScale = rawTank.imageScale,
            movement = rawTank.movement.copy(moveSpeed = 1.0, moveAccelerationSpeed = 0.25, moveDecelerationSpeed = 0.25, maxTurnSpeed = 8.0),
            retainedUnconsumed = emptySet(),
            fogOfWarSightRange = 15,
            movementCollision = MovementCollisionShape.Circle(6.0),
            mass = 3000.0,
        )
        // Arrange 10 strictly collinear idle units along x = 400.0 (zero initial lateral spread!)
        val idleIds = (0 until 10).map { "idle-$it" }
        val idleSpawns = idleIds.mapIndexed { idx, id ->
            UnitSpawn(id, "p1", tank.id, Position(400.0, 260.0 + idx * 12.2), 180.0)
        }
        // Arrange 3 spaced collinear pushers along the EXACT same x = 400.0 axis driving directly through
        // the idle column toward the backstop wall (zero initial lateral offset; spaced so trailing pushers
        // do not wrap wide and shove buckled wings back inward).
        val pusherIds = (0 until 3).map { "push-$it" }
        val pusherSpawns = pusherIds.mapIndexed { idx, id ->
            UnitSpawn(id, "p1", tank.id, Position(400.0, 170.0 + idx * 32.0), 180.0)
        }
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf(tank.id to tank),
            units = idleSpawns + pusherSpawns,
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            // Exercise pure UnitContact collinear multi-body pushing directly (bypassing ORCA steer-around)
            context.trafficSeparationEnabled = false
            for ((idx, id) in pusherIds.withIndex()) {
                assertIs<ActionResult.Accepted>(
                    context.submit(MoveAction("drive-$id", "p1", id, Position(400.0, 386.0 + idx * 0.5), context.world)),
                )
            }
            var prevById = context.observe().units.associate { it.unitId to it.position }
            for (step in 0L until 700L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                val units = frame.units
                // ADR-009: zero hard overlap & single-step displacement <= pusher maxSpeed
                for (i in units.indices) {
                    val u = units[i]
                    val prev = prevById.getValue(u.unitId)
                    val stepDist = hypot(u.position.x - prev.x, u.position.y - prev.y)
                    assertTrue(
                        stepDist <= 1.0 + 1e-5,
                        "ADR-009 single-step displacement cap violated at step $step for ${u.unitId}: $stepDist",
                    )
                    for (j in i + 1 until units.size) {
                        val d = hypot(u.position.x - units[j].position.x, u.position.y - units[j].position.y)
                        assertTrue(d >= 12.0 - 1e-4, "Zero hard overlap violated at step $step between ${u.unitId} and ${units[j].unitId}: $d")
                    }
                }
                prevById = units.associate { it.unitId to it.position }
            }
            val idleEnd = context.observe().units.filter { it.unitId in idleIds }
            val minX = idleEnd.minOf { it.position.x }
            val maxX = idleEnd.maxOf { it.position.x }
            val spanX = maxX - minX
            val leftShifted = idleEnd.count { it.position.x < 397.0 }
            val rightShifted = idleEnd.count { it.position.x > 403.0 }
            val pushEnd = context.observe().units.filter { it.unitId in pusherIds }
            assertTrue(
                spanX >= 18.0 && leftShifted >= 2 && rightShifted >= 2,
                "Collinear compressed idle column must buckle laterally into a 2D sheet (not 1D line): spanX=$spanX left=$leftShifted right=$rightShifted " +
                    "idle=${idleEnd.map { "(%.1f,%.1f)".format(it.position.x, it.position.y) }} " +
                    "push=${pushEnd.map { "(%.1f,%.1f,m=%s)".format(it.position.x, it.position.y, it.moveTarget != null) }}",
            )
        } finally {
            context.close()
        }
    }

    @Test
    fun unreachable_rally_emits_single_path_unreachable_event_and_clears_task() {
        val factoryPos = Position(250.0, 210.0)
        val base = fastAuthoredRallyFactoryScenario(factoryPos = factoryPos)
        // Seal the right half of the map behind a full-height vertical wall at column 25 so (610, 210) is truly unreachable.
        val blocked = BooleanArray(base.map.widthTiles * base.map.heightTiles) { idx ->
            val c = idx % base.map.widthTiles
            c == 25
        }
        val scene = base.copy(
            map = MapDefinition(
                base.map.widthTiles, base.map.heightTiles, base.map.tileWidth, base.map.tileHeight,
                base.map.tileSets, base.map.groundGids, BlockingGrid(base.map.widthTiles, base.map.heightTiles, blocked),
            ),
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(
                context.submit(UnitAction("set-rally", "p1", "f1", "setRally", context.world, target = Position(610.0, 210.0))),
            )
            assertIs<ActionResult.Accepted>(
                context.submit(ProduceAction("prod-1", "p1", "f1", "c_tank", hexNonce(701), context.world)),
            )
            val id = "produced:p1:${hexNonce(701)}"
            var unreachableCount = 0
            for (step in 0L until 400L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                unreachableCount += frame.events.count { it.kind == "path-unreachable" && it.unitId == id }
            }
            assertEquals(1, unreachableCount, "Unreachable rally must emit path-unreachable exactly once, not duplicate")
            val unit = context.observe().units.single { it.unitId == id }
            assertTrue(unit.tasks.isEmpty(), "Failed rally task must be popped cleanly: ${unit.tasks}")
            assertNull(unit.moveTarget, "Failed rally must not leave stale moveTarget")
        } finally {
            context.close()
        }
    }

    @Test
    fun north_rally_waits_for_producer_footprint_clearance_before_handoff() {
        val factoryPos = Position(310.0, 310.0)
        val northRally = Position(310.0, 120.0)
        val scene = fastAuthoredRallyFactoryScenario(factoryPos = factoryPos)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(
                context.submit(UnitAction("set-rally", "p1", "f1", "setRally", context.world, target = northRally)),
            )
            assertIs<ActionResult.Accepted>(
                context.submit(ProduceAction("prod-north", "p1", "f1", "c_tank", hexNonce(702), context.world)),
            )
            val id = "produced:p1:${hexNonce(702)}"
            var handoffY: Double? = null
            for (step in 0L until 900L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                val u = frame.units.firstOrNull { it.unitId == id } ?: continue
                if (handoffY == null && u.moveTarget == northRally) {
                    handoffY = u.position.y
                }
                if (u.moveTarget == null && hypot(u.position.x - northRally.x, u.position.y - northRally.y) <= 24.0) break
            }
            val clearedY = assertNotNull(handoffY, "Tank must hand off to north rally after clearing factory footprint")
            // Factory footprint centered at y=310 (3x3 tiles -> south edge y=340); tank radius=6 -> must clear y >= 345.
            assertTrue(
                clearedY >= 344.0,
                "Rally handoff must wait until hull physically clears producer south footprint edge (y>=344), got y=$clearedY",
            )
            val finalPos = context.observe().units.single { it.unitId == id }.position
            assertTrue(
                hypot(finalPos.x - northRally.x, finalPos.y - northRally.y) <= 24.0,
                "Tank must route around factory to north rally cleanly: $finalPos",
            )
        } finally {
            context.close()
        }
    }

    @Test
    fun mech_factory_alternating_exits_share_single_staging_cluster_and_fan_cleanly() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("mech-production/json")
        val set = assertIs<ContentResult.Success<LoadedContentSet>>(
            ContentLoader.loadContentPacks(listOf(root.resolve("production-package"))),
        ).value
        val rawMech = set.units.getValue("mechGun")
        val fastMech = UnitDefinition(
            id = rawMech.id, className = rawMech.className, radius = rawMech.radius, displayRadius = rawMech.displayRadius,
            totalFrames = rawMech.totalFrames, imagePath = rawMech.imagePath, imageScale = rawMech.imageScale,
            movement = rawMech.movement, retainedUnconsumed = rawMech.retainedUnconsumed,
            fogOfWarSightRange = rawMech.fogOfWarSightRange, combat = rawMech.combat, durability = rawMech.durability,
            mech = rawMech.mech, build = BuildProfile(100L, 0.025f), movementCollision = rawMech.movementCollision,
            mass = rawMech.mass, directHitTargetKind = rawMech.directHitTargetKind,
        )
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))).value
        val scene = Scenario(
            scenarioId = "eng-p017-008-mech-fan",
            map = map,
            unitDefinitions = set.units + (fastMech.id to fastMech),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            units = listOf(UnitSpawn("mf", "p1", "mechFactory", Position(310.0, 150.0), 0.0)),
            coordinateModel = CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            movementContract = MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("mf" to 8L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 20000L, "p2" to 0L)),
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (i in 1..6) {
                assertIs<ActionResult.Accepted>(
                    context.submit(ProduceAction("m-$i", "p1", "mf", "mechGun", hexNonce(800 + i), context.world)),
                )
            }
            var step = 0L
            while (step < 1400L) {
                val frame = context.advance(Step(step++, 1.0, "sim-tick"))
                val mechs = frame.units.filter { it.typeId == "mechGun" }
                if (mechs.size == 6 && mechs.all { it.moveTarget == null }) break
            }
            val mechs = context.observe().units.filter { it.typeId == "mechGun" }
            assertEquals(6, mechs.size)
            assertTrue(mechs.all { it.moveTarget == null }, "All 6 continuous mechs must settle cleanly")
            val minR = fastMech.movementCollision.boundingRadius * 2.0
            for (i in mechs.indices) for (j in i + 1 until mechs.size) {
                val d = hypot(mechs[i].position.x - mechs[j].position.x, mechs[i].position.y - mechs[j].position.y)
                assertTrue(d >= minR - 1e-4, "Zero hard overlap among fanned mechs: $d < $minR")
            }
            val spanX = mechs.maxOf { it.position.x } - mechs.minOf { it.position.x }
            assertTrue(spanX >= 50.0, "Alternating mech exits must fan cleanly: spanX=$spanX")
        } finally {
            context.close()
        }
    }

    @Test
    fun sea_factory_continuous_rectangular_ships_settle_without_corridor_deadlock() = WaterTestScene.withMap { rawMap ->
        // Use a taller 40-tile (800px) open water map (preserving left-half land / right-half water layout)
        // so 3 x 102px battleships do not hit the 400px south map edge.
        val tallH = 40
        val w = rawMap.widthTiles
        val landGid = rawMap.groundGids.first()
        val waterGid = rawMap.groundGids.last()
        val landCells = (0 until tallH).flatMap { r -> (0 until 20).map { c -> c to r } }.toSet()
        val map = MapDefinition(
            w, tallH, rawMap.tileWidth, rawMap.tileHeight,
            rawMap.tileSets,
            IntArray(w * tallH) { idx -> if (idx % w < 20) landGid else waterGid },
            BlockingGrid(w, tallH, BooleanArray(w * tallH)),
            landCells,
        )
        val base = SeaFactoryScene.scenario(map, "json", "seaFactoryT2")
        val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val packUnits = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(inputs.resolve("phase-seven-single-ship/json/package")),
        ).value.units
        val rawShip = packUnits.getValue("heavyBattleship")
        val fastShip = UnitDefinition(
            id = rawShip.id, className = rawShip.className, radius = rawShip.radius, displayRadius = rawShip.displayRadius,
            totalFrames = rawShip.totalFrames, imagePath = rawShip.imagePath, imageScale = rawShip.imageScale,
            movement = rawShip.movement, retainedUnconsumed = rawShip.retainedUnconsumed,
            fogOfWarSightRange = rawShip.fogOfWarSightRange, combat = rawShip.combat, durability = rawShip.durability,
            build = BuildProfile(100L, 0.005f), movementCollision = rawShip.movementCollision,
            explicitMovementCollision = rawShip.explicitMovementCollision,
            mass = rawShip.mass, directHitTargetKind = rawShip.directHitTargetKind,
        )
        val scene = base.copy(
            unitDefinitions = packUnits + (fastShip.id to fastShip),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            combatContract = CombatContract(12345, -1, base.units.mapIndexed { i, u -> u.unitId to i + 8L }.toMap(), 20L),
            economyContract = EconomyContract(mapOf("p1" to 30000L, "p2" to 0L)),
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (i in 1..3) {
                assertIs<ActionResult.Accepted>(
                    context.submit(ProduceAction("s-$i", "p1", "factory", "heavyBattleship", hexNonce(900 + i), context.world)),
                )
            }
            var step = 0L
            while (step < 4500L) {
                val frame = context.advance(Step(step++, 1.0, "sim-tick"))
                val ships = frame.units.filter { it.typeId == "heavyBattleship" }
                if (ships.size == 3 && ships.all { it.moveTarget == null }) break
            }
            val ships = context.observe().units.filter { it.typeId == "heavyBattleship" }
            assertEquals(3, ships.size)
            assertTrue(
                ships.all { it.moveTarget == null },
                "All 3 continuous rectangular battleships must settle outside seaFactory dock without corridor deadlock: " +
                    ships.map { "${it.position}/${it.moveTarget}" },
            )
            // Verify every settled battleship cleared the seaFactory footprint south edge (y=200)
            assertTrue(ships.all { it.position.y > 220.0 }, "All ships must leave the seaFactory dock: ${ships.map { it.position }}")
        } finally {
            context.close()
        }
    }
}
