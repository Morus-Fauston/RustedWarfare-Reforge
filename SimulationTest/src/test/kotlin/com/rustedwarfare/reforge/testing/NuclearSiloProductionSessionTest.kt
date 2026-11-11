package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class NuclearSiloProductionSessionTest {
    @Test fun three_formats_normally_produce_worker_construct_paid_silo_make_ammunition_and_hit_a_ground_point() {
        val public=Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini","json","hjson")) {
            val out=TestArchives.directory("RWR_NUCLEAR_SESSION_ROOT",javaClass,format).resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/nuclear-silo/$format"),
                public.resolve("platform/scenarios/nuclear-$format-production-chain.json"),out)
            var final: JsonObject?=null
            var born=false; var impact=false; var opened=false
            Files.newBufferedReader(out.resolve("client-diagnostic.jsonl")).use { reader -> reader.lineSequence().forEach { line ->
                val row=json.parseToJsonElement(line).jsonObject
                if (row["kind"] == s("action")) assertEquals(b(true),row.objectAt("result")["accepted"],row.toString())
                if (row["kind"] == s("observation")) {
                    val sample=row.objectAt("sample");final=sample
                    opened=opened || sample.arrayAt("units").map { it.jsonObject }.any { (it["nuclearDoors"] as? JsonPrimitive)?.doubleOrNull?.let { it>0 } == true }
                    for (event in sample.arrayAt("projectileEvents").map { it.jsonObject }.filter { it["appearance"] == s("NUCLEAR") }) {
                        born=born || event["kind"] == s("born")
                        impact=impact || event["kind"] == s("impact")
                    }
                }
            } }
            val units=assertNotNull(final).arrayAt("units").map { it.jsonObject }
            assertEquals(1500.0,units.single { it["unitId"] == s("b") }.number("health"))
            assertEquals(n(0),units.single { it["typeId"] == s("nukeLauncherC") }.objectAt("unitActions")["stockpile"])
            assertTrue(born && impact && opened)
        }
    }
}
