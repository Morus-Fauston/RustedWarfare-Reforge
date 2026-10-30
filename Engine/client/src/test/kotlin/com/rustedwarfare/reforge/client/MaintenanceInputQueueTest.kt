package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaintenanceInputQueueTest {
    @Test fun escape_cancels_only_placement_after_two_real_shift_orders_and_stop_alone_clears_the_tasks() {
        val actions = mutableListOf<Map<String, Any?>>()
        val evidence = ClientEvidence { kind, values -> if (kind == "action") actions += values }
        SandboxSession(scene().copy(units = scene().units.filter { it.typeId == "builder" },
            combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L), 3L)), evidence).use { session ->
            session.select(Position(120.0, 170.0)); session.select(Position(120.0, 210.0), SelectionMode.ADD)
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { it == Input.Keys.SHIFT_LEFT }, evidence = evidence)
            assertTrue(input.beginConstruction("landFactory"))
            for (point in listOf(Position(190.0, 170.0), Position(450.0, 170.0))) {
                assertEquals(PlacementState.CLEAR, session.previewConstruction("landFactory", point)?.state)
                val x = map.x(point.x).toInt(); val y = map.y(point.y).toInt()
                assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
                assertTrue(input.touchUp(x, y, 0, Input.Buttons.LEFT))
            }
            val queued = session.observation.units.associate { it.unitId to it.tasks }
            assertTrue(queued.values.all { it.map { task -> task.kind } == listOf(UnitTaskKind.BUILD, UnitTaskKind.BUILD) })
            val beforeCancel = actions.toList()
            assertTrue(input.keyDown(Input.Keys.ESCAPE))
            assertNull(input.constructionTypeId)
            assertEquals(setOf("a", "b"), session.selectedIds)
            assertEquals(queued, session.observation.units.associate { it.unitId to it.tasks })
            assertEquals(beforeCancel, actions)
            assertEquals(listOf(true, true, true, true), actions.filter { it["type"] == "BuildAction" }.map { it["append"] })
            assertEquals(1750.0, session.observation.credits)
            session.tick()
            val project = session.observation.units.single { it.typeId == "landFactory" }
            assertEquals(1050.0, session.observation.credits)
            assertTrue(session.observation.units.filter { it.typeId == "builder" }.all {
                it.tasks.map { task -> task.kind } == listOf(UnitTaskKind.CONSTRUCT, UnitTaskKind.BUILD)
            })
            assertTrue(input.keyDown(Input.Keys.S))
            assertTrue(session.observation.units.filter { it.typeId == "builder" }.all { it.tasks.isEmpty() })
            assertEquals(project, session.observation.units.single { it.unitId == project.unitId })
            assertEquals(1050.0, session.observation.credits)
            assertEquals(2, actions.count { it["type"] == "StopAction" })
        }
    }

    @Test fun both_shift_keys_append_legacy_resume_after_a_real_project_is_stopped() {
        for (shift in listOf(Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT)) {
            SandboxSession(scene().copy(maintenanceContract = null, units = scene().units.filter { it.typeId == "builder" },
                combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L), 3L))).use { session ->
                fun selectBoth() {
                    session.select(Position(120.0, 170.0))
                    session.select(Position(120.0, 210.0), SelectionMode.ADD)
                }
                selectBoth()
                assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 170.0)))
                session.tick()
                val project = session.observation.units.single { it.typeId == "landFactory" }
                assertTrue(project.constructionProgress < 1f)
                selectBoth(); session.stop()
                session.patrol(Position(450.0, 290.0), false)
                val map = MapProjection(600.0, 400.0)
                val keys = mutableSetOf(shift)
                val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                    { true }, { false }, { it in keys })
                val x = map.x(project.position.x).toInt(); val y = map.y(project.position.y).toInt()
                assertTrue(input.touchDown(x, y, 0, Input.Buttons.RIGHT))
                keys.clear()
                assertTrue(input.touchUp(x, y, 0, Input.Buttons.RIGHT))
                assertEquals(2, session.lastCommandBatch!!.accepted)
                for (worker in session.observation.units.filter { it.typeId == "builder" }) {
                    assertEquals(listOf(UnitTaskKind.PATROL, UnitTaskKind.CONSTRUCT), worker.tasks.map { it.kind })
                    assertEquals(project.unitId, worker.tasks.last().targetUnitId)
                }
                assertEquals(1050.0, session.observation.credits)
            }
        }
    }

    @Test fun a_reclaim_map_press_cannot_revive_after_dragging_out_of_the_viewport() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 210.0), SelectionMode.ADD)
            session.patrol(Position(450.0, 290.0), false)
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { key -> key == Input.Keys.SHIFT_LEFT },
                insideViewport = { x, y -> x in 24 until 624 && y in 101..500 })
            val before = session.observation.units.filter { it.typeId == "builder" }.associate { it.unitId to it.tasks }
            val x = map.x(190.0).toInt(); val y = map.y(170.0).toInt()
            assertTrue(input.beginReclaim())
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
            assertTrue(input.touchDragged(700, y, 0))
            input.touchDragged(x, y, 0)
            input.touchUp(x, y, 0, Input.Buttons.LEFT)
            assertEquals(before, session.observation.units.filter { it.typeId == "builder" }.associate { it.unitId to it.tasks })
            assertTrue(input.beginReclaim())
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
            assertTrue(input.touchUp(x, y, 0, Input.Buttons.LEFT))
            assertTrue(session.observation.units.filter { it.typeId == "builder" }.all {
                it.tasks.map { task -> task.kind } == listOf(UnitTaskKind.PATROL, UnitTaskKind.RECLAIM)
            })
        }
    }

    @Test fun a_right_press_cannot_change_its_target_at_release() = canceledRightPress("target")
    @Test fun a_right_press_cannot_survive_selection_away_and_back() = canceledRightPress("selection")
    @Test fun a_right_press_cannot_enter_a_new_session_with_the_same_ids() = canceledRightPress("session")
    @Test fun a_right_press_cannot_revive_after_dragging_out_of_the_viewport() = canceledRightPress("viewport")
    @Test fun entering_a_new_mode_cancels_the_old_right_press() = canceledRightPress("mode")

    private fun canceledRightPress(change: String) {
        val sessions = mutableListOf<SandboxSession>()
        fun selectBoth(session: SandboxSession) {
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 210.0), SelectionMode.ADD)
        }
        fun open(): SandboxSession = SandboxSession(scene()).also { session ->
            sessions += session
            selectBoth(session)
            session.reclaim("factory")
            repeat(100) { session.tick() }
            session.stop()
            assertTrue(session.observation.units.single { it.unitId == "factory" }.health!! in 1.0..<1200.0)
            session.patrol(Position(450.0, 290.0), false)
        }
        var current = open()
        val map = MapProjection(600.0, 400.0)
        val input = SandboxInput({ current }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
            { true }, { false }, { key -> key == Input.Keys.SHIFT_LEFT },
            insideViewport = { x, y -> x in 24 until 624 && y in 101..500 })
        val x = map.x(190.0).toInt(); val y = map.y(170.0).toInt()
        fun tasks() = current.observation.units.filter { it.typeId == "builder" }.associate { it.unitId to it.tasks }
        try {
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.RIGHT))
            when (change) {
                "selection" -> { current.select(Position(190.0, 170.0)); selectBoth(current) }
                "session" -> { current.close(); current = open() }
                "viewport" -> { input.touchDragged(700, y, 0); input.touchDragged(x, y, 0) }
                "mode" -> assertTrue(input.beginPatrol())
            }
            val before = tasks()
            input.touchUp(if (change == "target") map.x(450.0).toInt() else x, y, 0, Input.Buttons.RIGHT)
            assertEquals(before, tasks(), "A stale right press after $change must not send a new command")
            input.cancel()
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.RIGHT))
            assertTrue(input.touchUp(x, y, 0, Input.Buttons.RIGHT))
            assertTrue(tasks().values.all { it.size == 2 && it.last().kind == UnitTaskKind.CONSTRUCT &&
                it.last().targetUnitId == "factory" }, "A fresh owned right click still repairs after $change")
        } finally { sessions.forEach { it.close() } }
    }

    @Test fun right_click_repair_uses_the_shift_state_at_press_for_each_builder() {
        val actions = mutableListOf<Map<String, Any?>>()
        SandboxSession(scene(), ClientEvidence { kind, value -> if (kind == "action") actions += value }).use { session ->
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 210.0), SelectionMode.ADD)
            session.reclaim("factory")
            repeat(100) { session.tick() }
            session.stop()
            assertTrue(session.observation.units.single { it.unitId == "factory" }.health!! in 1.0..<1200.0)
            session.patrol(Position(450.0, 290.0), false)
            val map = MapProjection(600.0, 400.0)
            var shift = true
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { key -> shift && key == Input.Keys.SHIFT_LEFT })
            val x = map.x(190.0).toInt(); val y = map.y(170.0).toInt()
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.RIGHT))
            shift = false
            assertTrue(input.touchUp(x, y, 0, Input.Buttons.RIGHT))
            for (worker in session.observation.units.filter { it.typeId == "builder" }) {
                assertEquals(listOf(UnitTaskKind.PATROL, UnitTaskKind.CONSTRUCT), worker.tasks.map { it.kind })
                assertEquals("factory", worker.tasks.last().targetUnitId)
            }
            assertEquals(listOf(true, true), actions.filter { it["type"] == "RepairBuildingAction" }.map { it["append"] })
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.RIGHT))
            shift = true
            assertTrue(input.touchUp(x, y, 0, Input.Buttons.RIGHT))
            assertTrue(session.observation.units.filter { it.typeId == "builder" }.all {
                it.tasks.single().kind == UnitTaskKind.CONSTRUCT && it.tasks.single().targetUnitId == "factory"
            })
            assertEquals(listOf(true, true, false, false), actions.filter { it["type"] == "RepairBuildingAction" }.map { it["append"] })
        }
    }

    @Test fun reclaim_uses_shift_at_press_for_each_selected_builder_and_plain_press_replaces() {
        val actions = mutableListOf<Map<String, Any?>>()
        SandboxSession(scene(), ClientEvidence { kind, value -> if (kind == "action") actions += value }).use { session ->
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 210.0), SelectionMode.ADD)
            session.patrol(Position(450.0, 290.0), false)
            val map = MapProjection(600.0, 400.0)
            var shift = false
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { key -> shift && key == Input.Keys.SHIFT_LEFT })
            val x = map.x(190.0).toInt(); val y = map.y(170.0).toInt()
            fun click(append: Boolean) {
                assertTrue(input.beginReclaim())
                shift = append
                assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
                shift = !append
                assertTrue(input.touchUp(x, y, 0, Input.Buttons.LEFT))
            }
            click(true)
            assertEquals(setOf("a", "b"), session.selectedIds)
            for (worker in session.observation.units.filter { it.typeId == "builder" }) {
                assertEquals(listOf(UnitTaskKind.PATROL, UnitTaskKind.RECLAIM), worker.tasks.map { it.kind })
                assertEquals("factory", worker.tasks.last().targetUnitId)
            }
            assertEquals(listOf(true, true), actions.filter { it["type"] == "ReclaimBuildingAction" }.map { it["append"] })
            click(false)
            assertTrue(session.observation.units.filter { it.typeId == "builder" }.all {
                it.tasks.single().kind == UnitTaskKind.RECLAIM && it.tasks.single().targetUnitId == "factory"
            })
            assertEquals(listOf(true, true, false, false), actions.filter { it["type"] == "ReclaimBuildingAction" }.map { it["append"] })
            assertEquals(1750.0, session.observation.credits)
        }
    }

    private fun scene(): Scenario = ControlledScenario.load(inputRoot()).copy(
        units = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
        combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L, "factory" to 3L), 4L))

    private fun inputRoot(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/maintenance") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input"))
}
