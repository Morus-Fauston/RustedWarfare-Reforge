package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class NautilusNativePanelTest {
    @Test fun surface_cards_have_distinct_bounds_for_production_scouts_ammo_conversion_and_unload() {
        val cards = listOf(NativeActionPanel.navalProduction, NativeActionPanel.navalUnload) +
            listOf("launchScoutBot", "buildAntiNuke", "dive").map { NativeActionPanel.customBounds(it, naval = true) }
        for ((index, a) in cards.withIndex()) for (b in cards.drop(index + 1)) {
            assertFalse(a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height)
        }
        assertEquals(NativeActionPanel.customActions.getValue("buildAntiNuke"),
            NativeActionPanel.customBounds("buildAntiNuke", naval = false))
    }

    @Test fun naval_interceptor_card_uses_the_current_paid_price_and_displays_the_no_nukes_lock() {
        val base = NautilusSandboxScene.scenario()
        val point = Position(500.0, 300.0)
        val scene = base.copy(units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarineSurface", point, 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 6000L, "p2" to 0L)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L))
        val name = "buildAntiNuke"
        SandboxSession(scene).use { session ->
            session.select(point)
            fun view() = session.actionMenu.unitActions.single { it.name == name }
            assertTrue(view().available)
            assertTrue(view().tooltip.contains("$6000"))
            assertTrue(view().tooltip.contains("33.3s"))
            assertTrue(view().tooltip.contains("Ammo 0"))
            val actor = Actor().apply {
                setBounds(0f, 0f, 176f, 44f)
                addListener(CustomUnitActionButtonInput({ session }, { true }, name))
            }
            press(actor, true); press(actor, false)
            assertEquals(0L, session.observation.balance)
            assertTrue(view().cancellable)
            press(actor, true, Input.Buttons.RIGHT); press(actor, false, Input.Buttons.RIGHT)
            assertEquals(6000L, session.observation.balance)
            assertFalse(view().queued)
        }
        SandboxSession(scene, options = SandboxOptions(nukesEnabled = false)).use { session ->
            session.select(point)
            val locked = session.actionMenu.unitActions.single { it.name == name }
            assertFalse(locked.available)
            assertTrue(locked.tooltip.contains("Nukes disabled"))
        }
    }

    @Test fun native_scout_leg_regions_follow_the_world_joint_span_in_both_forms() {
        val base = NautilusSandboxScene.scenario()
        for ((type, point) in listOf("robotCrab" to Position(270.0, 300.0), "robotCrabWater" to Position(500.0, 300.0))) {
            val scene = base.copy(units = listOf(UnitSpawn("scout", "p1", type, point, 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("scout" to 8L), 9L))
            SandboxSession(scene).use { session ->
                for (scale in listOf(1.0, 1.25)) {
                    val projection = MapProjection(800.0, 600.0, MapArea(0.0, 0.0, 800.0 * scale, 600.0 * scale))
                    val legs = MechPresentation.project(session.observation, projection, session.renderResources).single().legs
                    assertEquals(6, legs.size)
                    for (leg in legs) {
                        val crop = assertNotNull(leg.shaft, "The native vertical scout sprite requires a shaft crop")
                        assertEquals(102, crop.sourceImageHeight)
                        assertTrue(crop.sourceHeight in 2..56)
                        assertEquals(102, crop.sourceHeight + 2 * crop.sourceY)
                        assertEquals(crop.sourceHeight.toFloat() * scale.toFloat(), crop.height)
                    }
                }
            }
        }
    }

    @Test fun scout_card_enters_ground_targeting_and_can_cancel_the_paid_order() {
        val base = NautilusSandboxScene.scenario()
        val scene = base.copy(units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarineSurface", Position(500.0, 300.0), 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 250L, "p2" to 0L)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L))
        SandboxSession(scene).use { session ->
            session.select(Position(500.0, 300.0))
            val name = "launchScoutBot"
            val view = session.actionMenu.unitActions.single { it.name == name }
            assertTrue(view.available)
            assertEquals("robotCrab", view.iconTypeId)
            var targeting: String? = null
            val bounds = NativeActionPanel.customActions.getValue(name)
            val actor = Actor().apply {
                setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
                addListener(CustomUnitActionButtonInput({ session }, { true }, name,
                    beginTargeting = { targeting = it; true }))
            }
            press(actor, true)
            assertNull(targeting)
            press(actor, false)
            assertEquals(name, targeting)
            assertEquals(250L, session.observation.balance)
            val point = Position(520.0, 300.0)
            val preview = assertNotNull(session.previewGroundAction(name, point))
            assertTrue(preview.valid)
            assertEquals(0.0, preview.minimumRange)
            assertEquals(500.0, preview.maximumRange)
            assertFalse(assertNotNull(session.previewGroundAction(name, Position(-100.0, 300.0))).valid)
            assertIs<ActionResult.Accepted>(session.unitAction(name, target = point))
            assertEquals(0L, session.observation.balance)
            assertTrue(session.actionMenu.unitActions.single { it.name == name }.cancellable)
            press(actor, true, Input.Buttons.RIGHT)
            press(actor, false, Input.Buttons.RIGHT)
            assertEquals(250L, session.observation.balance)
            assertFalse(session.actionMenu.unitActions.single { it.name == name }.queued)
        }
    }

    @Test fun scout_preview_tracks_a_live_building_until_it_is_reclaimed() {
        val base = NautilusSandboxScene.scenario()
        val mother = Position(205.0, 475.0)
        val builder = Position(130.0, 220.0)
        val scout = Position(300.0, 310.0)
        val site = Position(210.0, 310.0)
        val scene = base.copy(units = listOf(
            UnitSpawn("mother", "p1", "nautilusSubmarineLand", mother, 90.0),
            UnitSpawn("builder", "p1", "builder", builder, 90.0),
            UnitSpawn("scout", "p1", "robotCrab", scout, 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "builder" to 9L, "scout" to 10L), 11L))
        SandboxSession(scene).use { session ->
            val action = "launchScoutBot"
            fun preview() = assertNotNull(session.previewGroundAction(action, site)).valid
            session.select(mother)
            assertTrue(preview())
            session.select(builder)
            assertIs<ActionResult.Accepted>(session.build("experimentalLandFactory", site))
            fun factory() = session.observation.units.singleOrNull { it.alive && it.typeId == "experimentalLandFactory" }
            repeat(600) { if (factory() == null) session.tick() }
            val placed = assertNotNull(factory())
            assertEquals(site, placed.position)
            session.stop()
            session.select(mother)
            assertEquals("action-target-impassable",
                assertIs<ActionResult.Rejected>(session.unitAction(action, target = site)).error.code)
            assertFalse(preview(), "A visible live building must block the ground target preview")

            session.select(scout)
            assertIs<ActionResult.Accepted>(session.reclaim(placed.unitId))
            repeat(1000) { if (factory() != null) session.tick() }
            assertNull(factory())
            session.select(mother)
            assertTrue(preview())
            assertIs<ActionResult.Accepted>(session.unitAction(action, target = site))
        }
    }

    @Test fun scout_preview_does_not_reveal_an_unobserved_enemy_building() {
        val base = NautilusSandboxScene.scenario()
        val mother = Position(210.0, 550.0)
        val site = Position(210.0, 50.0)
        val scene = base.copy(units = listOf(
            UnitSpawn("mother", "p1", "nautilusSubmarineLand", mother, 90.0),
            UnitSpawn("enemy-building", "p2", "commandCenter", site, 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "enemy-building" to 9L), 10L))
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.NO_FOG)) {
            SandboxSession(scene, options = SandboxOptions(mode)).use { session ->
                session.select(mother)
                val buildingVisible = session.observation.units.any { it.unitId == "enemy-building" }
                assertEquals(mode == SandboxMode.NO_FOG, buildingVisible)
                assertEquals(!buildingVisible, assertNotNull(session.previewGroundAction("launchScoutBot", site)).valid)
            }
        }
    }

    @Test fun right_release_cancels_a_real_dive_queue_and_stale_selection_cannot_cancel_it() {
        val base = NautilusSandboxScene.scenario()
        val scene = base.copy(units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarineSurface", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L))
        SandboxSession(scene).use { session ->
            session.select(Position(500.0, 300.0))
            val bounds = NativeActionPanel.customActions.getValue("dive")
            val actor = Actor().apply {
                setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
                addListener(CustomUnitActionButtonInput({ session }, { true }, "dive"))
            }
            press(actor, true); press(actor, false)
            session.tick()
            assertTrue(session.actionMenu.unitActions.single { it.name == "dive" }.queued)
            press(actor, false, Input.Buttons.RIGHT)
            assertTrue(session.actionMenu.unitActions.single { it.name == "dive" }.queued)
            press(actor, true, Input.Buttons.RIGHT)
            session.select(Position(0.0, 0.0)); session.select(Position(500.0, 300.0))
            press(actor, false, Input.Buttons.RIGHT)
            assertTrue(session.actionMenu.unitActions.single { it.name == "dive" }.queued)
            press(actor, true, Input.Buttons.RIGHT)
            assertTrue(session.actionMenu.unitActions.single { it.name == "dive" }.queued)
            press(actor, false, Input.Buttons.RIGHT)
            assertFalse(session.actionMenu.unitActions.single { it.name == "dive" }.queued)
            repeat(135) { session.tick() }
            assertEquals("nautilusSubmarineSurface", session.observation.units.single().typeId)
        }
    }

    @Test fun native_surface_and_dive_cards_dispatch_on_owned_release_and_leave_room_for_unload() {
        val base = NautilusSandboxScene.scenario()
        val scene = base.copy(units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarine", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L))
        SandboxSession(scene).use { session ->
            session.select(Position(500.0, 300.0))
            fun card(name: String): Actor {
                val bounds = NativeActionPanel.customActions.getValue(name)
                val unload = NativeActionPanel.transportWithCustomAction
                assertFalse(bounds.x < unload.x + unload.width && bounds.x + bounds.width > unload.x &&
                    bounds.y < unload.y + unload.height && bounds.y + bounds.height > unload.y)
                return Actor().apply {
                    setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
                    addListener(CustomUnitActionButtonInput({ session }, { true }, name))
                }
            }
            val surface = card("surface")
            press(surface, false)
            assertFalse(session.actionMenu.unitActions.single { it.name == "surface" }.queued)
            press(surface, true)
            assertFalse(session.actionMenu.unitActions.single { it.name == "surface" }.queued)
            press(surface, false)
            assertTrue(session.actionMenu.unitActions.single { it.name == "surface" }.queued)
            session.tick()
            assertEquals("nautilusSubmarineSurface", session.observation.units.single().typeId)
            val dive = card("dive")
            press(dive, true); press(dive, false)
            assertTrue(session.actionMenu.unitActions.single { it.name == "dive" }.queued)
            repeat(135) { session.tick() }
            assertEquals("nautilusSubmarine", session.observation.units.single().typeId)
        }
    }

    private fun press(actor: Actor, down: Boolean, button: Int = Input.Buttons.LEFT) = actor.fire(InputEvent().apply {
        type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
        this.button = button; pointer = 0; stageX = actor.x + 10f; stageY = actor.y + 10f; touchFocus = false
    })
}
