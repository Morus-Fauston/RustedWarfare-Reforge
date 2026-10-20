package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.testing.p002.P002KnowledgeSuite
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrefixDiagnosticSerializationTest {
    @Test
    fun `real path search preserves diagnostics through public batch JSONL`() {
        val batch = PathPerfBatch.measureBatch("OPEN_NOFOG", 1, 0, testUnit())
        assertEquals(1, batch.samples.size)
        assertTrue(batch.samples.single().actualSearchCalls > 0)
        assertFields(sampleLine(batch), Position(50.0, 50.0), Position(410.0, 330.0),
            0, PathSearchReason.USER_MOVE, 0L)
    }

    @Test
    fun `real knowledge replan preserves diagnostics through public sample JSON`() {
        val batch = ReplanPerf.measure(testUnit(), 1, TerrainRetention.MEMORY, true)
        val initial = batch.steps.single { it.step == 0 }.samples.single()
        val replan = batch.steps.single { it.step == 2 }.samples.single()
        assertTrue(initial.actualSearchCalls > 0)
        assertTrue(replan.actualSearchCalls > 0)
        assertFields(ReplanPerf.sampleJson(initial), Position(50.0, 150.0), Position(550.0, 150.0),
            0, PathSearchReason.USER_MOVE, 0L)
        assertFields(ReplanPerf.sampleJson(replan), assertNotNull(replan.searchStart), Position(550.0, 150.0),
            replan.preservedPrefixPointCount, PathSearchReason.KNOWLEDGE_REPLAN, 2L)
    }

    @Test
    fun `constructed supplemental prefix and null fields survive both public serializers`() {
        val batch = PathPerfBatch.measureBatch("OPEN_NOFOG", 1, 0, testUnit())
        val constructed = batch.samples.single().copy(
            searchStart = Position(30.25, 50.5), searchTarget = Position(70.75, 60.125),
            preservedPrefixPointCount = 3, reason = PathSearchReason.ROUTE_REPAIR, executionStep = 17L)
        for (value in listOf(ReplanPerf.sampleJson(constructed), sampleLine(batch.copy(samples = listOf(constructed))))) {
            assertFields(value, Position(30.25, 50.5), Position(70.75, 60.125), 3, PathSearchReason.ROUTE_REPAIR, 17L)
        }
        val nullable = constructed.copy(searchStart = null, searchTarget = null,
            executionStep = null, preservedPrefixPointCount = 0)
        for (value in listOf(ReplanPerf.sampleJson(nullable), sampleLine(batch.copy(samples = listOf(nullable))))) {
            assertEquals(JsonNull, value.getValue("searchStart"))
            assertEquals(JsonNull, value.getValue("searchTarget"))
            assertEquals(JsonNull, value.getValue("executionStep"))
            assertNumber(value, "preservedPrefixPointCount")
            assertEquals(0, value.getValue("preservedPrefixPointCount").jsonPrimitive.int)
            assertReason(value, PathSearchReason.ROUTE_REPAIR)
        }
    }

    @Test
    fun `legacy P002 suite rejects retained oracle but emits full diagnostics`() {
        val root = Path.of(System.getProperty("repo.root"))
        val temp = Files.createTempDirectory("prefix-diagnostic-p002-")
        val result = P002KnowledgeSuite.run(root, "TestLab/suites/P002-knowledge-host.v1.json",
            temp, listOf("PrefixDiagnosticSerializationTest"))
        println("prefix-diagnostic-p002-output=${result.output}")
        // The frozen legacy path-point oracle rejects the current natural direct paths.
        assertEquals(2, result.exitCode, "Preserved legacy rejection archive: ${result.output}")
        fun search(name: String): JsonObject = Files.readAllLines(result.output.resolve("K07-replan/$name.jsonl"))
            .map { Json.parseToJsonElement(it).jsonObject }
            .single { it.getValue("label").jsonPrimitive.content == "advance:0" }
            .getValue("searches").jsonArray.single().jsonObject
        val raw = search("raw")
        val normalized = search("normalized")
        for (value in listOf(raw, normalized)) {
            assertNumber(value, "actualSearchCalls")
            assertTrue(value.getValue("actualSearchCalls").jsonPrimitive.long > 0)
            assertFields(value, Position(30.0, 50.0), Position(70.0, 50.0), 0, PathSearchReason.KNOWLEDGE_REPLAN, 0L)
        }
        for (field in listOf("searchStart", "searchTarget", "preservedPrefixPointCount", "reason", "executionStep")) {
            assertEquals(raw.getValue(field), normalized.getValue(field), field)
        }
    }

    private fun sampleLine(batch: PathPerfBatch.PathPerfBatchResult): JsonObject = PathPerfBatch.serializeBatch(batch)
        .map { Json.parseToJsonElement(it).jsonObject }
        .single { it.getValue("kind").jsonPrimitive.content == "sample" }

    private fun assertFields(value: JsonObject, start: Position, target: Position, prefix: Int,
                             reason: PathSearchReason, step: Long) {
        assertPosition(value.getValue("searchStart").jsonObject, start)
        assertPosition(value.getValue("searchTarget").jsonObject, target)
        assertNumber(value, "preservedPrefixPointCount")
        assertEquals(prefix, value.getValue("preservedPrefixPointCount").jsonPrimitive.int)
        assertNumber(value, "executionStep")
        assertEquals(step, value.getValue("executionStep").jsonPrimitive.long)
        assertReason(value, reason)
    }

    private fun assertPosition(value: JsonObject, expected: Position) {
        assertNumber(value, "x")
        assertNumber(value, "y")
        assertEquals(expected.x, value.getValue("x").jsonPrimitive.double)
        assertEquals(expected.y, value.getValue("y").jsonPrimitive.double)
    }

    private fun assertNumber(value: JsonObject, key: String) {
        val primitive = value.getValue(key).jsonPrimitive
        assertTrue(primitive is JsonPrimitive && primitive != JsonNull, key)
        assertFalse(primitive.isString, key)
    }

    private fun assertReason(value: JsonObject, reason: PathSearchReason) {
        val primitive = value.getValue("reason").jsonPrimitive
        assertTrue(primitive.isString)
        assertEquals(reason.name, primitive.content)
    }

    private fun testUnit() = UnitDefinition("tank", "tank", 11.0, 11.0, 1,
        Path.of("assets/units/tanks/tank.png"), 1.0,
        MovementProfile(MovementType.LAND, 1.1, 0.05, 0.02, 3.0, 0.01, false, false), emptySet())
}
