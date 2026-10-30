package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseSelectionInfoTest {
    @Test fun the_real_selection_view_keeps_hp_and_construction_separate_shows_only_owned_energy_and_preserves_worker_and_economic_information() {
        for ((type, expected) in listOf("c_turret_t1" to "700 / 700\nBuilding 0%",
            "laserDefence" to "500 / 500\nBuilding 0%\nEnergy 1.000")) {
            SandboxSession(scene()).use { session ->
                assertNull(session.selectedUnitInfo)
                session.select(Position(120.0, 170.0))
                assertIs<ActionResult.Accepted>(session.build(type, Position(190.0, 170.0)))
                session.tick()
                session.select(Position(120.0, 170.0))
                assertIs<ActionResult.Accepted>(session.stop())
                session.select(Position(180.0, 160.0))
                assertEquals(expected, session.selectedUnitInfo)
                session.select(Position(260.0, 160.0))
                assertEquals("foreign", session.selectedId)
                assertEquals("500 / 500", session.selectedUnitInfo, "Public HP must not reveal another owner's energy")
                session.select(Position(330.0, 270.0))
                assertEquals("500 / 500\n+$2\nActive", session.selectedUnitInfo)
                session.select(Position(120.0, 170.0))
                assertEquals("170 / 170\nTasks 0\nIdle", session.selectedUnitInfo)
                session.select(Position(120.0, 210.0), SelectionMode.ADD)
                assertEquals("Tasks 0 each\nIdle x2", session.selectedWorkerQueueText)
                assertEquals(session.selectedWorkerQueueText, session.selectedUnitInfo)
                session.close()
                assertNull(session.selectedUnitInfo)
            }
        }
    }

    private fun scene(): Scenario {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input"))
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val units = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0),
            UnitSpawn("foreign", "p2", "laserDefence", Position(260.0, 160.0), 0.0),
            UnitSpawn("fab", "p1", "fabricatorT1", Position(330.0, 270.0), 0.0))
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))), units = units,
            combatContract = CombatContract(1, -1, units.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 5L))
    }
}
