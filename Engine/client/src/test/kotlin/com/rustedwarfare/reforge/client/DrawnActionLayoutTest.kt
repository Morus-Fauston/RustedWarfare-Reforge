package com.rustedwarfare.reforge.client

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import kotlin.test.*

class DrawnActionLayoutTest {
    private val frame = ActionLayoutFrame(1, 2, listOf("u"), mapOf("u" to "owned"), 1,
        mapOf("width" to 960, "height" to 640, "screenX" to 0, "screenY" to 0,
            "screenWidth" to 960, "screenHeight" to 640))

    @Test fun a_visible_list_row_uses_its_real_actor_local_rectangle() {
        val root = Group()
        val list = Actor().apply { setBounds(60f, 200f, 332f, 120f) }
        root.addActor(list)
        val result = checkNotNull(DrawnActionLayout().afterDraw(root, frame, listOf(
            DrawnAction("content-unit:builder", list, "builder", localBounds = Rectangle(0f, 60f, 332f, 20f)),
            DrawnAction("outside", list, "Invisible row", localBounds = Rectangle(0f, -20f, 332f, 20f))), 1000))
        val action = (result.getValue("actions") as List<*>).single() as Map<*, *>
        assertEquals("content-unit:builder", action["actionId"])
        assertEquals(mapOf("x" to 60f, "y" to 260f, "width" to 332f, "height" to 20f), action["bounds"])
        assertEquals(true, action["enabled"])
    }

    @Test fun hidden_or_detached_actors_do_not_become_actions_and_hit_obstruction_disables_input() {
        val root = Group()
        val visible = Actor().apply { setBounds(0f, 0f, 20f, 20f) }
        val hiddenParent = Group().apply { isVisible = false }
        val hidden = Actor().apply { setBounds(40f, 0f, 20f, 20f) }
        val detached = Actor().apply { setBounds(70f, 0f, 20f, 20f) }
        root.addActor(visible); root.addActor(hiddenParent); hiddenParent.addActor(hidden)
        root.addActor(Actor().apply { setBounds(0f, 0f, 20f, 20f) })
        val result = checkNotNull(DrawnActionLayout().afterDraw(root, frame, listOf(
            DrawnAction("visible", visible, "Actual"), DrawnAction("hidden", hidden, "Old hidden text"),
            DrawnAction("detached", detached, "Old stage text")), 1000))
        val actions = result.getValue("actions") as List<*>
        assertEquals(1, actions.size)
        assertEquals("visible", (actions.single() as Map<*, *>)["actionId"])
        assertEquals(false, (actions.single() as Map<*, *>)["enabled"])
    }

    @Test fun only_binding_changes_or_a_new_presentation_publish_another_layout() {
        val root = Group()
        val actor = Actor().apply { setBounds(10f, 20f, 30f, 40f) }
        root.addActor(actor)
        val actions = listOf(DrawnAction("stop", actor, "Stop (S)"))
        val publisher = DrawnActionLayout()
        val first = checkNotNull(publisher.afterDraw(root, frame, actions, 1000))
        assertNull(publisher.afterDraw(root, frame, actions, 1001), "Wall time alone must not flood the diagnostic")
        actor.x = 20f
        val moved = checkNotNull(publisher.afterDraw(root, frame, actions, 1002))
        assertTrue((moved["layoutRevision"] as Long) > (first["layoutRevision"] as Long))
        val viewport = frame.viewport.toMutableMap()
        val selected = frame.copy(selectionRevision = 3, viewport = viewport)
        assertNotNull(publisher.afterDraw(root, selected, actions, 1003))
        viewport["width"] = 1200
        val resized = checkNotNull(publisher.afterDraw(root, selected, actions, 1004))
        publisher.invalidate()
        val restored = checkNotNull(publisher.afterDraw(root, selected, actions, 1005))
        assertTrue((restored["layoutRevision"] as Long) > (resized["layoutRevision"] as Long))
    }

    @Test fun drawn_actions_use_actual_actor_bounds_and_actual_display_text() {
        val root = Group()
        val parent = Group().apply { setPosition(5f, 7f) }
        val actor = Actor().apply { setBounds(10f, 20f, 30f, 40f) }
        root.addActor(parent); parent.addActor(actor)
        val result = checkNotNull(DrawnActionLayout().afterDraw(root, frame,
            listOf(DrawnAction("produce:owned", actor, "Actual displayed title")), 1000))
        val action = (result.getValue("actions") as List<*>).single() as Map<*, *>
        assertEquals("produce:owned", action["actionId"])
        assertEquals("Actual displayed title", action["text"])
        assertEquals(true, action["enabled"])
        assertEquals("left", action["button"])
        assertEquals(mapOf("x" to 15f, "y" to 27f, "width" to 30f, "height" to 40f), action["bounds"])
        assertEquals(1L, result["generation"])
        assertEquals(2L, result["selectionRevision"])
        assertEquals(1000L, result["drawnNanos"])
    }
}
