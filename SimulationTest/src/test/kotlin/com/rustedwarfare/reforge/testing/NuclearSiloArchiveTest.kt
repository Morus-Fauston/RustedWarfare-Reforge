package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class NuclearSiloArchiveTest {
    @Test fun three_formats_archive_paid_ammunition_real_ground_launch_and_reject_forged_stock_queue_or_flight() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/nuclear-$format-short.json")
            val contract = public.resolve("contracts/nuclear-$format-short.json")
            val out = TestArchives.directory("RWR_NUCLEAR_ARCHIVE_ROOT", javaClass, format).resolve("run")
            val result = Runner.run(RunRequest(scenario,contract,out,listOf("api:Runner.run/NuclearSiloArchiveTest")))
            assertEquals(0,result.exitCode,result.toString())
            val rows = Files.readAllLines(out.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            assertEquals(0,TraceFiles.compare(scenario,contract,out).exitCode)
            if (format != "ini") continue
            for (fault in listOf("stock", "queue", "doors", "flight")) {
                var changed = false
                val forged = rows.map { row ->
                    if (changed || row["kind"] != s("observation") || row["phase"] != s("after-advance")) row else {
                        val sample = row.objectAt("observation")
                        if (sample["stepIndex"] != n(if (fault == "flight") 3580 else 3345)) row else {
                            changed = true
                            val field = if (fault == "flight") "projectiles" else "units"
                            val values = sample.arrayAt(field).map { value ->
                                val item = value.jsonObject
                                if (fault == "flight") JsonObject(item + ("ballistic" to JsonObject(item.objectAt("ballistic") + ("speed" to n(99)))))
                                else if (item["unitId"] != s("silo")) item else when (fault) {
                                    "doors" -> JsonObject(item + ("nuclearDoors" to n(2)))
                                    else -> JsonObject(item + ("unitActions" to JsonObject(item.objectAt("unitActions") +
                                        if (fault == "stock") ("stockpile" to n(3)) else ("queue" to array(emptyList())))))
                                }
                            }
                            JsonObject(row + ("observation" to JsonObject(sample + (field to array(values)))))
                        }
                    }
                }
                assertTrue(changed,fault)
                assertTrue(TraceComparator.evaluate(readObject(scenario),readObject(contract),forged).exitCode != 0,fault)
            }
        }
    }
}
