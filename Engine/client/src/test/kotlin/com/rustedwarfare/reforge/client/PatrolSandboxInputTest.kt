package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class PatrolSandboxInputTest {
    @Test fun activating_patrol_on_an_empty_closed_or_immobile_selection_issues_no_point_and_map_outside_does_not_append() {
        val scene = ControlledScenario.load(root())
        SandboxSession(scene).use { s ->
            val input = SandboxInput({ s }, { MapProjection(600.0,400.0) }, { x,y -> Position(x.toDouble(),y.toDouble()) },
                { true }, { false }, { false })
            assertFalse(input.beginPatrol())
            s.select(Position(310.0,210.0));assertFalse(input.beginPatrol())
            s.select(Position(110.0,210.0));assertTrue(input.beginPatrol())
            assertFalse(input.touchDown(700,170,0,Input.Buttons.LEFT))
            assertFalse(input.touchUp(54,170,0,Input.Buttons.LEFT))
            assertEquals(0,input.patrolPointCount)
            assertTrue(s.observation.units.single { it.unitId=="a" }.tasks.isEmpty())
            input.touchDown(54,170,0,Input.Buttons.LEFT)
            input.touchUp(700,170,0,Input.Buttons.LEFT)
            assertEquals(0,input.patrolPointCount)
            s.close();assertFalse(input.beginPatrol())
        }
    }
    @Test fun first_shift_append_right_cancel_stop_focus_reset_and_stage_consumption_keep_their_distinct_effects() {
        for (shift in listOf(Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT)) {
            SandboxSession(ControlledScenario.load(root())).use { s ->
                s.select(Position(110.0, 210.0))
                val old = Position(180.0, 330.0); s.move(old)
                val map = MapProjection(600.0, 400.0)
                val keys = mutableSetOf<Int>()
                var editing = false
                val input = SandboxInput({ s }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                    { true }, { editing }, { it in keys })
                val mux = InputMultiplexer(object : InputAdapter() {
                    override fun keyDown(code: Int) = editing
                    override fun touchDown(x: Int, y: Int, pointer: Int, button: Int) = x >= 650
                }, input)
                editing = true; assertTrue(mux.keyDown(Input.Keys.P)); assertFalse(input.patrolMode)
                editing = false; assertTrue(input.keyDown(Input.Keys.P))
                assertTrue(mux.touchDown(700, 170, 0, Input.Buttons.LEFT)); assertEquals(0, input.patrolPointCount)
                keys += shift
                input.touchDown(54, 170, 0, Input.Buttons.LEFT)
                keys.clear()
                input.touchUp(54, 170, 0, Input.Buttons.LEFT)
                assertEquals(listOf(UnitTaskKind.MOVE, UnitTaskKind.PATROL), s.observation.units.single { it.unitId == "a" }.tasks.map { it.kind })
                assertEquals(listOf(old, Position(30.0, 330.0)), s.observation.units.single { it.unitId == "a" }.tasks.map { it.target })
                val tasks = s.observation.units.single { it.unitId == "a" }.tasks
                assertTrue(input.touchDown(574, 170, 0, Input.Buttons.RIGHT))
                assertFalse(input.touchUp(574, 170, 0, Input.Buttons.RIGHT))
                assertFalse(input.patrolMode); assertEquals(tasks, s.observation.units.single { it.unitId == "a" }.tasks)
                input.keyUp(Input.Keys.P); assertTrue(input.keyDown(Input.Keys.P))
                input.touchDown(574, 170, 0, Input.Buttons.LEFT)
                input.cancel()
                assertFalse(input.touchUp(574, 170, 0, Input.Buttons.LEFT))
                assertFalse(input.patrolMode); assertEquals(tasks, s.observation.units.single { it.unitId == "a" }.tasks)
                assertTrue(input.keyDown(Input.Keys.P)); assertTrue(input.patrolMode)
                assertEquals(0, input.patrolPointCount)
                assertTrue(input.keyDown(Input.Keys.S)); assertFalse(input.patrolMode)
                assertTrue(s.observation.units.single { it.unitId == "a" }.tasks.isEmpty())
            }
        }
    }
    @Test fun p_edge_and_map_release_issue_real_points_then_escape_preserves_the_patrol() {
        SandboxSession(ControlledScenario.load(root())).use { s ->
            s.select(Position(110.0, 210.0))
            s.move(Position(180.0, 330.0))
            val map = MapProjection(600.0, 400.0)
            val keys = mutableSetOf<Int>()
            val input = SandboxInput({ s }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { it in keys })
            assertTrue(input.keyDown(Input.Keys.P))
            assertTrue(input.keyDown(Input.Keys.P))
            assertEquals(1, s.observation.units.single { it.unitId == "a" }.tasks.size)
            assertTrue(input.touchDown(54, 170, 0, Input.Buttons.LEFT))
            s.select(Position(0.0, 0.0))
            assertTrue(input.touchUp(54, 170, 0, Input.Buttons.LEFT))
            assertEquals(listOf(Position(30.0, 330.0)), s.observation.units.single { it.unitId == "a" }.tasks.map { it.target })
            s.select(Position(110.0, 210.0))
            assertTrue(input.touchDown(574, 170, 0, Input.Buttons.LEFT))
            assertTrue(input.touchUp(574, 170, 0, Input.Buttons.LEFT))
            val tasks = s.observation.units.single { it.unitId == "a" }.tasks
            assertEquals(listOf(Position(30.0, 330.0), Position(550.0, 330.0)), tasks.map { it.target })
            assertTrue(input.keyDown(Input.Keys.ESCAPE))
            assertEquals(tasks, s.observation.units.single { it.unitId == "a" }.tasks)
            repeat(70) { s.tick() }
            assertTrue(s.observation.units.single { it.unitId == "a" }.position != Position(110.0, 210.0))
        }
    }
    private fun root(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-006/20261026-e-selection-contract-01/input")
}
