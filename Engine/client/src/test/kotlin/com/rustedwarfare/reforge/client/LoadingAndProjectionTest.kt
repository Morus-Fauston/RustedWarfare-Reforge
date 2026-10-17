package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.Scenario
import java.nio.file.Path
import kotlin.test.*

class LoadingAndProjectionTest {
    private val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir")))
    private fun await(loader: SandboxLoader): Result<Scenario> {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) { loader.poll()?.let { return it }; Thread.sleep(5) }
        error("Loader did not complete within 5 seconds")
    }

    @Test fun `failed load identifies the file and a subsequent valid request succeeds`() {
        SandboxLoader().use { loader ->
            loader.request(root.resolve("missing"))
            val failure = assertNotNull(await(loader).exceptionOrNull())
            assertTrue(failure.message!!.contains("p001-controlled.tmx"))
            assertTrue(failure.message!!.contains("BAD_REFERENCE"))
            loader.request(root)
            val loaded = await(loader).getOrThrow()
            assertEquals(33, loaded.map.blocking.blockedCount)
            assertEquals(Position(110.0, 110.0), loaded.units.first().position)
        }
    }

    @Test fun `cancelled request never publishes and a replacement starts from the fixture`() {
        SandboxLoader().use { loader ->
            loader.request(root)
            loader.cancel()
            assertNull(loader.poll())
            loader.request(root)
            assertEquals("p001-windows", await(loader).getOrThrow().scenarioId)
            assertNull(loader.poll())
        }
    }

    @Test fun `map coordinates have a top left origin and clicks outside the map are rejected`() {
        val map = MapProjection(600.0, 400.0)
        assertEquals(Position(0.0, 0.0), map.toWorld(24.0, 500.0))
        assertEquals(Position(110.0, 110.0), map.toWorld(134.0, 390.0))
        assertEquals(134f, map.x(110.0))
        assertEquals(390f, map.y(110.0))
        assertNull(map.toWorld(23.0, 500.0))
        assertNull(map.toWorld(624.0, 500.0))
        assertNull(map.toWorld(24.0, 100.0))
        assertNull(map.toWorld(Double.NaN, 200.0))
    }

    @Test fun `changed map aspect ratios preserve scale and center the entire map`() {
        val map = MapProjection(300.0, 400.0)
        assertEquals(Position(0.0, 0.0), map.toWorld(174.0, 500.0))
        assertNull(map.toWorld(100.0, 300.0))
    }
}
