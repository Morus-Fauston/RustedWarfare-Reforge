package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class MovementKnowledgeScenarioTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
    @Test fun `same world discovers wall stops returns and retains authorized knowledge`() {
        for (name in listOf("movement-knowledge-short", "movement-knowledge-chain")) {
            val output = Files.createTempDirectory("public-knowledge-").resolve("run")
            val result = Runner.run(RunRequest(root.resolve("scenarios/$name.json"),
                root.resolve("contracts/$name.json"), output, listOf(name)))
            assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
        }
    }

    @Test fun `missing player sample cannot pass even outside an asserted checkpoint`() {
        val scenario = root.resolve("scenarios/movement-knowledge-short.json")
        val contract = root.resolve("contracts/movement-knowledge-short.json")
        val output = Files.createTempDirectory("knowledge-missing-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("missing-player"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val missing = rows.map { row ->
            val observation = row["observation"] as? JsonObject
            if (row["phase"] == s("after-advance") && observation?.get("stepIndex") == n(5))
                JsonObject(row + ("observation" to JsonObject(observation - "players"))) else row
        }
        assertEquals(3, TraceComparator.evaluate(readObject(scenario), readObject(contract), missing).exitCode)
    }

    @Test fun `truncated or resized knowledge at an unasserted boundary is incomplete`() {
        val scenario = root.resolve("scenarios/movement-knowledge-short.json")
        val contract = root.resolve("contracts/movement-knowledge-short.json")
        val output = Files.createTempDirectory("knowledge-grid-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("grid-integrity"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        for (resize in listOf(false, true)) {
            val broken = rows.map { row ->
                val observation = row["observation"] as? JsonObject
                if (row["phase"] != s("after-advance") || observation?.get("stepIndex") != n(5)) row
                else {
                    val players = observation.objectAt("players")
                    val player = players.objectAt("p1")
                    val knowledge = player.objectAt("knowledge")
                    val cells = knowledge.objectAt("cells")
                    val damaged = if (resize) JsonObject(knowledge + mapOf("height" to n(1),
                        "cells" to obj("c0r0" to cells.required("c0r0"))))
                    else JsonObject(knowledge + ("cells" to JsonObject(cells - "c0r6")))
                    JsonObject(row + ("observation" to JsonObject(observation + ("players" to JsonObject(players +
                        ("p1" to JsonObject(player + ("knowledge" to damaged))))))))
                }
            }
            assertEquals(3, TraceComparator.evaluate(readObject(scenario), readObject(contract), broken).exitCode,
                "resize=$resize must not let missing terrain knowledge pass")
        }
    }

    @Test fun `knowledge grid declaration must match the hash verified map`() {
        val source = root.resolve("scenarios/movement-knowledge-short.json")
        val original = readObject(source)
        val rules = original.objectAt("automaticVision")
        val changed = JsonObject(original + ("automaticVision" to JsonObject(rules +
            ("grid" to obj("width" to n(1), "height" to n(1))))))
        // Resolve original content references first because this input snapshot lives in a temporary folder.
        val content = original.objectAt("content")
        fun absolute(ref: JsonObject) = JsonObject(ref + ("path" to s(source.parent.resolve(ref.text("path")).toString())))
        val relocated = JsonObject(changed + ("content" to obj("map" to absolute(content.objectAt("map")),
            "unitVariant" to absolute(content.objectAt("unitVariant")),
            "dependencies" to array(content.arrayAt("dependencies").map { absolute(it.jsonObject) }))))
        val folder = Files.createTempDirectory("knowledge-declaration-")
        val scenario = folder.resolve("scenario.json").also { Files.writeString(it, relocated.toString()) }
        val result = Runner.run(RunRequest(scenario, root.resolve("contracts/movement-knowledge-short.json"),
            folder.resolve("run"), listOf("wrong-grid-declaration")))
        assertEquals(64, result.exitCode)
        assertTrue(readObject(folder.resolve("run/comparison/result.json")).text("reason").contains("map dimensions"))
    }
}
