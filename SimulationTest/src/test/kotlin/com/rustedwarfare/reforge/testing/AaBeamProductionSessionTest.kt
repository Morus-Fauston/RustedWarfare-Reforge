package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AaBeamProductionSessionTest {
    @Test fun all_formats_normally_produce_move_stop_and_attack_with_a_visible_full_recharge_cycle() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val plan = public.resolve("platform/scenarios/aa-beam-$format-production-chain.json")
            val output = TestArchives.directory("RWR_AA_BEAM_ARCHIVE_ROOT", javaClass, format).resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/aa-beam-production/$format"), plan, output)
            val stages = mutableMapOf<String, JsonObject>()
            val actions = mutableMapOf<String, JsonObject>()
            var active = ""
            var sample: JsonObject? = null
            var locked = false
            var fullAfterLock = false
            var firedAfterFull = false
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                when (row["kind"]) {
                    s("observation") -> {
                        sample = row.objectAt("sample")
                        sample!!.arrayAt("units").map { it.jsonObject }.singleOrNull {
                            it["ownerPlayerId"] == s("p1") && it["typeId"] == s("aaBeamGunship")
                        }?.objectAt("energy")?.let { energy ->
                            if (energy["recharging"] == b(true)) locked = true
                            if (locked && energy["recharging"] == b(false) && energy.number("current") == 6.0) fullAfterLock = true
                            if (fullAfterLock && energy.number("current") < 6.0) firedAfterFull = true
                        }
                    }
                    s("intent-start") -> active = row.text("intentId")
                    s("action") -> { assertEquals(b(true), row.objectAt("result")["accepted"], "$format/$active $output"); actions[active] = row }
                    s("intent-end") -> stages[row.text("intentId")] = assertNotNull(sample)
                    else -> Unit
                }
            } }
            assertEquals(readObject(plan).arrayAt("intents").map { it.jsonObject.text("intentId") }.toSet(), actions.keys)
            fun owned(stage: String, type: String) = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["ownerPlayerId"] == s("p1") && it["typeId"] == s(type) }
            assertEquals(2500.0, owned("wait-air-3", "aaBeamGunship").number("maxHealth"))
            assertEquals(s("MoveAction"), actions.getValue("approach-air")["type"])
            assertEquals(s("AttackAction"), actions.getValue("attack-air")["type"])
            assertEquals(s("StopAction"), actions.getValue("stop-home")["type"])
            assertEquals(JsonNull, owned("stop-home", "aaBeamGunship")["moveTarget"])
            assertTrue(owned("approach-air", "aaBeamGunship").objectAt("position").number("x") > 400.0)
            assertTrue(owned("stop-home", "aaBeamGunship").objectAt("position").number("x") < 350.0)
            assertTrue(locked && fullAfterLock && firedAfterFull, "$format: actual owner recharge cycle missing at $output")
        }
    }
}
