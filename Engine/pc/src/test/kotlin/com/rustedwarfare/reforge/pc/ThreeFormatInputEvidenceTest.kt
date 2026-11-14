package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.LoadedSandbox
import com.rustedwarfare.reforge.client.SandboxLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreeFormatInputEvidenceTest {
    @Test
    fun identities_use_the_loaded_author_and_parent_bytes_even_after_the_files_change() {
        for (format in listOf("json", "hjson")) {
            val temporary = Files.createTempDirectory("p008-loaded-evidence-").toAbsolutePath().normalize()
            try {
                val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                    ?: Path.of("../../SimulationTest/public/inputs")
                val source = fixtures.resolve("production-combat/combat")
                val input = temporary.resolve("input")
                Files.walk(source).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
                    val destination = input.resolve(source.relativize(file)).toAbsolutePath().normalize()
                    require(destination.startsWith(temporary))
                    Files.createDirectories(destination.parent)
                    Files.copy(file, destination)
                } }
                val old = input.resolve("units/tank.ini").toAbsolutePath().normalize()
                val parent = input.resolve("units/base.ini").toAbsolutePath().normalize()
                require(old.startsWith(temporary) && parent.startsWith(temporary))
                Files.move(old, parent)
                val author = input.resolve("units/tank.$format")
                Files.writeString(author, if (format == "json")
                    """{"format":"rw-reforge.unit.v2","copyFrom":["base.ini"]}"""
                else "format: rw-reforge.unit.v2\ncopyFrom: [\"base.ini\"]")
                val loaded = load(input)
                Files.writeString(parent, "Invalid after this load completed")
                Files.writeString(author, "Invalid after this load completed")
                val output = temporary.resolve("run")
                JsonlEvidence(output, "loaded-$format", input).use { it.recordLoadReady(17L, loaded) }
                val rows = Files.readAllLines(output.resolve("reforge/client-diagnostic.jsonl"))
                    .filter(String::isNotBlank).map { JsonReader().parse(it) }
                val identity = rows.single { it.getString("kind") == "input-identities" }
                assertEquals(17L, identity.getLong("generation"))
                assertEquals(input.toString(), identity.getString("root"))
                assertEquals("p005-combat", identity.getString("scenarioId"))
                val files = identity.get("files").toList()
                val expected = listOf("maps/p001-controlled.tmx", "units/tank.$format", "units/base.ini", "combat.properties",
                    "assets/terrain.png", "units/tank.png", "units/tank_turret.png", "units/tank_dead.png").map(input::resolve).toSet()
                assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
                assertTrue(files.all { it.has("sha256") })
                val parentRow = files.single { it.getString("path") == parent.toString() }
                assertEquals("99aa63a6f6e87662e257af3ca7c4557290153e60e62fcdf74360e15caef612e8", parentRow.getString("sha256"))
                assertTrue(files.any { it.getString("path") == author.toString() && it.has("sha256") })
                assertTrue(files.none { it.getString("path") == old.toString() })
                if (format == "json") {
                    val authorRow = files.single { it.getString("path") == author.toString() }
                    assertEquals(55L, authorRow.getLong("bytes"))
                    assertEquals("fb5aac95b156141918ea415032306818d198c009703cb1ffbd8afe9c9087397a", authorRow.getString("sha256"))
                }
            } finally {
                Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                    require(owned.toAbsolutePath().normalize().startsWith(temporary))
                    Files.deleteIfExists(owned)
                } }
            }
        }
    }

    private fun load(root: Path): LoadedSandbox = SandboxLoader().use { loader ->
        loader.request(root)
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return@use it.getOrThrow() }
            Thread.sleep(5)
        }
        error("Sandbox loader did not complete within five seconds")
    }
}
