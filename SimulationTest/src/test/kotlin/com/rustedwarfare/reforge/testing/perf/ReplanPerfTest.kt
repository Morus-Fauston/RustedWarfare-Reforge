package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.path.PathSearchReason
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class ReplanPerfTest {
    private fun unit() = assertIs<ContentResult.Success<com.rustedwarfare.reforge.core.content.UnitDefinition>>(
        ContentLoader.loadUnitVariant(Path.of(System.getProperty("repo.root"),
            "TestLab/local/P001-移动沙盒输入/units/weaponless_tank.ini")),
    ).value

    @Test
    fun `real discovery replans once while unrelated and repeated knowledge does not`() {
        val batch = ReplanPerf.measure(unit(), 1, TerrainRetention.MEMORY, true)
        assertEquals(listOf(1, 0, 1, 0, 0, 1, 1, 0), batch.steps.map { it.samples.size })
        assertEquals(listOf(PathSearchReason.USER_MOVE, PathSearchReason.KNOWLEDGE_REPLAN,
            PathSearchReason.USER_MOVE, PathSearchReason.KNOWLEDGE_REPLAN),
            batch.steps.flatMap { it.samples }.map { it.reason })
    }

    @Test
    fun `validation rejects altered identity and illegal actual positions`() {
        val batch = ReplanPerf.measure(unit(), 1, TerrainRetention.MEMORY, true)
        fun altered(fields: Map<String, JsonElement>): ReplanBatch {
            val step = batch.steps.first()
            val observation = step.gameplay.getValue("observation").jsonObject
            val original = observation.getValue("units").jsonArray.single().jsonObject
            val changed = JsonObject(observation + ("units" to JsonArray(listOf(JsonObject(original + fields)))))
            return batch.copy(steps = listOf(step.copy(gameplay = JsonObject(step.gameplay + ("observation" to changed)))) + batch.steps.drop(1))
        }
        for ((name, value) in listOf("unitId" to "alias", "ownerPlayerId" to "p2", "typeId" to "other")) {
            assertFailsWith<IllegalStateException>(name) { ReplanPerf.validate(altered(mapOf(name to JsonPrimitive(value)))) }
        }
        for ((x, y) in listOf(-10000.0 to 150.0, 50.0 to 10000.0, 310.0 to 150.0, 30.0 to 30.0, Double.NaN to 150.0)) {
            val position = buildJsonObject { put("x", x); put("y", y) }
            assertFailsWith<IllegalStateException>("position $x,$y") { ReplanPerf.validate(altered(mapOf("position" to position))) }
        }
        val steps = batch.steps.toMutableList()
        steps[0] = steps[0].copy(samples = steps[0].samples.map { it.copy(unitId = "alias") })
        assertFailsWith<IllegalStateException> { ReplanPerf.validate(batch.copy(steps = steps)) }
    }

    @Test
    fun `fifty real owners execute all searches with diagnostic equivalence and bounded state`() {
        for (retention in TerrainRetention.entries) {
            val measured = ReplanPerf.measure(unit(), 50, retention, true)
            val control = ReplanPerf.measure(unit(), 50, retention, false)
            ReplanPerf.validate(measured)
            ReplanPerf.validate(control)
            assertTrue(ReplanPerf.sameGameplay(measured, control))
            assertEquals(200, measured.steps.sumOf { it.samples.size })
            assertEquals(100, measured.steps.flatMap { it.samples }.count { it.reason == PathSearchReason.KNOWLEDGE_REPLAN })
            assertFailsWith<IllegalStateException> { ReplanPerf.validate(measured.copy(steps = measured.steps.dropLast(1))) }
            val wrong = measured.steps.toMutableList()
            wrong[2] = wrong[2].copy(samples = emptyList())
            assertFailsWith<IllegalStateException> { ReplanPerf.validate(measured.copy(steps = wrong)) }
        }
    }
}
