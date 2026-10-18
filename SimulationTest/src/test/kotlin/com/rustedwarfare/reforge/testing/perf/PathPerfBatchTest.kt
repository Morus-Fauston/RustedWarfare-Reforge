package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathPerfBatchTest {
    @Test
    fun `nearest rank uses sorted ceil index`() {
        assertEquals(1L, PathPerfBatch.nearestRank(listOf(1L), 0.5))
        // sorted [1,2,3]; ceil(0.5*3)=2 -> index 1 -> 2.
        assertEquals(2L, PathPerfBatch.nearestRank(listOf(3L, 1L, 2L), 0.5))
        assertEquals(3L, PathPerfBatch.nearestRank(listOf(3L, 1L, 2L), 1.0))
        assertEquals(1L, PathPerfBatch.nearestRank(listOf(5L, 1L, 3L, 2L, 4L), 0.2))
    }

    @Test
    fun `nearest rank rejects empty and bad q without mutating caller list`() {
        assertFailsWith<IllegalArgumentException> { PathPerfBatch.nearestRank(emptyList(), 0.5) }
        assertFailsWith<IllegalArgumentException> { PathPerfBatch.nearestRank(listOf(1L), 0.0) }
        assertFailsWith<IllegalArgumentException> { PathPerfBatch.nearestRank(listOf(1L), 1.5) }
        assertFailsWith<IllegalArgumentException> { PathPerfBatch.nearestRank(listOf(1L), Double.NaN) }
        val values = mutableListOf(3L, 1L, 2L)
        PathPerfBatch.nearestRank(values, 0.5)
        assertEquals(listOf(3L, 1L, 2L), values)
    }

    @Test
    fun `independent geometry accepts hand-built legal path clear of blocked rectangle`() {
        // tile 20; blocked cell (3,3) = rect x 60..80, y 60..80.
        // Path centres (10,10) -> (30,10) -> (50,10): nearest-point distances 70.7 / 51.0 / 50.99 > 11.
        val points = listOf(Position(10.0, 10.0), Position(30.0, 10.0), Position(50.0, 10.0))
        val blocked = listOf(intArrayOf(3, 3))
        assertTrue(PathPerfBatch.independentGeometry(points, blocked, 32, 24, 20, 20, 11.0))
    }

    @Test
    fun `independent geometry rejects non-centre non-neighbour and circle overlap paths`() {
        val blocked = listOf(intArrayOf(1, 1))
        // Off-centre point.
        assertFalse(
            PathPerfBatch.independentGeometry(
                listOf(Position(30.0, 30.0), Position(51.0, 30.0)), blocked, 32, 24, 20, 20, 11.0,
            ),
        )
        // Not a 4-neighbour (diagonal jump).
        assertFalse(
            PathPerfBatch.independentGeometry(
                listOf(Position(30.0, 30.0), Position(70.0, 50.0)), emptyList(), 32, 24, 20, 20, 11.0,
            ),
        )
        // Second point (30,10) is distance 10 from rect x 20..40, y 20..40 -> 10 < radius 11.
        assertFalse(
            PathPerfBatch.independentGeometry(
                listOf(Position(10.0, 10.0), Position(30.0, 10.0)), blocked, 32, 24, 20, 20, 11.0,
            ),
        )
        // True out-of-bounds: widthTiles=1, point (30,10) -> col=1 (outside 0..0).
        assertFalse(
            PathPerfBatch.independentGeometry(listOf(Position(30.0, 10.0)), emptyList(), 1, 1, 20, 20, 11.0),
        )
        // Empty path.
        assertFalse(PathPerfBatch.independentGeometry(emptyList(), emptyList(), 32, 24, 20, 20, 11.0))
    }

    @Test
    fun `measure batch open no fog n1 collects one real search sample`() {
        val batch = PathPerfBatch.measureBatch("OPEN_NOFOG", 1, 0, testUnit())
        val sample = batch.samples.single()
        assertEquals("perf-move000", sample.actionId)
        assertEquals("perf-u000", sample.unitId)
        assertEquals(PathSearchOutcome.FOUND, sample.outcome)
        assertEquals(1L, sample.actualSearchCalls)
        assertFalse(sample.usedPlayerKnowledge)
        assertEquals(768L, sample.candidatesChecked)
        assertTrue(batch.units.single().pathPoints.isNotEmpty())
        assertTrue(batch.submitBatchNanos >= 0L)
        assertTrue(batch.advanceNanos >= 0L)
    }

    @Test
    fun `measure batch unknown wall plans flat and may cross the truth wall`() {
        val batch = PathPerfBatch.measureBatch("UNKNOWN_WALL", 1, 0, testUnit())
        val sample = batch.samples.single()
        assertTrue(sample.usedPlayerKnowledge)
        assertEquals(PathSearchOutcome.FOUND, sample.outcome)
        assertTrue(batch.units.single().pathPoints.any { (it.x / 20).toInt() == 16 })
    }

    @Test
    fun `measure batch memory gap crosses column sixteen on safe rows only`() {
        val batch = PathPerfBatch.measureBatch("MEMORY_GAP", 1, 0, testUnit())
        assertEquals(PathSearchOutcome.FOUND, batch.samples.single().outcome)
        assertTrue(batch.samples.single().usedPlayerKnowledge)
        val crossing = batch.units.single().pathPoints.filter { (it.x / 20).toInt() == 16 }
        assertTrue(crossing.isNotEmpty())
        assertTrue(crossing.all { (it.y / 20).toInt() in 11..13 })
    }

    @Test
    fun `measure batch memory unreachable reports early unreachable with full candidates`() {
        val batch = PathPerfBatch.measureBatch("MEMORY_UNREACHABLE", 1, 0, testUnit())
        val sample = batch.samples.single()
        assertEquals(PathSearchOutcome.UNREACHABLE, sample.outcome)
        assertEquals(1L, sample.actualSearchCalls)
        assertEquals(768L, sample.candidatesChecked)
        assertTrue(sample.expanded > 0)
        assertTrue(batch.units.single().pathPoints.isEmpty())
    }

    @Test
    fun `measure batch hardcore forget plans flat after empty projection`() {
        val batch = PathPerfBatch.measureBatch("HARDCORE_FORGET", 1, 0, testUnit())
        val sample = batch.samples.single()
        assertTrue(sample.usedPlayerKnowledge)
        assertEquals(PathSearchOutcome.FOUND, sample.outcome)
        assertTrue(batch.units.single().pathPoints.any { (it.x / 20).toInt() == 16 })
    }

    @Test
    fun `run rejects invalid inputs with exit code 64`() {
        val output = Files.createTempDirectory("path-perf-run-test-")
        val unit = Files.createTempFile("path-perf-unit-", ".ini")
        val sha = "0".repeat(64)
        val repo = Path.of("relative-repo")
        // Non-absolute repo root.
        assertEquals(64, PathPerfBatch.run(PathPerfConfig(repo, unit, sha, output)).exitCode)
        // Bad hash length.
        assertEquals(64, PathPerfBatch.run(PathPerfConfig(output.toAbsolutePath(), unit, "abc", output)).exitCode)
        // Missing unit file.
        val missing = output.resolve("missing-unit.ini")
        assertEquals(64, PathPerfBatch.run(PathPerfConfig(output.toAbsolutePath(), missing, sha, output)).exitCode)
        // Missing output root.
        val missingOutput = output.resolve("missing-output")
        assertEquals(64, PathPerfBatch.run(PathPerfConfig(output.toAbsolutePath(), unit, sha, missingOutput)).exitCode)
    }

    @Test
    fun `main argument parser accepts exactly one unique key set`() {
        val ok = PathPerfBatchMain.parse(
            arrayOf(
                "--repo-root", "/tmp/repo", "--unit", "/tmp/unit.ini",
                "--unit-sha", "a".repeat(64), "--output", "/tmp/output",
            ),
        )
        assertEquals(ok?.repoRoot, Path.of("/tmp/repo"))
        assertEquals(ok?.unitIni, Path.of("/tmp/unit.ini"))
        assertNull(PathPerfBatchMain.parse(emptyArray()))
        assertNull(PathPerfBatchMain.parse(arrayOf("--repo-root", "/a", "--repo-root", "/b")))
        assertNull(
            PathPerfBatchMain.parse(
                arrayOf("--repo-root", "/tmp/repo", "--unit", "/tmp/unit.ini", "--unit-sha", "x"),
            ),
        )
    }

    @Test
    fun `run control check writes one real pair with full state and sameGameplayState`() {
        val output = Files.createTempDirectory("path-perf-control-")
        assertTrue(PathPerfBatch.runControlCheck(output, "OPEN_NOFOG", 1, testUnit()))
        val file = output.resolve("control").resolve("OPEN_NOFOG-n1.jsonl")
        assertTrue(Files.isRegularFile(file))
        val lines = Files.readAllLines(file)
        val control = Json.parseToJsonElement(lines.first()).jsonObject
        assertEquals("control", control["kind"]!!.jsonPrimitive.content)
        assertEquals(true, control["sameGameplayState"]!!.jsonPrimitive.boolean)
        val units = lines.drop(1).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, units.size)
        assertTrue(units.all { it["kind"]!!.jsonPrimitive.content == "unit" })
        assertTrue(units.all { it.containsKey("position") && it.containsKey("heading") && it.containsKey("speed") })
    }

    @Test
    fun `serialize batch writes per-batch metadata and full unit values as json`() {
        val batch = PathPerfBatch.measureBatch("OPEN_NOFOG", 1, 0, testUnit())
        val lines = PathPerfBatch.serializeBatch(batch)
        assertTrue(lines.size >= 3)
        val meta = Json.parseToJsonElement(lines.first()).jsonObject
        assertEquals("batch", meta["kind"]!!.jsonPrimitive.content)
        assertEquals(1, meta["samples"]!!.jsonPrimitive.int)
        assertEquals(1L, meta["actualSearchCalls"]!!.jsonPrimitive.long)
        assertTrue(meta.containsKey("submitBatchNanos"))
        assertTrue(meta.containsKey("advanceNanos"))
        assertTrue(meta.containsKey("gcCollectionCountDelta"))
        assertTrue(meta.containsKey("gcCollectionTimeMillisDelta"))
        assertTrue(meta.containsKey("heapUsedBeforeBytes"))
        assertTrue(meta.containsKey("heapUsedAfterBytes"))
        assertTrue(meta.containsKey("requestCount"))
        assertTrue(meta.containsKey("acceptedMoves"))
        assertTrue(meta.containsKey("reuseCount"))
        assertTrue(meta.containsKey("replanCount"))
        assertTrue(meta.containsKey("stepIndex"))
        assertTrue(meta.containsKey("simulationTime"))
        assertTrue(meta.containsKey("events"))
        assertTrue(meta.containsKey("actionResults"))
        assertEquals(true, meta["correctnessPass"]!!.jsonPrimitive.boolean)
        val unitLine = Json.parseToJsonElement(lines.last()).jsonObject
        assertEquals("unit", unitLine["kind"]!!.jsonPrimitive.content)
        assertTrue(unitLine.containsKey("ownerPlayerId"))
        assertTrue(unitLine.containsKey("typeId"))
        assertTrue(unitLine.containsKey("heading"))
        assertTrue(unitLine.containsKey("speed"))
        assertTrue(unitLine.containsKey("angularVelocity"))
        assertTrue(unitLine["position"]!!.jsonObject.containsKey("x"))
        val moveTarget = unitLine["moveTarget"]
        assertTrue(
            moveTarget != null && (
                moveTarget is kotlinx.serialization.json.JsonNull ||
                    (moveTarget is kotlinx.serialization.json.JsonObject && moveTarget.containsKey("x"))
                ),
        )
    }

    @Test
    fun `run count guard rejects missing or duplicate planned counts`() {
        PathPerfBatch.verifyRunCounts(15, 120, 2440, 1525, 45)
        assertFailsWith<PathPerfDiagnosticsException> { PathPerfBatch.verifyRunCounts(14, 120, 2440, 1525, 45) }
        assertFailsWith<PathPerfDiagnosticsException> { PathPerfBatch.verifyRunCounts(15, 119, 2440, 1525, 45) }
        assertFailsWith<PathPerfDiagnosticsException> { PathPerfBatch.verifyRunCounts(15, 120, 2439, 1525, 45) }
        assertFailsWith<PathPerfDiagnosticsException> { PathPerfBatch.verifyRunCounts(15, 120, 2440, 1524, 45) }
        assertFailsWith<PathPerfDiagnosticsException> { PathPerfBatch.verifyRunCounts(15, 120, 2440, 1525, 44) }
    }

    @Test
    fun `diagnostics on and off pairs share the same gameplay state`() {
        for (scenario in PathPerfBatch.SCENARIOS) {
            val on = PathPerfBatch.measureBatch(scenario, 1, 0, testUnit(), diagnosticsEnabled = true)
            val off = PathPerfBatch.measureBatch(scenario, 1, 0, testUnit(), diagnosticsEnabled = false)
            assertTrue(PathPerfBatch.sameGameplayState(on, off), "scenario $scenario")
        }
    }

    @Test
    fun `summary fixture yields initial warmup and measured rows with phase percentiles`() {
        val batches = (0..7).map { round ->
            PathPerfBatch.measureBatch("OPEN_NOFOG", 1, round, testUnit())
        }
        val rows = PathPerfBatch.summarize(batches).filter { it.scenarioKey == "OPEN_NOFOG" && it.n == 1 }
        assertEquals(listOf("initial", "warmup", "measured"), rows.map { it.phase })
        val initial = rows.single { it.phase == "initial" }
        val warmup = rows.single { it.phase == "warmup" }
        val measured = rows.single { it.phase == "measured" }
        assertEquals(1, initial.rounds)
        assertEquals(2, warmup.rounds)
        assertEquals(5, measured.rounds)
        assertEquals(1, initial.searchSamples)
        assertEquals(2, warmup.searchSamples)
        assertEquals(5, measured.searchSamples)
        assertEquals(1, initial.batchSamples)
        assertEquals(2, warmup.batchSamples)
        assertEquals(5, measured.batchSamples)
        assertTrue(measured.searchP50Nanos >= 0L && measured.searchP95Nanos >= measured.searchP50Nanos)
    }

    private fun testUnit(): UnitDefinition = UnitDefinition(
        "tank",
        "tank",
        11.0,
        11.0,
        1,
        Path.of("assets/units/tanks/tank.png"),
        1.0,
        MovementProfile(MovementType.LAND, 1.1, 0.05, 0.02, 3.0, 0.01, false, false),
        emptySet(),
    )
}
