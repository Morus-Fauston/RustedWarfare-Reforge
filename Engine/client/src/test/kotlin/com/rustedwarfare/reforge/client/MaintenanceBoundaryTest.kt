package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaintenanceBoundaryTest {
    @Test fun maintenance_uses_a_strict_thirty_degree_facing_gate() {
        for ((heading, working) in listOf(60.0 to false, 60.01 to true)) {
            val scene = base().copy(units = listOf(
                UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), heading),
                UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
                combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("reclaim", "p1", "worker", "factory", c.world)))
                val view = c.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(working, view.construction!!.workers.single().working)
                assertEquals(1f, view.construction!!.workers.single().elapsed)
                assertEquals(1200.0, view.units.single { it.unitId == "factory" }.health)
            } finally { c.close() }
        }
    }

    @Test fun real_tank_damage_at_exactly_half_hp_allows_one_early_reclaim_then_waits_until_v100() {
        for (hits in listOf(24, 25)) {
            val base = base()
            val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0),
                UnitSpawn("sink", "p1", "landFactory", Position(450.0, 310.0), 0.0),
                UnitSpawn("gun", "p2", "c_tank", Position(310.0, 170.0), 270.0))
            val scene = base.copy(units = spawns, players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
                combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), 5L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                fun hp() = c.observe().units.single { it.unitId == "factory" }.health!!
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("damage", "p2", "gun", "factory", c.world)))
                val expected = if (hits == 24) 600.0 else 575.0
                while (hp() > expected && step < 2500) tick()
                assertEquals(expected, hp())
                assertTrue(c.observe().projectiles.none { it.targetUnitId == "factory" })
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("redirect", "p2", "gun", "sink", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("reclaim", "p1", "worker", "factory", c.world)))
                var firstDamage: Double? = null
                for (v in 1..99) {
                    val view = tick()
                    assertEquals(v.toFloat(), view.construction!!.workers.single().elapsed)
                    assertTrue(view.events.none { it.kind == "unit-damaged" && it.unitId == "factory" })
                    if (hp() != expected) {
                        if (firstDamage == null) firstDamage = hp()
                        assertEquals(firstDamage, hp(), "Crossing below half pauses subsequent work through V99")
                    }
                    if (hits == 25) assertEquals(575.0, hp())
                }
                if (hits == 24) assertNotNull(firstDamage, "Exactly 600 / 1200 permits work before V100")
                val before = hp()
                tick()
                assertEquals(100f, c.observe().construction!!.workers.single().elapsed)
                assertTrue(hp() < before)
                assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
            } finally { c.close() }
        }
    }

    @Test fun real_build_and_reclaim_work_reaches_half_progress_then_obeys_the_v100_guard() {
        val scene = base().copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            var action = 0
            fun tick(): Observation {
                assertTrue(step < 25000, "The finite real-work path must complete without a test setter or changed delta")
                return c.advance(Step(step++, 1.0, "sim-tick"))
            }
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "worker", "landFactory",
                Position(190.0, 170.0), "a".repeat(32), c.world)))
            val target = tick().units.single { it.typeId == "landFactory" }.unitId
            fun progress() = c.observe().units.single { it.unitId == target }.constructionProgress
            fun changes(reclaim: Boolean, count: Int) {
                val id = "work-${++action}"
                val order = if (reclaim) ReclaimBuildingAction(id, "p1", "worker", target, c.world)
                    else RepairBuildingAction(id, "p1", "worker", target, c.world)
                assertIs<ActionResult.Accepted>(c.submit(order))
                var before = progress()
                var seen = 0
                while (seen < count) {
                    tick()
                    val after = progress()
                    if (after != before) { seen++; before = after }
                }
            }
            // Fixed ordinary .001f construction and .0051f reclaim coefficients; all work is public commands.
            changes(false, 40)
            repeat(97) { changes(false, 51); changes(true, 10) }
            changes(false, 460)
            assertEquals(0x3f000000, progress().toRawBits())
            assertEquals(1200.0, c.observe().units.single { it.unitId == target }.health)
            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("half", "p1", "worker", target, c.world)))
            tick()
            val first = progress()
            assertTrue(first < .5f, "Exactly half progress is eligible before V100")
            assertEquals(1f, c.observe().construction!!.workers.single().elapsed)
            for (v in 2..99) {
                tick()
                assertEquals(v.toFloat(), c.observe().construction!!.workers.single().elapsed)
                assertEquals(first, progress(), "Below half must wait through V99")
            }
            tick()
            assertEquals(100f, c.observe().construction!!.workers.single().elapsed)
            assertTrue(progress() < first, "V100 resumes actual dismantling")
            assertEquals(1050.0, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun maintenance_starts_at_85_and_preserves_preparation_until_normal_decay_after_a_new_order() {
        for (distance in listOf(85.0, 85.01)) {
            val scene = base().copy(units = listOf(
                UnitSpawn("worker", "p1", "builder", Position(190.0 - distance, 170.0), 90.0),
                UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
                combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("start", "p1", "worker", "factory", c.world)))
                val first = c.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(distance == 85.0, first.construction!!.workers.single().working)
                assertEquals(1f, first.construction!!.workers.single().elapsed)
                if (distance == 85.0) {
                    for (step in 1L..35L) c.advance(Step(step, 1.0, "sim-tick"))
                    assertEquals(30f, c.observe().construction!!.workers.single().preparation)
                    assertTrue(c.observe().units.single { it.unitId == "factory" }.health!! < 1200.0)
                    assertIs<ActionResult.Accepted>(c.submit(RepairBuildingAction("new-order", "p1", "worker", "factory", c.world)))
                    assertEquals(0f, c.observe().construction!!.workers.single().elapsed)
                    assertEquals(30f, c.observe().construction!!.workers.single().preparation)
                    assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "worker", c.world)))
                    assertEquals(30f, c.observe().construction!!.workers.single().preparation)
                    val decay = c.advance(Step(36, 1.0, "sim-tick")).construction!!.workers.single()
                    assertEquals(28.7f, decay.preparation)
                    assertFalse(decay.working)
                }
            } finally { c.close() }
        }
    }

    private fun base(): Scenario = ControlledScenario.load(generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-005/20261029-implementation-01/input"))
}
