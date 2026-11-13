package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class NautilusProductionSessionTest {
    @Test fun normally_built_mother_pays_refunds_requeues_and_completes_ammo_while_using_movement_in_three_formats() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_NAUTILUS_SESSION_ROOT", javaClass, "naval-ammo-$format").resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/nautilus-production/$format"),
                public.resolve("platform/scenarios/nautilus-$format-naval-ammo.json"), output)
            val payments = mutableListOf<Double>()
            var previousBalance = 0.0
            var paidAction: JsonObject? = null
            var mother: JsonObject? = null
            var initialX: Double? = null
            var progressedInMotion = false
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).use { reader ->
                reader.lineSequence().forEach { line ->
                    val row = json.parseToJsonElement(line).jsonObject
                    if (row["kind"] == s("action") && row["actionName"] == s("buildAntiNuke")) {
                        assertEquals(b(true), row.objectAt("result")["accepted"], "$format $row")
                        paidAction = row
                    }
                    if (row["kind"] != s("observation")) return@forEach
                    val sample = row.objectAt("sample")
                    val balance = sample.number("credits")
                    if (paidAction != null) {
                        assertEquals(s("after-action"), row["phase"])
                        payments.add(balance - previousBalance)
                        paidAction = null
                    }
                    previousBalance = balance
                    val unit = sample.arrayAt("units").map { it.jsonObject }
                        .singleOrNull { it["typeId"] == s("nautilusSubmarine") } ?: return@forEach
                    mother = unit
                    val x = unit.objectAt("position").number("x")
                    val start = initialX ?: x.also { initialX = it }
                    val first = unit.objectAt("unitActions").arrayAt("queue").firstOrNull()?.jsonObject
                    progressedInMotion = progressedInMotion ||
                        x > start + 20 && first?.number("progress")?.let { it in .01..<1.0 } == true
                }
            }
            assertEquals(listOf(-6000.0, 6000.0, -6000.0), payments, format)
            assertTrue(progressedInMotion, format)
            val actions = assertNotNull(mother, format).objectAt("unitActions")
            assertEquals(n(1), actions["stockpile"], format)
            assertTrue(actions.arrayAt("queue").isEmpty(), format)
        }
    }

    @Test fun normally_built_land_nautilus_cancels_and_requeues_a_paid_scout_then_registers_owned_units_in_three_formats() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_NAUTILUS_SESSION_ROOT", javaClass, "scout-$format").resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/nautilus-production/$format"),
                public.resolve("platform/scenarios/nautilus-$format-scout-launch.json"), output)
            val rows = Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).use { reader ->
                reader.lineSequence().map { json.parseToJsonElement(it).jsonObject }.filter { it["kind"] in setOf(s("action"),
                    s("observation"), s("intent-start")) }.toList()
            }
            val actions = rows.filter { it["kind"] == s("action") && it["actionName"] == s("launchScoutBot") }
            assertEquals(3, actions.size)
            assertTrue(actions.all { it.objectAt("result")["accepted"] == b(true) })
            assertEquals(listOf(b(false), b(true), b(false)), actions.map { it["cancelQueued"] })
            fun balance(row: JsonObject): Double = row.objectAt("sample").let {
                it.number(if ("credits" in it) "credits" else "balance")
            }
            assertEquals(listOf(-250.0, 250.0, -250.0), actions.map { action ->
                val index = rows.indexOf(action)
                val before = rows.take(index).last { it["kind"] == s("observation") }
                val after = rows.drop(index + 1).first { it["kind"] == s("observation") }
                balance(after) - balance(before)
            })
            val samples = rows.filter { it["kind"] == s("observation") }.map { it.objectAt("sample") }
            val final = samples.last().arrayAt("units").map { it.jsonObject }
            val mother = final.single { it["typeId"] == s("nautilusSubmarineLand") }
            assertTrue(mother.objectAt("unitActions").objectAt("actions").objectAt("launchScoutBot").number("cooldownRemaining") > 0)
            val scouts = final.filter { it["typeId"] == s("robotCrab") }
            assertTrue(scouts.isNotEmpty(), "A normal near-ground launch must yield a scout in the fixed case")
            assertTrue(scouts.all { it["ownerPlayerId"] == s("p1") && it["carriedBy"] in setOf(null, JsonNull) &&
                it.objectAt("energy").number("current") in 0.0..<1.0 })
            assertTrue(samples.any { sample -> sample.arrayAt("events").any { it.jsonObject["kind"] == s("scout-bot-spawned") } })
        }
    }

    @Test fun normal_experimental_factory_chain_archives_land_water_dive_surface_unload_and_shore_return() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_NAUTILUS_SESSION_ROOT", javaClass, "land-$format").resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/nautilus-production/$format"),
                public.resolve("platform/scenarios/nautilus-$format-land-forms.json"), output)
            var actionCount = 0
            var spentEnergy = false
            var firedAtEnemy = false
            var final: JsonObject? = null
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).use { reader ->
                reader.lineSequence().forEach { line ->
                    val row = json.parseToJsonElement(line).jsonObject
                    if (row["kind"] == s("action")) {
                        actionCount++
                        assertEquals(b(true), row.objectAt("result")["accepted"], row.toString())
                    }
                    if (row["kind"] == s("observation")) {
                        val sample = row.objectAt("sample")
                        final = sample
                        spentEnergy = spentEnergy || sample.arrayAt("units").map { it.jsonObject }.any {
                            it["typeId"] == s("nautilusSubmarineLand") && it.objectAt("energy").number("current") < 6
                        }
                        firedAtEnemy = firedAtEnemy || sample.arrayAt("projectileEvents").map { it.jsonObject }.any {
                            it["kind"] == s("born") && it["targetUnitId"] == s("b") && it["sourceWeaponName"] == s("2")
                        }
                    }
                }
            }
            assertTrue(actionCount > 0)
            val units = assertNotNull(final).arrayAt("units").map { it.jsonObject }
            val mother = units.single { it["typeId"] == s("nautilusSubmarineLand") }
            assertEquals(n(0), mother.objectAt("transport")["passengerCount"])
            assertEquals(1, units.count { it["typeId"] == s("hoverTank") })
            assertTrue(spentEnergy, "Normally produced Land mother must actually spend weapon energy")
            assertTrue(firedAtEnemy, "Normal acquisition must include a real named-weapon birth against the enemy")
            assertEquals(b(false), units.single { it["unitId"] == s("b") }["alive"], "Real target must die from the normal attack")
        }
    }

    @Test fun three_syntaxes_pay_for_the_complete_shipyard_chain_then_produce_in_motion_and_retain_a_full_head() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_NAUTILUS_SESSION_ROOT", javaClass, format).resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/nautilus-production/$format"),
                public.resolve("platform/scenarios/nautilus-$format-production-chain.json"), output)
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val accepted = rows.filter { it["kind"] == s("action") }
            assertTrue(accepted.isNotEmpty())
            accepted.forEach { assertEquals(b(true), it.objectAt("result")["accepted"], it.toString()) }
            val samples = rows.filter { it["kind"] == s("observation") }.map { it.objectAt("sample") }
            assertEquals(listOf("commandCenter"), samples.first().arrayAt("units").map { it.jsonObject }
                .filter { it["ownerPlayerId"] == s("p1") }.map { it.text("typeId") })
            val final = samples.last()
            val mother = final.arrayAt("units").map { it.jsonObject }.single { it["typeId"] == s("nautilusSubmarine") }
            assertTrue(mother.objectAt("position").number("x") > 500)
            assertEquals(n(4), mother.objectAt("transport")["passengerCount"])
            assertEquals(n(3), mother.objectAt("transport")["capacity"])
            assertEquals(b(false), mother.objectAt("transport")["canUnload"])
            assertTrue(final.arrayAt("units").none { it.jsonObject["typeId"] == s("hoverTank") })
            val queue = final.arrayAt("factoryQueues").map { it.jsonObject }.single { it["factoryId"] == mother["unitId"] }
            assertEquals(1, queue.arrayAt("items").size)
            assertEquals(1.0, queue.arrayAt("items").single().jsonObject.number("progress"))
        }
    }
}
