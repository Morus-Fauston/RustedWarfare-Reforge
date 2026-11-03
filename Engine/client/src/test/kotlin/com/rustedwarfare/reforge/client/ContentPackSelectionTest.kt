package com.rustedwarfare.reforge.client

import java.nio.file.Path
import com.rustedwarfare.reforge.core.content.ContentLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContentPackSelectionTest {
    @Test
    fun a_cancelled_check_cannot_publish_its_late_result_over_a_new_package() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        ContentPackSelectionSession { path ->
            if (first.getAndSet(false)) {
                entered.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)) }
                catch (_: InterruptedException) { check(release.await(5, TimeUnit.SECONDS)) }
            }
            ContentLoader.loadContentPack(path)
        }.use { state ->
            try {
                state.edit(fixtures.resolve("content-packs/amber-scouts-ini")); state.checkPackage()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                state.cancel()
                assertFalse(state.busy)
                assertNull(state.checked)
                assertFalse(state.poll())
                state.edit(fixtures.resolve("content-packs/amber-scouts-hjson")); state.checkPackage()
                release.countDown()
                await(state)
                assertEquals("amber.scouts.hjson", assertNotNull(state.checked).id)
                assertFalse(state.enabled)
                assertNull(state.error)
                assertFalse(state.poll())
            } finally { release.countDown() }
        }
    }

    @Test
    fun checking_does_not_enable_a_pack_and_failed_edits_cannot_silently_change_the_next_match() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val path = fixtures.resolve("content-packs/amber-scouts-json")
        ContentPackSelectionSession().use { state ->
            assertTrue(state.canStart)
            assertNull(state.choice())
            state.edit(path)
            state.checkPackage()
            assertTrue(state.busy)
            assertFalse(state.canStart)
            await(state)
            assertNull(state.error)
            assertFalse(state.enabled)
            assertEquals(setOf("amber_scout", "amber_guard"), assertNotNull(state.checked).units.keys)
            assertNull(state.choice())
            state.enable(true)
            val first = assertNotNull(state.choice())
            assertEquals("amber_scout", first.unitId)
            val launch = SandboxLaunchState(SandboxConfiguration(fixtures.resolve("three-format-tanks/ini"),
                SandboxOptions(), contentPack = first))
            val request = launch.start()
            state.select("amber_guard")
            assertEquals("amber_guard", assertNotNull(state.choice()).unitId)
            assertEquals("amber_scout", first.unitId)
            assertEquals("amber_scout", request.configuration.contentPack!!.unitId)
            assertTrue(launch.complete(request.generation))
            assertFailsWith<IllegalStateException> { launch.configure(launch.configuration.copy(contentPack = state.choice())) }
            launch.exit()
            launch.configure(launch.configuration.copy(contentPack = state.choice()))
            assertEquals("amber_guard", launch.start().configuration.contentPack!!.unitId)
            state.edit(path.resolve("not-a-package"))
            assertFalse(state.canStart)
            state.checkPackage(); await(state)
            assertNotNull(state.error)
            assertFalse(state.canStart)
            assertEquals("amber.scouts.json", assertNotNull(state.checked).id)
            state.enable(false)
            assertTrue(state.canStart)
            assertNull(state.choice())
            state.edit(path); state.checkPackage(); await(state)
            assertNull(state.error)
            assertFalse(state.enabled)
            state.enable(true)
            assertEquals("amber_scout", assertNotNull(state.choice()).unitId)
        }
    }

    private fun await(state: ContentPackSelectionSession) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (state.busy && System.nanoTime() < deadline) {
            state.poll(); Thread.sleep(5)
        }
        check(!state.busy) { "Package check did not finish within five seconds" }
    }
}
