package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlinx.serialization.json.*
import kotlin.test.*

class ProducedWeaponArchiveTest {
    @Test fun `real archive consumer independently rejects altered authorized weapons and still reads legacy empty shape`() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val source = root.resolve("scenarios/heavy-mech-ini-short.json")
        val contractPath = root.resolve("contracts/heavy-mech-ini-short.json")
        val batch = System.getenv("RWRE_P005_ARCHIVE_ROOT")?.let { Files.createDirectories(Path.of(it)).resolve("core-short") }
            ?: Files.createTempDirectory("authorized-weapon-archive-").resolve("core-short")
        val result = Runner.run(RunRequest(source, contractPath, batch, listOf("ProducedWeaponArchiveTest/Runner.run")))
        assertEquals(0, result.exitCode)
        assertEquals(0, TraceFiles.compare(source, contractPath, batch).exitCode)
        val rows = Files.readAllLines(batch.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val scenario = readObject(source); val contract = readObject(contractPath)
        val index = rows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") }
        val sample = rows[index].objectAt("observation")
        val players = sample.objectAt("players"); val own = players.objectAt("p1")
        val weapons = own.arrayAt("weapons")
        assertEquals(listOf("smallLaser", "1", "2", "3"), weapons.map { it.jsonObject.text("weaponName") })
        assertTrue(players.objectAt("p2").arrayAt("weapons").isEmpty())
        val judgments = mutableListOf<JsonObject>()
        for (kind in listOf("missing", "wrong-name", "wrong-cooldown", "hidden-enemy")) {
            val first = weapons.first().jsonObject
            val altered = when (kind) {
                "missing" -> JsonObject(own - "weapons")
                "wrong-name" -> JsonObject(own + ("weapons" to array(listOf(JsonObject(first + ("weaponName" to s("forged")))) + weapons.drop(1))))
                "wrong-cooldown" -> JsonObject(own + ("weapons" to array(listOf(JsonObject(first + ("cooldown" to n(-999)))) + weapons.drop(1))))
                else -> own
            }
            val changedPlayers = if (kind == "hidden-enemy") JsonObject(players + ("p2" to JsonObject(
                players.objectAt("p2") + ("weapons" to weapons)))) else JsonObject(players + ("p1" to altered))
            val changedSample = JsonObject(sample + ("players" to changedPlayers))
            val changed = rows.toMutableList().apply { set(index, JsonObject(rows[index] + ("observation" to changedSample))) }
            val verdict = TraceComparator.evaluate(scenario, contract, changed, rows)
            assertNotEquals(0, verdict.exitCode, kind)
            judgments += obj("case" to s(kind), "result" to verdict.document)
        }
        // The old schema permits omission of empty optional weapon arrays. The reader retains
        // that path; it does not rewrite the original archive or weaken exact comparisons.
        val legacy = rows.map { row ->
            if (row["kind"] != s("observation")) row else {
                val observation = row.objectAt("observation")
                val oldPlayers = JsonObject(observation.objectAt("players").mapValues { (_, value) ->
                    val player = value.jsonObject
                    if (player.arrayAt("weapons").isEmpty()) JsonObject(player - "weapons") else player
                })
                JsonObject(row + ("observation" to JsonObject(observation + ("players" to oldPlayers))))
            }
        }
        assertEquals(0, TraceComparator.evaluate(scenario, contract, legacy).exitCode)
        Files.writeString(batch.parent.resolve("weapon-mutation-judgments.json"), array(judgments).toString())
    }

    @Test fun `first normally produced heavy mech reaches the authorized archive at step 5466`() {
        for (format in listOf("ini", "json", "hjson")) productionAndReopen(format)
    }

    private fun productionAndReopen(format: String) {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val base = ScenarioReader.load(root.resolve("scenarios/heavy-mech-production-$format-chain.json"),
            root.resolve("contracts/heavy-mech-production-$format-chain.json"))
        val input = base.copy(core = base.core.copy(units = base.core.units.map { it.copy(typeId = "builder") }),
            scenarioDocument = JsonObject(base.scenarioDocument + ("automaticVision" to
                obj("observers" to array(listOf(s("p1"), s("p2")))))))
        CoreAdapter(input).use { core ->
            val initial = core.initial()
            assertTrue(initial.combat!!.weapons.none { it.weaponName != null })
            val initialPlayers = core.playerViews()!!
            for (step in input.steps.takeWhile { it.stepIndex <= 5466 }) {
                input.actions.filter { it.integer("atStep") == step.stepIndex }.forEach { action ->
                    assertIs<com.rustedwarfare.reforge.core.sim.ActionResult.Accepted>(core.submit(action))
                }
                core.advance(step)
            }
            val bornId = "produced:p1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            val expected = listOf("smallLaser", "1", "2", "3")
            val actual = core.observe()
            assertEquals(5466L, actual.stepIndex)
            assertEquals(expected, actual.combat!!.weapons.filter { it.unitId == bornId }.map { it.weaponName })
            val player = core.playerViews()!!.objectAt("p1")
            val archived = player.arrayAt("weapons").map { it.jsonObject }.filter { it["unitId"] == s(bornId) }
            assertEquals(expected, archived.map { it.text("weaponName") })
            assertTrue(archived.all { it["cooldown"] != JsonNull })
            val bornPlayers = core.playerViews()!!
            core.close()
            val reset = core.reopen()
            assertNotEquals(actual.worldId, reset.worldId)
            assertTrue(core.playerViews()!!.objectAt("p1").arrayAt("weapons").isEmpty())
            // A supported combat archive must describe the now-empty state, rather than dropping
            // the entire capability when the last named weapon leaves this world.
            assertTrue(reset.json().objectAt("combat").arrayAt("weapons").isEmpty())
            assertTrue(reset.json().arrayAt("projectiles").isEmpty())
            assertTrue(reset.json().arrayAt("projectileEvents").isEmpty())
            val evidence = System.getenv("RWRE_P005_ARCHIVE_ROOT")?.let { Files.createDirectories(Path.of(it)) }
                ?: Files.createTempDirectory("produced-core-boundaries-")
            Files.writeString(evidence.resolve("selected-core-boundaries-$format.json"), obj(
                "scope" to s("Selected real CoreAdapter serialization boundaries; not a complete Runner trace"),
                "sourceScenarioSha256" to s(base.scenarioIdentity.sha256),
                "override" to s("All initial unit types are builder; same paid build, upgrade and production actions"),
                "initial" to JsonObject(initial.json() + ("players" to initialPlayers)),
                "birth5466" to JsonObject(actual.json() + ("players" to bornPlayers)),
                "reopened" to JsonObject(reset.json() + ("players" to core.playerViews()!!))
            ).toString(), CREATE_NEW)
        }
    }
}
