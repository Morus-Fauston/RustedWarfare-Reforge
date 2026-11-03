package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContentPackSandboxTest {
    @Test
    fun changed_sources_and_incompatible_original_visuals_fail_without_publishing_and_repair_can_reopen() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("three-format-tanks/ini").toAbsolutePath().normalize()
        val temporary = Files.createTempDirectory("p008-pack-recovery-").toAbsolutePath().normalize()
        try {
            val source = fixtures.resolve("content-packs/amber-scouts-json")
            Files.walk(source).use { paths -> paths.forEach { file ->
                val destination = temporary.resolve(source.relativize(file))
                require(destination.normalize().startsWith(temporary))
                if (Files.isDirectory(file)) Files.createDirectories(destination) else Files.copy(file, destination)
            } }
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(temporary)).value
            val choice = ContentPackChoice(pack, "amber_scout")
            val resources = SandboxRenderResources.from(ControlledScenario.load(root))
            fun visuals(selection: VisualAssetSelection = VisualAssetSelection()) = PreparedVisualAssets(selection,
                "owned", "owned-mapping", resources.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath,
                    unit.backImagePath, unit.turretImagePath, unit.deadImagePath, unit.totalFrames) },
                mapOf("controlled-strip-2" to resources.tileSets.single().imagePath))
            SandboxLoader().use { loader ->
                for (changed in listOf(pack.manifest, temporary.resolve("units/base.ini"), temporary.resolve("units/tank.png"))) {
                    val bytes = Files.readAllBytes(changed)
                    Files.write(changed, bytes + byteArrayOf(32))
                    loader.requestPack(root, choice)
                    assertTrue(await(loader).isFailure, "Changed after menu check: $changed")
                    assertEquals(null, loader.pollPrepared())
                    Files.write(changed, bytes)
                    loader.requestPack(root, choice) { Files.write(changed, bytes + byteArrayOf(32)); visuals() }
                    assertTrue(await(loader).isFailure, "Changed during visual preparation: $changed")
                    assertEquals(null, loader.pollPrepared())
                    Files.write(changed, bytes)
                    loader.requestPack(root, choice) { visuals() }
                    assertEquals(pack.sources, await(loader).getOrThrow().contentSources)
                }
                loader.requestPack(root, choice) { visuals(VisualAssetSelection(VisualAssetSource.ORIGINAL)) }
                val incompatible = await(loader)
                assertTrue(incompatible.isFailure)
                assertTrue(incompatible.exceptionOrNull()?.message?.contains("VISUAL_MAPPING_UNSUPPORTED") == true)
                loader.requestPack(root, choice); loader.cancel()
                assertEquals(null, loader.pollPrepared())
                loader.requestPack(root, ContentPackChoice(pack, "amber_guard"))
                assertEquals("amber_guard", await(loader).getOrThrow().scenario.units.first().typeId)
            }
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
            } }
        }
    }

    @Test
    fun placeholder_selection_keeps_the_packages_own_images_for_new_ids() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("three-format-tanks/ini").toAbsolutePath().normalize()
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(fixtures.resolve("content-packs/amber-scouts-ini"))).value
        val before = SandboxRenderResources.from(ControlledScenario.load(root))
        val visuals = PreparedVisualAssets(VisualAssetSelection(), "owned-placeholder", "original-map-placeholder",
            before.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath, unit.backImagePath,
                unit.turretImagePath, unit.deadImagePath, unit.totalFrames) },
            mapOf("controlled-strip-2" to before.tileSets.single().imagePath))
        SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "amber_guard")) { visuals }
            val loaded = await(loader).getOrThrow()
            assertEquals("amber_guard", loaded.scenario.units.first().typeId)
            for ((id, definition) in pack.units) {
                assertEquals(definition.imagePath, loaded.renderResources.units.getValue(id).imagePath)
                assertEquals(definition.combat!!.turretImagePath, loaded.visuals!!.units.getValue(id).turret)
            }
            assertEquals(visuals.terrain, loaded.visuals!!.terrain)
            assertEquals(pack.sources, loaded.contentSources)
        }
    }

    @Test
    fun a_checked_local_package_plays_the_selected_new_unit_through_the_real_loader_and_session() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val root = fixtures.resolve("three-format-tanks/ini").toAbsolutePath().normalize()
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(
                ContentLoader.loadContentPack(fixtures.resolve("content-packs/amber-scouts-$format"))).value
            SandboxLoader().use { loader ->
                loader.requestPack(root, ContentPackChoice(pack, "amber_scout"))
                val loaded = await(loader).getOrThrow()
                assertEquals("p008-content-pack", loaded.scenario.scenarioId)
                assertEquals(pack.sources, loaded.contentSources)
                assertEquals(setOf("amber_scout", "amber_guard"), loaded.scenario.unitDefinitions.keys)
                SandboxSession(loaded.scenario).use { session ->
                    session.select(Position(110.0, 210.0))
                    assertEquals("a", session.selectedId)
                    assertEquals("amber_scout", session.observation.units.single { it.unitId == "a" }.typeId)
                    assertEquals(250.0, session.observation.units.single { it.unitId == "a" }.maxHealth)
                    assertIs<ActionResult.Accepted>(session.command(Position(150.0, 210.0)))
                    repeat(30) { session.tick() }
                    assertTrue(session.observation.units.single { it.unitId == "a" }.position.x > 110.0)
                    assertIs<ActionResult.Accepted>(session.command(Position(350.0, 210.0)))
                    var projectile = false; var damage = false
                    repeat(1500) {
                        if (!damage) {
                            session.tick()
                            projectile = projectile || session.observation.projectiles.isNotEmpty()
                            damage = session.observation.units.any { (it.health ?: 250.0) < 250.0 }
                        }
                    }
                    assertTrue(projectile, format)
                    assertTrue(damage, format)
                }
            }
        }
    }

    private fun await(loader: SandboxLoader): Result<LoadedSandbox> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return it }
            Thread.sleep(5)
        }
        error("Content pack sandbox did not load within five seconds")
    }
}
