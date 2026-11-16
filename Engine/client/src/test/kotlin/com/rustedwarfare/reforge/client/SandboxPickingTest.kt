package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.SandboxCommand
import com.rustedwarfare.reforge.core.sim.SandboxCommandStatus
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxPickingTest {
    @Test
    fun spawn_uses_the_host_receipt_then_leaves_pick_mode_or_repeats_explicitly() {
        withPick { session, map, input ->
            val spawn = SandboxPick.Spawn(SandboxCommand.Spawn("c_tank", Position(0.0, 0.0), "p1"))
            val initial = session.observation.units.size
            assertTrue(input.beginSandboxPick(spawn))
            click(input, map, Position(1.0, 1.0))
            assertEquals("invalid-placement", session.lastSandboxReceipt?.code)
            assertEquals(spawn, input.sandboxPick)
            assertTrue(input.sandboxPickFeedback.orEmpty().contains("invalid-placement"))
            click(input, map, Position(190.0, 110.0))
            assertEquals(SandboxCommandStatus.QUEUED, session.lastSandboxReceipt?.status)
            assertEquals(initial, session.observation.units.size)
            assertEquals(spawn, input.sandboxPick)
            session.tick()
            assertNull(input.sandboxPick)
            assertEquals(initial + 1, session.observation.units.size)
            assertEquals(SandboxCommandStatus.APPLIED, session.lastSandboxReceipt?.status)

            val repeated = spawn.copy(repeat = true)
            assertTrue(input.beginSandboxPick(repeated))
            for (point in listOf(Position(250.0, 110.0), Position(290.0, 110.0))) {
                click(input, map, point)
                session.tick()
                assertEquals(repeated, input.sandboxPick)
                assertEquals(SandboxCommandStatus.APPLIED, session.lastSandboxReceipt?.status)
            }
            click(input, map, Position(340.0, 110.0), Input.Buttons.RIGHT)
            assertNull(input.sandboxPick)
            assertEquals(initial + 3, session.observation.units.size)
        }
    }

    @Test
    fun a_unit_that_moves_away_between_press_and_release_is_not_destroyed() {
        withPick { session, map, input ->
            val target = session.observation.units.single { it.unitId == "a" }
            val initial = target.position
            session.select(initial)
            assertIs<ActionResult.Accepted>(session.move(Position(50.0, 330.0)))
            assertTrue(input.beginSandboxPick(SandboxPick.Destroy))
            val (x, y) = point(map, initial)
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
            repeat(100) { session.tick() }
            assertNotNull(session.observation.units.single { it.unitId == target.unitId }.takeIf {
                it.alive && it.position != initial
            })
            assertTrue(input.touchUp(x, y, 0, Input.Buttons.LEFT))
            assertNull(session.lastSandboxReceipt)
            assertEquals(SandboxPick.Destroy, input.sandboxPick)
        }
    }

    @Test
    fun point_templates_use_the_shared_syntax_and_ordinary_sessions_cannot_arm_them() {
        val setup = assertNotNull(SandboxPick.parseSpawn("c_tank p2 heading=90 state=damaged", repeat = true))
        assertEquals("p2", setup.setup.ownerPlayerId)
        assertTrue(setup.repeat)
        assertEquals(setup, SandboxPick.parseSpawn("/c_tank p2 heading=90 state=damaged", repeat = true))
        assertEquals(SandboxPick.Health(2.5), SandboxPick.parseHealth("+2.5"))
        assertEquals(SandboxPick.Health(-2.5), SandboxPick.parseHealth("/-2.5"))
        assertNull(SandboxPick.parseHealth("//-2.5"))
        assertNull(SandboxPick.parseHealth("2.5"))
        assertNull(SandboxPick.parseSpawn("c_tank", repeat = false))
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("production-combat/production")
        SandboxSession(ControlledScenario.load(root)).use { session ->
            val input = SandboxInput({ session }, { MapProjection(600.0, 400.0) },
                { x, y -> Position(x.toDouble(), y.toDouble()) }, { true }, { false }, { false })
            assertFalse(input.beginSandboxPick(setup))
            assertNull(input.sandboxPick)
            assertNull(session.lastSandboxReceipt)
        }
    }

    @Test
    fun destroy_highlights_the_pressed_target_and_a_drag_or_hud_crossing_does_not_submit() {
        withPick(battlefield = { x, _ -> x < 480 }) { session, map, input ->
            val target = session.observation.units.single { it.unitId == "a" }
            val coords = point(map, target.position)
            var stageOwns = false
            val ui = object : InputAdapter() {
                override fun touchDown(x: Int, y: Int, pointer: Int, button: Int): Boolean {
                    stageOwns = x >= 480
                    return stageOwns
                }
                override fun touchDragged(x: Int, y: Int, pointer: Int) = stageOwns
                override fun touchUp(x: Int, y: Int, pointer: Int, button: Int): Boolean {
                    val consumed = stageOwns
                    stageOwns = false
                    return consumed
                }
            }
            val mux = InputMultiplexer(ui, input)
            assertTrue(input.beginSandboxPick(SandboxPick.Destroy))
            mux.mouseMoved(coords.first, coords.second)
            assertEquals(target.unitId, input.sandboxPickPreview?.targetId)
            mux.touchDown(485, coords.second, 0, Input.Buttons.LEFT)
            mux.touchDragged(coords.first, coords.second, 0)
            mux.touchUp(coords.first, coords.second, 0, Input.Buttons.LEFT)
            mux.touchDown(coords.first, coords.second, 0, Input.Buttons.LEFT)
            mux.touchDragged(coords.first + 18, coords.second, 0)
            mux.touchDragged(coords.first, coords.second, 0)
            mux.touchUp(coords.first, coords.second, 0, Input.Buttons.LEFT)
            mux.touchDown(coords.first, coords.second, 0, Input.Buttons.LEFT)
            mux.touchUp(485, coords.second, 0, Input.Buttons.LEFT)
            assertNull(session.lastSandboxReceipt)
            assertTrue(session.observation.units.single { it.unitId == target.unitId }.alive)
            click(input, map, target.position)
            assertEquals(SandboxCommandStatus.QUEUED, session.lastSandboxReceipt?.status)
            session.tick()
            assertFalse(session.observation.units.single { it.unitId == target.unitId }.alive)
            assertEquals(target.unitId, session.lastSandboxReceipt?.unitChange?.unitId)
            assertNull(input.sandboxPick)
        }
    }

    @Test
    fun health_uses_the_same_text_command_receipt_and_escape_cancels_the_mode() {
        withPick { session, map, input ->
            val target = session.observation.units.single { it.unitId == "a" }
            val command = "/health ${target.unitId} -2.5"
            assertTrue(input.beginSandboxPick(SandboxPick.Health(-2.5)))
            click(input, map, target.position)
            assertEquals(SandboxCommand.Health(target.unitId, -2.5), session.lastSandboxReceipt?.command)
            session.tick()
            val picked = assertNotNull(session.lastSandboxReceipt?.unitChange)
            assertEquals(requireNotNull(target.health) - 2.5, picked.healthAfter)
            assertNull(input.sandboxPick)
            assertTrue(input.beginSandboxPick(SandboxPick.Destroy))
            assertTrue(input.keyDown(Input.Keys.ESCAPE))
            assertNull(input.sandboxPick)
            session.submitSandboxCommand(command)
            session.tick()
            assertEquals(picked.healthAfter?.minus(2.5), session.lastSandboxReceipt?.unitChange?.healthAfter)
            assertTrue(session.observation.units.single { it.unitId == target.unitId }.alive)
        }
    }

    private fun withPick(battlefield: (Int, Int) -> Boolean = { _, _ -> true },
                         test: (SandboxSession, MapProjection, SandboxInput) -> Unit) {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("production-combat/production")
        SandboxSession(ControlledScenario.load(root), options = SandboxOptions(deploymentEnabled = true)).use { session ->
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false }, battlefieldAt = battlefield)
            test(session, map, input)
        }
    }

    private fun point(map: MapProjection, world: Position) = map.x(world.x).roundToInt() to map.y(world.y).roundToInt()

    private fun click(input: SandboxInput, map: MapProjection, world: Position, button: Int = Input.Buttons.LEFT) {
        val (x, y) = point(map, world)
        assertTrue(input.touchDown(x, y, 0, button))
        if (button == Input.Buttons.LEFT) assertTrue(input.touchUp(x, y, 0, button))
    }
}
