package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaintenanceQueueRangeTest {
    @Test fun a_real_repair_overflow_keeps_the_next_repair_working_through_ninety_while_a_cold_repair_approaches() {
        for (distance in listOf(88.0, 90.0, 90.01)) for (warm in listOf(false, true)) {
            val base = ControlledScenario.load(inputRoot())
            val scene = base.copy(units = listOf(
                UnitSpawn("worker", "p1", "builder", Position(160.0, 290.0), 90.0),
                UnitSpawn("damager", "p1", "builder", Position(330.0, 290.0), 270.0),
                UnitSpawn("first", "p1", "extractorT1", Position(190.0, 290.0), 0.0),
                UnitSpawn("next", "p1", "landFactory", Position(160.0 + distance, 290.0), 0.0)),
                combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "damager" to 2L, "first" to 3L, "next" to 4L), 5L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene), "$distance/$warm").context
            try {
                var step = 0L
                fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
                fun hp(id: String) = context.observe().units.single { it.unitId == id }.health!!
                assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("damage-next", "p1", "damager", "next", context.world)))
                if (warm) assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("damage-first", "p1", "worker", "first", context.world)))
                while (hp("next") == 1200.0 && step < 100L) tick()
                assertEquals(1198.97998046875, hp("next"))
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-damager", "p1", "damager", context.world)))
                if (warm) {
                    assertEquals(798.97998046875, hp("first"))
                    assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-worker", "p1", "worker", context.world)))
                    assertIs<ActionResult.Accepted>(context.submit(RepairBuildingAction("repair-first", "p1", "worker", "first", context.world)))
                    assertIs<ActionResult.Accepted>(context.submit(RepairBuildingAction("repair-next", "p1", "worker", "next", context.world, append = true)))
                    var pop: Observation? = null
                    repeat(20) {
                        if (pop == null) {
                            val frame = tick()
                            if (frame.units.single { it.unitId == "worker" }.tasks.firstOrNull()?.actionId == "repair-next") pop = frame
                        }
                    }
                    val frame = assertNotNull(pop)
                    assertEquals(800.0, hp("first"))
                    assertEquals(1198.97998046875, hp("next"), "The first repair pop frame cannot also repair the next target")
                    assertTrue(frame.construction!!.workers.single { it.unitId == "worker" }.working)
                    assertEquals(Position(160.0, 290.0), frame.units.single { it.unitId == "worker" }.position)
                } else assertIs<ActionResult.Accepted>(context.submit(RepairBuildingAction("repair-next", "p1", "worker", "next", context.world)))
                val before = hp("next")
                val frame = tick()
                val worker = frame.units.single { it.unitId == "worker" }
                if (warm && distance <= 90.0) {
                    assertTrue(hp("next") > before)
                    assertTrue(frame.construction!!.workers.single { it.unitId == "worker" }.working)
                    assertEquals(Position(160.0, 290.0), worker.position)
                } else {
                    assertEquals(before, hp("next"))
                    assertFalse(frame.construction!!.workers.single { it.unitId == "worker" }.working)
                    assertNotNull(worker.moveTarget)
                    assertTrue(worker.position.x > 160.0)
                }
            } finally { context.close() }
        }
    }

    @Test fun repair_pop_does_not_dispatch_a_far_or_near_patrol_or_consume_an_extra_tail_in_the_same_tick() {
        for (injured in listOf(true, false)) for (near in listOf(false, true)) {
            val base = ControlledScenario.load(inputRoot())
            val scene = base.copy(units = listOf(
                UnitSpawn("worker", "p1", "builder", Position(160.0, 290.0), 90.0),
                UnitSpawn("first", "p1", "extractorT1", Position(190.0, 290.0), 0.0)),
                combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "first" to 2L), 3L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                if (injured) {
                    c.submit(ReclaimBuildingAction("damage", "p1", "worker", "first", c.world))
                    while (c.observe().units.single { it.unitId == "first" }.health == 800.0 && step < 100L) tick()
                    assertEquals(798.97998046875, c.observe().units.single { it.unitId == "first" }.health)
                    c.submit(StopAction("stop", "p1", "worker", c.world))
                }
                assertIs<ActionResult.Accepted>(c.submit(RepairBuildingAction("repair", "p1", "worker", "first", c.world)))
                val point = if (near) Position(160.0, 290.0) else Position(450.0, 290.0)
                assertIs<ActionResult.Accepted>(c.submit(PatrolAction("patrol", "p1", "worker", point, true, c.world)))
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("extra", "p1", "worker", "first", c.world,
                    append = true)))
                var popped: Observation? = null
                repeat(40) {
                    if (popped == null) {
                        val frame = tick()
                        if (frame.units.single { it.unitId == "worker" }.tasks.none { it.actionId == "repair" }) popped = frame
                    }
                }
                val frame = assertNotNull(popped)
                val worker = frame.units.single { it.unitId == "worker" }
                assertEquals(listOf("patrol", "extra"), worker.tasks.map { it.actionId }, "$injured/$near")
                assertNull(worker.moveTarget, "A popped construction activity must not dispatch its new Patrol head")
                assertTrue(worker.pathPoints.isEmpty())
                assertEquals(Position(160.0, 290.0), worker.position)
                assertEquals(800.0, frame.units.single { it.unitId == "first" }.health)
                val later = tick().units.single { it.unitId == "worker" }
                if (near) assertEquals(listOf("extra", "patrol"), later.tasks.map { it.actionId })
                else {
                    assertEquals(listOf("patrol", "extra"), later.tasks.map { it.actionId })
                    assertNotNull(later.moveTarget, "The next tick must actually start the waiting Patrol")
                    assertTrue(later.pathPoints.isNotEmpty())
                }
                assertEquals(800.0, c.observe().units.single { it.unitId == "first" }.health)
            } finally { c.close() }
        }
    }

    @Test fun a_real_repair_overflow_keeps_the_next_reclaim_working_through_ninety_but_a_cold_order_does_not() {
        for (distance in listOf(88.0, 90.0, 90.01)) {
            for (warm in listOf(false, true)) {
                val base = ControlledScenario.load(inputRoot())
                val scene = base.copy(units = listOf(
                    UnitSpawn("worker", "p1", "builder", Position(160.0, 290.0), 90.0),
                    UnitSpawn("first", "p1", "extractorT1", Position(190.0, 290.0), 0.0),
                    UnitSpawn("next", "p1", "landFactory", Position(160.0 + distance, 290.0), 0.0)),
                    combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "first" to 2L, "next" to 3L), 4L))
                val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene), "$distance/$warm").context
                try {
                    var step = 0L
                    fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                    fun hp(id: String) = c.observe().units.single { it.unitId == id }.health!!
                    if (warm) {
                        assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("damage", "p1", "worker", "first", c.world)))
                        while (hp("first") == 800.0 && step < 100L) tick()
                        assertEquals(798.97998046875, hp("first"), "One actual ordinary reclaim work tick")
                        assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "worker", c.world)))
                        assertIs<ActionResult.Accepted>(c.submit(RepairBuildingAction("repair", "p1", "worker", "first", c.world)))
                        assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("next-order", "p1", "worker", "next", c.world,
                            append = true)))
                        var overflow: Observation? = null
                        repeat(20) {
                            if (overflow == null) {
                                val frame = tick()
                                val worker = frame.units.single { it.unitId == "worker" }
                                if (worker.tasks.firstOrNull()?.actionId == "next-order") overflow = frame
                            }
                        }
                        val popped = assertNotNull(overflow, "The real repair must strictly overflow and pop")
                        assertEquals(800.0, hp("first"))
                        assertEquals(1200.0, hp("next"), "No second task is dispatched in the repair pop tick")
                        assertTrue(popped.construction!!.workers.single().working)
                        assertEquals(Position(160.0, 290.0), popped.units.single { it.unitId == "worker" }.position)
                    } else {
                        assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("next-order", "p1", "worker", "next", c.world)))
                    }
                    val before = hp("next")
                    val next = tick()
                    if (warm && distance <= 90.0) {
                        assertEquals(1198.97998046875, hp("next"), "$distance uses the retained working range")
                        assertTrue(next.construction!!.workers.single().working)
                        assertEquals(Position(160.0, 290.0), next.units.single { it.unitId == "worker" }.position)
                    } else {
                        assertEquals(before, hp("next"), "$distance/$warm must approach instead of doing work")
                        assertFalse(next.construction!!.workers.single().working)
                        val worker = next.units.single { it.unitId == "worker" }
                        assertEquals("next-order", worker.tasks.firstOrNull()?.actionId)
                        assertNotNull(worker.moveTarget, "Outside work range retains the real approach order")
                        assertTrue(worker.position.x > 160.0, "The real movement update must advance toward the target")
                    }
                } finally { c.close() }
            }
        }
    }

    private fun inputRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")
}
