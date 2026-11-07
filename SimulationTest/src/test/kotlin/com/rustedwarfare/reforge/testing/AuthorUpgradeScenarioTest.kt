package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthorUpgradeScenarioTest {
    @Test fun capability_labels_cannot_hide_or_invent_an_author_upgrade() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val scenario = public.resolve("scenarios/author-upgrade-json.json")
        val contract = public.resolve("contracts/author-upgrade-json.json")
        for ((type, loader) in listOf("spfactory" to "author-producer", "spfactoryt2" to "author-upgrading-producer")) {
            val doc = readObject(scenario)
            val content = doc.objectAt("content")
            val declarations = content.arrayAt("unitVariants").map { value ->
                val entry = value.jsonObject
                if (entry.id("id") == type) JsonObject(entry + ("loader" to s(loader))) else entry
            }
            val snapshot = Files.createTempFile("rwr-author-upgrade-capability-", ".json").toAbsolutePath().normalize()
            try {
                Files.writeString(snapshot, JsonObject(doc + ("content" to JsonObject(content +
                    ("unitVariants" to JsonArray(declarations))))).toString())
                val failure = assertFailsWith<InputError> { ScenarioReader.load(scenario, contract, snapshot) }
                assertTrue(failure.message.orEmpty().contains("actual upgrade capability"), failure.message)
            } finally {
                require(snapshot.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                Files.deleteIfExists(snapshot)
            }
        }
    }

    @Test fun real_player_views_require_only_the_authorized_current_tier_upgrade_queue() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = TestArchives.directory("RWR_AUTHOR_WATER_ARCHIVE_ROOT", javaClass, "rwr-author-upgrade-views-")
        Files.createDirectories(output)
        val scenario = public.resolve("scenarios/author-upgrade-views-json.json")
        val contract = public.resolve("contracts/author-upgrade-views-json.json")
        val target = output.resolve("upgrade-views")
        val result = Runner.run(RunRequest(scenario, contract, target, listOf("api:Runner.run/AuthorUpgradeScenarioTest")))
        assertEquals(0, result.exitCode, result.toString())
        assertEquals(0, TraceFiles.compare(scenario, contract, target).exitCode)
        val rows = Files.readAllLines(target.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val samples = rows.filter { it["kind"] == s("observation") }
        assertEquals(301, samples.size)
        val index = rows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") &&
            it.objectAt("observation").integer("stepIndex") == 10L }
        assertTrue(index >= 0)
        val original = rows[index]
        val observation = original.objectAt("observation")
        val players = observation.objectAt("players")
        assertEquals(setOf("p1", "p2"), players.keys)
        assertEquals(setOf("f"), players.objectAt("p1").objectAt("upgradeQueues").keys)
        assertTrue(players.objectAt("p2").objectAt("upgradeQueues").isEmpty())
        assertTrue(samples.last().objectAt("observation").objectAt("players").objectAt("p1").objectAt("upgradeQueues").isEmpty())
        val mutant = rows.toMutableList()
        val wrongOwner = JsonObject(players.objectAt("p1") + ("upgradeQueues" to JsonObject(emptyMap())))
        val changedPlayers = JsonObject(players + ("p1" to wrongOwner))
        mutant[index] = JsonObject(original + ("observation" to JsonObject(observation + ("players" to changedPlayers))))
        val rejected = TraceComparator.evaluate(readObject(scenario), readObject(contract), mutant)
        Files.writeString(output.resolve("missing-authorized-upgrade-queue.json"), rejected.document.toString())
        assertEquals(3, rejected.exitCode)
        assertTrue(rejected.document.toString().contains("player-queues"), rejected.document.toString())
    }

    @Test fun three_formats_archive_the_shared_production_upgrade_fifo_and_require_its_complete_upgrade_queues() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = TestArchives.directory("RWR_AUTHOR_WATER_ARCHIVE_ROOT", javaClass, "rwr-author-upgrade-archive-")
        Files.createDirectories(output)
        var baseline: List<JsonObject>? = null
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/author-upgrade-$format.json")
            val contract = public.resolve("contracts/author-upgrade-$format.json")
            val target = output.resolve("upgrade-$format")
            val result = Runner.run(RunRequest(scenario, contract, target, listOf("api:Runner.run/AuthorUpgradeScenarioTest")))
            assertEquals(0, result.exitCode, "$format $result")
            assertEquals(0, TraceFiles.compare(scenario, contract, target).exitCode)
            val rows = Files.readAllLines(target.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val normalized = rows.map(TraceComparator::normalize)
            if (baseline == null) baseline = normalized else assertEquals(baseline, normalized, "$format complete mixed FIFO records")
            val index = rows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") &&
                it.objectAt("observation").integer("stepIndex") == 10L }
            assertTrue(index >= 0)
            val original = rows[index]
            val observation = original.objectAt("observation")
            assertTrue(observation.objectAt("upgrades").isNotEmpty())
            val mutant = rows.toMutableList()
            mutant[index] = JsonObject(original + ("observation" to JsonObject(observation + ("upgrades" to JsonObject(emptyMap())))))
            val rejected = TraceComparator.evaluate(readObject(scenario), readObject(contract), mutant)
            Files.writeString(output.resolve("$format-missing-upgrade-queue.json"), rejected.document.toString())
            assertEquals(3, rejected.exitCode)
            assertTrue(rejected.document.toString().contains("missing-upgrades"), rejected.document.toString())
        }
    }
}
