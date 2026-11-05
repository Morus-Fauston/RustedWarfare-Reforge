package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ContentPackCompositionEvidenceTest {
    @Test
    fun diagnostics_preserve_all_enabled_metadata_and_effective_author_chains_after_files_change() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val temporary = Files.createTempDirectory("p008-composed-evidence-").toAbsolutePath().normalize()
        try {
            val source = fixtures.resolve("content-pack-combinations/sandbox")
            val input = temporary.resolve("input")
            Files.walk(source).use { paths -> paths.forEach { file ->
                val target = input.resolve(source.relativize(file))
                require(target.normalize().startsWith(temporary))
                if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
            } }
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(
                ContentLoader.loadContentPacks(listOf(input.resolve("final"), input.resolve("overlay"), input.resolve("base")))).value
            val loaded = SandboxLoader().use { loader ->
                loader.requestPack(input, ContentPackChoice(content, "amber_scout"))
                val deadline = System.nanoTime() + 5_000_000_000L
                var value: LoadedSandbox? = null
                while (value == null && System.nanoTime() < deadline) {
                    value = loader.pollPrepared()?.getOrThrow(); if (value == null) Thread.sleep(5)
                }
                assertNotNull(value)
            }
            for (identity in loaded.contentSources) Files.writeString(identity.path, "Changed after successful startup")
            val output = temporary.resolve("run")
            JsonlEvidence(output, "composed-pack", input).use { it.recordLoadReady(71L, loaded) }
            val record = Files.readAllLines(output.resolve("reforge/client-diagnostic.jsonl"))
                .filter(String::isNotBlank).map { JsonReader().parse(it) }.single { it.getString("kind") == "input-identities" }
            val set = assertNotNull(record.get("contentSet"), "The enabled set and effective authors must be recorded")
            assertEquals(listOf("base.scouts", "overlay.scouts", "final.scouts"), set.get("packages").map { it.getString("id") })
            for (pack in content.packages) {
                val row = set.get("packages").single { it.getString("id") == pack.id }
                val identity = content.sources.single { it.path == pack.manifest }
                assertEquals(identity.path.toString(), row.get("manifest").getString("path"))
                assertEquals(identity.sha256, row.get("manifest").getString("sha256"))
                assertEquals(identity.bytes, row.get("manifest").getLong("bytes"))
            }
            val units = set.get("units")
            val scout = units.get("amber_scout")
            assertEquals("final.scouts", scout.getString("packageId"))
            assertEquals(input.resolve("final/units/scout.ini").toString(), scout.getString("entryPath"))
            assertEquals(listOf("base.scouts", "overlay.scouts"), scout.get("replacedPackageIds").map { it.asString() })
            assertEquals("base.scouts", units.get("amber_guard").getString("packageId"))
            for (identity in content.sources) {
                val row = record.get("files").single { Path.of(it.getString("path")) == identity.path }
                assertEquals(identity.sha256, row.getString("sha256")); assertEquals(identity.bytes, row.getLong("bytes"))
            }
            assertEquals("final.scouts", record.get("contentPack").getString("id"))
            assertEquals("amber_scout", record.get("contentPack").getString("unitId"))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
            } }
        }
    }
}
