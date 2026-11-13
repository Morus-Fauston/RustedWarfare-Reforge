package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class RepairBayProductionSessionTest {
    @Test fun three_syntaxes_normally_pay_produce_build_fight_repair_stop_and_reclaim() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_REPAIRBAY_SESSION_ROOT", javaClass, format).resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/repairbay-production/$format"),
                public.resolve("platform/scenarios/repairbay-$format-production-chain.json"), output)
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            var sample: JsonObject? = null
            var weaponInjury = false
            var active = ""
            val stages = mutableMapOf<String, JsonObject>()
            val submitted = mutableSetOf<String>()
            for (row in rows) when (row["kind"]) {
                s("intent-start") -> active = row.text("intentId")
                s("observation") -> {
                    sample = row.objectAt("sample")
                    if (active in setOf("approach-enemy", "return-to-bay")) weaponInjury = weaponInjury ||
                        sample.arrayAt("units").any { u -> u.jsonObject.let {
                            it["typeId"] == s("c_tank") && it["ownerPlayerId"] == s("p1") && it.number("health") < 210.0 } }
                }
                s("action") -> {
                    submitted += active
                    assertEquals(b(true), row.objectAt("result")["accepted"], row.toString())
                }
                s("intent-end") -> stages[row.text("intentId")] = assertNotNull(sample)
                else -> Unit
            }
            for (required in listOf("produce-worker", "build-bay", "build-factory", "produce-tank", "approach-enemy",
                "return-to-bay", "reclaim-partial", "repair-mobile", "reclaim-terminal")) assertTrue(required in submitted, required)
            assertTrue(weaponInjury, "The paid mobile target must receive real enemy weapon damage")
            fun tank(stage: String) = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["typeId"] == s("c_tank") && it["ownerPlayerId"] == s("p1") }
            val stopped = tank("stop-reclaim").number("health")
            assertTrue(stopped < 210.0)
            assertEquals(tank("reclaim-partial").number("health"), stopped)
            assertTrue(tank("repair-mobile").number("health") > stopped)
            assertEquals(tank("repair-mobile").number("health"), tank("stop-repair").number("health"))
            assertTrue(tank("automatic-repair").number("health") > tank("stop-repair").number("health"))
            val last = stages.getValue("reclaim-terminal")
            assertTrue(last.arrayAt("units").none { it.jsonObject["unitId"] == tank("stop-repair")["unitId"] })
            assertTrue(rows.filter { it["kind"] == s("observation") }.any { row ->
                row.objectAt("sample").arrayAt("events").any { it.jsonObject["kind"] == s("building-reclaimed") } })
        }
    }
}
