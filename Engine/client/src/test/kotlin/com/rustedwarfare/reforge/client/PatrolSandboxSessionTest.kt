package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class PatrolSandboxSessionTest {
    @Test fun actual_evidence_preserves_the_patrol_action_and_owner_tasks_through_stop() {
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        SandboxSession(ControlledScenario.load(root()), ClientEvidence { kind, values -> rows += kind to values }).use { s ->
            s.select(Position(110.0, 210.0))
            val point = Position(30.0, 330.0)
            assertIs<ActionResult.Accepted>(s.patrol(point, false))
            val action = rows.last { it.first == "action" }.second
            assertEquals("PatrolAction", action["type"])
            assertEquals(mapOf("x" to 30.0, "y" to 330.0), action["target"])
            assertEquals(false, action["append"])
            val sample = rows.last { it.first == "observation" }.second["sample"] as Map<*, *>
            val actor = (sample["units"] as List<*>).map { it as Map<*, *> }.single { it["unitId"] == "a" }
            val tasks = actor["tasks"] as List<*>
            assertEquals(1, tasks.size)
            assertEquals(mapOf("kind" to "patrol", "target" to mapOf("x" to 30.0, "y" to 330.0),
                "targetUnitId" to null), tasks.single())
            s.stop()
            val stopped = rows.last { it.first == "observation" }.second["sample"] as Map<*, *>
            assertTrue(((stopped["units"] as List<*>).map { it as Map<*, *> }.single { it["unitId"] == "a" }["tasks"] as List<*>).isEmpty())
            assertEquals(1, tasks.size, "Old sink values remain immutable after Stop")
        }
    }
    @Test fun session_patrol_dispatches_the_fixed_selection_and_reports_real_partial_failure() {
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        SandboxSession(ControlledScenario.load(root()), ClientEvidence { kind, values -> rows += kind to values }).use { s ->
            s.select(Position(110.0, 210.0))
            s.select(Position(310.0, 210.0), SelectionMode.ADD)
            s.select(Position(430.0, 210.0), SelectionMode.ADD)
            val selected = s.selectedIds
            val point = Position(30.0, 330.0)
            assertIs<ActionResult.Rejected>(s.patrol(point, false, selected))
            val batch = checkNotNull(s.lastCommandBatch)
            assertEquals(listOf("a", "f"), batch.members.map { it.unitId })
            assertEquals(1, batch.accepted); assertEquals(1, batch.rejected); assertEquals(1, batch.skipped)
            assertEquals(point, s.observation.units.single { it.unitId == "a" }.tasks.single().target)
            assertTrue(s.observation.units.single { it.unitId == "f" }.tasks.isEmpty())
            s.select(Position(0.0, 0.0))
            val next = Position(550.0, 330.0)
            s.patrol(next, true, selected)
            assertEquals(listOf(point, next), s.observation.units.single { it.unitId == "a" }.tasks.map { it.target })
            assertTrue(s.selectedIds.isEmpty(), "Dispatch must use the captured press selection without changing current selection")
            assertEquals(4, rows.count { it.first == "action" && it.second["type"] == "PatrolAction" })
            repeat(80) { s.tick() }
            assertTrue(s.observation.units.single { it.unitId == "a" }.position != Position(110.0, 210.0))
        }
    }
    private fun root(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/patrol") } ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-006/20261026-e-selection-contract-01/input"))
}
