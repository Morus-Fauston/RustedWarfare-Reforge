package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class BunkerProductionSessionTest {
    @Test fun all_formats_pay_for_a_normal_product_then_move_deploy_fight_undeploy_and_move_again() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_BUNKER_SESSION_ROOT", javaClass, format).resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/bunker-production/$format"),
                public.resolve("platform/scenarios/bunker-$format-production-chain.json"), output)
            var sample: JsonObject? = null
            val stages = mutableMapOf<String, JsonObject>()
            val submitted = mutableSetOf<String>()
            var active = ""
            var injured = false
            var previousShield: Double? = null
            var shieldHit = false
            var recoveredAfterHit = false
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach {
                val row = json.parseToJsonElement(it).jsonObject
                when (row["kind"]) {
                    s("intent-start") -> active = row.text("intentId")
                    s("observation") -> {
                        sample = row.objectAt("sample")
                        sample!!.arrayAt("units").map { it.jsonObject }.filter {
                            it["ownerPlayerId"] == s("p1") && it.text("typeId").startsWith("mechBunker")
                        }.forEach { unit ->
                            injured = injured || unit.number("health") < 800.0
                            val shield = (unit["shield"] as? JsonObject)?.number("current")
                            val previous = previousShield
                            if (shield != null && previous != null) {
                                if (shield < previous) shieldHit = true
                                if (shieldHit && shield > previous) recoveredAfterHit = true
                            }
                            previousShield = shield
                        }
                    }
                    s("action") -> {
                        submitted += active
                        assertEquals(b(true), row.objectAt("result")["accepted"], row.toString())
                    }
                    s("intent-end") -> stages[row.text("intentId")] = assertNotNull(sample)
                    else -> Unit
                }
            } }
            assertTrue(submitted.containsAll(listOf("produce-worker", "build-t1", "produce-bunker", "deploy", "undeploy", "return")))
            fun bunker(stage: String) = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["ownerPlayerId"] == s("p1") && it.text("typeId").startsWith("mechBunker") }
            assertEquals(s("mechBunkerDeployed"), bunker("deploy")["typeId"])
            assertEquals(s("mechBunker"), bunker("undeploy")["typeId"])
            assertEquals(bunker("deploy")["unitId"], bunker("final-stop")["unitId"])
            assertEquals(JsonNull, bunker("final-stop")["moveTarget"])
            assertTrue(bunker("final-stop").objectAt("position").number("x") < 300)
            assertTrue(injured && shieldHit && recoveredAfterHit,
                "$format: the normal chain must include actual damage, shield depletion and recovery")
        }
    }
}
