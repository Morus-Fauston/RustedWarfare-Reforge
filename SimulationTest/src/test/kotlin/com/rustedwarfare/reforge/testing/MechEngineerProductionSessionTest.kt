package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class MechEngineerProductionSessionTest {
    @Test fun three_syntaxes_normally_produce_cross_cliffs_fight_construct_repair_stop_and_reclaim() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_ENGINEER_SESSION_ROOT", javaClass, format).resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/mech-engineer-production/$format"),
                public.resolve("platform/scenarios/engineer-$format-production-chain.json"), output)
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            var sample: JsonObject? = null
            var active = ""
            var engineerHurt = false
            var mobileHurt = false
            var crossed = false
            var aaShot = false
            val stages = mutableMapOf<String, JsonObject>()
            val submitted = mutableSetOf<String>()
            for (row in rows) when (row["kind"]) {
                s("intent-start") -> active = row.text("intentId")
                s("observation") -> {
                    sample = row.objectAt("sample")
                    for (unit in sample.arrayAt("units").map { it.jsonObject }.filter { it["ownerPlayerId"] == s("p1") }) {
                        if (unit["typeId"] == s("mechEngineer")) {
                            engineerHurt = engineerHurt || unit.number("health") < 2000.0
                            crossed = crossed || unit.objectAt("position").number("x") > 400.0
                        }
                        if (unit["typeId"] == s("mechGun") && active in setOf("build-mobile", "mobile-under-fire"))
                            mobileHurt = mobileHurt || unit.number("health") < 500.0
                    }
                    aaShot = aaShot || sample.arrayAt("projectileEvents").any { it.jsonObject.let { event ->
                        event["kind"] == s("born") && event["sourceWeaponName"] == s("body") && event["targetUnitId"] == s("c") } }
                }
                s("action") -> {
                    submitted += active
                    assertEquals(b(true), row.objectAt("result")["accepted"], row.toString())
                }
                s("intent-end") -> stages[row.text("intentId")] = assertNotNull(sample)
                else -> Unit
            }
            for (required in listOf("produce-worker", "build-t1", "upgrade-t2", "produce-engineer", "cross-cliff-and-fight",
                "build-mobile", "clear-engineer-target", "return-to-mobile", "mobile-under-fire",
                "reclaim-partial", "manual-repair", "reclaim-terminal", "build-bay")) assertTrue(required in submitted, required)
            assertTrue(crossed)
            assertTrue(engineerHurt, "The paid engineer must receive real ground weapon damage")
            assertTrue(aaShot, "The engineer's real body weapon must fire at an air target")
            assertTrue(mobileHurt, "The paid mobile nano project must receive real weapon damage")
            fun mobile(stage: String) = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["typeId"] == s("mechGun") && it["ownerPlayerId"] == s("p1") }
            val stopped = mobile("stop-reclaim").number("health")
            assertEquals(mobile("reclaim-partial").number("health"), stopped)
            assertTrue(mobile("manual-repair").number("health") > stopped)
            assertEquals(mobile("manual-repair").number("health"), mobile("stop-repair").number("health"))
            assertTrue(mobile("automatic-repair").number("health") > mobile("stop-repair").number("health"))
            assertTrue(stages.getValue("reclaim-terminal").arrayAt("units").none { it.jsonObject["unitId"] == mobile("stop-repair")["unitId"] })
            val final = stages.getValue("build-bay").arrayAt("units").map { it.jsonObject }
            assertEquals(1.0, final.single { it["typeId"] == s("repairbay") }.number("constructionProgress"))
        }
    }
}
