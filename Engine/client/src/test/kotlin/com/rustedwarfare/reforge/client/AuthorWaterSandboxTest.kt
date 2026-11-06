package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class AuthorWaterSandboxTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    @Test fun hidden_unpictured_weapon_and_independent_turret_survive_the_normal_preparation_and_draw_path() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = Files.createTempDirectory("rwr-hidden-water-client-").toAbsolutePath().normalize()
            try {
                val source = fixtures.resolve("author-water/$format")
                Files.walk(source).use { paths -> paths.forEach { path ->
                    val target = root.resolve(source.relativize(path)).normalize()
                    require(target.startsWith(root))
                    if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
                } }
                val child = root.resolve("package/units/spcruiser.$format")
                Files.writeString(child, Files.readString(child).replace("cannon.png", "NONE"))
                val template = root.resolve("package/units/base.ini")
                Files.writeString(template, Files.readString(template).replace("[template_gun]", "[template_gun]\ninvisible: true") +
                    "\n[turret_port]\ninvisible: false\nimage: cannon.png\n")
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
                val choice = ContentPackChoice(pack, "spcruiser")
                val initial = PreparedVisualAssets(VisualAssetSelection(), "owned-hidden-water", "fixed", emptyMap(),
                    SandboxRenderResources.from(contentPackScenario(root, choice)).tileSets.associate { it.supportedLayout() to it.imagePath })
                val prepared = contentPackVisuals(initial, choice)
                val base = ResourceImagePreparation.baseLayer(prepared, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
                val resolved = prepared.withResourceImages(ResourceImageComposer.compose(base, emptyList()))
                assertFalse(ResourceId.parse("reforge:units/spcruiser/turret") in base.images)
                assertFalse(ResourceId.parse("reforge:units/spcruiser/weapons/starboard/turret") in base.images)
                val port = ResourceId.parse("reforge:units/spcruiser/weapons/port/turret")
                assertEquals(root.resolve("package/units/cannon.png"), base.images.getValue(port).path)
                SandboxLoader().use { loader ->
                    loader.requestPack(root, choice) { resolved }
                    val loaded = await(loader)
                    val appearance = loaded.renderResources.units.getValue("spcruiser")
                    assertNull(appearance.turretImagePath)
                    assertTrue(appearance.weaponImages.getValue("starboard").invisible)
                    assertNull(appearance.weaponImages.getValue("starboard").imagePath)
                    SandboxSession(loaded.scenario).use { session ->
                        val view = session.observation
                        val drawings = appearance.turretDrawings(view.units.single { it.unitId == "a" }, view.weapons.filter { it.unitId == "a" })
                        assertEquals(listOf("port"), drawings.map { it.weaponName })
                        assertEquals(base.images.getValue(port).path, drawings.single().imagePath)
                        assertEquals(view.weapons.single { it.unitId == "a" && it.weaponName == "port" }.pivot, drawings.single().pivot)
                    }
                }
            } finally {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    require(path.toAbsolutePath().normalize().startsWith(root))
                    Files.deleteIfExists(path)
                } }
            }
        }
    }

    private fun await(loader: SandboxLoader): LoadedSandbox {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return it.getOrThrow() }
            Thread.sleep(5)
        }
        error("Normal hidden-water preparation timed out")
    }

    private fun load(format: String): LoadedSandbox {
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("author-water/$format/package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(fixtures.resolve("author-water/$format"), ContentPackChoice(pack, "spcruiser"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("Author water normal content choice did not load")
        }
    }

    @Test fun normal_content_choice_opens_the_controlled_water_scene_for_all_formats() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = load(format)
            assertEquals("p008-content-pack", loaded.scenario.scenarioId)
            assertEquals(listOf(Position(500.0, 300.0), Position(650.0, 300.0)), loaded.scenario.units.map { it.position })
            SandboxSession(loaded.scenario).use { session ->
                assertEquals(listOf("spcruiser", "spcruiser"), session.observation.units.map { it.typeId })
                assertEquals(listOf("port", "starboard"), session.observation.weapons.filter { it.unitId == "a" }.map { it.weaponName })
            }
        }
    }
    @Test fun selected_author_id_is_the_default_player_facing_name() {
        SandboxSession(load("json").scenario).use { session ->
            session.select(Position(500.0, 300.0))
            assertEquals("a", session.selectedId)
            assertEquals("spcruiser selected", session.playerFeedback)
        }
    }
    @Test fun normal_selected_hull_sails_attacks_with_both_inherited_turrets_and_draws_their_poses() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = load(format)
            val appearance = SandboxRenderResources.from(loaded.scenario).units.getValue("spcruiser")
            SandboxSession(loaded.scenario).use { session ->
                val initial = session.observation
                session.select(Position(500.0, 300.0))
                assertIs<ActionResult.Accepted>(session.move(Position(500.0, 400.0)))
                repeat(120) { session.tick() }
                val sailed = session.observation.units.single { it.unitId == "a" }
                assertTrue(sailed.position.y > 310.0, "$format did not sail: ${sailed.position}")
                assertNotNull(sailed.waterPose)
                assertEquals(0.0, sailed.height)
                assertIs<ActionResult.Accepted>(session.command(Position(650.0, 300.0)))
                val fired = mutableSetOf<String>()
                var independent = false
                repeat(300) {
                    session.tick()
                    val view = session.observation
                    fired += view.projectileEvents.filter { it.sourceUnitId == "a" && it.kind == "born" }
                        .mapNotNull { it.sourceWeaponName }
                    val weapons = view.weapons.filter { it.unitId == "a" }
                    val drawings = appearance.turretDrawings(view.units.single { it.unitId == "a" }, weapons)
                    assertEquals(listOf("port", "starboard"), drawings.map { it.weaponName })
                    assertEquals(weapons.map { it.pivot }, drawings.map { it.pivot })
                    assertTrue(drawings.all { it.imagePath == appearance.turretImagePath })
                    independent = independent || weapons.map { it.turretHeading }.distinct().size == 2
                }
                assertEquals(setOf("port", "starboard"), fired, "$format actual projectile births")
                assertTrue(independent, "$format poses collapsed")
                assertTrue(session.observation.units.single { it.unitId == "b" }.health!! < 4000.0)
                assertEquals(Position(500.0, 300.0), initial.units.single { it.unitId == "a" }.position)
            }
        }
    }
    @Test fun the_normal_package_checker_enables_the_author_choice_without_sandbox_placement() {
        for (format in listOf("ini", "json", "hjson")) ContentPackSelectionSession().use { selection ->
            selection.edit(fixtures.resolve("author-water/$format/package"))
            selection.checkPackage()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (selection.busy && System.nanoTime() < deadline) { selection.poll(); Thread.sleep(5) }
            assertFalse(selection.busy)
            assertNull(selection.error)
            assertEquals(setOf("spcruiser"), selection.availableUnits)
            selection.enable(true)
            selection.select("spcruiser")
            assertTrue(selection.canStart)
            assertEquals("spcruiser", selection.choice()!!.unitId)
        }
    }
}
