package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.LoadedSandbox
import com.rustedwarfare.reforge.client.SandboxLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class ThreeFormatEconomyEvidenceTest {
    @Test
    fun builder_economic_and_template_identities_are_the_actual_loaded_bytes_without_phantom_ini_or_reparse() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs").toAbsolutePath().normalize()
        for (family in listOf("start", "factory", "economy", "queue", "maintenance", "construction")) for (format in listOf("ini", "json", "hjson")) {
            val temporary = Files.createTempDirectory("p008-economic-evidence-").toAbsolutePath().normalize()
            try {
                val source = fixtures.resolve("three-format-economy/$family/$format")
                val input = temporary.resolve("input")
                Files.walk(source).use { paths -> paths.forEach { original ->
                    val destination = input.resolve(source.relativize(original)).toAbsolutePath().normalize()
                    require(destination.startsWith(temporary))
                    if (Files.isDirectory(original)) Files.createDirectories(destination) else Files.copy(original, destination)
                } }
                val loaded = load(input)
                val expected = loaded.contentSources.associateBy { it.path }
                val required = mutableSetOf("builder.$format", "tank.$format", "commandCenter.$format")
                if (family != "start") required += "landFactory.$format"
                if (family in setOf("economy", "queue", "maintenance", "construction")) required += listOf(
                    "extractor.$format", "extractor_common.$format", "extractorT2.$format", "extractorT3.$format",
                    "fabricatorT1.$format", "fabricatorT2.$format", "fabricatorT3.$format")
                assertTrue(expected.keys.map { it.fileName.toString() }.containsAll(required), "$family/$format")
                for ((path, identity) in expected) {
                    val original = Files.readAllBytes(path)
                    assertEquals(original.size.toLong(), identity.bytes)
                    assertEquals(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }, identity.sha256)
                    Files.writeString(path, "Invalid self-owned bytes after a successful actual load")
                }
                val output = temporary.resolve("run")
                JsonlEvidence(output, "economic-$family-$format", input).use { it.recordLoadReady(43L, loaded) }
                val rows = Files.readAllLines(output.resolve("reforge/client-diagnostic.jsonl")).filter(String::isNotBlank)
                    .map { JsonReader().parse(it) }
                val identity = rows.single { it.getString("kind") == "input-identities" }
                assertEquals(43L, identity.getLong("generation"))
                val files = identity.get("files").toList()
                assertTrue(files.all { it.has("sha256") }, "$family/$format: no phantom or missing file")
                val authors = files.map { Path.of(it.getString("path")) }.filter {
                    it.parent == input.resolve("units") && it.fileName.toString().substringAfterLast('.') in setOf("ini", "json", "hjson", "properties")
                }.toSet()
                assertEquals(expected.keys, authors, "$family/$format: exact actual authors/parents, not directory enumeration")
                for ((path, actual) in expected) {
                    val recorded = files.single { Path.of(it.getString("path")) == path }
                    assertEquals(actual.sha256, recorded.getString("sha256"))
                    assertEquals(actual.bytes, recorded.getLong("bytes"))
                }
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
