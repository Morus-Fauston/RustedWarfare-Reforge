package com.rustedwarfare.reforge.pc

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.CancellationException
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import com.badlogic.gdx.utils.JsonReader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class VisualAssetCacheTest {
    @Test fun another_jvm_holding_the_profile_lock_is_busy_and_releases_cleanly() {
        VisualAssetTestFixture().use { fixture ->
            val cache = Files.createDirectories(fixture.cache.resolve(fixture.profile.key))
            val source = fixture.settings.resolveSibling("VisualLockHolder.java")
            Files.writeString(source, """
                import java.nio.channels.FileChannel;
                import java.nio.file.*;
                class VisualLockHolder {
                    public static void main(String[] args) throws Exception {
                        try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                             var lock = channel.lock()) {
                            System.out.println("LOCKED"); System.out.flush(); System.in.read();
                        }
                    }
                }
            """.trimIndent())
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                source.toString(), cache.resolve("prepare.lock").toString()).redirectErrorStream(true).start()
            try {
                assertEquals("LOCKED", CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }.get(15, TimeUnit.SECONDS))
                val failure = assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
                assertTrue(failure.message.orEmpty().contains("CACHE_BUSY"))
                assertTrue(Files.notExists(cache.resolve("ready.json")))
            } finally {
                process.outputStream.close()
                if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
            }
            assertEquals(0, process.exitValue())
            fixture.service().prepare(fixture.root).verify()
        }
    }
    @Test fun failed_ready_pointer_publish_keeps_the_pointer_and_never_reuses_an_orphan_generation() {
        VisualAssetTestFixture().use { fixture ->
            val old = fixture.service().prepare(fixture.root)
            val cache = fixture.cache.resolve(fixture.profile.key)
            val pointer = Files.readAllBytes(cache.resolve("ready.json"))
            Files.write(old.outputs.getValue("body"), byteArrayOf(42))
            var orphan: Path? = null
            val service = PcVisualAssetService(fixture.cache, fixture.profile, atomicMove = { source, target, replace ->
                if (replace) throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "own pointer failure fixture")
                moveVisualAtomically(source, target, false); orphan = target
            })
            val failure = assertFailsWith<IllegalArgumentException> { service.prepare(fixture.root) }
            assertTrue(failure.message.orEmpty().contains("PREPARE_FAILED"))
            assertContentEquals(pointer, Files.readAllBytes(cache.resolve("ready.json")))
            assertTrue(Files.isDirectory(requireNotNull(orphan)))
            val next = fixture.service().prepare(fixture.root)
            assertTrue(next.generation != orphan && next.generation != old.generation)
            next.verify()
        }
    }
    @Test fun cancellation_after_generation_move_does_not_publish_the_ready_pointer() {
        VisualAssetTestFixture().use { fixture ->
            val service = PcVisualAssetService(fixture.cache, fixture.profile, atomicMove = { source, target, replace ->
                moveVisualAtomically(source, target, replace)
                if (!replace) Thread.currentThread().interrupt()
            })
            try {
                assertFailsWith<CancellationException> { service.prepare(fixture.root) }
                assertTrue(Files.notExists(fixture.cache.resolve(fixture.profile.key).resolve("ready.json")))
            } finally { Thread.interrupted() }
            fixture.service().prepare(fixture.root).verify()
        }
    }
    @Test fun an_unwritable_cache_location_is_an_error_and_does_not_change_source_or_cache_file() {
        VisualAssetTestFixture().use { fixture ->
            val source = Files.readAllBytes(fixture.source)
            Files.writeString(fixture.cache, "own blocked location")
            val failure = assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
            assertTrue(failure.message.orEmpty().contains("PREPARE_FAILED"))
            assertEquals("own blocked location", Files.readString(fixture.cache))
            assertContentEquals(source, Files.readAllBytes(fixture.source))
        }
    }
    @Test fun missing_fixed_generation_manifest_reports_an_actionable_preparation_error() {
        VisualAssetTestFixture().use { fixture ->
            val prepared = fixture.service().prepare(fixture.root)
            Files.delete(prepared.generation.resolve("manifest.json"))
            val failure = assertFailsWith<IllegalArgumentException> { prepared.verify() }
            assertTrue(failure.message.orEmpty().contains("PREPARE_FAILED"))
            fixture.service().prepare(fixture.root).verify()
        }
    }
    @Test fun a_running_match_rejects_changes_to_its_fixed_generation_manifest() {
        VisualAssetTestFixture().use { fixture ->
            val prepared = fixture.service().prepare(fixture.root)
            Files.writeString(prepared.generation.resolve("manifest.json"), "damaged metadata")
            assertFailsWith<IllegalArgumentException> { prepared.verify() }
            val repaired = fixture.service().prepare(fixture.root)
            assertTrue(repaired.generation != prepared.generation)
            repaired.verify()
        }
    }
    @Test fun completed_manifest_binds_source_recipe_and_every_output_dimension_length_and_hash() {
        VisualAssetTestFixture().use { fixture ->
            val prepared = fixture.service().prepare(fixture.root)
            val manifest = JsonReader().parse(Files.readString(prepared.generation.resolve("manifest.json")))
            assertEquals(1, manifest.getInt("schemaVersion"))
            assertEquals(fixture.profile.key, manifest.getString("profile"))
            assertTrue(manifest.getBoolean("complete"))
            assertEquals("own.png", manifest.get("sources").get(0).getString("path"))
            val output = manifest.get("outputs").get("body")
            assertEquals(2, output.getInt("width"))
            assertEquals(1, output.getInt("height"))
            assertEquals(Files.size(prepared.outputs.getValue("body")), output.getLong("bytes"))
            assertEquals(visualHash(Files.readAllBytes(prepared.outputs.getValue("body"))), output.getString("sha256"))
        }
    }
    @Test fun source_changed_during_preparation_keeps_the_previous_ready_pointer() {
        VisualAssetTestFixture().use { fixture ->
            val old = fixture.service().prepare(fixture.root)
            val ready = fixture.cache.resolve(fixture.profile.key).resolve("ready.json")
            val pointer = Files.readAllBytes(ready)
            Files.write(old.outputs.getValue("body"), byteArrayOf(42))
            val service = PcVisualAssetService(fixture.cache, fixture.profile, atomicMove = { source, target, replace ->
                moveVisualAtomically(source, target, replace)
                if (!replace) Files.write(fixture.source, byteArrayOf(7))
            })
            val failure = assertFailsWith<IllegalArgumentException> { service.prepare(fixture.root) }
            assertTrue(failure.message.orEmpty().contains("SOURCE_ASSET_MISMATCH"))
            assertContentEquals(pointer, Files.readAllBytes(ready))
        }
    }
    @Test fun unsupported_atomic_publish_fails_without_making_incomplete_output_ready() {
        VisualAssetTestFixture().use { fixture ->
            val service = PcVisualAssetService(fixture.cache, fixture.profile, atomicMove = { source, target, _ ->
                throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "self-owned failure fixture")
            })
            val failure = assertFailsWith<IllegalArgumentException> { service.prepare(fixture.root) }
            assertTrue(failure.message.orEmpty().contains("PREPARE_FAILED"))
            assertTrue(Files.notExists(fixture.cache.resolve(fixture.profile.key).resolve("ready.json")))
            // The next normal request can prepare; the unreferenced staging directory is never a cache hit.
            fixture.service().prepare(fixture.root).verify()
        }
    }
    @Test fun cancelled_request_never_publishes_a_ready_generation() {
        VisualAssetTestFixture().use { fixture ->
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<CancellationException> { fixture.service().prepare(fixture.root) }
                assertTrue(Files.notExists(fixture.cache.resolve(fixture.profile.key).resolve("ready.json")))
            } finally { Thread.interrupted() }
        }
    }
    @Test fun occupied_profile_is_reported_without_waiting_or_publishing() {
        VisualAssetTestFixture().use { fixture ->
            val cache = Files.createDirectories(fixture.cache.resolve(fixture.profile.key))
            FileChannel.open(cache.resolve("prepare.lock"), CREATE, WRITE).use { channel ->
                channel.lock().use {
                    val failure = assertFailsWith<IllegalArgumentException> { fixture.service().prepare(fixture.root) }
                    assertTrue(failure.message.orEmpty().contains("CACHE_BUSY"))
                    assertTrue(Files.notExists(cache.resolve("ready.json")))
                }
            }
        }
    }
}
