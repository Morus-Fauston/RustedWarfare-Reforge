package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class MovementMicroProbeTest {
    @Test
    fun probe_measures_arrivals_gate_throughput_step_latency_allocation_and_contact_sheet() {
        val repoRoot = Path.of(checkNotNull(System.getProperty("repo.root"))).toAbsolutePath().normalize()
        val tempOut = Files.createTempDirectory("movement-micro-probe-test-")
        try {
            val definitions = MovementMicroProbe.loadStandardDefinitions(repoRoot)
            val entry = checkNotNull(com.rustedwarfare.reforge.core.sim.ScenarioCatalog.byId("narrow-bridge-yield"))
            val report = MovementMicroProbe.runSceneProbe(
                entry = entry,
                definitions = definitions,
                outputDir = tempOut.resolve("narrow-bridge-yield"),
                stepsOverride = 260,
                renderSheets = true,
            )
            assertEquals("narrow-bridge-yield", report.getValue("sceneId").jsonPrimitive.content)
            assertEquals(260, report.getValue("steps").jsonPrimitive.int)
            assertEquals(2, report.getValue("totalMobileUnits").jsonPrimitive.int)
            val latency = report.getValue("stepLatencyMs").jsonObject
            assertTrue(latency.getValue("p50").jsonPrimitive.content.toDouble() >= 0.0)
            assertTrue(latency.getValue("p99").jsonPrimitive.content.toDouble() >= latency.getValue("p50").jsonPrimitive.content.toDouble())
            val alloc = report.getValue("heapAllocation").jsonObject
            assertTrue(alloc.getValue("totalSimBytes").jsonPrimitive.long >= 0L)
            val gates = report.getValue("gateThroughput").jsonArray
            assertEquals(2, gates.size)
            val sheetPath = Path.of(assertNotNull(report.getValue("contactSheet").jsonPrimitive.content))
            assertTrue(Files.isRegularFile(sheetPath) && Files.size(sheetPath) > 0L)
        } finally {
            Files.walk(tempOut).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
