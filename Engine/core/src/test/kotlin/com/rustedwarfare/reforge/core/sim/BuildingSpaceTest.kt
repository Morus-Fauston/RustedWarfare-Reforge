package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.path.PathSearchReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class BuildingSpaceTest {
    private fun publicValues(view: PlayerObservation): List<Any?> = listOf(view.sampleId, view.worldId,
        view.scenarioId, view.playerId, view.stepIndex, view.simulationTime, view.units, view.projectiles,
        view.events, view.actionResults, view.projectileEvents, view.balance, view.vision?.epoch,
        view.vision?.basicCells, view.vision?.effectiveCells, view.knowledge?.revision, view.knowledge?.cells)

    @Test
    fun newly_visible_building_replans_and_lost_sight_drops_building_cost_in_both_retention_modes() {
        val modes = listOf(KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
            KnowledgeRules(TerrainRetention.HARDCORE_FORGET, PathInformationSource.PLAYER_KNOWLEDGE, false),
            KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.ORIGINAL_COMPATIBILITY, false))
        for (rules in modes) {
            val base = BuildingTestContent.scenario()
            val input = base.copy(unitDefinitions = base.unitDefinitions.mapValues { (_, unit) -> BuildingTestContent.variant(unit, sight = 4) },
                players = base.players.map { it.copy(teamId = "allied") },
                units = base.units.map { if (it.unitId == "f") it.copy(ownerPlayerId = "p2") else it }, automaticVision = rules)
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                context.enablePathDiagnostics(context.world)
                assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "f" })
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("discover", "p1", "a", Position(510.0, 210.0), context.world)))
                assertTrue(context.observe().units.single { it.unitId == "a" }.pathPoints.all { it.y in 180.0..<240.0 })
                var sawFactory = false
                repeat(500) { step ->
                    context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    val unit = context.observe().units.single { it.unitId == "a" }
                    assertTrue(unit.position.x !in 280.0..<340.0 || unit.position.y !in 180.0..<240.0)
                    val view = context.observePlayer(context.world, "p1")
                    if (view.units.any { it.unitId == "f" }) {
                        sawFactory = true
                        assertEquals(false, view.knowledge!!.cell(15, 10).lastKnownBlocked, "Buildings must not become hard-ground memory")
                    }
                }
                assertTrue(sawFactory)
                assertTrue(context.observe().units.single { it.unitId == "a" }.position.x > 350.0)
                assertTrue(context.drainPathDiagnostics(context.world).any { it.reason == PathSearchReason.KNOWLEDGE_REPLAN })
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("return", "p1", "a", Position(110.0, 210.0), context.world)))
                for (step in 500L..1099L) context.advance(Step(step, 1.0, "sim-tick"))
                val lost = context.observePlayer(context.world, "p1")
                assertTrue(lost.units.none { it.unitId == "f" })
                assertEquals(if (rules.retention == TerrainRetention.MEMORY) false else null,
                    lost.knowledge!!.cell(15, 10).lastKnownBlocked)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("new-after-loss", "p1", "a", Position(510.0, 210.0), context.world)))
                assertTrue(context.observe().units.single { it.unitId == "a" }.pathPoints.all { it.y in 180.0..<240.0 },
                    "A remembered ground tile must not retain an unseen unit building")
            } finally { context.close() }
            val shared = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(automaticVision = rules.copy(shareAlliedVision = true)))).context
            try {
                assertTrue(shared.observePlayer(shared.world, "p1").units.any { it.unitId == "f" })
                assertIs<ActionResult.Accepted>(shared.submit(MoveAction("shared", "p1", "a", Position(510.0, 210.0), shared.world)))
                assertTrue(shared.observe().units.single { it.unitId == "a" }.pathPoints.any { it.y < 180.0 || it.y >= 240.0 })
            } finally { shared.close() }
        }
    }

    @Test
    fun same_authorized_cells_reuse_immutable_building_value_without_global_truth_revisions() {
        val empty = BuildingKnowledge.projected(null, emptySet())
        assertSame(empty, BuildingKnowledge.projected(empty, emptySet()))
        val visible = BuildingKnowledge.projected(empty, setOf(15 to 10))
        assertEquals(1L, visible.revision)
        assertSame(visible, BuildingKnowledge.projected(visible, linkedSetOf(15 to 10)))
        assertFailsWith<UnsupportedOperationException> { (visible.cells as MutableSet).clear() }
        val lost = BuildingKnowledge.projected(visible, emptySet())
        assertEquals(2L, lost.revision)
        assertSame(lost, BuildingKnowledge.projected(lost, emptySet()))
        assertEquals(setOf(15 to 10), visible.cells)
    }

    @Test
    fun real_long_failure_after_building_path_history_restores_layers_routes_and_retry() {
        val base = BuildingTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val witness = UnitDefinition("witness", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 0,
            tank.combat!!.copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
        val walker = UnitDefinition("walker", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 4, durability =
                com.rustedwarfare.reforge.core.content.DurabilityProfile(210.0, tank.combat!!.deadImagePath))
        val input = base.copy(unitDefinitions = base.unitDefinitions + (witness.id to witness) + (walker.id to walker),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(190.0, 210.0), 90.0),
                UnitSpawn("b", "p2", tank.id, Position(220.0, 210.0), 270.0),
                UnitSpawn("c", "p1", witness.id, Position(220.0, 330.0), 180.0),
                UnitSpawn("d", "p1", walker.id, Position(170.0, 210.0), 90.0),
                UnitSpawn("f", "p2", "landFactory", Position(310.0, 210.0), 0.0)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 11L, "d" to 12L, "f" to 10L), Long.MAX_VALUE - 3))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        try {
            for (context in contexts) {
                context.enablePathDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("history", "p1", "d", Position(510.0, 210.0), context.world)))
                for (step in 0L..74L) context.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(context.drainPathDiagnostics(context.world).isNotEmpty())
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("scheduled-route", "p1", "d", Position(510.0, 250.0), context.world, 75)))
            }
            val failed = contexts[0]
            val old = failed.observe()
            val oldPlayers = contexts.map { it.observePlayer(it.world, "p1") }
            val oldPlayer = oldPlayers[0]
            assertEquals(publicValues(oldPlayers[1]), publicValues(oldPlayer))
            assertFailsWith<ArithmeticException> { failed.advance(Step(75, 1.0, "sim-tick")) }
            assertSame(old, failed.observe())
            assertSame(oldPlayer, failed.observePlayer(failed.world, "p1"))
            assertTrue(failed.drainPathDiagnostics(failed.world).isEmpty())
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("correct-aim", "p2", "b", "c", context.world)))
                context.advance(Step(75, 1.0, "sim-tick"))
            }
            assertEquals(contexts[1].observe().units, failed.observe().units)
            assertEquals(contexts[1].observe().projectiles, failed.observe().projectiles)
            assertEquals(contexts[1].observe().combat, failed.observe().combat)
            assertEquals(publicValues(contexts[1].observePlayer(contexts[1].world, "p1")), publicValues(failed.observePlayer(failed.world, "p1")))
            assertEquals(1, failed.drainPathDiagnostics(failed.world).count { it.actionId == "scheduled-route" })
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun hidden_factory_does_not_enter_owner_route_but_truth_still_clips_real_motion() {
        for (source in PathInformationSource.entries) {
            val base = BuildingTestContent.scenario()
            val definitions = base.unitDefinitions.mapValues { (_, unit) -> BuildingTestContent.variant(unit, sight = 1) }
            val input = base.copy(unitDefinitions = definitions,
                // Same-team, non-shared vision isolates space from the existing automatic weapon gate.
                players = base.players.map { it.copy(teamId = "allied") },
                units = base.units.map { if (it.unitId == "f") it.copy(ownerPlayerId = "p2") else it },
                automaticVision = KnowledgeRules(TerrainRetention.MEMORY, source, false))
            val empty = input.copy(units = input.units.filter { it.unitId != "f" },
                combatContract = input.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L)))
            val contexts = listOf(input, empty).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(it)).context }
            try {
                for (context in contexts) {
                    assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "f" })
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("through", "p1", "a", Position(510.0, 210.0), context.world)))
                }
                assertEquals(publicValues(contexts[0].observePlayer(contexts[0].world, "p1")),
                    publicValues(contexts[1].observePlayer(contexts[1].world, "p1")))
                repeat(100) { step ->
                    contexts.forEach { it.advance(Step(step.toLong(), 1.0, "sim-tick")) }
                    assertEquals(publicValues(contexts[0].observePlayer(contexts[0].world, "p1")),
                        publicValues(contexts[1].observePlayer(contexts[1].world, "p1")))
                }
                for (step in 100L..349L) contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                assertTrue(contexts[0].observe().units.single { it.unitId == "a" }.position.x < 280.0)
                assertTrue(contexts[0].observePlayer(contexts[0].world, "p1").units.none { it.unitId == "f" })
                assertTrue(contexts[1].observe().units.single { it.unitId == "a" }.position.x > 350.0)
            } finally { contexts.forEach { it.close() } }
        }
    }

    @Test
    fun actual_1200_hp_death_releases_footprint_and_old_health_snapshot_stays_immutable() {
        val base = BuildingTestContent.scenario()
        val map = base.map.let { map -> MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
            map.tileSets, map.groundGids, BlockingGrid(map.widthTiles, map.heightTiles,
                BooleanArray(map.widthTiles * map.heightTiles) { it % map.widthTiles == 24 && it / map.widthTiles == 10 })) }
        val input = base.copy(map = map, units = base.units.map {
            when (it.unitId) {
                "a" -> it.copy(position = Position(190.0, 210.0))
                "f" -> it.copy(ownerPlayerId = "p2")
                else -> it
            }
        } + UnitSpawn("f2", "p2", "landFactory", Position(310.0, 90.0), 0.0),
            combatContract = base.combatContract!!.copy(unitObjectIds = base.combatContract!!.unitObjectIds + ("f2" to 11L), nextObjectId = 12L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val old = context.observe()
            var nextStep = 0L
            while (context.observe().units.single { it.unitId == "f" }.alive && nextStep < 4000L)
                context.advance(Step(nextStep++, 1.0, "sim-tick"))
            assertTrue(!context.observe().units.single { it.unitId == "f" }.alive, "Real ordinary projectiles must kill the factory")
            println("native-factory-real-death ticks=$nextStep finalHp=${context.observe().units.single { it.unitId == "f" }.health}")
            assertEquals(1200.0, old.units.single { it.unitId == "f" }.health)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("through-corpse", "p1", "a", Position(430.0, 210.0), context.world)))
            assertTrue(context.observe().units.single { it.unitId == "a" }.pathPoints.all { it.y in 180.0..<240.0 },
                "A dead building must be absent from new path cost")
            repeat(300) { context.advance(Step(nextStep++, 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single { it.unitId == "a" }.position.x > 350.0)
            assertTrue(context.observe().units.single { it.unitId == "f2" }.alive)
            for ((name, goal) in listOf("still-live-building" to Position(310.0, 90.0), "still-hard-wall" to Position(490.0, 210.0))) {
                val blocked = assertIs<ActionResult.Accepted>(context.submit(MoveAction(name, "p1", "a", goal, context.world)))
                assertTrue(blocked.events.any { it.kind == "path-unreachable" })
                assertEquals(null, context.observe().units.single { it.unitId == "a" }.moveTarget)
            }
            assertTrue(!map.blocking.isBlocked(15, 10), "The original ground map must not be overwritten with building cost")
            assertTrue(map.blocking.isBlocked(24, 10))
            val oldWorld = context.world
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(input))
            assertTrue(context.observe().units.single { it.unitId == "f" }.alive)
            assertEquals(1200.0, context.observe().units.single { it.unitId == "f" }.health)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("fresh-live-cost", "p1", "a", Position(430.0, 210.0), context.world)))
            assertTrue(context.observe().units.single { it.unitId == "a" }.pathPoints.any { it.y < 180.0 || it.y >= 240.0 })
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(
                StopAction("old-building-world", "p1", "a", oldWorld))).error.code)
        } finally { context.close() }
    }

    @Test
    fun footprint_bounds_use_long_offsets_and_circle_tangency_stays_independent() {
        val base = BuildingTestContent.scenario()
        fun withFactory(position: Position) = base.copy(units = base.units.map {
            if (it.unitId == "f") it.copy(position = position) else it })
        assertIs<InitResult.Accepted>(SimulationContext.initialize(withFactory(Position(30.0, 30.0)))).context.close()
        assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(SimulationContext.initialize(
            withFactory(Position(29.999, 30.0)))).error.code)
        val factory = base.unitDefinitions.getValue("landFactory")
        val bad = BuildingTestContent.variant(factory, footprint = BuildingFootprint(Int.MIN_VALUE, -1, Int.MAX_VALUE, 1))
        assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(SimulationContext.initialize(
            base.copy(unitDefinitions = base.unitDefinitions + (bad.id to bad)))).error.code)
        assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(SimulationContext.initialize(
            withFactory(Position(110.0, 210.0)))).error.code)
        val blocked = base.map.let { map -> MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
            map.tileSets, map.groundGids, BlockingGrid(map.widthTiles, map.heightTiles,
                BooleanArray(map.widthTiles * map.heightTiles) { it % map.widthTiles == 15 && it / map.widthTiles == 10 })) }
        assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(SimulationContext.initialize(base.copy(map = blocked))).error.code)
        val overlap = base.copy(units = base.units + UnitSpawn("f2", "p2", "landFactory", Position(330.0, 210.0), 0.0),
            combatContract = base.combatContract!!.copy(unitObjectIds = base.combatContract!!.unitObjectIds + ("f2" to 11L), nextObjectId = 12L))
        assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(SimulationContext.initialize(overlap)).error.code)
    }

    @Test
    fun real_radius30_factory_initializes_and_no_fog_move_really_goes_around_its_footprint() {
        val input = BuildingTestContent.scenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val before = context.observe()
            val factory = before.units.single { it.unitId == "f" }
            assertEquals(1200.0, factory.health)
            assertEquals(1200.0, factory.maxHealth)
            assertEquals(30.0, input.unitDefinitions.getValue(factory.typeId).radius)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("past-factory", "p1", "a", Position(510.0, 210.0), context.world)))
            val routed = context.observe().units.single { it.unitId == "a" }
            assertTrue(routed.pathPoints.any { it.y < 180.0 || it.y >= 240.0 }, "NoFog must plan around the live 3x3 building cost")
            var after = context.observe()
            repeat(600) { step ->
                after = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                val unit = after.units.single { it.unitId == "a" }
                val column = (unit.position.x / 20).toInt()
                val row = (unit.position.y / 20).toInt()
                assertTrue(column !in 14..16 || row !in 9..11, "A real moving unit must not enter the live factory cost cells")
            }
            assertTrue(after.units.single { it.unitId == "a" }.position.x > 350.0, "Actual motion must pass beyond the factory")
            assertEquals(1200.0, before.units.single { it.unitId == "f" }.health)
        } finally { context.close() }
    }
}
