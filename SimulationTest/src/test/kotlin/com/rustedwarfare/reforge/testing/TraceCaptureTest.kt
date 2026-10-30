package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*

class TraceCaptureTest {
    private val repo = Path.of(System.getProperty("repo.root"))
    private val fixture = repo.resolve("SimulationTest/public/scenarios/select-move-stop.json")
    private val contract = repo.resolve("SimulationTest/public/contracts/select-move-stop.json")

    @Test fun `nonfinite collected state cannot become JSON or silently become zero`() {
        val input = ScenarioReader.load(fixture, contract)
        val output = Files.createTempDirectory("simulation-invalid-capture-").resolve("run")
        CoreAdapter(input).use { core ->
            val initial = core.initial()
            val invalid = Observation(initial.sampleId, initial.worldId, initial.scenarioId, initial.stepIndex,
                initial.simulationTime, listOf(initial.units.single().copy(speed = Double.NaN)), emptyList(), emptyList())
            RunArchive(output).use { archive ->
                assertFailsWith<CaptureError> { archive.observation(invalid, "initial", input) }
            }
        }
        assertEquals("", output.resolve("reforge/raw.jsonl").readText())
        assertEquals("", output.resolve("reforge/normalized.jsonl").readText())
    }

    @Test fun `controller failure retains the command error and incomplete footer`() {
        val output = Files.createTempDirectory("simulation-controller-failure-").resolve("run")
        val result = Runner.run(RunRequest(fixture, contract, output,
            listOf("controller-failure"), cancelled = { throw java.io.IOException("controller input unavailable") }))
        assertEquals(4, result.exitCode)
        val manifest = readObject(output.resolve("manifest.json"))
        assertEquals("execution-failed", manifest.text("status"))
        assertTrue(manifest.text("error").contains("controller input unavailable"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), manifest["complete"])
        assertTrue(output.resolve("logs/execution.json").readText().contains("controller-failure"))
    }
}
