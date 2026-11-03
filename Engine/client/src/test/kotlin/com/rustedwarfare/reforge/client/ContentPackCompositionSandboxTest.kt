package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ContentPackCompositionSandboxTest {
    @Test
    fun package_switch_refresh_duplicate_failure_and_explicit_disable_all_keep_the_actual_enabled_identity() {
        withPacks { _, base, overlay ->
            ContentPackSelectionSession().use { state ->
                fun import(path: Path) {
                    state.edit(path); state.checkPackage()
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (state.busy && System.nanoTime() < deadline) { state.poll(); Thread.sleep(5) }
                    check(!state.busy)
                }
                import(base); state.enable(true)
                import(overlay); state.enable(true)
                assertEquals(2, state.packages.size)
                val frozen = assertNotNull(state.choice())
                state.selectPackage("base.scouts"); assertTrue(state.enabled)
                state.enable(false)
                assertFalse(state.canStart)
                assertTrue(state.compositionError?.contains("dependencies") == true)
                assertEquals(2, frozen.content.packages.size)
                state.enable(true); assertTrue(state.canStart)
                import(overlay)
                assertEquals(2, state.packages.size, "Refreshing the same ordinary root replaces its entry")
                assertFalse(state.enabled)
                assertEquals(listOf("base.scouts"), state.enabledPackages.map { it.id })
                state.enable(true)
                val duplicate = base.parent.resolve("duplicate")
                Files.walk(base).use { paths -> paths.forEach { file ->
                    val target = duplicate.resolve(base.relativize(file))
                    require(target.normalize().startsWith(base.parent))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
                import(duplicate)
                assertTrue(state.error?.contains("IDENTITY_CONFLICT") == true)
                assertEquals("overlay.scouts", state.checked?.id)
                assertEquals(2, state.packages.size)
                assertEquals(2, state.enabledPackages.size)
                assertFalse(state.canStart)
                state.disableAll()
                assertTrue(state.canStart); assertEquals(null, state.choice())
                assertEquals(420.0, frozen.content.units.getValue("amber_scout").maximumHealth)
            }
        }
    }

    @Test
    fun explicit_enabled_packages_survive_another_import_and_missing_dependency_selection_can_be_repaired() {
        withPacks { _, base, overlay ->
            ContentPackSelectionSession().use { state ->
                fun import(path: Path) {
                    state.edit(path); state.checkPackage()
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (state.busy && System.nanoTime() < deadline) { state.poll(); Thread.sleep(5) }
                    check(!state.busy)
                    assertEquals(null, state.error)
                    assertFalse(state.enabled, "Checking must not enable the newly imported package")
                }
                import(overlay); state.enable(true)
                assertFalse(state.canStart, "The missing base must block the entire enabled set")
                import(base); state.enable(true)
                assertTrue(state.canStart)
                val choice = assertNotNull(state.choice())
                assertEquals(listOf("base.scouts", "overlay.scouts"), choice.content.packages.map { it.id })
                assertEquals(420.0, choice.content.units.getValue("amber_scout").maximumHealth)
                assertEquals("overlay.scouts", choice.content.provenance.getValue("amber_scout").packageId)
                state.select("amber_guard")
                assertEquals("amber_guard", assertNotNull(state.choice()).unitId)
                assertEquals("amber_scout", choice.unitId, "Published choices are immutable")
            }
        }
    }

    @Test
    fun a_whole_checked_set_reaches_real_simulation_and_keeps_replaced_base_files_in_the_startup_guard() {
        withPacks { root, base, overlay ->
            val set = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(overlay, base))).value
            val choice = ContentPackChoice(set, "amber_scout")
            SandboxLoader().use { loader ->
                loader.requestPack(root, choice)
                val loaded = await(loader).getOrThrow()
                assertEquals(setOf("amber_scout", "amber_guard"), loaded.scenario.unitDefinitions.keys)
                assertEquals(set.sources, loaded.contentSources)
                SandboxSession(loaded.scenario).use { session ->
                    session.select(Position(110.0, 210.0))
                    assertEquals(420.0, session.observation.units.single { it.unitId == "a" }.maxHealth)
                    assertIs<ActionResult.Accepted>(session.command(Position(150.0, 210.0)))
                    repeat(30) { session.tick() }
                    assertTrue(session.observation.units.single { it.unitId == "a" }.position.x > 110.0)
                    assertIs<ActionResult.Accepted>(session.command(Position(350.0, 210.0)))
                    repeat(1500) { session.tick() }
                    assertTrue(session.observation.units.any { (it.health ?: 420.0) < 420.0 })
                }
                val image = base.resolve("units/tank.png"); val bytes = Files.readAllBytes(image)
                Files.write(image, bytes + byteArrayOf(32))
                loader.requestPack(root, choice)
                assertTrue(await(loader).isFailure)
                Files.write(image, bytes)
                loader.requestPack(root, choice)
                assertTrue(await(loader).isSuccess)
            }
        }
    }

    @Test
    fun a_locally_checked_dependent_pack_cannot_bypass_composition_through_the_single_pack_choice() {
        withPacks { root, _, overlay ->
            val checked = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(overlay)).value
            val attempt = runCatching {
                SandboxLoader().use { loader ->
                    loader.requestPack(root, ContentPackChoice(checked, "amber_scout"))
                    await(loader).getOrThrow()
                }
            }
            assertTrue(attempt.isFailure, "A missing dependency must never publish a sandbox through the legacy choice")
            assertTrue(attempt.exceptionOrNull()?.message?.contains("dependencies") == true)
        }
    }

    private fun withPacks(check: (Path, Path, Path) -> Unit) {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val temporary = Files.createTempDirectory("p008-combined-sandbox-").toAbsolutePath().normalize()
        try {
            val source = fixtures.resolve("content-packs/amber-scouts-json")
            val base = temporary.resolve("base"); val overlay = temporary.resolve("overlay")
            for ((target, id) in listOf(base to "base.scouts", overlay to "overlay.scouts")) {
                Files.walk(source).use { paths -> paths.forEach { file ->
                    val destination = target.resolve(source.relativize(file))
                    require(destination.normalize().startsWith(temporary))
                    if (Files.isDirectory(file)) Files.createDirectories(destination) else Files.copy(file, destination)
                } }
                val metadata = if (target == base) Files.readString(target.resolve("pack.json"))
                    .replace("amber.scouts.json", id) else """
                    {"format":"rw-reforge.content-pack.v1","id":"overlay.scouts","name":"Overlay scouts",
                     "units":[{"type":"tank","file":"units/scout.json"}],"dependencies":["base.scouts"],
                     "replacements":[{"contentId":"amber_scout","targetPackId":"base.scouts","targetContentId":"amber_scout"}]}
                """.trimIndent()
                if (target == overlay) {
                    val author = target.resolve("units/scout.json")
                    Files.writeString(author, Files.readString(author).replace("250", "420"))
                }
                Files.writeString(target.resolve("pack.json"), metadata)
            }
            check(fixtures.resolve("three-format-tanks/ini").toAbsolutePath().normalize(), base, overlay)
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
            } }
        }
    }

    private fun await(loader: SandboxLoader): Result<LoadedSandbox> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return it }
            Thread.sleep(5)
        }
        error("Content combination did not load within five seconds")
    }
}
