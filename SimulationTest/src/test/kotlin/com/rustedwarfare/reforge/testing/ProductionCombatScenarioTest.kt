package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class ProductionCombatScenarioTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test fun `template identity remains required when copyFrom has whitespace before colon`() {
        val source = root.resolve("scenarios/upgrade-cancel-short.json")
        val original = readObject(source)
        val temp = Files.createTempDirectory("template-identity-")
        val copied = temp.resolve("units")
        Files.createDirectories(copied)
        Files.list(root.resolve("inputs/production-combat/upgrade/units")).use { files ->
            files.forEach { Files.copy(it, copied.resolve(it.fileName)) }
        }
        val turret = copied.resolve("turret_t1.ini")
        Files.writeString(turret, Files.readString(turret).replace("copyFrom:", "copyFrom :"))
        fun absolute(ref: JsonObject): JsonObject {
            val path = source.parent.resolve(ref.text("path")).normalize().toAbsolutePath()
            val actual = if (path.parent.fileName.toString() == "units") copied.resolve(path.fileName) else path
            return JsonObject(ref + mapOf("path" to s(actual.toString()), "sha256" to s(sha256(actual))))
        }
        val content = original.objectAt("content")
        val references = content.arrayAt("unitVariants").take(1).map { JsonObject(absolute(it.jsonObject) + ("loader" to s("turret"))) }
        val dependencies = content.arrayAt("dependencies").map { absolute(it.jsonObject) }
        val contract = temp.resolve("contract.json")
        val originalContract = readObject(root.resolve("contracts/upgrade-cancel-short.json"))
        Files.writeString(contract, JsonObject(originalContract + ("rules" to array(listOf(obj(
            "ruleId" to s("fixed-initial-life"), "when" to obj("phase" to s("initial"), "stepIndex" to n(0)),
            "assertions" to array(listOf(obj("path" to s("units.t.health"), "operator" to s("equals"), "value" to n(700))))
        ))))).toString())
        val scenario = temp.resolve("scenario.json")
        fun write(deps: List<JsonObject>) = Files.writeString(scenario, JsonObject(original + mapOf(
            "actions" to array(emptyList()), "content" to obj("map" to absolute(content.objectAt("map")),
                "unitVariants" to array(references), "dependencies" to array(deps)))).toString())
        write(dependencies)
        assertEquals(0, Runner.run(RunRequest(scenario, contract, temp.resolve("complete"), listOf("template-complete"))).exitCode)
        write(dependencies.filter { it.id("id") != "template" })
        assertEquals(64, Runner.run(RunRequest(scenario, contract, temp.resolve("missing"), listOf("template-missing"))).exitCode)
    }

    @Test fun `formal archive comparison accepts all content references and refuses every missing identity`() {
        val scenario = root.resolve("scenarios/production-combat-short.json")
        val contract = root.resolve("contracts/production-combat-short.json")
        val output = Files.createTempDirectory("production-archive-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("archive-base"))).exitCode)
        val result = TraceFiles.compare(scenario, contract, output, output)
        assertEquals(0, result.exitCode, result.document.toString())
        val manifestFile = output.resolve("manifest.json")
        val manifest = readObject(manifestFile)
        val identities = manifest.arrayAt("inputs")
        for (identity in identities) {
            Files.writeString(manifestFile, JsonObject(manifest + ("inputs" to array(identities.filter { it != identity }))).toString())
            assertEquals(3, TraceFiles.compare(scenario, contract, output).exitCode, identity.toString())
        }
    }

    @Test fun `point defense intercepts a real hostile shot on the fixed age boundary`() {
        val output = Files.createTempDirectory("defense-short-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/point-defense-short.json"),
            root.resolve("contracts/point-defense-short.json"), output, listOf("defense-short")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
    }

    @Test fun `every intermediate economy queue and authorized unit sample is structurally complete`() {
        val scenario = root.resolve("scenarios/production-combat-short.json")
        val contract = root.resolve("contracts/production-combat-short.json")
        val output = Files.createTempDirectory("partial-samples-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("partial-base"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val mutations = mutableListOf<Pair<String, (JsonObject) -> JsonObject>>()
        fun change(name: String, path: String, transform: (JsonObject) -> JsonObject) {
            fun edit(value: JsonObject, keys: List<String>): JsonObject = if (keys.isEmpty()) transform(value)
                else JsonObject(value + (keys.first() to edit(value.objectAt(keys.first()), keys.drop(1))))
            mutations += name to { view -> edit(view, path.split('.')) }
        }
        change("host factory key", "economy.queues") { JsonObject(it - "f") }
        change("player factory key", "players.p1.factoryQueues") { JsonObject(it - "f") }
        change("null balance", "players.p1") { JsonObject(it + ("balance" to JsonNull)) }
        change("string credits", "players.p1") { JsonObject(it + ("credits" to s("0"))) }
        for (field in listOf("position", "heading", "speed", "angularVelocity", "moveTarget", "pathPoints", "movementState",
            "health", "maxHealth", "alive", "attackTargetId", "pointDefense")) {
            change("player missing $field", "players.p1") { player ->
                JsonObject(player + ("units" to array(player.arrayAt("units").mapIndexed { index, unit ->
                    if (index == 0) JsonObject(unit.jsonObject - field) else unit
                })))
            }
        }
        for (field in listOf("typeId", "progress")) {
            change("player queue missing $field", "players.p1.factoryQueues") { queues ->
                val items = queues.arrayAt("f")
                assertTrue(items.isNotEmpty(), "negative case must damage a live queue")
                JsonObject(queues + ("f" to array(items.map { JsonObject(it.jsonObject - field) })))
            }
        }
        val missed = mutations.mapNotNull { (name, mutate) ->
            val damaged = rows.map { row ->
                if (row["phase"] == s("after-advance") && row.objectAt("observation")["stepIndex"] == n(250))
                    JsonObject(row + ("observation" to mutate(row.objectAt("observation")))) else row
            }
            val judgment = TraceComparator.evaluate(readObject(scenario), readObject(contract), damaged)
            if (judgment.exitCode == 3) null else "$name: ${judgment.exitCode}"
        }
        assertEquals(emptyList(), missed)
    }

    @Test fun `formal upgrade and cancellation keep owner queue money and player privacy`() {
        val output = Files.createTempDirectory("upgrade-short-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/upgrade-cancel-short.json"),
            root.resolve("contracts/upgrade-cancel-short.json"), output, listOf("upgrade-short")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
    }

    @Test fun `upgrade samples require registered host queues and complete authorized items`() {
        val scenario = root.resolve("scenarios/upgrade-cancel-short.json")
        val contract = root.resolve("contracts/upgrade-cancel-short.json")
        val output = Files.createTempDirectory("upgrade-partial-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("upgrade-partial"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val changes = listOf("host-key", "host-actionKey", "host-targetTypeId", "host-progress", "host-cost", "host-rate",
            "player-key", "player-actionKey", "player-targetTypeId", "player-progress")
        val missed = changes.mapNotNull { change ->
            val damaged = rows.map { row ->
                if (row["phase"] != s("after-advance") || row.objectAt("observation")["stepIndex"] != n(0)) row
                else {
                    val view = row.objectAt("observation")
                    val player = view.objectAt("players").objectAt("p1")
                    val host = change.startsWith("host-")
                    val queues = if (host) view.objectAt("upgrades") else player.objectAt("upgradeQueues")
                    val items = queues.arrayAt("t")
                    assertEquals(1, items.size)
                    val field = change.substringAfter('-')
                    val broken = if (field == "key") JsonObject(queues - "t")
                        else JsonObject(queues + ("t" to array(items.map { JsonObject(it.jsonObject - field) })))
                    val next = if (host) JsonObject(view + ("upgrades" to broken)) else
                        JsonObject(view + ("players" to JsonObject(view.objectAt("players") +
                            ("p1" to JsonObject(player + ("upgradeQueues" to broken))))))
                    JsonObject(row + ("observation" to next))
                }
            }
            val result = TraceComparator.evaluate(readObject(scenario), readObject(contract), damaged)
            if (result.exitCode == 3) null else "$change: ${result.exitCode}"
        }
        assertEquals(emptyList(), missed)
    }

    @Test fun `production pays cancels completes and the actual product receives commands in one world`() {
        val output = Files.createTempDirectory("production-combat-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/production-combat-short.json"),
            root.resolve("contracts/production-combat-short.json"), output, listOf("production-short")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
    }

    @Test fun `missing and forged product money or life samples cannot pass between checkpoints`() {
        val scenario = root.resolve("scenarios/production-combat-short.json")
        val contract = root.resolve("contracts/production-combat-short.json")
        val output = Files.createTempDirectory("production-negative-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("negative-base"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        for (change in listOf("drop-product", "forge-owner", "forge-producer", "drop-life", "drop-money", "drop-player-money")) {
            val damaged = rows.map { row ->
                val view = row["observation"] as? JsonObject
                if (row["phase"] != s("after-advance") || view?.get("stepIndex") != n(503)) row
                else {
                    val units = view.arrayAt("units")
                    val product = units.map { it.jsonObject }.single { it.id("unitId").startsWith("produced:") }
                    val next = when (change) {
                        "drop-product" -> JsonObject(view + ("units" to array(units.filter { it != product })))
                        "forge-owner" -> JsonObject(view + ("units" to array(units.map { if (it == product) JsonObject(product + ("ownerPlayerId" to s("p2"))) else it })))
                        "forge-producer" -> {
                            val production = view.objectAt("production")
                            val forged = array(production.arrayAt("units").map { JsonObject(it.jsonObject + ("producerId" to s("enemy"))) })
                            JsonObject(view + ("production" to JsonObject(production + ("units" to forged))))
                        }
                        "drop-life" -> JsonObject(view + ("units" to array(units.map { if (it == product) JsonObject(product - "health") else it })))
                        "drop-money" -> JsonObject(view - "economy")
                        else -> JsonObject(view + ("players" to JsonObject(view.objectAt("players") +
                            ("p1" to JsonObject(view.objectAt("players").objectAt("p1") - "balance")))))
                    }
                    JsonObject(row + ("observation" to next))
                }
            }
            assertEquals(3, TraceComparator.evaluate(readObject(scenario), readObject(contract), damaged).exitCode, change)
        }
        val wrong = readObject(contract).let { original -> JsonObject(original + ("rules" to array(original.arrayAt("rules").map {
            val rule = it.jsonObject
            if (rule.id("ruleId") == "pay-and-cancel") JsonObject(rule + ("assertions" to array(listOf(
                obj("path" to s("economy.balances.p1"), "operator" to s("equals"), "value" to n(1750)))))) else rule
        }))) }
        assertEquals(2, TraceComparator.evaluate(readObject(scenario), wrong, rows).exitCode)
        val missingSample = rows.filterNot { it["phase"] == s("after-advance") && it.objectAt("observation")["stepIndex"] == n(503) }
        assertEquals(3, TraceComparator.evaluate(readObject(scenario), readObject(contract), missingSample).exitCode)
    }
}
