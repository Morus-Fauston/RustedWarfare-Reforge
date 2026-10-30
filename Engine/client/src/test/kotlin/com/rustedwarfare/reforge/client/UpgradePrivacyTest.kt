package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class UpgradePrivacyTest {
    private fun base(): Scenario {
        val repo = System.getProperty("repo.root")?.let { Path.of(it) } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        return ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/turret-upgrades") } ?: repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-004/20261026-e-upgrade-input-01/input")))
    }
    private fun observer(tank: UnitDefinition) = UnitDefinition("observer", tank.className, tank.radius,
        tank.displayRadius, tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 1)

    @Test fun real_hidden_area_hits_multiple_victims_without_changing_any_nested_public_client_frame() {
        val base = base()
        val tank = base.unitDefinitions.getValue("c_tank")
        val observer = observer(tank)
        val dummy = UnitDefinition("dummy", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 1,
            durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + mapOf(observer.id to observer, dummy.id to dummy),
            players = base.players + Player("p3", PlayerControl.NONE, "three"),
            units = listOf(UnitSpawn("a", "p1", "observer", Position(110.0, 310.0), 90.0),
                UnitSpawn("t", "p2", "c_turret_t1_artillery", Position(430.0, 90.0), 90.0),
                UnitSpawn("z", "p3", "dummy", Position(510.0, 90.0), 270.0),
                UnitSpawn("z2", "p3", "dummy", Position(510.0, 114.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L, "z" to 12L, "z2" to 13L), 15L),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)))
        val witness = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val rows = (0L..49L).map { witness.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(rows.any { row -> row.projectileEvents.any { it.kind == "impact" && it.projectile.ground != null } })
            assertTrue(rows.last().units.filter { it.unitId.startsWith("z") }.all { it.health!! < 210.0 })
        } finally { witness.close() }
        val absent = scene.copy(units = scene.units.take(1), combatContract = scene.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L)))
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val rows = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
            val sessions = listOf(scene, absent).mapIndexed { index, input -> SandboxSession(input,
                ClientEvidence { kind, values -> rows[index] += kind to values }, SandboxOptions(mode)) }
            try {
                repeat(51) { step ->
                    assertEquals(sessions[0].observation.fields(), sessions[1].observation.fields(), "$mode/$step")
                    assertEquals(sessions[0].observation.vision!!.basicCells, sessions[1].observation.vision!!.basicCells)
                    assertEquals(sessions[0].observation.vision!!.effectiveCells, sessions[1].observation.vision!!.effectiveCells)
                    assertEquals(sessions[0].observation.knowledge!!.revision, sessions[1].observation.knowledge!!.revision)
                    assertEquals(sessions[0].frame.terrain, sessions[1].frame.terrain)
                    assertEquals(rows[0], rows[1], "Every nested ClientEvidence value: $mode/$step")
                    rows.forEach { it.clear() }; sessions.forEach { it.tick() }
                }
            } finally { sessions.forEach { it.close() } }
        }
    }

    @Test fun hidden_enemy_payment_progress_completion_and_new_sight_do_not_advance_the_other_players_sample_or_payload() {
        val base = base()
        val observer = observer(base.unitDefinitions.getValue("c_tank"))
        val input = base.copy(unitDefinitions = base.unitDefinitions + (observer.id to observer),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a", "p1", "observer", Position(110.0, 310.0), 90.0),
                UnitSpawn("t", "p2", "c_turret_t1", Position(430.0, 90.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L), 15L),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 1750L)))
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(
                automaticVision = SandboxOptions(mode).knowledgeRules()))).context }
            try {
                contexts.forEach { it.observePlayer(it.world, "p1") }
                val old = contexts[0].observePlayer(contexts[0].world, "p1")
                assertIs<ActionResult.Accepted>(contexts[0].submit(UpgradeAction("hidden", "p2", "t", "upgrade_gun", contexts[0].world)))
                assertSame(old, contexts[0].observePlayer(contexts[0].world, "p1"))
                repeat(1004) { step ->
                    contexts.forEach { it.advance(Step(step.toLong(), 1.0, "sim-tick")) }
                    val views = contexts.map { it.observePlayer(it.world, "p1") }
                    assertEquals(views[0].fields(), views[1].fields(), "$mode/$step")
                    assertEquals(views[0].vision!!.effectiveCells, views[1].vision!!.effectiveCells)
                    assertEquals(views[0].knowledge!!.revision, views[1].knowledge!!.revision)
                }
                assertEquals("c_turret_t2_gun", contexts[0].observe().units.single { it.unitId == "t" }.typeId)
                assertEquals("c_turret_t1", contexts[1].observe().units.single { it.unitId == "t" }.typeId)
                assertTrue(old.upgradeQueues.isEmpty())
            } finally { contexts.forEach { it.close() } }
        }
    }
}
