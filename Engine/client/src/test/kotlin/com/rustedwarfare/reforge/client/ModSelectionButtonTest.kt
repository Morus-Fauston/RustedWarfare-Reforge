package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonObject
import org.hjson.JsonValue
import org.hjson.Stringify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModSelectionButtonTest {
    @Test fun the_loaded_pack_displays_one_eligible_action_and_releases_it_to_the_same_single_unit() {
        val loaded = AaBeamSandboxScene.load()
        val choice = checkNotNull(loaded.contentPack)
        assertEquals("Boost flight", choice.singleSelectionButtons.getValue("aaBeamGunship").label)
        val scene = loaded.scenario.copy(units = listOf(
            UnitSpawn("one", "p1", "aaBeamGunship", Position(130.0, 160.0), 90.0),
            UnitSpawn("two", "p1", "aaBeamGunship", Position(250.0, 160.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(390.0, 160.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("one" to 8L, "two" to 9L, "enemy" to 10L), 11L))
        SandboxSession(scene, singleSelectionButtons = choice.singleSelectionButtons).use { session ->
            fun button() = session.actionMenu.unitActions.singleOrNull { it.title == "Boost flight" }
            fun select(id: String, mode: SelectionMode = SelectionMode.REPLACE) =
                session.select(session.observation.units.single { it.unitId == id }.displayedBodyPosition(), mode)
            val input = CustomUnitActionButtonInput({ session }, { true }, "afterBurn")
            val actor = Actor().apply { setBounds(752f, 188f, 84f, 40f); addListener(input) }
            fun event(typeValue: InputEvent.Type) = actor.fire(InputEvent().apply {
                type = typeValue
                button = Input.Buttons.LEFT
                pointer = 0
                stageX = 762f; stageY = 198f
                touchFocus = false
            })

            assertNull(button())
            select("one")
            assertEquals("afterBurn", button()?.name)
            event(InputEvent.Type.touchDown)
            select("two", SelectionMode.ADD)
            assertEquals(setOf("one", "two"), session.selectedIds)
            assertNull(button())
            assertTrue(session.actionMenu.unitActions.none { it.name == "afterBurn" })
            event(InputEvent.Type.touchUp)
            assertTrue(session.observation.units.filter { it.ownerPlayerId == "p1" }
                .all { it.unitActions?.queue.orEmpty().isEmpty() })
            select("enemy")
            assertEquals("enemy", session.selectedId)
            assertNull(button())
            event(InputEvent.Type.touchDown)
            assertFalse(input.pressed)
            select("two")
            event(InputEvent.Type.touchDown); event(InputEvent.Type.touchUp)
            session.tick()
            assertEquals("aaBeamGunship_afterburn", session.observation.units.single { it.unitId == "two" }.typeId)
            assertEquals("aaBeamGunship", session.observation.units.single { it.unitId == "one" }.typeId)
            assertNull(button())
        }
    }

    @Test fun an_hjson_button_preserves_the_real_cancellation_and_refuses_a_ground_target_action() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val source = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(fixtures.resolve("authored-roles/package"))).value
        val root = Files.createTempDirectory("reforge-single-button-hjson-").toRealPath()
        try {
            for (asset in source.sources) {
                if (asset.path == source.manifest) continue
                val target = root.resolve(source.root.relativize(asset.path))
                Files.createDirectories(target.parent)
                Files.copy(asset.path, target)
            }
            val manifest = root.resolve("pack.hjson")
            val metadata = JsonValue.readJSON(Files.readString(source.manifest)).asObject()
            fun configure(action: String) {
                metadata.set("singleSelectionButton", JsonObject().add("unitType", "support_hover")
                    .add("action", action).add("label", "Deploy support"))
                Files.writeString(manifest, "# Authored local command\n" + metadata.toString(Stringify.HJSON))
            }
            configure("deploy")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            val choice = ContentPackChoice(pack, "support_hover")
            assertEquals("deploy", choice.singleSelectionButtons.getValue("support_hover").action)
            val base = ControlledScenario.load(fixtures.resolve("platform-basics/maintenance"))
            val home = Position(210.0, 230.0)
            val scene = base.copy(unitDefinitions = pack.units,
                units = listOf(UnitSpawn("support", "p1", "support_hover", home, 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("support" to 8L), 9L),
                maintenanceContract = null,
                economyContract = EconomyContract(base.players.associate { it.playerId to 4000L }))
            SandboxSession(scene, singleSelectionButtons = choice.singleSelectionButtons).use { session ->
                session.select(home)
                fun card() = session.actionMenu.battleActions().single { it.id == "unit-action:deploy" }
                assertEquals("Deploy support", card().title)
                assertTrue(card().enabled)
                assertIs<ActionResult.Accepted>(session.unitAction("deploy"))
                assertFalse(card().enabled)
                assertTrue(card().cancelEnabled)
                assertIs<ActionResult.Accepted>(session.unitAction("deploy", cancelQueued = true))
                assertEquals("Deploy support", card().title)
                assertTrue(card().enabled)
            }
            configure("blink")
            val rejected = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, rejected.code)
            assertEquals("singleSelectionButton.action", rejected.field)
        } finally {
            Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
