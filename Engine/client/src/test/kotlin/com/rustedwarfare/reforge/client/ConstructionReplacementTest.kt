package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionReplacementTest {
    @Test fun unsafe_envelopes_stale_worlds_permissions_and_invalid_schedules_never_preclear() {
        for (case in listOf("nan", "nonce", "stale", "duplicate", "id", "actor", "owner", "missing-worker", "past")) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            val other = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(PatrolAction("head", "p1", "worker",
                    Position(450.0, 290.0), false, c.world)))
                val before = c.observePlayer(c.world, "p1").units.single()
                val order = BuildAction(when (case) { "duplicate" -> "head"; "id" -> "bad id"; else -> "invalid" },
                    when (case) { "actor" -> "unknown"; "owner" -> "p2"; else -> "p1" },
                    if (case == "missing-worker") "missing" else "worker", "landFactory",
                    if (case == "nan") Position(Double.NaN, 170.0) else Position(190.0, 170.0),
                    if (case == "nonce") "not-a-nonce" else "1".repeat(32),
                    if (case == "stale") other.world else c.world, atStep = if (case == "past") -1L else null)
                assertIs<ActionResult.Rejected>(c.submit(order), case)
                assertEquals(before, c.observePlayer(c.world, "p1").units.single(), case)
                assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
            } finally { other.close(); c.close() }
        }
    }

    @Test fun future_orders_reserve_without_clearing_and_preclear_only_when_executed() {
        for (append in listOf(false, true)) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            try {
                c.submit(PatrolAction("head", "p1", "worker", Position(450.0, 290.0), false, c.world))
                val reserved = assertIs<ActionResult.Accepted>(c.submit(BuildAction("future", "p1", "worker",
                    "unavailable-type", Position(190.0, 170.0), "1".repeat(32), c.world, 2L, append)))
                assertEquals(2L, reserved.queuedForStep)
                assertEquals("head", c.observePlayer(c.world, "p1").units.single().tasks.single().actionId)
                repeat(2) { step ->
                    c.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    assertEquals("head", c.observePlayer(c.world, "p1").units.single().tasks.single().actionId)
                }
                val execution = c.advance(Step(2, 1.0, "sim-tick"))
                assertTrue(execution.actionResults.any { it is ActionResult.Rejected && it.actionId == "future" &&
                    it.error.code == "unsupported-construction-type" })
                val tasks = c.observePlayer(c.world, "p1").units.single().tasks
                if (append) assertEquals("head", tasks.single().actionId) else assertTrue(tasks.isEmpty())
            } finally { c.close() }
        }
    }

    @Test fun a_plain_safe_construction_order_preclears_before_capability_rejection_and_publishes_that_effect() {
        for (kind in listOf("build", "repair", "reclaim", "resume")) for (append in listOf(false, true)) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(PatrolAction("head", "p1", "worker",
                    Position(450.0, 290.0), false, c.world)))
                val before = c.observePlayer(c.world, "p1").units.single()
                val order = when (kind) {
                    "build" -> BuildAction("invalid", "p1", "worker", "unavailable-type", Position(190.0, 170.0),
                        "1".repeat(32), c.world, append = append)
                    "repair" -> RepairBuildingAction("invalid", "p1", "worker", "missing", c.world, append = append)
                    "reclaim" -> ReclaimBuildingAction("invalid", "p1", "worker", "missing", c.world, append = append)
                    else -> ResumeConstructionAction("invalid", "p1", "worker", "missing", c.world, append = append)
                }
                val result = assertIs<ActionResult.Rejected>(c.submit(order))
                assertEquals(when (kind) {
                    "build" -> "unsupported-construction-type"
                    "resume" -> "invalid-construction-target"
                    else -> "invalid-maintenance-target"
                }, result.error.code)
                val owner = c.observePlayer(c.world, "p1")
                val after = owner.units.single()
                if (append) {
                    assertEquals(before.tasks, after.tasks, kind)
                    assertEquals(before.moveTarget, after.moveTarget, kind)
                    assertEquals(before.pathPoints, after.pathPoints, kind)
                } else {
                    assertTrue(after.tasks.isEmpty(), "$kind must publish its preclear even though rejected")
                    assertNull(after.moveTarget)
                    assertTrue(after.pathPoints.isEmpty())
                }
                assertEquals(1750.0, owner.credits)
            } finally { c.close() }
        }
    }

    private fun scene(): Scenario {
        val base = ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")))
        return base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
    }
}
