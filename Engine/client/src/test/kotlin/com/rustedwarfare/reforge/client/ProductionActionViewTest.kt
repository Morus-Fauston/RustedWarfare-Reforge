package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import kotlin.test.*

class ProductionActionViewTest {
    @Test fun scene2d_production_press_consumes_left_and_right_and_cancels_a_drag_outside() {
        val session = SandboxSession(scene(1000L))
        try {
            session.select(Position(310.0, 210.0))
            val actor = Actor().apply { setBounds(0f, 0f, 84f, 64f) }
            val listener = ProductionButtonInput({ session }, { true })
            actor.addListener(listener)
            fun fire(type: InputEvent.Type, button: Int, x: Float = 10f): Boolean {
                // This headless Actor test owns dispatch; the real Stage touch-focus chain is verified in PC runs.
                val event = InputEvent().apply { this.type = type; this.button = button; pointer = 0; stageX = x; stageY = 10f; touchFocus = false }
                actor.fire(event)
                return event.isHandled
            }
            assertTrue(fire(InputEvent.Type.touchDown, Input.Buttons.LEFT))
            fire(InputEvent.Type.touchUp, Input.Buttons.LEFT)
            assertEquals(1, session.productionAction!!.count)
            assertTrue(fire(InputEvent.Type.touchDown, Input.Buttons.RIGHT))
            fire(InputEvent.Type.touchUp, Input.Buttons.RIGHT)
            assertEquals(0, session.productionAction!!.count)
            assertEquals(1000.0, session.observation.credits)
            assertTrue(fire(InputEvent.Type.touchDown, Input.Buttons.LEFT))
            fire(InputEvent.Type.touchUp, Input.Buttons.LEFT, 100f)
            assertEquals(0, session.productionAction!!.count)
            assertTrue(fire(InputEvent.Type.touchDown, Input.Buttons.RIGHT))
            listener.cancel()
            fire(InputEvent.Type.touchUp, Input.Buttons.RIGHT)
            assertEquals(0, session.productionAction!!.count)
        } finally { session.close() }
    }

    private fun scene(initial: Long = 496L): Scenario {
        val repo = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent
        val base = ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/production") } ?: repo.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input")))
        val builder = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadBuilder((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/start/units/builder.properties") } ?: repo.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-001/20261027-intake-01/builder-input-01/units/builder.properties")))).value
        val f = base.unitDefinitions.getValue("landFactory")
        val factory = UnitDefinition(f.id, f.className, f.radius, f.displayRadius, f.totalFrames, f.imagePath,
            f.imageScale, f.movement, emptySet(), durability = f.durability, building = f.building,
            production = ProductionProfile("builder"), income = IncomeProfile(10f, 4.5f))
        return base.copy(unitDefinitions = mapOf(factory.id to factory, builder.id to builder),
            units = listOf(UnitSpawn("f", "p1", factory.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("g", "p2", factory.id, Position(510.0, 330.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "g" to 11L), 12L),
            economyContract = EconomyContract(mapOf("p1" to initial, "p2" to 0L)))
    }

    @Test fun selected_action_tracks_real_funds_queue_and_cancel_without_exposing_enemy_actions() {
        val session = SandboxSession(scene())
        try {
            assertNull(session.productionAction)
            session.select(Position(310.0, 210.0), SelectionMode.REPLACE)
            val before = assertNotNull(session.productionAction)
            assertEquals("Builder", before.title)
            assertEquals(500L, before.price)
            assertEquals(170.0, before.maximumHealth)
            assertEquals(.8, before.moveSpeed)
            assertFalse(before.affordable)
            repeat(10) { session.tick() }
            assertTrue(assertNotNull(session.productionAction).affordable)
            assertEquals(500.5, session.observation.credits)
            assertIs<ActionResult.Accepted>(session.produce())
            assertEquals(.5, session.observation.credits)
            val paid = assertNotNull(session.productionAction)
            assertEquals(1, paid.count)
            assertEquals(0f, paid.progress)
            assertFalse(paid.affordable)
            assertIs<ActionResult.Accepted>(session.cancelProduction())
            assertEquals(500.5, session.observation.credits)
            assertEquals(0, assertNotNull(session.productionAction).count)
            session.select(Position(510.0, 330.0), SelectionMode.REPLACE)
            assertNull(session.productionAction)
        } finally { session.close() }
        assertNull(session.productionAction)
    }
}
