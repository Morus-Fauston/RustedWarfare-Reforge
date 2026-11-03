package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MechSessionEvidenceTest {
    @Test
    fun actual_three_format_sessions_write_authorized_foot_poses_and_keep_plain_unit_records_unchanged() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val temporary = Files.createTempDirectory("p008-mech-jsonl-").toRealPath()
        require(temporary.parent == Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
        try {
            for (format in listOf("ini", "json", "hjson")) {
                val root = fixtures.resolve("mech-production/$format").toAbsolutePath().normalize()
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
                val loaded = SandboxLoader().use { loader ->
                    loader.requestPack(root, ContentPackChoice(pack, "mechGun")); await(loader)
                }
                val output = temporary.resolve(format)
                JsonlEvidence(output, "mech-$format", root).use { sink ->
                    sink.recordLoadReady(1L, loaded)
                    SandboxSession(loaded.scenario, sink).use { session ->
                        session.select(Position(110.0, 210.0))
                        assertEquals("a", session.selectedId)
                        assertIs<ActionResult.Accepted>(session.command(Position(250.0, 210.0)))
                        repeat(10) { session.tick() }
                        assertEquals(2.05, session.observation.units.first { it.unitId == "a" }.mechPose!!.legs.first().height, 1e-12)
                    }
                }
                val samples = Files.readAllLines(output.resolve("reforge/client-diagnostic.jsonl"))
                    .filter(String::isNotBlank).map { JsonReader().parse(it) }.filter { it.getString("kind") == "observation" }
                fun unit(row: com.badlogic.gdx.utils.JsonValue) = row.get("sample").get("units").toList().first { it.getString("unitId") == "a" }
                val initial = assertNotNull(unit(samples.first { it.getString("phase") == "initial" }).get("mechPose"), format)
                assertEquals(90.0, initial.getDouble("forwardHeading"))
                assertEquals(2, initial.get("legs").size)
                assertEquals(.1, initial.get("legs").get(0).getDouble("height"))
                assertEquals(110.0, initial.get("legs").get(0).get("foot").getDouble("x"))
                assertFalse(initial.has("target") || initial.has("warmup") || initial.has("hold"))
                val moving = assertNotNull(unit(samples.last { it.getString("phase") == "step" }).get("mechPose"), format)
                assertEquals(2.05, moving.get("legs").get(0).getDouble("height"), 1e-12)
                assertEquals(112.8, moving.get("legs").get(0).get("foot").getDouble("x"), 1e-12)
                assertEquals("SWINGING", moving.get("legs").get(0).getString("phase"))
                assertFalse(moving.get("legs").get(0).has("target"))
            }
            val root = fixtures.resolve("mech-production/ini").toAbsolutePath().normalize()
            val loaded = SandboxLoader().use { loader -> loader.request(root); await(loader) }
            val output = temporary.resolve("plain")
            JsonlEvidence(output, "plain", root).use { sink ->
                sink.recordLoadReady(1L, loaded)
                SandboxSession(loaded.scenario, sink).use { it.tick() }
            }
            val samples = Files.readAllLines(output.resolve("reforge/client-diagnostic.jsonl"))
                .filter(String::isNotBlank).map { JsonReader().parse(it) }.filter { it.getString("kind") == "observation" }
            assertTrue(samples.isNotEmpty())
            assertTrue(samples.all { row -> row.get("sample").get("units").toList().none { it.has("mechPose") } })
        } finally {
            val paths = Files.walk(temporary).use { it.sorted(Comparator.reverseOrder()).toList() }
            for (path in paths) { require(path.normalize().startsWith(temporary)); Files.delete(path) }
        }
    }

    private fun await(loader: SandboxLoader): LoadedSandbox {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return it.getOrThrow() }
            Thread.sleep(5)
        }
        error("Real mech sandbox load exceeded five seconds")
    }
}
