package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class PlatformSessionRunnerTest {
    private val publicRoot = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test fun `session command carries a bounded explicit load budget into actual resource preparation`() {
        val temporary = Files.createTempDirectory("platform-load-budget-").toAbsolutePath().normalize()
        try {
            val input = publicRoot.resolve("inputs/resource-image-packs/sandbox")
            val plan = publicRoot.resolve("platform/scenarios/resource-images-amber-first.json")
            val output = temporary.resolve("actual")
            // The package also has MainKt.main; select the actual Session process entry point.
            fun invoke(args: Array<String>) {
                try { Class.forName("com.rustedwarfare.reforge.testing.PlatformSessionRunnerKt")
                    .getMethod("main", Array<String>::class.java).invoke(null, args) }
                catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
            }
            invoke(arrayOf(input.toString(), plan.toString(), output.toString(), "180"))
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            assertTrue(rows.any { it["kind"] == s("visual-assets-ready") })
            assertTrue(rows.all { it["generation"] == n(1) }, "Every first-world record carries the same explicit generation")
            for (invalid in listOf("0", "-1", "601", "invalid")) {
                val refused = temporary.resolve("refused-$invalid")
                assertFailsWith<IllegalArgumentException> { invoke(arrayOf(input.toString(), plan.toString(), refused.toString(), invalid)) }
                assertFalse(Files.exists(refused), "Invalid budget cannot publish even an empty run")
            }
        } finally {
            require(temporary.isAbsolute && temporary.fileName.toString().startsWith("platform-load-budget-"))
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun `actual resource sound pack uses the shared playback consumer without changing gameplay`() {
        val temporary = Files.createTempDirectory("platform-sound-").toAbsolutePath().normalize()
        try {
            val input = temporary.resolve("input")
            fun copy(from: Path, to: Path) = Files.walk(from).use { paths -> paths.forEach { file ->
                val target = to.resolve(from.relativize(file))
                if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
            } }
            copy(publicRoot.resolve("inputs/production-combat/combat"), input)
            copy(publicRoot.resolve("inputs/resource-sound-packs/wav"), input.resolve("sounds"))
            fun run(name: String, withSound: Boolean): List<JsonObject> {
                val plan = temporary.resolve("$name.json")
                val packs = if (withSound) "\"resourcePacks\":{\"directories\":[\"sounds\"]}," else ""
                Files.writeString(plan, """{"schemaVersion":1,$packs"intents":[
                    {"intentId":"choose","kind":"select","position":{"x":110,"y":210}},
                    {"intentId":"attack","kind":"attack","position":{"x":350,"y":210},"waitSteps":600}]}""")
                val output = temporary.resolve(name)
                PlatformSessionRunner.run(input, plan, output)
                return Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            }
            val control = run("control", false); val actual = run("actual", true)
            val backend = actual.single { it["kind"] == s("sound-backend-ready") }
            assertEquals(s("device-free"), backend["backend"])
            val requests = actual.filter { it["kind"] == s("sound-play-requested") }
            assertTrue(requests.isNotEmpty(), "Real Session combat must reach the shared sound consumer")
            assertTrue(requests.all { it["resourceId"] == s("owned:sounds/shot") && it["eventId"] == s("reforge:events/tank-fire") })
            assertEquals(requests.size, requests.map { it.integer("frame") to it.text("path") }.distinct().size)
            assertTrue(actual.none { it["kind"] == s("sound-play-failed") })
            fun samples(rows: List<JsonObject>) = rows.filter { it["kind"] == s("observation") }.map { it["sample"] }
            assertEquals(samples(control), samples(actual), "Audio bindings and presentation RNG cannot change gameplay")
        } finally {
            require(temporary.isAbsolute && temporary.fileName.toString().startsWith("platform-sound-"))
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun `invalid explicit package sets publish no observation and a repaired plan can run`() {
        val temporary = Files.createTempDirectory("platform-invalid-combination-").toAbsolutePath().normalize()
        try {
            val cases = listOf(
                """{"directories":["overlay"],"unitId":"amber_scout"}""" to "dependencies",
                """{"directories":[],"unitId":"amber_scout"}""" to "contentPacks",
                """{"directories":["base","base"],"unitId":"amber_scout"}""" to "IDENTITY_CONFLICT",
                """{"directories":["../outside"],"unitId":"amber_scout"}""" to "inside input snapshot",
                """{"directories":[null],"unitId":"amber_scout"}""" to "directory text",
                """{"directories":[""],"unitId":"amber_scout"}""" to "directory text",
                """{"directories":"base","unitId":"amber_scout"}""" to "Expected array",
                """{"directory":"base","directories":["overlay"],"unitId":"amber_scout"}""" to "Expected contentPack",
            )
            val input = publicRoot.resolve("inputs/content-pack-combinations/sandbox")
            for ((index, case) in cases.withIndex()) {
                val (selection, message) = case
                val plan = temporary.resolve("invalid-$index.json")
                Files.writeString(plan, """{"schemaVersion":1,"contentPack":$selection,
                    "intents":[{"intentId":"choose","kind":"select","position":{"x":110,"y":210}}]}""")
                val output = temporary.resolve("invalid-$index")
                val failure = assertFailsWith<InputError> { PlatformSessionRunner.run(input, plan, output) }
                assertTrue(failure.message?.contains(message) == true, "$selection: ${failure.message}")
                assertEquals(emptyList(), Files.readAllLines(output.resolve("client-diagnostic.jsonl")), selection)
            }
            val repaired = temporary.resolve("repaired.json")
            Files.writeString(repaired, """{"schemaVersion":1,"contentPack":{"directories":["base","overlay","final"],"unitId":"amber_scout"},
                "intents":[{"intentId":"choose","kind":"select","position":{"x":110,"y":210}}]}""")
            val output = temporary.resolve("repaired")
            PlatformSessionRunner.run(input, repaired, output)
            val initial = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                .first { it["kind"] == s("observation") }.objectAt("sample")
            assertEquals(600.0, initial.arrayAt("units").single { it.jsonObject["unitId"] == s("a") }.jsonObject.number("maxHealth"))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
            } }
        }
    }

    @Test fun `explicit package directory lists run the same replacement in either order and record final sources`() {
        val temporary = Files.createTempDirectory("platform-composed-pack-").toAbsolutePath().normalize()
        try {
            for ((index, directories) in listOf("[\"base\",\"overlay\"]", "[\"overlay\",\"base\"]").withIndex()) {
                val plan = temporary.resolve("plan-$index.json")
                Files.writeString(plan, """{"schemaVersion":1,"contentPack":{"directories":$directories,"unitId":"amber_scout"},
                    "intents":[{"intentId":"choose","kind":"select","position":{"x":110,"y":210}}]}""")
                val output = temporary.resolve("run-$index")
                PlatformSessionRunner.run(publicRoot.resolve("inputs/content-pack-combinations/sandbox"), plan, output)
                val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                val initial = rows.first { it["kind"] == s("observation") }.objectAt("sample")
                val unit = initial.arrayAt("units").single { it.jsonObject["unitId"] == s("a") }.jsonObject
                assertEquals("amber_scout", unit.text("typeId")); assertEquals(420.0, unit.number("maxHealth"))
                val ready = rows.single { it["kind"] == s("content-pack-ready") }
                assertEquals(listOf("base.scouts", "overlay.scouts"), ready.arrayAt("packageIds").map { it.jsonPrimitive.content })
                val source = ready.objectAt("effectiveSources").objectAt("amber_scout")
                assertEquals("overlay.scouts", source.text("packageId"))
                assertEquals(listOf(s("base.scouts")), source.arrayAt("replacedPackageIds").toList())
                val paths = ready.arrayAt("sources").map { Path.of(it.jsonObject.text("path")) }
                assertTrue(paths.any { it.endsWith("base/pack.json") })
                assertTrue(paths.any { it.endsWith("overlay/pack.hjson") })
            }
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
            } }
        }
    }

    @Test fun `a content package plan loads the selected new unit through the actual sandbox loader`() {
        val temporary = Files.createTempDirectory("platform-content-pack-").toAbsolutePath().normalize()
        try {
            fun copy(source: Path, destination: Path) {
                Files.walk(source).use { paths -> paths.forEach { file ->
                    val target = destination.resolve(source.relativize(file)).normalize()
                    require(target.startsWith(temporary))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
            }
            val input = temporary.resolve("input")
            copy(publicRoot.resolve("inputs/three-format-tanks/ini"), input)
            copy(publicRoot.resolve("inputs/content-packs/amber-scouts-json"), input.resolve("package"))
            val plan = temporary.resolve("plan.json")
            Files.writeString(plan, """{"schemaVersion":1,"contentPack":{"directory":"package","unitId":"amber_scout"},
                "intents":[{"intentId":"choose","kind":"select","position":{"x":110,"y":210}}]}""")
            val output = temporary.resolve("run")
            PlatformSessionRunner.run(input, plan, output)
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val initial = rows.first { it["kind"] == s("observation") }.objectAt("sample")
            assertEquals("p008-content-pack", initial.text("scenarioId"))
            val unit = initial.arrayAt("units").single { it.jsonObject["unitId"] == s("a") }.jsonObject
            assertEquals("amber_scout", unit.text("typeId"))
            assertEquals(250.0, unit.number("maxHealth"))
            assertEquals("amber.scouts.json", rows.single { it["kind"] == s("content-pack-ready") }.text("id"))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
            } }
        }
    }

    @Test fun `maintenance intentions pay and refund an economic upgrade then enqueue reclaim on the same real world`() {
        val output = Files.createTempDirectory("platform-maintenance-").resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/platform-basics/maintenance"),
            publicRoot.resolve("platform/scenarios/maintenance.json"), output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val actions = rows.filter { it["kind"] == s("action") }
        assertEquals(listOf("SelectAction", "UpgradeAction", "CancelUpgradeAction", "SelectAction", "ReclaimUnitAction"),
            actions.map { it.text("type") })
        assertTrue(actions.all { it.objectAt("result")["accepted"] == b(true) })
        for ((type, delta) in listOf("UpgradeAction" to -1400.0, "CancelUpgradeAction" to 1400.0)) {
            val index = rows.indexOfFirst { it["type"] == s(type) }
            assertEquals(s("upgrade_t2"), rows[index]["upgradeId"])
            assertEquals(delta, rows[index+1].objectAt("sample").number("balance") - rows[index-1].objectAt("sample").number("balance"))
        }
        val reclaim = rows.indexOfFirst { it["type"] == s("ReclaimUnitAction") }
        assertEquals(s("extractor"), rows[reclaim]["targetUnitId"])
        val worker = rows[reclaim+1].objectAt("sample").arrayAt("units").single { it.jsonObject["unitId"] == s("worker") }.jsonObject
        assertEquals("reclaim", worker.arrayAt("tasks").single().jsonObject.text("kind"))
        assertEquals("extractor", worker.arrayAt("tasks").single().jsonObject.text("targetUnitId"))
        assertEquals(1, rows.filter { it["kind"] == s("observation") }.map { it.objectAt("sample").text("worldId") }.distinct().size)
    }

    @Test fun `shared turret upgrade and cancel intentions use the real branch and exact refund`() {
        val output = Files.createTempDirectory("platform-upgrade-").resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/platform-basics/upgrade"),
            publicRoot.resolve("platform/scenarios/upgrade.json"), output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        for ((type, delta) in listOf("UpgradeAction" to -1000.0, "CancelUpgradeAction" to 1000.0)) {
            val index = rows.indexOfFirst { it["type"] == s(type) }
            assertTrue(index > 0)
            assertEquals(b(true), rows[index].objectAt("result")["accepted"])
            assertEquals(s("upgrade_gun"), rows[index]["upgradeId"])
            assertEquals(delta, rows[index+1].objectAt("sample").number("balance") - rows[index-1].objectAt("sample").number("balance"))
        }
    }

    @Test fun `shared build intent creates a real new extractor without rewriting its identity`() {
        val output = Files.createTempDirectory("platform-building-").resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/platform-basics/construction"),
            publicRoot.resolve("platform/scenarios/construction.json"), output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val build = rows.single { it["type"] == s("BuildAction") }
        assertEquals(b(true), build.objectAt("result")["accepted"])
        val creations = rows.filter { it["kind"] == s("observation") }.flatMap { row ->
            row.objectAt("sample").arrayAt("events").map { it.jsonObject }.filter { it["kind"] == s("construction-created") }.map { row to it }
        }
        assertEquals(1, creations.size)
        val (row, event) = creations.single()
        val unit = row.objectAt("sample").arrayAt("units").single { it.jsonObject["unitId"] == event["unitId"] }.jsonObject
        assertEquals(s("extractorT1"), unit["typeId"])
        assertEquals(obj("x" to n(190.0), "y" to n(290.0)), unit["position"])
        assertEquals(0.0, unit.number("constructionProgress"))
        assertTrue(event.text("unitId").isNotBlank())
    }

    @Test fun `client movement stop and attack intentions use the same selected actor`() {
        val folder = Files.createTempDirectory("platform-command-")
        val plan = folder.resolve("plan.json")
        Files.writeString(plan, """{"schemaVersion":1,"intents":[
            {"intentId":"choose-tank","kind":"select","unitId":"a","position":{"x":110,"y":210}},
            {"intentId":"move-tank","kind":"move","unitId":"a","position":{"x":232,"y":208},"waitSteps":20},
            {"intentId":"stop-tank","kind":"stop","unitId":"a","waitSteps":20},
            {"intentId":"attack-enemy","kind":"attack","unitId":"a","targetUnitId":"b","position":{"x":430,"y":210}}
        ]}""")
        val output = folder.resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/production-combat/production"), plan, output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val actions = rows.filter { it["kind"] == s("action") }
        assertEquals(listOf("SelectAction", "MoveAction", "StopAction", "AttackAction"), actions.map { it.text("type") })
        assertTrue(actions.all { it["unitId"] == s("a") && it.objectAt("result")["accepted"] == b(true) })
        val stop = rows.indexOfFirst { it["type"] == s("StopAction") }
        fun tank(index: Int) = rows[index].objectAt("sample").arrayAt("units").single { it.jsonObject["unitId"] == s("a") }.jsonObject
        assertTrue(tank(stop-1).number("speed") > 0.0)
        val beforeAttack = rows.indexOfFirst { it["type"] == s("AttackAction") }-1
        assertEquals(0.0, tank(beforeAttack).number("speed"))
        assertEquals(JsonNull, tank(beforeAttack)["moveTarget"])
        assertEquals(s("b"), actions.last()["targetUnitId"])
    }

    @Test fun `shared production and cancel intentions preserve immediate payment and refund`() {
        val folder = Files.createTempDirectory("platform-production-")
        val plan = folder.resolve("plan.json")
        Files.writeString(plan, """{"schemaVersion":1,"intents":[
            {"intentId":"choose-factory","kind":"select","unitId":"f","position":{"x":310,"y":210}},
            {"intentId":"make-tank","kind":"produce","unitId":"f","typeId":"c_tank"},
            {"intentId":"cancel-tank","kind":"cancelProduction","unitId":"f","typeId":"c_tank"}
        ]}""")
        val output = folder.resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/production-combat/production"), plan, output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val actions = rows.filter { it["kind"] == s("action") }
        assertEquals(listOf("SelectAction", "ProduceAction", "CancelProductionAction"), actions.map { it.text("type") })
        for ((type, delta) in listOf("ProduceAction" to -350, "CancelProductionAction" to 350)) {
            val index = rows.indexOfFirst { it["kind"] == s("action") && it["type"] == s(type) }
            assertEquals(b(true), rows[index].objectAt("result")["accepted"])
            val before = rows[index-1].objectAt("sample").number("balance")
            val after = rows[index+1].objectAt("sample").number("balance")
            assertEquals(delta.toDouble(), after-before)
        }
    }

    @Test fun `shared selection intent records a fresh real Session action and observation`() {
        val folder = Files.createTempDirectory("platform-session-")
        val plan = folder.resolve("plan.json")
        Files.writeString(plan, """{"schemaVersion":1,"intents":[
            {"intentId":"choose-factory","kind":"select","unitId":"f","position":{"x":310,"y":210}}
        ]}""")
        val output = folder.resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/production-combat/production"), plan, output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val action = rows.single { it["kind"] == s("action") }
        assertEquals(s("SelectAction"), action["type"])
        assertEquals(s("f"), action["unitId"])
        assertEquals(b(true), action.objectAt("result")["accepted"])
        val before = rows.last { it.integer("sequence") < action.integer("sequence") && it["phase"] == s("before-action") }
        val after = rows.first { it.integer("sequence") > action.integer("sequence") && it["phase"] == s("after-action") }
        assertEquals(before.objectAt("sample")["worldId"], after.objectAt("sample")["worldId"])
        assertEquals(s("closed"), rows.last()["kind"])
    }
}
