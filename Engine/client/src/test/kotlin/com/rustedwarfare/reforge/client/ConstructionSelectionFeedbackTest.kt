package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionSelectionFeedbackTest {
    @Test fun an_empty_batch_after_a_real_success_does_not_retain_the_previous_success_feedback() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 290.0)))
            assertTrue(session.selectedIds.isEmpty())
            assertEquals(1, session.lastCommandBatch!!.accepted)
            assertNull(session.move(Position(450.0, 290.0)))
            assertEquals(0, session.lastCommandBatch!!.accepted)
            assertTrue(session.lastCommandBatch!!.members.isEmpty())
            assertEquals("No selection", session.playerFeedback)
        }
    }

    @Test fun an_all_foreign_selection_records_skips_instead_of_reusing_a_successful_batch() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 290.0), true))
            session.select(Position(240.0, 170.0))
            session.select(Position(350.0, 230.0), SelectionMode.ADD)
            assertNull(session.move(Position(450.0, 290.0)))
            val batch = assertNotNull(session.lastCommandBatch)
            assertEquals(0, batch.accepted); assertEquals(0, batch.rejected); assertEquals(2, batch.skipped)
            assertTrue(batch.members.isEmpty())
            assertEquals("Accepted 0, rejected 0, skipped 2", session.playerFeedback)
            assertFalse(session.playerFeedback.contains("z"))
        }
    }

    @Test fun a_selected_workers_real_combat_death_reconciles_selection_and_cannot_revive_a_held_build() {
        val actions = mutableListOf<Map<String, Any?>>()
        SandboxSession(scene(), ClientEvidence { kind, value -> if (kind == "action") actions += value }).use { session ->
            session.select(Position(120.0, 170.0)); session.select(Position(120.0, 210.0), SelectionMode.ADD)
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false })
            assertTrue(input.beginConstruction("landFactory"))
            val x = map.x(190.0).toInt(); val y = map.y(290.0).toInt()
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
            var ticks = 0
            while (session.observation.units.any { it.unitId == "a" && it.alive } && ticks++ < 1200) session.tick()
            assertTrue(session.observation.units.none { it.unitId == "a" && it.alive })
            assertEquals(setOf("b"), session.selectedIds)
            input.touchUp(x, y, 0, Input.Buttons.LEFT)
            assertTrue(actions.none { it["type"] == "BuildAction" })
            assertTrue(input.beginConstruction("landFactory"))
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT)); assertTrue(input.touchUp(x, y, 0, Input.Buttons.LEFT))
            assertEquals(listOf("b"), actions.filter { it["type"] == "BuildAction" }.map { it["unitId"] })
        }
    }

    private fun scene(): Scenario {
        val repo = System.getProperty("repo.root")?.let { Path.of(it) } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val base = ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")))
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val spawns = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0),
            UnitSpawn("z1", "p2", "c_tank", Position(240.0, 170.0), 270.0),
            UnitSpawn("z2", "p2", "c_tank", Position(350.0, 230.0), 270.0))
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground }, BlockingGrid(40, 25, BooleanArray(1000))),
            units = spawns, combatContract = CombatContract(1, -1,
                spawns.mapIndexed { index, spawn -> spawn.unitId to index + 1L }.toMap(), spawns.size + 1L))
    }
}
