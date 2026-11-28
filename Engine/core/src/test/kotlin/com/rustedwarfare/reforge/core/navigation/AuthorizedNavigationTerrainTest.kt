package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.BuildingProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.VisibleTerrainCell
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.InitResult
import com.rustedwarfare.reforge.core.sim.MoveAction
import com.rustedwarfare.reforge.core.sim.Player
import com.rustedwarfare.reforge.core.sim.PlayerControl
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.SimulationContext
import com.rustedwarfare.reforge.core.sim.Step
import com.rustedwarfare.reforge.core.sim.TestContentFactory
import com.rustedwarfare.reforge.core.sim.UnitSpawn
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthorizedNavigationTerrainTest {
    @Test
    fun fog_without_new_terrain_changes_keeps_revision_constant_and_achieves_100_percent_planning_cache_hits() {
        val base = com.rustedwarfare.reforge.core.sim.BuildingTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val factoryDef = base.unitDefinitions.getValue("landFactory")
        val width = 36
        val height = 24
        val blocked = BooleanArray(width * height) { idx ->
            // Hidden obstacle outside p1's sight range -> must NEVER leak into p1's AuthorizedNavigationTerrain
            idx == (20 * width + 32)
        }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height) { 1 }, BlockingGrid(width, height, blocked))
        val scenario = base.copy(
            map = map,
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            unitDefinitions = mapOf(tank.id to tank, factoryDef.id to factoryDef),
            units = listOf(
                UnitSpawn("fac", "p1", factoryDef.id, Position(110.0, 110.0), 0.0),
                // Overlaps factory east edge so safe overlap-escape exercises BuildingOverlapEscape
                UnitSpawn("u-overlap", "p1", tank.id, Position(142.0, 110.0), 90.0),
                UnitSpawn("u-roam", "p1", tank.id, Position(220.0, 200.0), 90.0),
            ),
            combatContract = com.rustedwarfare.reforge.core.sim.CombatContract(12345, -1, mapOf("fac" to 10L, "u-overlap" to 11L, "u-roam" to 12L), 20L),
            economyContract = com.rustedwarfare.reforge.core.sim.EconomyContract(mapOf("p1" to 2000L, "p2" to 2000L)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val world = ctx.world
            // Warm up initial authorized terrain cache
            val auth0 = ctx.authorizedNavigationTerrain(world, "p1", MovementType.LAND)
            assertFalse(auth0.isBlocked(32, 20), "Unexplored truth obstacle must remain isolated from p1 AuthorizedNavigationTerrain")
            assertTrue(auth0.isBlocked(5, 5), "Visible own building footprint must be blocked in AuthorizedNavigationTerrain")

            // Step 1..15: stationary world -> vision unchanged -> revision MUST stay constant AND 100% cache hit rate!
            val snapBefore = ctx.knowledgeSnapshot(world, "p1")
            // Prime u-overlap and u-roam planningTerrainFor once before measuring steady-state hits
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("prime", "p1", "u-overlap", Position(180.0, 110.0), world)))
            ctx.advance(Step(0L, 1.0, "sim-tick"))
            val snapAfterPrime = ctx.knowledgeSnapshot(world, "p1")
            ctx.resetPlanningTerrainCacheStats()

            for (step in 1L..15L) {
                ctx.advance(Step(step, 1.0, "sim-tick"))
                val snapNow = ctx.knowledgeSnapshot(world, "p1")
                if (ctx.observe().units.single { it.unitId == "u-overlap" }.moveTarget == null) {
                    val revBeforeIdle = snapNow.revision
                    ctx.advance(Step(step + 100L, 1.0, "sim-tick"))
                    val snapIdle = ctx.knowledgeSnapshot(world, "p1")
                    assertEquals(revBeforeIdle, snapIdle.revision, "Stationary fog step with zero knowledge changes must keep revision constant")
                    assertSame(snapNow, snapIdle, "Stationary fog step must reuse exact KnowledgeSnapshot instance")
                    break
                }
            }
            assertEquals(0L, ctx.planningTerrainCacheMisses, "Planning terrain cache must have 0 misses when known terrain is unchanged")
            assertTrue(ctx.planningTerrainCacheHits > 0L, "Expected positive planning terrain cache hits")
            assertTrue(snapAfterPrime.revision >= snapBefore.revision)

            // Phase 2: Pre-explore corridor with u-roam, then drive back & forth across already-known plain ground.
            // Visibility shifts (`visible` toggles true/false under MEMORY), so `terrainRevision` stays constant and cache hit rate MUST be 100%!
            var t = 200L
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("explore-east", "p1", "u-roam", Position(420.0, 200.0), world)))
            while (t < 460L && ctx.observe().units.single { it.unitId == "u-roam" }.tasks.isNotEmpty()) {
                ctx.advance(Step(t++, 1.0, "sim-tick"))
            }
            val terrainRevExplored = ctx.knowledgeSnapshot(world, "p1").terrainRevision
            ctx.resetPlanningTerrainCacheStats()
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("return-west", "p1", "u-roam", Position(220.0, 200.0), world)))
            while (t < 720L && ctx.observe().units.single { it.unitId == "u-roam" }.tasks.isNotEmpty()) {
                ctx.advance(Step(t++, 1.0, "sim-tick"))
            }
            assertEquals(
                terrainRevExplored,
                ctx.knowledgeSnapshot(world, "p1").terrainRevision,
                "Traversing already-explored MEMORY terrain must keep terrainRevision constant",
            )
            assertEquals(0L, ctx.planningTerrainCacheMisses, "100% cache hit rate required across explored MEMORY terrain")
            assertTrue(ctx.planningTerrainCacheHits >= 20L, "Expected repeated O(1) planning terrain cache hits, got ${ctx.planningTerrainCacheHits}")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun wide_unit_grazing_newly_discovered_obstacle_triggers_immediate_knowledge_replan() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        // Wide circular unit with explicit radius = 18.0 (> 10.0 half-tile, requiring clearance from adjacent row)
        val wideTank = TestContentFactory.unitWith(tank, radius = 18.0)
        val width = 30
        val height = 20
        // Obstacle at col=12, row=6 -> y in [120, 140].
        // Wide unit moves along y = 110 (row=5 center): center-line y=110 does NOT enter row=6 ([120, 140)),
        // but distance from y=110 to y=120 is 10.0 < clearanceRadius (18.0), so swept hull penetrates row=6!
        val blocked = BooleanArray(width * height) { idx -> idx == (6 * width + 12) }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, blocked))
        val scenario = base.copy(
            map = map,
            unitDefinitions = mapOf(wideTank.id to wideTank),
            units = listOf(UnitSpawn("wide", "p1", wideTank.id, Position(80.0, 110.0), 90.0)),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val world = ctx.world
            ctx.enableKnowledge(world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            ctx.enablePathDiagnostics(world)

            val goal = Position(420.0, 110.0)
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("wide-go", "p1", "wide", goal, world)))
            ctx.advance(Step(0L, 1.0, "sim-tick"))
            ctx.drainPathDiagnostics(world)

            // Center-only check is false (y=110 does not enter row=6 [120, 140)), while radius-aware check is true!
            assertFalse(KnowledgePathImpact.affectsCenter(Position(80.0, 110.0), listOf(goal), goal, 12, 6, 20, 20))
            assertTrue(KnowledgePathImpact.affectsSegment(Position(80.0, 110.0), listOf(goal), goal, 18.0, 12, 6, 20, 20, 600.0, 400.0))

            // Reveal obstacle at (12, 6) in fog -> must trigger KNOWLEDGE_REPLAN on the very next step!
            ctx.acceptVisibleTerrain(world, "p1", listOf(VisibleTerrainCell(12, 6, blocked = true, groundGid = 0)))
            ctx.advance(Step(1L, 1.0, "sim-tick"))
            val replans = ctx.drainPathDiagnostics(world)
            assertTrue(
                replans.any { it.unitId == "wide" && it.reason == PathSearchReason.KNOWLEDGE_REPLAN },
                "Wide unit whose hull margin grazes newly discovered obstacle (12,6) must immediately trigger KNOWLEDGE_REPLAN, got $replans",
            )

            // Advance to completion and verify unit detours cleanly without getting stuck on (12, 6)
            var arrived = false
            for (step in 2L..600L) {
                val obs = ctx.advance(Step(step, 1.0, "sim-tick"))
                if (obs.events.any { it.kind == "movement-arrived" && it.unitId == "wide" }) {
                    arrived = true
                    break
                }
            }
            assertTrue(arrived, "Wide unit must complete detoured route around newly discovered obstacle")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun dead_shallow_modules_and_fake_propulsion_distances_are_physically_removed_and_dual_guidance_contracts_align() {
        val coreRoot = locateCoreMainKotlinRoot()
        val deletedPaths = listOf(
            "com/rustedwarfare/reforge/core/path/PathPlanner.kt",
            "com/rustedwarfare/reforge/core/path/CenterLineCells.kt",
            "com/rustedwarfare/reforge/core/sim/ExactCenterLineCells.kt",
            "com/rustedwarfare/reforge/core/sim/LocalMotionSegment.kt",
            "com/rustedwarfare/reforge/core/sim/LocalMotionMaintenance.kt",
            "com/rustedwarfare/reforge/core/sim/ExperimentalPolicies.kt",
        )
        for (rel in deletedPaths) {
            assertFalse(Files.exists(coreRoot.resolve(rel)), "Shallow/dead module must stay physically deleted: $rel")
        }

        val orchestratorSrc = Files.readString(coreRoot.resolve("com/rustedwarfare/reforge/core/navigation/UnitMovementOrchestrator.kt"))
        for (forbidden in listOf("maxOf(d2", "maxOf(propulsionDistance", "maxOf(distanceSquared", "maxOf(fallbackDistSq", "LocalMotion")) {
            assertFalse(
                orchestratorSrc.contains(forbidden),
                "UnitMovementOrchestrator.kt must contain zero fabricated propulsion distances or LocalMotion remnants: $forbidden",
            )
        }

        // Verify WaypointRouteAdapter & ChunkedFlowFieldAdapter satisfy the same NavigationGuidance contract
        val base = TestContentFactory.openScenario()
        val spawns = (0 until 6).map { i ->
            UnitSpawn("u$i", "p1", "tank", Position(80.0 + (i % 3) * 28.0, 120.0 + (i / 3) * 28.0), 90.0)
        }
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(units = spawns))).context
        try {
            val world = ctx.world
            val goal = Position(420.0, 220.0)
            // Single unit -> WaypointRouteAdapter
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("single", "p1", "u0", goal, world)))
            // 5-unit shared group -> ChunkedFlowFieldAdapter
            val groupIds = listOf("u1", "u2", "u3", "u4", "u5")
            val group = assertNotNull(ctx.requestGroupMove("p1", groupIds, goal))
            for (id in groupIds) {
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("grp-$id", "p1", id, goal, world), group))
            }
            for (step in 0L..12L) ctx.advance(Step(step, 1.0, "sim-tick"))

            val hostField = SimulationContext::class.java.getDeclaredField("movementHost").apply { isAccessible = true }
            val host = hostField.get(ctx) as com.rustedwarfare.reforge.core.navigation.UnitMovementHost
            val u0 = host.units.getValue("u0")
            val u1 = host.units.getValue("u1")
            val terrain = host.planningTerrainFor(u0, base.map)

            val wpSample = host.navigationGuidance.sampleGuidance(host, u0, terrain, goal, true, 12L)
            val ffSample = host.navigationGuidance.sampleGuidance(host, u1, terrain, goal, true, 12L)
            val directWp = WaypointRouteAdapter.sampleGuidance(host, u0, terrain, goal, true, 12L)

            assertFalse(wpSample.fromFlowField)
            assertEquals(directWp, wpSample)
            var expectedPolyDist = 0.0
            var cursor = u0.position
            for (pt in u0.pathPoints) {
                expectedPolyDist += hypot(pt.x - cursor.x, pt.y - cursor.y)
                cursor = pt
            }
            assertEquals(expectedPolyDist, wpSample.remainingDistance, 1e-9)
            assertTrue(ffSample.fromFlowField)
            assertTrue(ffSample.remainingDistance > 0.0 && ffSample.corridorClearanceWorld > 0.0)
            assertTrue(ChunkedFlowFieldAdapter::class.java.interfaces.contains(com.rustedwarfare.reforge.core.navigation.NavigationGuidance::class.java))
            assertTrue(WaypointRouteAdapter::class.java.interfaces.contains(com.rustedwarfare.reforge.core.navigation.NavigationGuidance::class.java))
        } finally {
            ctx.close()
        }
    }

    private fun locateCoreMainKotlinRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val direct = dir.resolve("src/main/kotlin")
            if (Files.isDirectory(direct.resolve("com/rustedwarfare/reforge/core"))) return direct
            val fromRepo = dir.resolve("Engine/core/src/main/kotlin")
            if (Files.isDirectory(fromRepo.resolve("com/rustedwarfare/reforge/core"))) return fromRepo
            dir = dir.parent
        }
        error("Could not locate Engine/core/src/main/kotlin from ${Path.of("").toAbsolutePath()}")
    }
}
