package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AfterburnProductionSessionTest {
    @Test fun normal_paid_product_can_cancel_wait_real_cooldown_and_recover_automatically_in_all_formats() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val plan = public.resolve("platform/scenarios/afterburn-$format-production-chain.json")
            val output = TestArchives.directory("RWR_AFTERBURN_SESSION_ROOT", javaClass, format).resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/aa-beam-production/$format"), plan, output)
            val stages = mutableMapOf<String, JsonObject>()
            val actions = mutableMapOf<String, JsonObject>()
            var active = ""
            var sample: JsonObject? = null
            var productId: String? = null
            var automatic = false
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                when (row["kind"]) {
                    s("intent-start") -> active = row.text("intentId")
                    s("observation") -> {
                        sample = row.objectAt("sample")
                        val unit = sample!!.arrayAt("units").map { it.jsonObject }.singleOrNull {
                            it["ownerPlayerId"] == s("p1") && it.text("typeId").startsWith("aaBeamGunship")
                        }
                        if (unit != null) {
                            if (productId == null) productId = unit.text("unitId")
                            assertEquals(productId, unit.text("unitId"), "$format: conversion replaced product identity")
                            if (active == "automatic-recovery" && sample!!.arrayAt("events").any {
                                    it.jsonObject["kind"] == s("unit-action-automatic") &&
                                        it.jsonObject["unitId"] == s(productId!!)
                                }) {
                                automatic = true
                                assertEquals(s("aaBeamGunship"), unit["typeId"])
                                assertEquals(60.0, unit.objectAt("unitActions").number("automaticCooldown"))
                                assertEquals(6.0, unit.objectAt("energy").number("current"))
                                assertEquals(750.0, unit.objectAt("moveTarget").number("x"))
                                assertEquals(50.0, unit.objectAt("moveTarget").number("y"))
                            }
                        }
                    }
                    s("action") -> {
                        assertEquals(b(true), row.objectAt("result")["accepted"], "$format/$active at $output")
                        actions[active] = row
                    }
                    s("intent-end") -> stages[row.text("intentId")] = assertNotNull(sample)
                    else -> Unit
                }
            } }
            fun aa(stage: String) = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["unitId"] == s(assertNotNull(productId)) }
            fun afterBurn(stage: String) = aa(stage).objectAt("unitActions").objectAt("actions").objectAt("afterBurn")
            for (stage in listOf("burn-first", "burn-move", "burn-second"))
                assertEquals(s("aaBeamGunship_afterburn"), aa(stage)["typeId"], "$format/$stage")
            for (stage in listOf("cancel-first", "cooldown-ready", "automatic-recovery", "final-stop"))
                assertEquals(s("aaBeamGunship"), aa(stage)["typeId"], "$format/$stage")
            assertTrue(afterBurn("cancel-first").number("cooldownRemaining") > 0)
            assertEquals(b(false), afterBurn("cancel-first")["available"])
            assertEquals(0.0, afterBurn("cooldown-ready").number("cooldownRemaining"))
            assertEquals(b(true), afterBurn("cooldown-ready")["available"])
            assertTrue(automatic, "$format: no actual empty-energy automatic recovery at $output")
            assertEquals(JsonNull, aa("final-stop")["moveTarget"])
            assertTrue(aa("burn-move").objectAt("position").number("x") < 200)
            for (stage in listOf("burn-first", "cancel-first", "burn-second")) {
                assertEquals(s("UnitAction"), actions.getValue(stage)["type"])
                assertEquals(b(false), actions.getValue(stage)["cancelQueued"])
            }
        }
    }
}
