package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreeFormatSandboxTest {
    @Test
    fun all_three_author_formats_select_move_and_damage_through_the_same_session() {
        for (format in listOf("ini", "json", "hjson")) withAuthor(format) { root ->
            val result = runCatching { ControlledScenario.load(root) }
            assertTrue(result.isSuccess, "$format: ${result.exceptionOrNull()?.message}")
            SandboxSession(result.getOrThrow()).use { session ->
                session.select(Position(110.0, 210.0))
                assertEquals("a", session.selectedId)
                assertIs<ActionResult.Accepted>(session.command(Position(150.0, 210.0)))
                repeat(30) { session.tick() }
                assertTrue(session.observation.units.single { it.unitId == "a" }.position.x > 110.0)
                assertIs<ActionResult.Accepted>(session.command(Position(350.0, 210.0)))
                var projectile = false
                var damage = false
                repeat(1500) {
                    if (!damage) {
                        session.tick()
                        projectile = projectile || session.observation.projectiles.isNotEmpty()
                        damage = session.observation.units.any { (it.health ?: 210.0) < 210.0 }
                    }
                }
                assertTrue(projectile, "$format: no projectile")
                assertTrue(damage, "$format: no combat damage")
            }
        }
    }

    @Test
    fun background_loader_carries_actual_sources_and_rejects_changes_during_visual_preparation() {
        withAuthor("json") { root ->
            SandboxLoader().use { loader ->
                loader.request(root)
                val initial = await(loader).getOrThrow()
                val base = root.resolve("units/base.ini")
                assertEquals(listOf(root.resolve("units/tank.json"), base), initial.contentSources.map { it.path })
                assertEquals("99aa63a6f6e87662e257af3ca7c4557290153e60e62fcdf74360e15caef612e8", initial.contentSources[1].sha256)
                val appearance = initial.renderResources
                val visuals = PreparedVisualAssets(VisualAssetSelection(), "self-owned", "fixed-owned-fixture",
                    appearance.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath, unit.backImagePath,
                        unit.turretImagePath, unit.deadImagePath, unit.totalFrames) },
                    mapOf("controlled-strip-2" to appearance.tileSets.single().imagePath))
                loader.request(root) {
                    Files.writeString(base, Files.readString(base).replace("price: 350", "price: 420"))
                    visuals
                }
                val failure = assertNotNull(await(loader).exceptionOrNull())
                assertTrue(failure.message.orEmpty().contains("source bytes changed"), failure.toString())
                assertNull(loader.pollPrepared())
                assertEquals(350L, assertNotNull(initial.scenario.unitDefinitions.getValue("c_tank").build).price)
                assertEquals("99aa63a6f6e87662e257af3ca7c4557290153e60e62fcdf74360e15caef612e8", initial.contentSources[1].sha256)
                loader.request(root)
                val reopened = await(loader).getOrThrow()
                assertEquals(420L, assertNotNull(reopened.scenario.unitDefinitions.getValue("c_tank").build).price)
                assertTrue(initial.contentSources[1].sha256 != reopened.contentSources[1].sha256)
            }
        }
    }

    @Test
    fun missing_conflicting_and_invalid_authors_do_not_publish_and_repaired_inputs_reopen() {
        withAuthor("hjson") { root ->
            val author = root.resolve("units/tank.hjson")
            val original = Files.readString(author)
            val extra = root.resolve("units/tank.json").toAbsolutePath().normalize()
            require(author.toAbsolutePath().normalize().startsWith(root) && extra.startsWith(root))
            SandboxLoader().use { loader ->
                loader.request(root)
                val initial = await(loader).getOrThrow()
                Files.writeString(extra, "Invalid second candidate")
                loader.request(root)
                val conflict = assertNotNull(await(loader).exceptionOrNull()).message.orEmpty()
                assertTrue(conflict.contains("IDENTITY_CONFLICT") && conflict.contains("tank.json") && conflict.contains("tank.hjson"), conflict)
                assertNull(loader.pollPrepared())
                Files.delete(extra)
                Files.writeString(author, "format: rw-reforge.unit.v2\ncopyFrom: [\"base.ini\"]\ncore: {maxHp:\"bad\"}")
                loader.request(root)
                val invalid = assertNotNull(await(loader).exceptionOrNull()).message.orEmpty()
                assertTrue(invalid.contains("MALFORMED_SYNTAX") && invalid.contains("maxHp"), invalid)
                Files.delete(author)
                loader.request(root)
                val missing = assertNotNull(await(loader).exceptionOrNull()).message.orEmpty()
                assertTrue(missing.contains("BAD_REFERENCE") && missing.contains("found 0"), missing)
                Files.writeString(author, original)
                loader.request(root)
                loader.cancel()
                assertNull(loader.pollPrepared())
                loader.request(root)
                val reopened = await(loader).getOrThrow()
                assertEquals(210.0, assertNotNull(reopened.scenario.unitDefinitions.getValue("c_tank").combat).maxHp)
                assertEquals(initial.contentSources, reopened.contentSources)
            }
        }
    }

    private fun await(loader: SandboxLoader): Result<LoadedSandbox> {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return it }
            Thread.sleep(5)
        }
        error("Sandbox loader did not complete within five seconds")
    }

    private fun withAuthor(format: String, check: (Path) -> Unit) {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("production-combat/combat")
        val root = Files.createTempDirectory("p008-format-sandbox-").toAbsolutePath().normalize()
        try {
            Files.walk(source).use { paths -> paths.filter(Files::isRegularFile).forEach { input ->
                val destination = root.resolve(source.relativize(input)).toAbsolutePath().normalize()
                require(destination.startsWith(root))
                Files.createDirectories(destination.parent)
                Files.copy(input, destination)
            } }
            if (format != "ini") {
                val original = root.resolve("units/tank.ini").toAbsolutePath().normalize()
                val parent = root.resolve("units/base.ini").toAbsolutePath().normalize()
                require(original.startsWith(root) && parent.startsWith(root))
                Files.move(original, parent)
                Files.writeString(root.resolve("units/tank.$format"), if (format == "json")
                    """{"format":"rw-reforge.unit.v2","copyFrom":["base.ini"]}"""
                else "format: rw-reforge.unit.v2\n# Formal Hjson author input\ncopyFrom: [\"base.ini\"]")
            }
            check(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(owned)
            } }
        }
    }
}
