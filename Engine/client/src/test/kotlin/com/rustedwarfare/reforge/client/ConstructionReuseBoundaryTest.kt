package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionReuseBoundaryTest {
    @Test fun completed_same_id_reuse_requires_strict_circle_intersection_even_when_placement_footprints_overlap() {
        for (distance in listOf(0.0, 40.0, 60.0, 80.0)) {
            val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0 + distance), 90.0),
                UnitSpawn("target", "p1", "landFactory", Position(190.0, 170.0), 0.0))
            checkReuse(spawns, Position(190.0, 170.0 + distance), if (distance < 60.0) "target" else null)
        }
    }

    @Test fun a_blocked_build_does_not_reuse_another_owner_or_another_registered_type() {
        for ((owner, type) in listOf("p2" to "landFactory", "p1" to "fabricatorT1")) {
            val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("target", owner, type, Position(190.0, 170.0), 0.0))
            checkReuse(spawns, Position(190.0, 170.0), null)
        }
    }

    @Test fun multiple_legal_candidates_use_actual_initial_activity_order_instead_of_nearest_position() {
        for (firstOnRight in listOf(false, true)) {
            val a = if (firstOnRight) 250.0 else 190.0
            val z = if (firstOnRight) 190.0 else 250.0
            // initialize orders these actual IDs; reverse declaration does not fabricate a new order.
            val spawns = listOf(UnitSpawn("z", "p1", "landFactory", Position(z, 170.0), 0.0),
                UnitSpawn("worker", "p1", "builder", Position(150.0, 170.0), 90.0),
                UnitSpawn("a", "p1", "landFactory", Position(a, 170.0), 0.0))
            checkReuse(spawns, Position(210.0, 170.0), "a")
        }
    }

    @Test fun a_real_upgraded_extractor_is_not_reused_by_a_tier_one_order_on_its_resource_pool() {
        val base = base()
        val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            UnitSpawn("target", "p1", "extractorT1", Position(190.0, 290.0), 0.0))
        val scene = base.copy(units = spawns, economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "target" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", "target", "upgrade_t2", context.world)))
            while (context.observe().units.single { it.unitId == "target" }.typeId != "extractorT2" && step < 2000L) tick()
            assertEquals("extractorT2", context.observe().units.single { it.unitId == "target" }.typeId)
            while (context.observe().incomeStates.single { it.unitId == "target" }.elapsed != 0f && step < 2100L) tick()
            assertEquals(0f, context.observe().incomeStates.single { it.unitId == "target" }.elapsed)
            val balance = context.observePlayer(context.world, "p1").credits
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("tier-one", "p1", "worker", "extractorT1",
                Position(190.0, 290.0), "3".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("tail", "p1", "worker", Position(450.0, 290.0), true, context.world)))
            val rejectedPlacement = tick()
            assertTrue(rejectedPlacement.events.any { it.kind == "construction-unavailable" })
            assertEquals(1, rejectedPlacement.units.count { it.definitionFamily() })
            assertEquals("extractorT2", rejectedPlacement.units.single { it.unitId == "target" }.typeId)
            assertEquals(listOf("tail"), rejectedPlacement.units.single { it.unitId == "worker" }.tasks.map { it.actionId })
            assertEquals(balance, context.observePlayer(context.world, "p1").credits)
            assertTrue(rejectedPlacement.construction!!.buildings.isEmpty())
        } finally { context.close() }
    }

    private fun checkReuse(spawns: List<UnitSpawn>, order: Position, expectedTarget: String?) {
        val base = base()
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val scene = base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground }, BlockingGrid(40, 25, BooleanArray(1000))),
            units = spawns, combatContract = CombatContract(1, -1,
                spawns.mapIndexed { index, spawn -> spawn.unitId to index + 1L }.toMap(), spawns.size + 1L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "landFactory", order, "1".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("tail", "p1", "worker", Position(450.0, 290.0), true, context.world)))
            val frame = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(spawns.size, frame.units.size)
            assertTrue(frame.events.none { it.kind == "construction-created" })
            assertEquals(1750.0, context.observePlayer(context.world, "p1").credits)
            val worker = frame.units.single { it.unitId == "worker" }
            if (expectedTarget == null) {
                assertEquals(listOf("tail"), worker.tasks.map { it.actionId })
                assertTrue(frame.events.any { it.kind == "construction-unavailable" })
                assertNull(worker.moveTarget)
            } else {
                assertEquals(listOf(UnitTaskKind.CONSTRUCT, UnitTaskKind.PATROL), worker.tasks.map { it.kind })
                assertEquals(expectedTarget, worker.tasks.first().targetUnitId)
                assertEquals(1f, frame.units.single { it.unitId == expectedTarget }.constructionProgress)
            }
        } finally { context.close() }
    }
    private fun UnitObservation.definitionFamily() = typeId.startsWith("extractor")
    private fun base(): Scenario {
        val repo = System.getProperty("repo.root")?.let { Path.of(it) } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        return ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")))
    }
}
