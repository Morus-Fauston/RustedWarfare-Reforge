package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionMixedDispatchTest {
    @Test fun mixed_legacy_resume_dispatches_to_the_worker_without_turning_the_command_into_a_move() {
        val base = scene().let { it.copy(maintenanceContract = null,
            units = it.units.map { unit -> if (unit.unitId == "z") unit.copy(position = Position(340.0, 290.0)) else unit }) }
        SandboxSession(base).use { session ->
            session.select(Position(120.0, 170.0))
            assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 170.0)))
            session.tick()
            val target = session.observation.units.single { it.typeId == "landFactory" }
            assertTrue(target.constructionProgress < 1f)
            session.select(Position(120.0, 170.0)); session.select(Position(120.0, 230.0), SelectionMode.ADD)
            session.patrol(Position(450.0, 290.0), false)
            session.select(Position(340.0, 290.0), SelectionMode.ADD)
            assertEquals(setOf("a", "t", "z"), session.selectedIds)
            assertTrue(session.constructionActions.isEmpty())
            val result = assertIs<ActionResult.Rejected>(session.command(target.position, true))
            assertEquals("cannot-build", result.error.code)
            val batch = assertNotNull(session.lastCommandBatch)
            assertEquals(listOf("a", "t"), batch.members.map { it.unitId })
            assertEquals(1, batch.accepted); assertEquals(1, batch.rejected); assertEquals(1, batch.skipped)
            assertEquals("Accepted 1, rejected 1, skipped 1", session.playerFeedback)
            val worker = session.observation.units.single { it.unitId == "a" }
            assertEquals(listOf(UnitTaskKind.PATROL, UnitTaskKind.CONSTRUCT), worker.tasks.map { it.kind })
            assertEquals(target.unitId, worker.tasks.last().targetUnitId)
            assertEquals(listOf(UnitTaskKind.PATROL), session.observation.units.single { it.unitId == "t" }.tasks.map { it.kind })
            assertEquals(1050.0, session.observation.credits)
        }
    }

    @Test fun mixed_repair_keeps_the_legal_worker_and_rejects_the_tank_individually() = mixedMaintenance(false)
    @Test fun mixed_reclaim_keeps_the_legal_worker_and_rejects_the_tank_individually() = mixedMaintenance(true)

    private fun mixedMaintenance(reclaim: Boolean) {
        val base = scene()
        val spawns = base.units.map { if (it.unitId == "z") it.copy(position = Position(340.0, 290.0)) else it } +
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)
        SandboxSession(base.copy(units = spawns, combatContract = CombatContract(1, -1,
            spawns.mapIndexed { index, spawn -> spawn.unitId to index + 1L }.toMap(), 5L))).use { session ->
            session.select(Position(120.0, 170.0))
            assertIs<ActionResult.Accepted>(session.reclaim("factory"))
            repeat(100) { session.tick() }
            session.stop()
            val target = session.observation.units.single { it.unitId == "factory" }
            assertTrue(target.health!! in 1.0..<1200.0)
            session.select(Position(120.0, 230.0), SelectionMode.ADD)
            session.patrol(Position(450.0, 290.0), false)
            session.select(Position(340.0, 290.0), SelectionMode.ADD)
            assertEquals(setOf("a", "t", "z"), session.selectedIds)
            assertFalse(session.canMaintain, "This ticket does not invent a mixed-menu aggregation")
            val result = assertIs<ActionResult.Rejected>(if (reclaim) session.reclaim(target.position, true) else session.command(target.position, true))
            assertEquals("cannot-maintain", result.error.code)
            val batch = assertNotNull(session.lastCommandBatch)
            assertEquals(listOf("a", "t"), batch.members.map { it.unitId })
            assertEquals(1, batch.accepted); assertEquals(1, batch.rejected); assertEquals(1, batch.skipped)
            assertEquals("Accepted 1, rejected 1, skipped 1", session.playerFeedback)
            val worker = session.observation.units.single { it.unitId == "a" }
            assertEquals(listOf(UnitTaskKind.PATROL, if (reclaim) UnitTaskKind.RECLAIM else UnitTaskKind.CONSTRUCT), worker.tasks.map { it.kind })
            assertEquals(target.unitId, worker.tasks.last().targetUnitId)
            assertEquals(listOf(UnitTaskKind.PATROL), session.observation.units.single { it.unitId == "t" }.tasks.map { it.kind })
            assertEquals(setOf("a", "t", "z"), session.selectedIds)
            assertEquals(1750.0, session.observation.credits)
        }
    }

    @Test fun a_hidden_mixed_menu_does_not_swallow_the_public_per_member_build_dispatch() {
        for (append in listOf(false, true)) SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 230.0), SelectionMode.ADD)
            session.patrol(Position(450.0, 290.0), false)
            session.select(Position(280.0, 230.0), SelectionMode.ADD)
            assertEquals(setOf("a", "t", "z"), session.selectedIds)
            assertTrue(session.constructionActions.isEmpty(), "Unobserved mixed-menu aggregation stays outside this ticket")
            val result = assertIs<ActionResult.Rejected>(session.build("landFactory", Position(190.0, 170.0), append))
            assertEquals("cannot-build", result.error.code, "The last controllable tank rejects independently")
            val batch = assertNotNull(session.lastCommandBatch)
            assertEquals(listOf("a", "t"), batch.members.map { it.unitId })
            assertEquals(1, batch.accepted); assertEquals(1, batch.rejected); assertEquals(1, batch.skipped)
            assertEquals("Accepted 1, rejected 1, skipped 1", session.playerFeedback)
            assertEquals(if (append) setOf("a", "t", "z") else emptySet(), session.selectedIds,
                "At least one real acceptance decides replacement UI success, not the last result")
            val builder = session.observation.units.single { it.unitId == "a" }
            val tank = session.observation.units.single { it.unitId == "t" }
            assertEquals(if (append) listOf(UnitTaskKind.PATROL, UnitTaskKind.BUILD) else listOf(UnitTaskKind.BUILD), builder.tasks.map { it.kind })
            assertEquals(if (append) listOf(UnitTaskKind.PATROL) else emptyList(), tank.tasks.map { it.kind })
            assertTrue(session.observation.units.single { it.unitId == "z" }.tasks.isEmpty())
            assertEquals(1750.0, session.observation.credits)
        }
    }

    private fun scene(): Scenario {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
            .resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")
        return ControlledScenario.load(root).copy(units = listOf(
            UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("t", "p1", "c_tank", Position(120.0, 230.0), 90.0),
            UnitSpawn("z", "p2", "c_tank", Position(280.0, 230.0), 270.0)),
            combatContract = CombatContract(1, -1, mapOf("a" to 1L, "t" to 2L, "z" to 3L), 4L))
    }
}
