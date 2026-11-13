package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.client.TestScenarioChoice
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class DropshipPairedSessionTest {
    @Test fun paired_transport_scenes_execute_actual_load_and_unload_commands_in_three_formats() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/air-transport-$format-short.json")
            val choice = TestScenarioChoice.open(scenario)
            choice.verifyStable()
            val output = TestArchives.directory("RWR_AIR_TRANSPORT_ARCHIVE_ROOT", javaClass, format).resolve("run")
            PlatformSessionRunner.run(choice.root, scenario, output)
            val actions = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                .filter { it["kind"] == s("action") }
            val loads = actions.filter { it["type"] == s("LoadAction") }
            assertEquals(1, loads.size, "$format: $actions")
            assertEquals(s("INTO"), loads.single()["direction"])
            assertEquals(s("craft"), loads.single()["targetUnitId"])
            val unloads = actions.filter { it["type"] == s("UnloadAction") }
            assertEquals(listOf(b(true), b(false), b(true)), unloads.map { it["unloading"] })
            assertTrue((loads + unloads).all { it.objectAt("result")["accepted"] == b(true) })
            val before = readObject(scenario)
            for ((name, replacement) in listOf("direction" to s("UP"), "targetUnitId" to s("a-passenger"), "unloading" to s("true"))) {
                val source = before.arrayAt("intents").map { value ->
                    val intent = value.jsonObject
                    if ((name == "unloading" && intent["intentId"] == s("unload")) ||
                        (name != "unloading" && intent["intentId"] == s("board"))) JsonObject(intent + (name to replacement)) else intent
                }
                val malformed = output.parent.resolve("invalid-$name.json")
                Files.writeString(malformed, JsonObject(before + ("intents" to array(source))).toString())
                val error = assertFailsWith<IllegalArgumentException> { TestScenarioChoice.open(malformed, choice.root) }
                assertTrue(error.message.orEmpty().contains("TEST_SCENE_INTENT_MISMATCH"), error.toString())
            }
        }
    }
}
