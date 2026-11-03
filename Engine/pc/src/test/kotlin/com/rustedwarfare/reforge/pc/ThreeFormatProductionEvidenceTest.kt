package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.LoadedSandbox
import com.rustedwarfare.reforge.client.SandboxLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreeFormatProductionEvidenceTest {
    @Test
    fun native_and_turret_diagnostics_use_actual_loaded_authors_without_phantom_legacy_files_or_reparsing() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs").toAbsolutePath().normalize()
        for (family in listOf("production", "center", "turret", "point-defense")) for (format in listOf("json", "hjson", "ini")) {
            val temporary = Files.createTempDirectory("p008-production-evidence-").toAbsolutePath().normalize()
            try {
                val source = fixtures.resolve("three-format-production/$family/$format")
                val input = temporary.resolve("input")
                Files.walk(source).use { paths -> paths.forEach { original ->
                    val destination = input.resolve(source.relativize(original)).toAbsolutePath().normalize()
                    require(destination.startsWith(temporary))
                    if (Files.isDirectory(original)) Files.createDirectories(destination) else Files.copy(original, destination)
                } }
                val loaded = load(input)
                val expected = loaded.contentSources.associateBy { it.path }
                assertTrue(expected.size >= 2)
                for (path in expected.keys) Files.writeString(path, "Self-owned invalid bytes after successful load")
                val output = temporary.resolve("run")
                JsonlEvidence(output, "native-$family-$format", input).use { it.recordLoadReady(29L, loaded) }
                val rows = Files.readAllLines(output.resolve("reforge/client-diagnostic.jsonl"))
                    .filter(String::isNotBlank).map { JsonReader().parse(it) }
                val identity = rows.single { it.getString("kind") == "input-identities" }
                assertEquals(29L, identity.getLong("generation"))
                val files = identity.get("files").toList()
                assertTrue(files.all { it.has("sha256") }, "$family/$format: phantom legacy or missing input")
                for ((path, sourceIdentity) in expected) {
                    val row = files.single { Path.of(it.getString("path")) == path }
                    assertEquals(sourceIdentity.sha256, row.getString("sha256"), path.toString())
                    assertEquals(sourceIdentity.bytes, row.getLong("bytes"))
                }
                val actualAuthors = files.map { Path.of(it.getString("path")) }.filter {
                    it.parent == input.resolve("units") && it.fileName.toString().substringAfterLast('.') in setOf("ini", "json", "hjson", "properties")
                }.toSet()
                val unported = if (family == "center") setOf(input.resolve("units/builder.properties")) else emptySet()
                assertEquals(expected.keys + unported, actualAuthors, "$family/$format")
            } finally {
                Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                    require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
                } }
            }
        }
    }

    private fun load(root: Path): LoadedSandbox = SandboxLoader().use { loader ->
        loader.request(root)
        repeat(5000) { loader.pollPrepared()?.let { return@use it.getOrThrow() }; Thread.sleep(1) }
        error("Actual background loader did not complete")
    }
}
