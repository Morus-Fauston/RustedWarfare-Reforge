package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionQueueTest {
    @Test fun a_full_queue_keeps_twenty_nine_active_items_and_reports_no_new_item_only_to_its_owner() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(PatrolAction("head", "p1", "worker",
                Position(450.0, 290.0), false, c.world)))
            for (index in 1..28) assertIs<ActionResult.Accepted>(c.submit(BuildAction("queued-$index", "p1", "worker",
                "landFactory", Position(190.0, 170.0), index.toString(16).padStart(32, '0'), c.world, append = true)))
            val before = c.observePlayer(c.world, "p1").units.single().tasks
            assertEquals(29, before.size)
            val overflow = assertIs<ActionResult.Accepted>(c.submit(BuildAction("overflow", "p1", "worker",
                "landFactory", Position(330.0, 170.0), "f".repeat(32), c.world, append = true)))
            assertEquals(listOf(Event("construction-queue-full", "worker")), overflow.events)
            val owner = c.observePlayer(c.world, "p1")
            assertEquals(before, owner.units.single().tasks)
            assertTrue(owner.events.any { it.kind == "construction-queue-full" && it.unitId == "worker" })
            val other = c.observePlayer(c.world, "p2")
            assertTrue(other.events.none { it.kind == "construction-queue-full" })
            assertTrue(other.units.all { it.tasks.isEmpty() })
            assertEquals(1750.0, owner.credits)
        } finally { c.close() }
    }

    @Test fun all_three_maintenance_orders_append_behind_a_live_head_and_default_to_replacement() {
        for (kind in listOf("repair", "reclaim", "resume")) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("create", "p1", "worker", "landFactory",
                    Position(190.0, 170.0), "1".repeat(32), c.world)))
                val target = c.advance(Step(0, 1.0, "sim-tick")).units.single { it.typeId == "landFactory" }.unitId
                assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "worker", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(PatrolAction("head", "p1", "worker",
                    Position(450.0, 290.0), false, c.world)))
                fun order(id: String, append: Boolean = false): Action = when (kind) {
                    "repair" -> RepairBuildingAction(id, "p1", "worker", target, c.world, append = append)
                    "reclaim" -> ReclaimBuildingAction(id, "p1", "worker", target, c.world, append = append)
                    else -> ResumeConstructionAction(id, "p1", "worker", target, c.world, append = append)
                }
                assertIs<ActionResult.Accepted>(c.submit(order("tail", true)))
                val queued = c.observePlayer(c.world, "p1").units.single { it.unitId == "worker" }.tasks
                assertEquals(listOf("head", "tail"), queued.map { it.actionId }, kind)
                assertEquals(if (kind == "reclaim") UnitTaskKind.RECLAIM else UnitTaskKind.CONSTRUCT, queued.last().kind)
                assertEquals(target, queued.last().targetUnitId)
                assertIs<ActionResult.Accepted>(c.submit(order("replacement")))
                assertEquals(listOf("replacement"), c.observePlayer(c.world, "p1").units
                    .single { it.unitId == "worker" }.tasks.map { it.actionId }, kind)
            } finally { c.close() }
        }
    }

    @Test fun appending_a_build_preserves_the_active_patrol_and_plain_build_replaces_it() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(PatrolAction("patrol", "p1", "worker",
                Position(450.0, 290.0), false, c.world)))
            val head = c.observePlayer(c.world, "p1").units.single().tasks.single()
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("append", "p1", "worker", "landFactory",
                Position(190.0, 170.0), "1".repeat(32), c.world, append = true)))
            val queued = c.observePlayer(c.world, "p1").units.single().tasks
            assertEquals(listOf(UnitTaskKind.PATROL, UnitTaskKind.BUILD), queued.map { it.kind })
            assertEquals(head, queued.first())
            assertEquals("append", queued.last().actionId)
            assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("replace", "p1", "worker", "landFactory",
                Position(330.0, 170.0), "2".repeat(32), c.world)))
            assertEquals(listOf("replace"), c.observePlayer(c.world, "p1").units.single().tasks.map { it.actionId })
        } finally { c.close() }
    }

    private fun scene(): Scenario {
        val base = ControlledScenario.load(inputRoot())
        return base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
    }

    private fun inputRoot(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input"))
}
