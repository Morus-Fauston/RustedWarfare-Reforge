package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class PublicScenarioTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test fun `public select move stop runs through the real core with fixed checkpoints`() {
        val output = Files.createTempDirectory("public-scenario-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/select-move-stop.json"),
            root.resolve("contracts/select-move-stop.json"), output, listOf("public-short")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
    }

    @Test fun `public run preserves failure identity for wrong expected value missing event and interruption`() {
        val scenario = root.resolve("scenarios/select-move-stop.json")
        val original = readObject(root.resolve("contracts/select-move-stop.json"))
        fun changedRule(ruleId: String, change: (JsonObject) -> JsonObject) = JsonObject(original +
            ("rules" to array(original.arrayAt("rules").map { if (it.jsonObject.id("ruleId") == ruleId) change(it.jsonObject) else it })))
        val wrong = changedRule("move.accepted") { rule -> JsonObject(rule + ("assertions" to array(listOf(
            obj("path" to s("units.u1.position.x"), "operator" to s("equals"), "value" to n(999)))))) }
        val missing = changedRule("stop.accepted") { rule -> JsonObject(rule + ("when" to
            obj("event" to s("action-accepted"), "actionId" to s("stop-1"), "stepIndex" to n(5)))) }
        val stale = changedRule("stop.accepted") { rule -> JsonObject(rule + ("when" to
            obj("event" to s("action-accepted"), "actionId" to s("foreign-stop"), "actionKind" to s("stop"), "stepIndex" to n(3)))) }
        for ((name, document, exit) in listOf(Triple("wrong", wrong, 2), Triple("missing", missing, 3), Triple("stale", stale, 3))) {
            val folder = Files.createTempDirectory("public-$name-")
            val contract = folder.resolve("contract.json").also { Files.writeString(it, document.toString()) }
            val result = Runner.run(RunRequest(scenario, contract, folder.resolve("run"), listOf(name)))
            assertEquals(exit, result.exitCode)
            val judgment = readObject(folder.resolve("run/comparison/result.json"))
            if (exit == 2) assertEquals(s("move.accepted"), judgment["ruleId"])
            else assertTrue(judgment.text("reason").contains("stop.accepted"))
        }
        val output = Files.createTempDirectory("public-interrupted-").resolve("run")
        var checks = 0
        val result = Runner.run(RunRequest(scenario, root.resolve("contracts/select-move-stop.json"), output,
            listOf("cancel-mid-run"), cancelled = { ++checks >= 6 }))
        assertEquals(3, result.exitCode)
        assertEquals("cancelled", result.status)
        assertEquals(b(false), readObject(output.resolve("manifest.json"))["complete"])
    }
}
