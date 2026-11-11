package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class NautilusProductionSessionTest {
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
