package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaintenancePrivacyTest {
    @Test fun a_scheduled_maintenance_command_rechecks_a_target_reclaimed_after_submission() {
        val base = base()
        val spawns = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(units = spawns,
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), 4L)))).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(RepairBuildingAction("late", "p1", "b", "factory", c.world, 1500L)))
            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("remove", "p1", "a", "factory", c.world)))
            for (step in 0L..1499L) c.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(c.observe().units.none { it.unitId == "factory" })
            val view = c.advance(Step(1500, 1.0, "sim-tick"))
            assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(view.actionResults.single { it.actionId == "late" }).error.code)
            assertTrue(view.units.single { it.unitId == "b" }.tasks.isEmpty())
            assertEquals(2310.0000083446503, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun ordinary_combat_corpses_cannot_be_repaired_or_reclaimed() {
        val base = base()
        val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0),
            UnitSpawn("gun", "p2", "c_tank", Position(310.0, 170.0), 270.0))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(units = spawns,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), 4L)))).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill", "p2", "gun", "factory", c.world)))
            var step = 0L
            while (c.observe().units.single { it.unitId == "factory" }.alive && step < 4200)
                c.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(c.observe().units.single { it.unitId == "factory" }.alive)
            assertEquals(emptyList(), c.observe().reclaimedUnitIds)
            assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(c.submit(
                RepairBuildingAction("repair-dead", "p1", "worker", "factory", c.world))).error.code)
            assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(c.submit(
                ReclaimBuildingAction("reclaim-dead", "p1", "worker", "factory", c.world))).error.code)
            assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun foreign_allied_nonbuilding_self_and_missing_targets_have_the_same_finite_rejection() {
        val base = base()
        val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("own", "p1", "landFactory", Position(190.0, 170.0), 0.0),
            UnitSpawn("foreign", "p2", "landFactory", Position(450.0, 170.0), 0.0),
            UnitSpawn("ally", "p3", "landFactory", Position(450.0, 310.0), 0.0),
            UnitSpawn("other-worker", "p1", "builder", Position(120.0, 210.0), 90.0))
        val scene = base.copy(units = spawns,
            players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.LOCAL, "red"),
                Player("p3", PlayerControl.LOCAL, "blue")),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)),
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), 6L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for ((i, target) in listOf("foreign", "ally", "other-worker", "worker", "missing").withIndex()) {
                val before = c.observe()
                for (repair in listOf(false, true)) {
                    val id = "reject-$i-$repair"
                    val order = if (repair) RepairBuildingAction(id, "p1", "worker", target, c.world)
                        else ReclaimBuildingAction(id, "p1", "worker", target, c.world)
                    assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(c.submit(order)).error.code)
                    assertEquals(before.units, c.observe().units)
                    assertEquals(before.economy!!.preciseBalances, c.observe().economy!!.preciseBalances)
                }
            }
            assertEquals("cannot-maintain", assertIs<ActionResult.Rejected>(c.submit(
                ReclaimBuildingAction("not-worker", "p1", "own", "own", c.world))).error.code)
        } finally { c.close() }
    }

    @Test fun hidden_reclaim_and_its_terminal_events_do_not_change_an_observers_entire_payload_in_either_knowledge_mode() {
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val base = base()
            val spawns = listOf(UnitSpawn("observer", "p1", "builder", Position(50.0, 70.0), 90.0),
                UnitSpawn("worker", "p2", "builder", Position(380.0, 250.0), 90.0),
                UnitSpawn("factory", "p2", "landFactory", Position(450.0, 250.0), 0.0))
            val scene = base.copy(units = spawns, players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
                automaticVision = SandboxOptions(mode).knowledgeRules(),
                combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), 4L))
            val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
            fun payload(c: SimulationContext): List<Any?> {
                val v = c.observePlayer(c.world, "p1")
                return listOf(v.fields(), v.vision?.worldId, v.vision?.playerId, v.vision?.epoch,
                    v.vision?.basicCells, v.vision?.effectiveCells, v.knowledge?.worldId, v.knowledge?.playerId,
                    v.knowledge?.width, v.knowledge?.height, v.knowledge?.revision, v.knowledge?.cells)
            }
            fun assertPayloadEqual(label: String) {
                val expected = payload(contexts[0])
                val actual = payload(contexts[1])
                assertTrue(expected == actual,
                    "$label differs at payload indices ${expected.indices.filter { expected[it] != actual[it] }}")
            }
            try {
                contexts.forEach { c ->
                    assertTrue(c.observePlayer(c.world, "p1").units.none { it.unitId == "factory" })
                }
                assertPayloadEqual("$mode initial")
                assertIs<ActionResult.Accepted>(contexts[1].submit(ReclaimBuildingAction("hidden-reclaim", "p2", "worker", "factory", contexts[1].world)))
                var terminal = false
                for (step in 0L..1499L) {
                    contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                    assertPayloadEqual("$mode step $step")
                    if (contexts[1].observe().units.none { it.unitId == "factory" }) {
                        terminal = true
                        val owner = contexts[1].observePlayer(contexts[1].world, "p2")
                        assertEquals(listOf("factory"), owner.events.filter { it.kind == "building-reclaimed" }.map { it.unitId })
                        assertEquals(560.0000083446503, owner.credits)
                        break
                    }
                }
                assertTrue(terminal)
                assertTrue(contexts[0].observe().units.any { it.unitId == "factory" && it.alive })
                for ((index, target) in listOf("factory", "nonexistent").withIndex()) {
                    val results = contexts.map { c -> c.submit(ReclaimBuildingAction("probe-$index", "p1", "observer", target, c.world)) }
                    assertEquals(results[0], results[1])
                    assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(results[1]).error.code)
                    assertPayloadEqual("$mode rejected target $index")
                }
            } finally { contexts.forEach { it.close() } }
        }
    }

    private fun base(): Scenario = ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/maintenance") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-005/20261029-implementation-01/input")))
}
