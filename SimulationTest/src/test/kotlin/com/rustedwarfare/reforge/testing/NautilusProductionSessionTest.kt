package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class NautilusProductionSessionTest {
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
