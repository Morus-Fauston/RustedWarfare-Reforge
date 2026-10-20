package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Files
import java.nio.file.Path
import java.awt.geom.Line2D
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
        // The old centres clear the wall, but a radius-11 circle extends outside the map.
        val points = listOf(Position(10.0, 10.0), Position(30.0, 10.0), Position(50.0, 10.0))
        val blocked = listOf(intArrayOf(3, 3))
        assertFalse(PathPerfBatch.independentGeometry(points, blocked, 32, 24, 20, 20, 11.0))
        // Tangency to the map is allowed; every segment remains at least 49 from the wall.
        val tangent = listOf(Position(11.0, 11.0), Position(31.0, 11.0), Position(51.0, 11.0))
        assertTrue(PathPerfBatch.independentGeometry(tangent, blocked, 32, 24, 20, 20, 11.0))
    }

    @Test
    fun `independent geometry accepts a clear long diagonal segment`() {
        val points = listOf(Position(30.0, 30.0), Position(70.0, 50.0))
        assertTrue(PathPerfBatch.independentGeometry(points, emptyList(), 5, 5, 20, 20, 1.0))
    }

    @Test
    fun `independent geometry rejects collision between two clear endpoints`() {
        // Both endpoints are distance 10 from the rectangle; the segment crosses its interior.
        val points = listOf(Position(30.0, 50.0), Position(70.0, 50.0))
        assertFalse(PathPerfBatch.independentGeometry(points, listOf(intArrayOf(2, 2)), 5, 5, 20, 20, 1.0))
    }

    @Test
    fun `open direct batch records one attempt and zero A star work`() {
        val batch = PathPerfBatch.measureBatch("OPEN_NOFOG", 1, 0, testUnit())
        val sample = batch.samples.single()
        assertEquals(1L, sample.actualSearchCalls)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L), listOf(
            sample.candidatesChecked, sample.popped, sample.expanded, sample.enqueued,
            sample.neighborsChecked, sample.heuristicCalls,
        ))
        assertEquals(listOf(Position(410.0, 330.0)), batch.units.single().pathPoints)
    }

    @Test
    fun `independent geometry rejects actual overlap outside and empty paths`() {
        val blocked = listOf(intArrayOf(1, 1))
        // The first point is inside the blocked rectangle; off-centre points themselves are valid.
        assertFalse(
            PathPerfBatch.independentGeometry(
                listOf(Position(30.0, 30.0), Position(51.0, 30.0)), blocked, 32, 24, 20, 20, 11.0,
            ),
        )
        // D-076 permits clear long diagonal segments.
        assertTrue(
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
        assertEquals(0L, sample.candidatesChecked)
        assertEquals(listOf(Position(410.0, 330.0)), batch.units.single().pathPoints)
        assertTrue(batch.submitBatchNanos >= 0L)
        assertTrue(batch.advanceNanos >= 0L)
    }

    @Test
    fun `measure batch unknown wall plans flat and may cross the truth wall`() {
        val batch = PathPerfBatch.measureBatch("UNKNOWN_WALL", 1, 0, testUnit())
        val sample = batch.samples.single()
        assertTrue(sample.usedPlayerKnowledge)
        assertEquals(PathSearchOutcome.FOUND, sample.outcome)
        assertTrue(crossesWallBand(listOf(start(0)) + batch.units.single().pathPoints))
    }

    @Test
    fun `measure batch memory gap crosses the whole wall band with circle clearance`() {
        val batch = PathPerfBatch.measureBatch("MEMORY_GAP", 1, 0, testUnit())
        assertEquals(PathSearchOutcome.FOUND, batch.samples.single().outcome)
        assertTrue(batch.samples.single().usedPlayerKnowledge)
        val points = listOf(start(0)) + batch.units.single().pathPoints
        assertTrue(crossesWallBand(points))
        assertTrue(PathPerfBatch.independentGeometry(points, gapBlocks(), 32, 24, 20, 20, 11.0))
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
        assertTrue(crossesWallBand(listOf(start(0)) + batch.units.single().pathPoints))
    }

    @Test
    fun `independent geometry follows the frozen closed segment boundary table`() {
        data class Case(val points: List<Position>, val blocks: List<IntArray>, val radius: Double,
                        val expected: Boolean, val tileHeight: Int = 20)
        fun p(vararg coordinates: Double) = coordinates.toList().chunked(2).map { Position(it[0], it[1]) }
        val block22 = listOf(intArrayOf(2, 2))
        val cases = listOf(
            Case(p(31.0, 29.0, 72.0, 73.0), emptyList(), 1.0, true),
            Case(p(70.0, 50.0), block22, 1.0, true),
            Case(p(30.0, 50.0, 70.0, 50.0), block22, 1.0, false),
            Case(p(30.0, 30.0, 70.0, 30.0, 70.0, 70.0), listOf(intArrayOf(3, 2)), 1.0, false),
            Case(p(30.0, 30.0, 70.0, 30.0, 70.0, 50.0), block22, 1.0, true),
            Case(p(30.0, 50.0, 70.0, 50.0), listOf(intArrayOf(2, 1)), 10.0, false),
            Case(p(30.0, 51.0, 70.0, 51.0), listOf(intArrayOf(2, 1)), 10.0, true),
            Case(p(36.0, 37.0), block22, 5.0, false),
            Case(p(36.0, 37.0), block22, 4.0, true),
            Case(p(39.0, 41.0), listOf(intArrayOf(2, 1)), 1.0, true),
            Case(p(30.0, 50.0, 50.0, 30.0), block22, 0.0, false),
            Case(p(30.0, 40.0, 70.0, 40.0), block22, 0.0, false),
            Case(p(50.0, 50.0), block22, 0.0, false),
            Case(p(30.0, 50.0, 70.0, 50.0), block22, 10.0, false, 30),
            Case(p(0.0, 0.0, 99.0, 99.0), emptyList(), Double.MAX_VALUE, false),
            Case(p(1.0, 1.0, 99.0, 99.0), emptyList(), 1.0, true),
            Case(p(0.99, 1.0, 99.0, 99.0), emptyList(), 1.0, false),
            Case(p(1.0, 0.99, 99.0, 99.0), emptyList(), 1.0, false),
            Case(p(1.0, 1.0, 99.01, 99.0), emptyList(), 1.0, false),
            Case(p(1.0, 1.0, 99.0, 99.01), emptyList(), 1.0, false),
            Case(p(-0.0, 0.0, 100.0, 100.0), emptyList(), 0.0, true),
        )
        for ((index, case) in cases.withIndex()) for (points in listOf(case.points, case.points.reversed())) {
            assertEquals(case.expected,
                PathPerfBatch.independentGeometry(points, case.blocks, 5, 5, 20, case.tileHeight, case.radius),
                "frozen case $index, points=$points")
        }
    }

    @Test
    fun `independent geometry rejects malformed inputs and avoids integer map overflow`() {
        val good = listOf(Position(30.0, 30.0))
        fun check(points: List<Position> = good, blocks: List<IntArray> = emptyList(),
                  width: Int = 5, height: Int = 5, tw: Int = 20, th: Int = 20, radius: Double = 1.0) =
            PathPerfBatch.independentGeometry(points, blocks, width, height, tw, th, radius)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(check(points = listOf(Position(value, 30.0))))
            assertFalse(check(points = listOf(Position(30.0, value))))
            assertFalse(check(radius = value))
        }
        assertFalse(check(radius = -1.0))
        assertFalse(check(width = 0))
        assertFalse(check(height = -1))
        assertFalse(check(tw = 0))
        assertFalse(check(th = -1))
        for (point in listOf(Position(-1.0, 1.0), Position(1.0, -1.0), Position(100.0, 1.0), Position(1.0, 100.0))) {
            assertFalse(check(points = listOf(point)))
        }
        for (block in listOf(intArrayOf(), intArrayOf(1), intArrayOf(-1, 0), intArrayOf(5, 0), intArrayOf(0, 5))) {
            assertFalse(check(blocks = listOf(block)))
        }
        assertTrue(check(width = Int.MAX_VALUE, height = Int.MAX_VALUE, tw = Int.MAX_VALUE, th = Int.MAX_VALUE))
    }

    @Test
    fun `memory gap real batches follow the fixed direct and fallback index table`() {
        val direct = setOf(0, 1, 2, 11, 12, 13, 14, 22, 23, 24, 25, 34, 35, 36, 45, 46, 47, 48)
        for (n in listOf(1, 10, 50)) {
            val batch = PathPerfBatch.measureBatch("MEMORY_GAP", n, 0, testUnit())
            val directSamples = batch.samples.filter { it.candidatesChecked == 0L }
            assertEquals(direct.filter { it < n }.map { "perf-u%03d".format(it) }.toSet(),
                directSamples.map { it.unitId }.toSet())
            assertEquals(when (n) { 1 -> 1; 10 -> 3; else -> 18 }, directSamples.size)
            assertEquals(when (n) { 1 -> 0; 10 -> 7; else -> 32 }, batch.samples.count { it.candidatesChecked == 768L })
            for ((index, outcome) in batch.units.withIndex()) {
                assertEquals(index in direct,
                    PathPerfBatch.independentGeometry(listOf(start(index), goal(index)), gapBlocks(), 32, 24, 20, 20, 11.0),
                    "independent direct index $index")
                assertTrue(crossesWallBand(listOf(start(index)) + outcome.pathPoints))
                assertTrue(PathPerfBatch.independentGeometry(listOf(start(index)) + outcome.pathPoints,
                    gapBlocks(), 32, 24, 20, 20, 11.0))
                assertEquals(PathSearchOutcome.FOUND, batch.samples[index].outcome)
                assertTrue(batch.samples[index].usedPlayerKnowledge)
            }
        }
    }

    @Test
    fun `validator rejects changed owner and unit type against the actual input`() {
        val custom = PathPerfBatch.measureBatch("OPEN_NOFOG", 1, 0, testUnit("own-variant"))
        PathPerfBatch.validateBatch("OPEN_NOFOG", 1, custom, fixedMap("OPEN_NOFOG"), "own-variant")
        val batch = PathPerfBatch.measureBatch("OPEN_NOFOG", 1, 0, testUnit())
        val unit = batch.units.single()
        val mutations = listOf(unit.copy(ownerPlayerId = "p2"), unit.copy(typeId = "other-type"))
        val rejected = mutations.map { changed ->
            runCatching { validate(batch.copy(units = listOf(changed))) }.exceptionOrNull() is PathPerfCorrectnessException
        }
        assertEquals(listOf(true, true), rejected)
    }

    @Test
    fun `validator rejects changed scenario identity dimensions and truth map`() {
        val batch = PathPerfBatch.measureBatch("UNKNOWN_WALL", 1, 0, testUnit())
        val map = fixedMap("UNKNOWN_WALL")
        val changedMaps = listOf(
            fixedMap("OPEN_NOFOG"),
            MapDefinition(31, 24, 20, 20, emptyList(), IntArray(31 * 24), BlockingGrid(31, 24, BooleanArray(31 * 24))),
            MapDefinition(32, 24, 20, 30, emptyList(), IntArray(32 * 24), map.blocking),
        )
        val rejected = changedMaps.map { changed ->
            runCatching { validate(batch, changed) }.exceptionOrNull() is PathPerfCorrectnessException
        } + listOf(
            batch.copy(scenarioKey = "OPEN_NOFOG"), batch.copy(n = 10), batch.copy(round = -1), batch.copy(diagnosticsEnabled = false),
        ).map { changed ->
            runCatching { PathPerfBatch.validateBatch("UNKNOWN_WALL", 1, changed, map, testUnit().id) }
                .exceptionOrNull() is PathPerfCorrectnessException
        }
        assertEquals(List(7) { true }, rejected)
    }

    @Test
    fun `validator rejects wrong direct fallback counters IDs and entire colliding segments`() {
        val batch = PathPerfBatch.measureBatch("MEMORY_GAP", 10, 0, testUnit())
        fun sampleChange(index: Int, value: com.rustedwarfare.reforge.core.path.PathSearchSample) =
            batch.copy(samples = batch.samples.mapIndexed { i, original -> if (i == index) value else original })
        fun pathChange(index: Int, path: List<Position>) = batch.copy(
            units = batch.units.mapIndexed { i, original -> if (i == index) original.copy(pathPoints = path) else original },
            samples = batch.samples.mapIndexed { i, original -> if (i == index) original.copy(pathPointCount = path.size) else original },
        )
        val cases = listOf(
            sampleChange(0, batch.samples[0].copy(candidatesChecked = 768)),
            sampleChange(3, batch.samples[3].copy(candidatesChecked = 0)),
            sampleChange(0, batch.samples[0].copy(expanded = 1)),
            sampleChange(0, batch.samples[0].copy(actionId = "perf-move001")),
            sampleChange(0, batch.samples[0].copy(unitId = "perf-u001")),
            batch.copy(samples = batch.samples.drop(1)),
            batch.copy(samples = batch.samples.dropLast(1) + batch.samples.first()),
            pathChange(0, listOf(Position(70.0, 50.0), goal(0))),
            // A lone goal is geometrically clear; only including fixed start rejects this first segment.
            pathChange(3, listOf(goal(3))),
            // The first segment stays left of the wall; only the last segment crosses its blocked lower half.
            pathChange(3, listOf(Position(110.0, 400.0), goal(3))),
            // Leave the map with the circle while preserving wall clearance and the fixed goal.
            pathChange(3, listOf(Position(0.0, start(3).y), Position(start(3).x + 1.0, start(3).y)) + batch.units[3].pathPoints),
        )
        for ((index, changed) in cases.withIndex()) {
            val error = assertFailsWith<IllegalStateException>("mutation $index") { validate(changed) }
            if (index >= 8) assertTrue(error.message.orEmpty().contains("independent geometry"), "mutation $index must reach full segment geometry")
        }
        assertTrue(PathPerfBatch.independentGeometry(listOf(goal(3)), gapBlocks(), 32, 24, 20, 20, 11.0))
    }

    @Test
    fun `validator requires exact target and diagnostic path count including unreachable`() {
        val found = PathPerfBatch.measureBatch("OPEN_NOFOG", 1, 0, testUnit())
        val unreachable = PathPerfBatch.measureBatch("MEMORY_UNREACHABLE", 1, 0, testUnit())
        val mutations = listOf(
            found.copy(units = listOf(found.units.single().copy(moveTarget = null))),
            found.copy(samples = listOf(found.samples.single().copy(pathPointCount = 99))),
            unreachable.copy(units = listOf(unreachable.units.single().copy(moveTarget = goal(0)))),
            found.copy(acceptedMoves = 0),
        )
        assertEquals(List(4) { true }, mutations.map { changed ->
            runCatching { validate(changed) }.exceptionOrNull() is PathPerfCorrectnessException
        })
    }

    @Test
    fun `validator preserves step result counter GC heap and identity failure gates`() {
        val batch = PathPerfBatch.measureBatch("OPEN_NOFOG", 10, 0, testUnit())
        val mutations = listOf(
            batch.copy(stepIndex = 1),
            batch.copy(simulationTime = 2.0),
            batch.copy(actionResults = batch.actionResults.drop(1)),
            batch.copy(actionResults = batch.actionResults.dropLast(1) + batch.actionResults.first()),
            batch.copy(actionResults = batch.actionResults.map { it.copy(accepted = false, errorCode = "not-owner") }),
            batch.copy(requestCount = 9), batch.copy(actualSearchCalls = 9), batch.copy(reuseCount = 1), batch.copy(replanCount = 1),
            batch.copy(gcAvailable = false, gcCollectionCountDelta = 0, gcCollectionTimeMillisDelta = 0),
            batch.copy(gcAvailable = true, gcCollectionCountDelta = -1),
            batch.copy(heapUsedAfterBytes = -1),
            batch.copy(units = batch.units.dropLast(1)),
            batch.copy(units = batch.units.dropLast(1) + batch.units.first()),
            batch.copy(samples = batch.samples.map { it.copy(scheduledStep = 1) }),
            batch.copy(samples = batch.samples.map { it.copy(usedPlayerKnowledge = true) }),
            batch.copy(samples = batch.samples.map { it.copy(actualSearchCalls = 0) }),
            batch.copy(samples = batch.samples.map { it.copy(outcome = PathSearchOutcome.FAILED) }),
        )
        mutations.forEachIndexed { index, changed ->
            assertFailsWith<IllegalStateException>("preserved gate $index") { validate(changed) }
        }
        val unreachable = PathPerfBatch.measureBatch("MEMORY_UNREACHABLE", 1, 0, testUnit())
        assertFailsWith<PathPerfCorrectnessException> { validate(unreachable.copy(events = emptyList())) }
    }

    @Test
    fun `environment records the actual algorithm validator contract and JDK geometry identity`() {
        val environment = Json.parseToJsonElement(PathPerfBatch.buildEnvironment("a".repeat(64), testUnit(), Path.of("."))).jsonObject
        assertEquals("natural-direct-then-four-neighbour-grid.v1", environment["pathAlgorithm"]?.jsonPrimitive?.content)
        assertEquals("fixed-fixture-full-segment-jdk-line2d.v3", environment["pathValidator"]?.jsonPrimitive?.content)
        assertEquals("6d9bb3b98a4dff9ba056ecba0d3589bf7191a9134f7be967dc548cc1dd7b9dd0",
            environment["validatorContractSha256"]?.jsonPrimitive?.content)
        assertEquals(true, environment["javaDesktopModulePresent"]?.jsonPrimitive?.boolean)
        assertEquals("java.awt.geom.Line2D", environment["independentGeometryApi"]?.jsonPrimitive?.content)
        assertEquals("a".repeat(64), environment["unitSha256"]!!.jsonPrimitive.content)
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
        for (scenario in PathPerfBatch.SCENARIOS) for (n in listOf(1, 10, 50)) {
            val on = PathPerfBatch.measureBatch(scenario, n, 0, testUnit(), diagnosticsEnabled = true)
            val off = PathPerfBatch.measureBatch(scenario, n, 0, testUnit(), diagnosticsEnabled = false)
            assertTrue(PathPerfBatch.sameGameplayState(on, off), "scenario $scenario n=$n")
            assertTrue(off.samples.isEmpty())
            assertEquals(0L, off.actualSearchCalls)
            assertEquals(n.toLong(), on.actualSearchCalls)
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

    private fun start(index: Int) = Position(50.0 + 20 * (index % 10), 50.0 + 20 * (index / 10))

    private fun validate(batch: PathPerfBatch.PathPerfBatchResult, map: MapDefinition = fixedMap(batch.scenarioKey)) =
        PathPerfBatch.validateBatch(batch.scenarioKey, batch.n, batch, map, testUnit().id)

    private fun goal(index: Int) = Position(410.0 + 20 * (index % 10), 330.0 + 20 * (index / 10))

    private fun gapBlocks() = (0 until 24).filter { it !in 10..14 }.map { intArrayOf(16, it) }

    private fun crossesWallBand(points: List<Position>): Boolean = listOf(320.0, 340.0).all { x ->
        points.zipWithNext().any { (a, b) -> Line2D.linesIntersect(a.x, a.y, b.x, b.y, x, 0.0, x, 480.0) }
    }

    private fun fixedMap(scenario: String): MapDefinition {
        val blocked = BooleanArray(32 * 24) { index ->
            index % 32 == 16 && when (scenario) {
                "OPEN_NOFOG" -> false
                "MEMORY_UNREACHABLE" -> true
                else -> index / 32 !in 10..14
            }
        }
        return MapDefinition(32, 24, 20, 20, emptyList(), IntArray(32 * 24), BlockingGrid(32, 24, blocked))
    }

    private fun testUnit(id: String = "tank"): UnitDefinition = UnitDefinition(
        id,
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
