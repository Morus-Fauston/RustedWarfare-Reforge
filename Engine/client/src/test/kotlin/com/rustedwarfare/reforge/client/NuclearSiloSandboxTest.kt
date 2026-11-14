package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.*

internal object NuclearSandboxScene {
    fun scenario(): Scenario {
        val base = RepairBaySandboxScene.load().scenario
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val content = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("nuclear-silo/ini/production-package"))).value
        return base.copy(unitDefinitions = content.units, units = listOf(
            UnitSpawn("builder", "p1", "builder", Position(100.0, 180.0), 90.0),
            UnitSpawn("silo", "p1", "nukeLauncherC", Position(220.0, 180.0), 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 100000L, "p2" to 0L)),
            maintenanceContract = null,
            combatContract = CombatContract(12345, -1, mapOf("builder" to 1L, "silo" to 2L), 3L))
    }
}

class NuclearSiloSandboxTest {
    @Test fun normal_silo_content_composes_with_the_verified_five_slot_image_layout() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("nuclear-silo/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("normal-package"))).value
            val choice = ContentPackChoice(pack, "commandCenter")
            val resources = SandboxRenderResources.from(contentPackScenario(root, choice))
            val prepared = contentPackVisuals(PreparedVisualAssets(VisualAssetSelection(), "owned-nuclear-layout", "fixed",
                emptyMap(), resources.tileSets.associate { it.supportedLayout() to it.imagePath }), choice)
            val base = ResourceImagePreparation.baseLayer(prepared, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
            // Fixed DEC009/v7 layouts; no original bytes enter this public test.
            val layouts = mapOf("body" to (84 to 70), "back" to (84 to 70), "dead" to (84 to 70),
                "arm1" to (38 to 29), "arm2" to (42 to 29))
            val replacements = layouts.map { (slot, size) ->
                val id = ResourceId.parse("reforge:units/nukelauncherc/$slot")
                id to base.images.getValue(id).copy(declaration = ResourceImageDeclaration("$slot.png", size.first, size.second, 1),
                    pixelWidth = size.first, pixelHeight = size.second)
            }.toMap()
            val metadata = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"owned:nuclear-layout","name":"Owned layout","description":"Self-owned layout data","icon":"reforge:units/nukelauncherc/body"}""".toByteArray(), "pack.json")
            val composed = ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata, replacements)))
            for (id in replacements.keys) assertEquals("owned:nuclear-layout", composed.providers.getValue(id))
        }
    }

    @Test fun multiple_silos_launch_only_one_member_and_allow_repeated_launches_from_the_same_member() {
        val base = NuclearSandboxScene.scenario()
        val scene = base.copy(units = base.units + UnitSpawn("silo-z", "p1", "nukeLauncherC", Position(320.0,180.0),90.0),
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("builder" to 1L, "silo" to 2L, "silo-z" to 3L), nextObjectId = 4L))
        SandboxSession(scene).use { s ->
            for (point in listOf(Position(220.0,180.0),Position(320.0,180.0))) {
                s.select(point)
                repeat(2) { assertIs<ActionResult.Accepted>(s.unitAction("buildNuke")) }
                repeat(6668) { s.tick() }
            }
            s.selectBox(Position(180.0,140.0),Position(360.0,220.0))
            assertEquals(2,s.selectedIds.size)
            assertTrue(s.actionMenu.unitActions.single { it.name == "launchNuke" }.available)
            repeat(2) { assertIs<ActionResult.Accepted>(s.unitAction("launchNuke",target=Position(590.0,180.0))) }
            val silos = s.observation.units.filter { it.typeId == "nukeLauncherC" }.sortedBy { it.unitId }
            assertEquals(listOf(0,2),silos.map { it.unitActions!!.stockpile })
            assertEquals(listOf(2,0),silos.map { it.unitActions!!.queue.size })
            fun rightClickLaunch() {
                val actor = com.badlogic.gdx.scenes.scene2d.Actor().also { it.setSize(84f, 52f) }
                val listener = CustomUnitActionButtonInput({ s }, { true }, "launchNuke")
                val event = com.badlogic.gdx.scenes.scene2d.InputEvent().also { it.listenerActor = actor }
                assertTrue(listener.touchDown(event, 10f, 10f, 0, com.badlogic.gdx.Input.Buttons.RIGHT))
                listener.touchUp(event, 10f, 10f, 0, com.badlogic.gdx.Input.Buttons.RIGHT)
            }
            fun stocks() = s.observation.units.filter { it.typeId == "nukeLauncherC" }.sortedBy { it.unitId }
                .map { it.unitActions!!.stockpile }
            fun queued() = s.observation.units.filter { it.typeId == "nukeLauncherC" }.sortedBy { it.unitId }
                .map { it.unitActions!!.queue.size }
            // The launch-eligible member has no queue; cancellation must select the other member.
            rightClickLaunch()
            assertEquals(listOf(1,2), stocks())
            assertEquals(listOf(1,0), queued())
            rightClickLaunch()
            assertEquals(listOf(2,2), stocks())
            assertEquals(listOf(0,0), queued())
            assertFalse(s.actionMenu.unitActions.single { it.name == "launchNuke" }.cancellable)
            rightClickLaunch()
            assertEquals(listOf(2,2), stocks())
            assertEquals(listOf(0,0), queued())
            // Mixed selection retains the same single eligible nuclear member behavior.
            s.select(Position(100.0,180.0), SelectionMode.ADD)
            assertEquals(3, s.selectedIds.size)
            assertIs<ActionResult.Accepted>(s.unitAction("launchNuke", target=Position(590.0,180.0)))
            rightClickLaunch()
            assertEquals(listOf(2,2), stocks())
            assertEquals(listOf(0,0), queued())
        }
    }

    @Test fun silo_doors_are_real_bound_images_and_remain_in_the_visual_source_closure() {
        val scene = NuclearSandboxScene.scenario()
        val definition = scene.unitDefinitions.getValue("nukeLauncherC")
        val resources = SandboxRenderResources.from(scene)
        val appearance = resources.units.getValue("nukeLauncherC")
        assertEquals(definition.nuclearSilo!!.arm1ImagePath, appearance.arm1ImagePath)
        assertEquals(definition.nuclearSilo!!.arm2ImagePath, appearance.arm2ImagePath)
        val images = definition.imageBindings()
        assertEquals(definition.nuclearSilo!!.arm1ImagePath, images[UnitImageSlot.ARM1]!!.resource)
        assertEquals(definition.nuclearSilo!!.arm2ImagePath, images[UnitImageSlot.ARM2]!!.resource)
        assertEquals(images.visualImages().bindings().images, images.images)
    }

    @Test fun three_formats_normally_start_produce_a_builder_construct_a_silo_and_launch_a_real_paid_round() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("nuclear-silo/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("normal-package"))).value
            val scene = contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
            assertEquals("p009-nuclear-production", scene.scenarioId)
            assertEquals(listOf("commandCenter"), scene.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            assertTrue(NativeActionPanel.supports(scene.scenarioId))
            SandboxSession(scene).use { s ->
                fun until(limit: Int, ready: () -> Boolean) {
                    repeat(limit) { if (!ready()) s.tick() }
                    assertTrue(ready(), "$format: ${s.feedback}")
                }
                s.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(s.produce("builder"))
                until(600) { s.observation.units.any { it.typeId == "builder" } }
                s.select(s.observation.units.single { it.typeId == "builder" }.position)
                assertEquals(45000L, s.constructionActions.single().price)
                assertIs<ActionResult.Accepted>(s.build("nukeLauncherC", Position(250.0, 150.0)))
                until(1800) { s.observation.units.any { it.typeId == "nukeLauncherC" && it.constructionProgress == 1f } }
                s.select(Position(250.0, 150.0))
                assertEquals("Nuclear silo", s.actionMenu.selectedTitle)
                assertIs<ActionResult.Accepted>(s.unitAction("buildNuke"))
                until(3400) { s.observation.units.single { it.typeId == "nukeLauncherC" }.unitActions!!.stockpile == 1 }
                assertIs<ActionResult.Accepted>(s.unitAction("launchNuke", target = Position(540.0, 300.0)))
                // Native command center caps each hit at 2500, including nuclear area hits.
                until(1000) { s.observation.units.single { it.unitId == "b" }.health == 1500.0 }
                assertEquals(0, s.observation.units.single { it.typeId == "nukeLauncherC" }.unitActions!!.stockpile)
            }
        }
    }


    @Test fun an_authored_builder_constructs_an_alternate_launcher_and_the_client_targets_with_its_paid_round() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("nuclear-silo/json")
        val packageRoot = root.resolve("normal-package")
        val temporary = Files.createTempDirectory("authored-silo-sandbox-")
        Files.walk(packageRoot).use { paths ->
            paths.forEach { source ->
                val copy = temporary.resolve(packageRoot.relativize(source))
                if (!Files.isDirectory(source)) Files.copy(source, copy) else Files.createDirectories(copy)
            }
        }
        val type = "frontier_silo"
        val silo = temporary.resolve("nukeLauncherC.json")
        val building = JsonValue.readJSON(Files.readString(silo)).asObject()
        building.set("name", type).set("ordnanceRole", "launcher")
        Files.writeString(silo, building.toString())
        val builder = temporary.resolve("builder.json")
        val worker = JsonValue.readJSON(Files.readString(builder)).asObject()
        worker.set("format", "rw-reforge.constructor.v1").set("constructionTypes", type)
        Files.writeString(builder, worker.toString())
        val manifest = temporary.resolve("pack.json")
        Files.writeString(manifest, Files.readString(manifest).replace("\"type\": \"builder\"", "\"type\": \"constructor\""))
        val loaded = ContentLoader.loadContentPack(temporary)
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
        val scene = contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
        SandboxSession(scene).use { s ->
            fun until(limit: Int, ready: () -> Boolean) {
                repeat(limit) { if (!ready()) s.tick() }
                assertTrue(ready(), s.feedback)
            }
            s.select(Position(130.0, 130.0))
            assertIs<ActionResult.Accepted>(s.produce("builder"))
            until(600) { s.observation.units.any { it.typeId == "builder" } }
            s.select(s.observation.units.single { it.typeId == "builder" }.position)
            assertEquals(45000L, s.constructionActions.single { it.typeId == type }.price)
            val location = Position(250.0, 150.0)
            assertIs<ActionResult.Accepted>(s.build(type, location))
            until(1800) { s.observation.units.any { it.typeId == type && it.constructionProgress == 1f } }
            val siloId = s.observation.units.single { it.typeId == type }.unitId
            s.select(location)
            fun stock() = s.observation.units.single { it.unitId == siloId }.unitActions?.stockpile
            val projection = MapProjection(scene.map.widthTiles * 20.0, scene.map.heightTiles * 20.0)
            val input = SandboxInput({ s }, { projection }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false })
            fun click(name: String) {
                val actor = com.badlogic.gdx.scenes.scene2d.Actor().also { it.setSize(84f, 40f) }
                val listener = CustomUnitActionButtonInput({ s }, { true }, name, beginTargeting = input::beginGroundAction)
                val event = com.badlogic.gdx.scenes.scene2d.InputEvent().also { it.listenerActor = actor }
                assertTrue(listener.touchDown(event, 10f, 10f, 0, com.badlogic.gdx.Input.Buttons.LEFT))
                listener.touchUp(event, 10f, 10f, 0, com.badlogic.gdx.Input.Buttons.LEFT)
            }
            assertTrue(s.actionMenu.unitActions.single { it.name == "buildNuke" }.available)
            val balance = checkNotNull(s.observation.balance)
            click("buildNuke")
            assertEquals(balance - 11000L, s.observation.balance)
            until(3400) { stock() == 1 }
            s.select(Position(130.0, 130.0), SelectionMode.ADD)
            assertEquals(setOf("a", siloId), s.selectedIds)
            assertTrue(s.actionMenu.unitActions.single { it.name == "launchNuke" }.groundTargeted)
            click("launchNuke")
            val target = Position(540.0, 300.0)
            val x = projection.x(target.x).toInt()
            val y = projection.y(target.y).toInt()
            input.mouseMoved(x, y)
            assertTrue(input.groundActionPreview!!.valid)
            assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
            assertEquals(0, stock())
            val queued = assertNotNull(s.observation.units.single { it.unitId == siloId }.unitActions?.queue?.single()?.target)
            assertEquals(target.x, queued.x, 2.0)
            assertEquals(target.y, queued.y, 2.0)
            until(1000) { s.observation.units.single { it.unitId == "b" }.health == 1500.0 }
        }
    }

    @Test fun native_ammunition_card_reports_price_stock_queue_and_refund_and_launch_card_enters_point_selection() {
        SandboxSession(NuclearSandboxScene.scenario()).use { s ->
            s.select(Position(220.0, 180.0))
            assertEquals("Nuclear silo", s.actionMenu.selectedTitle)
            val card = s.actionMenu.unitActions.single { it.name == "buildNuke" }
            assertEquals("Build nuke", card.title)
            assertTrue(card.tooltip.contains("11000"))
            val actor = com.badlogic.gdx.scenes.scene2d.Actor().also { it.setSize(84f, 40f) }
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ s }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) }, { true }, { false }, { false })
            fun click(name: String, button: Int) {
                val listener = CustomUnitActionButtonInput({ s }, { true }, name, beginTargeting = input::beginGroundAction)
                actor.addListener(listener)
                val event = com.badlogic.gdx.scenes.scene2d.InputEvent().also { it.listenerActor = actor }
                assertTrue(listener.touchDown(event, 10f, 10f, 0, button))
                listener.touchUp(event, 10f, 10f, 0, button)
                actor.removeListener(listener)
            }
            click("buildNuke", com.badlogic.gdx.Input.Buttons.LEFT)
            assertEquals(89000L, s.observation.balance)
            assertTrue(s.actionMenu.unitActions.single { it.name == "buildNuke" }.cancellable)
            click("buildNuke", com.badlogic.gdx.Input.Buttons.RIGHT)
            assertEquals(100000L, s.observation.balance)
            click("buildNuke", com.badlogic.gdx.Input.Buttons.LEFT)
            repeat(3334) { s.tick() }
            assertTrue(s.actionMenu.selectedUnitInfo!!.contains("Ammo 1"))
            click("launchNuke", com.badlogic.gdx.Input.Buttons.LEFT)
            assertEquals("launchNuke", input.groundActionName)
            assertEquals(1, s.observation.units.single { it.unitId == "silo" }.unitActions!!.stockpile)
        }
    }

    @Test fun launch_point_selection_only_commits_on_a_valid_owned_release_and_escape_does_not_spend_stock() {
        for (change in listOf("none", "escape", "selection", "viewport", "right")) {
            SandboxSession(NuclearSandboxScene.scenario()).use { s ->
                s.select(Position(220.0, 180.0))
                assertIs<ActionResult.Accepted>(s.unitAction("buildNuke"))
                repeat(3334) { s.tick() }
                fun stock() = s.observation.units.single { it.unitId == "silo" }.unitActions!!.stockpile
                val map = MapProjection(600.0, 400.0)
                val input = SandboxInput({ s }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                    { true }, { false }, { false }, insideViewport = { x, _ -> x < 650 })
                assertTrue(input.beginGroundAction("launchNuke"))
                assertEquals(1, stock())
                val x = map.x(520.0).toInt(); val y = map.y(180.0).toInt()
                input.mouseMoved(x, y)
                assertTrue(input.groundActionPreview!!.valid)
                assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
                assertEquals(1, stock())
                when (change) {
                    "escape" -> input.keyDown(com.badlogic.gdx.Input.Keys.ESCAPE)
                    "selection" -> { s.select(Position(100.0, 180.0)); s.select(Position(220.0, 180.0)) }
                    "viewport" -> { input.touchDragged(700, y, 0); input.touchDragged(x, y, 0) }
                    "right" -> { input.cancel(); input.beginGroundAction("launchNuke"); input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.RIGHT) }
                }
                input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
                val queue = s.observation.units.single { it.unitId == "silo" }.unitActions!!.queue
                if (change == "none") {
                    assertEquals(0, stock())
                    assertEquals("launchNuke", queue.single().name)
                    assertEquals(Position(520.0, 180.0), queue.single().target)
                    assertNull(input.groundActionName)
                } else {
                    assertEquals(1, stock(), change)
                    assertTrue(queue.isEmpty(), change)
                }
            }
        }
    }

    @Test fun host_no_nukes_setting_hides_and_blocks_new_construction_but_keeps_existing_ammunition_actions() {
        for (enabled in listOf(false, true)) {
            SandboxSession(NuclearSandboxScene.scenario(), options = SandboxOptions(nukesEnabled = enabled)).use { s ->
                s.select(Position(100.0, 180.0))
                assertEquals(enabled, s.constructionActions.any { it.typeId == "nukeLauncherC" })
                if (!enabled) {
                    assertNull(s.previewConstruction("nukeLauncherC", Position(360.0, 180.0)))
                    assertNull(s.build("nukeLauncherC", Position(360.0, 180.0)))
                    assertEquals(100000L, s.observation.balance)
                }
                s.select(Position(220.0, 180.0))
                assertIs<ActionResult.Accepted>(s.unitAction("buildNuke"))
                assertEquals(89000L, s.observation.balance)
            }
        }
    }
}
