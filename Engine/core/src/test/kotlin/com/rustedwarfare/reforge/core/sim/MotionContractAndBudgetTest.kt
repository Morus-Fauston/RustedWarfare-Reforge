package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.TransportProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.path.PathSearchReason
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ENG-P017-005 contract & large-map (`350x350`) step-budget / rollback verification:
 * 1. 50 pursuers chasing a continuously moving target across obstacles on a `350x350` map stay within
 *    the per-step planning budget, slice work across ticks, avoid every-tick replanning storms via
 *    target-cell hysteresis + tail splice, and roll back identically on candidate-step failure.
 * 2. 30 builders crossing obstacles on a `350x350` map slice `RangePathSearch` (`targetRange > 0`)
 *    within the unified step budget without false `path-unreachable` and roll back byte-identically.
 * 3. Task queues with `tasks.size >= 2` and non-`MOVE` tasks carrying orthogonal [MotionContract]
 *    preserve short-distance reverse, narrow-bridge mouth yielding, and shared-cluster landing.
 * 4. Navigation/avoidance modules remain strictly decoupled from [UnitTaskKind] and `ThreadLocal`.
 */
class MotionContractAndBudgetTest {

    private fun largeObstacleMap(width: Int = 350, height: Int = 350): MapDefinition {
        val blocked = BooleanArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            // Cross-corridor 8-tile obstacle wall forcing ~100-180 A* node expansions per unit on a 350x350 grid
            x in 80..81 && y in 74..81
        }
        return MapDefinition(
            widthTiles = width,
            heightTiles = height,
            tileWidth = 20,
            tileHeight = 20,
            tileSets = emptyList(),
            groundGids = IntArray(blocked.size),
            blocking = BlockingGrid(width, height, blocked),
        )
    }

    @Test
    fun fifty_pursuers_chasing_moving_target_on_350x350_map_respect_step_budget_hysteresis_and_rollback() {
        val base = BuildingTestContent.scenario()
        val attackerDef = BuildingTestContent.variant(base.unitDefinitions.getValue("c_tank"), sight = 400)
        val map = largeObstacleMap()
        val pursuerSpawns = List(50) { i ->
            val col = i % 5
            val row = i / 5
            UnitSpawn(
                unitId = "p1-tank-${i.toString().padStart(2, '0')}",
                ownerPlayerId = "p1",
                typeId = attackerDef.id,
                position = Position(1200.0 + col * 44.0, 1360.0 + row * 44.0),
                heading = 90.0,
            )
        }
        val runnerSpawn = UnitSpawn(
            unitId = "p2-runner",
            ownerPlayerId = "p2",
            typeId = attackerDef.id,
            position = Position(2160.0, 1540.0),
            heading = 180.0,
        )
        val allSpawns = pursuerSpawns + runnerSpawn
        val objectIds = allSpawns.mapIndexed { idx, s -> s.unitId to (idx + 1L) }.toMap()
        val scene = base.copy(
            map = map,
            players = listOf(
                Player("p1", PlayerControl.LOCAL, "t1"),
                Player("p2", PlayerControl.LOCAL, "t2"),
            ),
            unitDefinitions = mapOf(attackerDef.id to attackerDef),
            units = allSpawns,
            combatContract = CombatContract(12345, -1, objectIds, allSpawns.size + 1L),
            economyContract = null,
        )

        val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val rollbackCtx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (ctx in listOf(control, rollbackCtx)) {
                ctx.enablePlanningWorkDiagnostics(ctx.world)
                ctx.enablePathDiagnostics(ctx.world)
                assertIs<ActionResult.Accepted>(
                    ctx.submit(MoveAction("runner-move", "p2", runnerSpawn.unitId, Position(2200.0, 2200.0), ctx.world)),
                )
                for (p in pursuerSpawns) {
                    assertIs<ActionResult.Accepted>(
                        ctx.submit(AttackAction("atk-${p.unitId}", "p1", p.unitId, runnerSpawn.unitId, ctx.world)),
                    )
                }
            }

            var peakUsed = 0
            var multiStepSliced = false
            var lateAttackSearches = 0
            val startPosById = pursuerSpawns.associate { it.unitId to it.position }

            for (tick in 0L until 200L) {
                val step = Step(tick, 1.0, "sim-tick")
                if (tick == 0L || tick == 2L || tick == 25L) {
                    assertFailsWith<IllegalStateException> {
                        rollbackCtx.advanceWithInjectedFailureForTest(step)
                    }
                }
                val expectedObs = control.advance(step)
                val actualObs = rollbackCtx.advance(step)

                assertEquals(expectedObs.units, actualObs.units, "Mismatch in units after step $tick")
                assertEquals(expectedObs.events, actualObs.events, "Mismatch in events after step $tick")
                assertEquals(expectedObs.combat, actualObs.combat, "Mismatch in combat state after step $tick")

                val controlWork = control.drainPlanningWorkDiagnostics(control.world).single()
                val rollbackWork = rollbackCtx.drainPlanningWorkDiagnostics(rollbackCtx.world).single()
                assertTrue(
                    controlWork.used <= controlWork.limit,
                    "Step $tick exceeded planning budget: used=${controlWork.used}, limit=${controlWork.limit}",
                )
                assertEquals(controlWork.used, rollbackWork.used, "Rollback retry changed step $tick budget usage")
                peakUsed = maxOf(peakUsed, controlWork.used)
                if (tick > 0L && controlWork.used > 0) multiStepSliced = true

                val pathSamples = control.drainPathDiagnostics(control.world)
                rollbackCtx.drainPathDiagnostics(rollbackCtx.world)
                if (tick >= 120L) {
                    lateAttackSearches += pathSamples.count { it.reason == PathSearchReason.ATTACK_PURSUIT }
                }
                assertTrue(
                    expectedObs.events.none { it.kind == "path-unreachable" },
                    "Pursuers must not emit false path-unreachable while sliced: ${expectedObs.events}",
                )
            }

            assertTrue(peakUsed > 0, "Expected tactical pursuit searches to consume budgeted work")
            assertTrue(multiStepSliced, "50 cross-obstacle 350x350 searches must slice across multiple ticks")
            assertTrue(
                lateAttackSearches < 120,
                "Moving-target hysteresis + tail splice must prevent 50x/tick replanning storms (65 ticks * 50 units = 3250): lateAttackSearches=$lateAttackSearches",
            )
            val finalObs = control.observe()
            val movedPursuers = finalObs.units.count { u ->
                u.ownerPlayerId == "p1" && ScreenPlane.distanceSquared(u.position, startPosById.getValue(u.unitId)) > 400.0
            }
            assertEquals(50, movedPursuers, "All 50 pursuers must acquire routes and advance across the map")
        } finally {
            control.close()
            rollbackCtx.close()
        }
    }

    @Test
    fun thirty_builders_crossing_obstacles_on_350x350_map_slice_range_search_and_rollback_identically() {
        val fixturesRoot = System.getProperty("public.fixtures.dir")
            ?.let { Path.of(it).resolve("build-economy/factory/units") }
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isDirectory(it.resolve("SimulationTest/public")) }
                .resolve("SimulationTest/public/inputs/build-economy/factory/units")
        val builderDef = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadBuilder(fixturesRoot.resolve("builder.properties")),
        ).value
        val factoryDef = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadFactory(fixturesRoot.resolve("landFactory.properties")),
        ).value

        val base = BuildingTestContent.scenario()
        val map = largeObstacleMap()
        val builderSpawns = List(30) { i ->
            val col = i % 5
            val row = i / 5
            UnitSpawn(
                unitId = "worker-${i.toString().padStart(2, '0')}",
                ownerPlayerId = "p1",
                typeId = builderDef.id,
                position = Position(1220.0 + col * 44.0, 1420.0 + row * 44.0),
                heading = 90.0,
            )
        }
        val damagedFactorySpawn = UnitSpawn(
            unitId = "damaged-factory",
            ownerPlayerId = "p1",
            typeId = factoryDef.id,
            position = Position(2150.0, 1550.0),
            heading = 90.0,
        )
        val allSpawns = builderSpawns + damagedFactorySpawn
        val objectIds = allSpawns.mapIndexed { idx, s -> s.unitId to (idx + 1L) }.toMap()
        val combinedDefs = base.unitDefinitions + mapOf(builderDef.id to builderDef, factoryDef.id to factoryDef)
        val basePrices = MaintenanceContract.requiredBuildingTypeIds(combinedDefs).associateWith { id ->
            combinedDefs.getValue(id).construction?.price ?: 700L
        }
        val scene = base.copy(
            map = map,
            unitDefinitions = combinedDefs,
            units = allSpawns,
            economyContract = EconomyContract(mapOf("p1" to 50000L, "p2" to 0L)),
            maintenanceContract = MaintenanceContract(basePrices),
            combatContract = CombatContract(12345, -1, objectIds, allSpawns.size + 1L),
        )

        val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val rollbackCtx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val buildSite = Position(2150.0, 1730.0)
            val stepsField = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
            for (ctx in listOf(control, rollbackCtx)) {
                val state = (stepsField.get(ctx) as CandidateSteps).current
                state.units.getValue(damagedFactorySpawn.unitId).health = 200.0
                ctx.enablePlanningWorkDiagnostics(ctx.world)
                builderSpawns.forEachIndexed { idx, spawn ->
                    if (idx < 15) {
                        assertIs<ActionResult.Accepted>(
                            ctx.submit(RepairUnitAction("rep-${spawn.unitId}", "p1", spawn.unitId, damagedFactorySpawn.unitId, ctx.world)),
                        )
                    } else {
                        val nonce = idx.toString(16).padStart(32, '0')
                        assertIs<ActionResult.Accepted>(
                            ctx.submit(BuildAction("bld-${spawn.unitId}", "p1", spawn.unitId, factoryDef.id, buildSite, nonce, ctx.world)),
                        )
                    }
                }
            }

            val builderStartPos = builderSpawns.associate { it.unitId to it.position }
            var slicedSteps = 0
            for (tick in 0L until 240L) {
                val step = Step(tick, 1.0, "sim-tick")
                if (tick == 0L || tick == 2L || tick == 10L) {
                    assertFailsWith<IllegalStateException> {
                        rollbackCtx.advanceWithInjectedFailureForTest(step)
                    }
                }
                val expectedObs = control.advance(step)
                val actualObs = rollbackCtx.advance(step)

                assertEquals(expectedObs.units, actualObs.units, "Builder units diverged after rollback at step $tick")
                assertEquals(expectedObs.events, actualObs.events, "Builder events diverged after rollback at step $tick")
                val expectedPlayer = control.observePlayer(control.world, "p1")
                val actualPlayer = rollbackCtx.observePlayer(rollbackCtx.world, "p1")
                assertEquals(expectedPlayer.units, actualPlayer.units, "Player units diverged after rollback at step $tick")
                assertEquals(expectedPlayer.balance, actualPlayer.balance, "Player balance diverged after rollback at step $tick")

                val work = control.drainPlanningWorkDiagnostics(control.world).single()
                val retryWork = rollbackCtx.drainPlanningWorkDiagnostics(rollbackCtx.world).single()
                assertTrue(work.used <= work.limit, "Step $tick exceeded budget: used=${work.used}, limit=${work.limit}")
                assertEquals(work.used, retryWork.used, "Rollback changed RangePathSearch work on step $tick")
                if (work.used > 0) slicedSteps++

                assertTrue(
                    expectedObs.events.none { it.kind == "path-unreachable" || it.kind == "movement-cancelled" },
                    "Suspended builders must not emit unreachable/cancelled on step $tick: ${expectedObs.events}",
                )
            }

            assertTrue(slicedSteps >= 2, "30 cross-obstacle RangePathSearch jobs must slice across >= 2 steps (got $slicedSteps)")
            val routedBuilders = control.observe().units.count { u ->
                u.unitId.startsWith("worker-") && u.moveTarget != null &&
                    ScreenPlane.distanceSquared(u.position, builderStartPos.getValue(u.unitId)) > 225.0
            }
            assertEquals(30, routedBuilders, "All 30 builders must receive budgeted range routes and advance")
        } finally {
            control.close()
            rollbackCtx.close()
        }
    }

    @Test
    fun queued_and_non_move_tasks_with_motion_contract_enable_short_reverse_bridge_yield_and_shared_landing() {
        val base = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 6.0)
        val stepsField = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }

        // 1. Short-distance reverse with tasks.size >= 2 AND with non-MOVE task carrying MotionContract.
        val dropshipDef = UnitDefinition(
            id = "test_dropship",
            className = tank.className,
            radius = tank.radius,
            displayRadius = tank.displayRadius,
            totalFrames = tank.totalFrames,
            imagePath = tank.imagePath,
            imageScale = tank.imageScale,
            movement = tank.movement.copy(reverseSpeedPercentage = 0.0),
            retainedUnconsumed = tank.retainedUnconsumed,
            transport = TransportProfile.NATIVE_DROPSHIP,
        )
        val shortTarget = Position(140.0, 200.0)
        val dropshipQueuedMove = MutableUnit("ds-1", "p1", dropshipDef.id, dropshipDef, Position(200.0, 200.0), 90.0).apply {
            taskQueue = UnitTaskQueue()
                .append(UnitTask(UnitTaskKind.MOVE, "m1", shortTarget, motionContract = MotionContract.pointMove(shortTarget)))
                .append(UnitTask(UnitTaskKind.PATROL, "p2", Position(400.0, 200.0)))
            updateNavigation {
                it.bindCommandTarget(shortTarget, originMoveId = "m1", contract = MotionContract.pointMove(shortTarget))
                    .withPathPoints(listOf(shortTarget))
            }
        }
        assertEquals(2, dropshipQueuedMove.tasks.size)
        assertTrue(
            BodyMotion.turn(dropshipQueuedMove, shortTarget, 1.0, wasReversing = false).reversing,
            "Short-distance reverse must remain active when tasks.size >= 2",
        )

        val dropshipNonMove = MutableUnit("ds-2", "p1", dropshipDef.id, dropshipDef, Position(200.0, 200.0), 90.0).apply {
            val customContract = MotionContract.pointMove(shortTarget, allowShortReverse = true)
            taskQueue = UnitTaskQueue()
                .append(UnitTask(UnitTaskKind.PATROL, "patrol-with-reverse", shortTarget, motionContract = customContract))
            updateNavigation {
                it.bindCommandTarget(shortTarget, originMoveId = "patrol-with-reverse", contract = customContract)
                    .withPathPoints(listOf(shortTarget))
            }
        }
        assertEquals(UnitTaskKind.PATROL, dropshipNonMove.tasks.single().kind)
        assertTrue(
            BodyMotion.turn(dropshipNonMove, shortTarget, 1.0, wasReversing = false).reversing,
            "Non-MOVE task carrying MotionContract(allowShortReverse = true) must reverse on short turn",
        )

        // 2. Narrow-bridge mouth yielding with tasks.size >= 2 and non-MOVE task carrying MotionContract(allowPassageYield = true).
        val rawBridgeTank = base.unitDefinitions.getValue("tank")
        val bridgeTank = TestContentFactory.unitWith(
            rawBridgeTank,
            radius = 8.0,
            movement = rawBridgeTank.movement.copy(moveSpeed = 1.25, maxTurnSpeed = 4.2, turnAcceleration = 0.3),
        )
        val width = 32
        val height = 15
        val bridgeBlocked = BooleanArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if (x == 0 || x == width - 1 || y == 0 || y == height - 1) true
            else if (x in 13..18) y != 7
            else false
        }
        val bridgeMap = MapDefinition(width, height, 20, 20, emptyList(), IntArray(bridgeBlocked.size), BlockingGrid(width, height, bridgeBlocked))
        val bridgeScene = base.copy(
            map = bridgeMap,
            unitDefinitions = mapOf("tank" to bridgeTank),
            units = listOf(
                UnitSpawn("west", "p1", "tank", Position(220.0, 150.0), 90.0),
                UnitSpawn("east", "p1", "tank", Position(410.0, 150.0), 270.0),
            ),
        )
        val bridgeCtx = assertIs<InitResult.Accepted>(SimulationContext.initialize(bridgeScene)).context
        try {
            assertIs<ActionResult.Accepted>(bridgeCtx.submit(MoveAction("w-move", "p1", "west", Position(500.0, 150.0), bridgeCtx.world)))
            assertIs<ActionResult.Accepted>(bridgeCtx.submit(PatrolAction("w-tail", "p1", "west", Position(540.0, 150.0), append = true, world = bridgeCtx.world)))
            assertIs<ActionResult.Accepted>(bridgeCtx.submit(PatrolAction("e-patrol", "p1", "east", Position(140.0, 150.0), append = false, world = bridgeCtx.world)))
            assertIs<ActionResult.Accepted>(bridgeCtx.submit(PatrolAction("e-tail", "p1", "east", Position(100.0, 150.0), append = true, world = bridgeCtx.world)))

            // Equip the non-MOVE patrol task on `east` with explicit allowPassageYield = true via MotionContract.
            val initialBridgeState = (stepsField.get(bridgeCtx) as CandidateSteps).current
            val eastUnit = initialBridgeState.units.getValue("east")
            val eastHead = eastUnit.tasks.first()
            val yieldPatrolContract = MotionContract.patrolLeg(Position(140.0, 150.0), allowPassageYield = true)
            eastUnit.taskQueue = eastUnit.taskQueue.replaceHead(eastHead.copy(motionContract = yieldPatrolContract))

            var sawMouthYield = false
            var westCrossed = false
            var eastCrossed = false
            for (tick in 0L until 800L) {
                bridgeCtx.advance(Step(tick, 1.0, "sim-tick"))
                val liveState = (stepsField.get(bridgeCtx) as CandidateSteps).current
                val liveWest = liveState.units.getValue("west")
                val liveEast = liveState.units.getValue("east")
                if (liveWest.passageEntry?.admitted == false || liveEast.passageEntry?.admitted == false) {
                    assertEquals(2, liveEast.tasks.size, "East unit must retain its 2-task queue while yielding")
                    sawMouthYield = true
                }
                if (liveWest.position.x > 400.0) westCrossed = true
                if (liveEast.position.x < 230.0) eastCrossed = true
                if (westCrossed && eastCrossed) break
            }
            assertTrue(sawMouthYield, "Narrow-bridge mouth admission must trigger with tasks.size == 2 and non-MOVE yield contract")
            assertTrue(westCrossed, "West unit must cross the narrow bridge")
            assertTrue(eastCrossed, "East non-MOVE unit must cross the narrow bridge")
        } finally {
            bridgeCtx.close()
        }

        // 3. SharedCluster terminal landing on non-MOVE tasks with tasks.size == 2.
        val openMap = MapDefinition(40, 40, 20, 20, emptyList(), IntArray(1600), BlockingGrid(40, 40, BooleanArray(1600)))
        val clusterSpawns = List(4) { i ->
            UnitSpawn("c$i", "p1", "tank", Position(160.0, 220.0 + i * 26.0), 90.0)
        }
        val clusterScene = base.copy(map = openMap, unitDefinitions = mapOf("tank" to bridgeTank), units = clusterSpawns)
        val clusterCtx = assertIs<InitResult.Accepted>(SimulationContext.initialize(clusterScene)).context
        try {
            val click = Position(420.0, 260.0)
            val ids = clusterSpawns.map { it.unitId }
            val group = assertNotNull(clusterCtx.requestGroupMove("p1", ids, click))
            val state = (stepsField.get(clusterCtx) as CandidateSteps).current
            val sharedContract = MotionContract.sharedCluster(click)
            for (id in ids) {
                val u = state.units.getValue(id)
                u.taskQueue = UnitTaskQueue()
                    .append(UnitTask(UnitTaskKind.ATTACK, "cluster-$id", targetUnitId = "c0", motionContract = sharedContract))
                    .append(UnitTask(UnitTaskKind.ATTACK, "hold-$id", targetUnitId = "c0"))
                u.updateNavigation {
                    it.withLandingMember(LandingMember(group))
                        .bindCommandTarget(click, originMoveId = "cluster-$id", contract = sharedContract)
                }
                u.adoptRoute(listOf(click), click, -1L, -1L)
                state.landingOccupancy.update(u)
            }
            var allParked = false
            for (tick in 0L until 700L) {
                clusterCtx.advance(Step(tick, 1.0, "sim-tick"))
                val liveCluster = (stepsField.get(clusterCtx) as CandidateSteps).current
                if (liveCluster.units.values.count { it.landingMember?.parked == true && it.tasks.size == 1 } == ids.size) {
                    allParked = true
                    break
                }
            }
            val diag = (stepsField.get(clusterCtx) as CandidateSteps).current.units.values.joinToString("; ") {
                "${it.unitId}@(${"%.1f".format(it.position.x)},${"%.1f".format(it.position.y)}) parked=${it.landingMember?.parked} tasks=${it.tasks.size}"
            }
            assertTrue(allParked, "Non-MOVE tasks carrying SharedCluster MotionContract with tasks.size == 2 must settle in TerminalLanding [$diag]")
        } finally {
            clusterCtx.close()
        }
    }

    @Test
    fun navigation_and_avoidance_files_contain_zero_unit_task_kind_and_zero_thread_local() {
        val repoRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("Engine/core/src/main/kotlin")) }
            ?: return // Sealed test-jar runs without source tree skip raw file scan
        val simDir = repoRoot.resolve("Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/sim")
        val decoupledFiles = listOf(
            "BodyMotion.kt",
            "NarrowPassage.kt",
            "SharedLandingSites.kt",
            "UnitMovementOrchestrator.kt",
            "MovePlanning.kt",
            "NavigationGuidance.kt",
            "UnitContact.kt",
        )
        val blockComments = Regex("/\\*[\\s\\S]*?\\*/")
        val lineComments = Regex("//.*")
        for (name in decoupledFiles) {
            val file = simDir.resolve(name)
            if (!Files.isRegularFile(file)) continue
            val codeOnly = Files.readString(file)
                .replace(blockComments, "")
                .replace(lineComments, "")
            assertFalse("UnitTaskKind" in codeOnly, "$name must not reference UnitTaskKind directly")
            assertFalse(".tasks" in codeOnly, "$name must not inspect unit.tasks directly")
            assertFalse(".taskQueue" in codeOnly, "$name must not mutate unit.taskQueue directly")
        }

        val pathfindingFile = simDir.resolve("Pathfinding.kt")
        if (Files.isRegularFile(pathfindingFile)) {
            val codeOnly = Files.readString(pathfindingFile)
                .replace(blockComments, "")
                .replace(lineComments, "")
            assertFalse("ThreadLocal" in codeOnly, "Pathfinding.kt must not retain unbudgeted ThreadLocal frontier state")
        }
    }

    @Test
    fun suspended_repair_unreachable_respects_policy_and_pops_abandoned_move_task() {
        // Compact 6x5 walled room (30 cells -> 240 CLEARANCE work each) with a 1-tile doorway at (3, 2) dynamically
        // sealed by a friendly blocker, so all 3 suspended repairs complete within the default 512 budget on tick 0.
        val w = 6
        val h = 5
        val blocked = BooleanArray(w * h)
        for (r in 0 until h) {
            if (r != 2) blocked[r * w + 3] = true
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("tank" to tank),
            units = listOf(
                UnitSpawn("blocker", "p1", "tank", Position(70.0, 50.0), 90.0),
                UnitSpawn("early", "p1", "tank", Position(30.0, 30.0), 90.0),
                UnitSpawn("expired", "p1", "tank", Position(30.0, 50.0), 90.0),
                UnitSpawn("worker", "p1", "tank", Position(30.0, 70.0), 90.0),
            ),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val stepsField = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
            val terrainMethod = SimulationContext::class.java.getDeclaredMethod(
                "planningTerrainFor",
                MutableUnit::class.java,
                MapDefinition::class.java,
                MovementType::class.java,
            ).apply { isAccessible = true }
            val state = (stepsField.get(ctx) as CandidateSteps).current
            val blocker = state.units.getValue("blocker")
            val planningTerrain = terrainMethod.invoke(ctx, blocker, map, MovementType.LAND) as com.rustedwarfare.reforge.core.path.PlanningTerrain
            val unreachableTarget = Position(100.0, 50.0)
            val tinyBudget = PathWorkBudget(2)

            // 1. Early-stalled MOVE (< ROUTE_FAILURE_TICKS = 480) suspended into tacticalJobs with STALL_MOVE_GUARDED.
            val early = state.units.getValue("early")
            val routeRadius = UnitLocomotion.terrainPassabilityRadius(early, map.tileWidth, map.tileHeight)
            val routeHull = HullRoute.forUnit(early, map)
            early.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, "early-m", target = unreachableTarget))
            early.updateNavigation {
                it.bindCommandTarget(unreachableTarget, originMoveId = "early-m", contract = MotionContract.pointMove(unreachableTarget))
            }
            early.adoptRoute(listOf(unreachableTarget), unreachableTarget, -1L, -1L)
            early.updateNavigation { it.withStalledRouteTicks(20) }
            val policy = ClearanceAStarPolicy()
            val earlyPending = state.movePlanning.resolveTactical(
                TacticalRouteRequest(
                    unitId = "early",
                    actionId = "early-m",
                    atStep = 0L,
                    start = early.position,
                    target = unreachableTarget,
                    radius = routeRadius,
                    targetRange = 0.0,
                    terrain = planningTerrain,
                    hull = routeHull,
                    occupancy = UnitContact.routeAround(early, listOf(blocker)),
                    occupantIds = listOf("blocker"),
                    reason = PathSearchReason.ROUTE_REPAIR,
                    isRepair = true,
                    unreachablePolicy = TacticalUnreachablePolicy.STALL_MOVE_GUARDED,
                ),
                policy,
                tinyBudget,
                null,
            )
            assertIs<TacticalRouteResolution.Pending>(earlyPending)

            // 2. Expired-stalled MOVE (>= 480) suspended with STALL_MOVE_GUARDED -> must cancel AND pop taskQueue.
            val expired = state.units.getValue("expired")
            expired.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, "exp-m", target = unreachableTarget))
            expired.updateNavigation {
                it.bindCommandTarget(unreachableTarget, originMoveId = "exp-m", contract = MotionContract.pointMove(unreachableTarget))
            }
            expired.adoptRoute(listOf(unreachableTarget), unreachableTarget, -1L, -1L)
            expired.updateNavigation { it.withStalledRouteTicks(500) }
            val expPending = state.movePlanning.resolveTactical(
                TacticalRouteRequest(
                    unitId = "expired",
                    actionId = "exp-m",
                    atStep = 0L,
                    start = expired.position,
                    target = unreachableTarget,
                    radius = routeRadius,
                    targetRange = 0.0,
                    terrain = planningTerrain,
                    hull = routeHull,
                    occupancy = UnitContact.routeAround(expired, listOf(blocker)),
                    occupantIds = listOf("blocker"),
                    reason = PathSearchReason.ROUTE_REPAIR,
                    isRepair = true,
                    unreachablePolicy = TacticalUnreachablePolicy.STALL_MOVE_GUARDED,
                ),
                policy,
                PathWorkBudget(2),
                null,
            )
            assertIs<TacticalRouteResolution.Pending>(expPending)

            // 3. Construction detour suspended with SILENT_IGNORE -> must keep route and emit zero cancel events.
            val worker = state.units.getValue("worker")
            worker.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.CONSTRUCT, "work-c", targetUnitId = "blocker"))
            worker.updateNavigation {
                it.bindCommandTarget(
                    unreachableTarget,
                    originMoveId = "work-c",
                    contract = MotionContract.rangeApproach(unreachableTarget, 40.0, tracksRouteStall = true),
                )
            }
            worker.adoptRoute(listOf(unreachableTarget), unreachableTarget, -1L, -1L)
            worker.updateNavigation { it.withStalledRouteTicks(600) }
            val workPending = state.movePlanning.resolveTactical(
                TacticalRouteRequest(
                    unitId = "worker",
                    actionId = "work-c",
                    atStep = 0L,
                    start = worker.position,
                    target = unreachableTarget,
                    radius = routeRadius,
                    targetRange = 40.0,
                    terrain = planningTerrain,
                    hull = routeHull,
                    occupancy = UnitContact.routeAround(worker, listOf(blocker)),
                    occupantIds = listOf("blocker"),
                    reason = PathSearchReason.ROUTE_REPAIR,
                    isRepair = true,
                    unreachablePolicy = TacticalUnreachablePolicy.SILENT_IGNORE,
                ),
                policy,
                PathWorkBudget(2),
                null,
            )
            assertIs<TacticalRouteResolution.Pending>(workPending)

            // Advance 3 ticks so all 3 suspended repairs complete inside drainCompletedRepairTacticalRoutes on tick 0.
            val allEvents = mutableListOf<Event>()
            for (tick in 0L until 3L) {
                allEvents += ctx.advance(Step(tick, 1.0, "sim-tick")).events
            }
            val live = (stepsField.get(ctx) as CandidateSteps).current

            val liveEarly = live.units.getValue("early")
            assertEquals(unreachableTarget, liveEarly.moveTarget, "Early stall (<480) unreachable repair must preserve moveTarget")
            assertEquals(1, liveEarly.tasks.size, "Early stall (<480) unreachable repair must preserve queued MOVE task")
            assertTrue(allEvents.none { it.unitId == "early" && (it.kind == "movement-cancelled" || it.kind == "path-unreachable") })

            val liveExpired = live.units.getValue("expired")
            assertNull(
                liveExpired.moveTarget,
                "Expired stall (>=480) unreachable repair must cancel moveTarget [stalled=${liveExpired.stalledRouteTicks}, pending=${live.movePlanning.hasPendingTactical("expired")}, pos=${liveExpired.position}, events=$allEvents]",
            )
            assertTrue(liveExpired.tasks.isEmpty(), "Expired stall (>=480) unreachable repair must pop head MOVE task (no zombie resurrection)")
            assertTrue(allEvents.any { it.unitId == "expired" && it.kind == "movement-cancelled" })
            assertTrue(allEvents.any { it.unitId == "expired" && it.kind == "path-unreachable" })

            val liveWorker = live.units.getValue("worker")
            assertEquals(unreachableTarget, liveWorker.moveTarget, "SILENT_IGNORE repair must not cancel construction route")
            assertTrue(allEvents.none { it.unitId == "worker" && (it.kind == "movement-cancelled" || it.kind == "path-unreachable") })
        } finally {
            ctx.close()
        }
    }
}
