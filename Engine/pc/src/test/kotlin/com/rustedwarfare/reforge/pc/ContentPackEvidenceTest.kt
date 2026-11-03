package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ContentPackEvidenceTest {
    @Test
    fun a_package_records_actual_loaded_metadata_templates_and_images_without_a_fixed_fixture_manifest() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val temporary = Files.createTempDirectory("p008-pack-evidence-").toAbsolutePath().normalize()
            try {
                val input = fixtures.resolve("three-format-tanks/ini").toAbsolutePath().normalize()
                val source = fixtures.resolve("content-packs/amber-scouts-$format")
                val packRoot = temporary.resolve("package")
                Files.walk(source).use { paths -> paths.forEach { file ->
                    val target = packRoot.resolve(source.relativize(file))
                    require(target.normalize().startsWith(temporary))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(packRoot)).value
                val loaded = SandboxLoader().use { loader ->
                    loader.requestPack(input, ContentPackChoice(pack, "amber_guard"))
                    val deadline = System.nanoTime() + 5_000_000_000L
                    var value: LoadedSandbox? = null
                    while (value == null && System.nanoTime() < deadline) {
                        value = loader.pollPrepared()?.getOrThrow(); if (value == null) Thread.sleep(5)
                    }
                    assertNotNull(value)
                }
                for (identity in loaded.contentSources) Files.writeString(identity.path, "Changed after successful load")
                val output = temporary.resolve("run")
                JsonlEvidence(output, "pack-$format", input).use { it.recordLoadReady(51L, loaded) }
                val rows = Files.readAllLines(output.resolve("reforge/client-diagnostic.jsonl"))
                    .filter(String::isNotBlank).map { JsonReader().parse(it) }
                val record = rows.single { it.getString("kind") == "input-identities" }
                val files = record.get("files").toList()
                val expected = pack.sources.map { it.path }.toSet() + setOf(input.resolve("maps/p001-controlled.tmx"), input.resolve("assets/terrain.png"))
                assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
                assertTrue(files.all { it.has("sha256") })
                for (identity in pack.sources) {
                    val row = files.single { Path.of(it.getString("path")) == identity.path }
                    assertEquals(identity.sha256, row.getString("sha256")); assertEquals(identity.bytes, row.getLong("bytes"))
                }
                val selected = record.get("contentPack")
                assertEquals(pack.id, selected.getString("id"))
                assertEquals("amber_guard", selected.getString("unitId"))
                assertEquals(pack.sources.single { it.path == pack.manifest }.sha256, record.get("sandboxManifest").getString("sha256"))
            } finally {
                Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                    require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
                } }
            }
        }
    }
}
