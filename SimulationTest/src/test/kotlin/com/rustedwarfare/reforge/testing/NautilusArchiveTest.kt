package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class NautilusArchiveTest {
    @Test fun three_syntaxes_archive_internal_births_strict_excess_waiting_and_full_price_cancel() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/nautilus-$format-short.json")
            val contract = public.resolve("contracts/nautilus-$format-short.json")
            val output = TestArchives.directory("RWR_NAUTILUS_ARCHIVE_ROOT", javaClass, format).resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/NautilusArchiveTest")))
            assertEquals(0, result.exitCode, result.toString())
            assertEquals(0, TraceFiles.compare(scenario, contract, output).exitCode)
            if (format == "json") {
                val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                for (fault in listOf("capacity", "energy", "duplicate-passenger", "ordinary-unload")) {
                    val changed = rows.map { row ->
                        if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                            row.objectAt("observation")["stepIndex"] != n(1000)) row else {
                            val sample = row.objectAt("observation")
                            val units = sample.arrayAt("units").map { value ->
                                val unit = value.jsonObject
                                if (unit["unitId"] != s("mother")) unit else if (fault == "energy")
                                    JsonObject(unit + ("energy" to JsonObject(unit.objectAt("energy") + ("current" to n(0)))))
                                else {
                                    val cargo = unit.objectAt("transport")
                                    val alteration = when (fault) {
                                        "capacity" -> "capacity" to n(4)
                                        "ordinary-unload" -> "canUnload" to b(true)
                                        else -> "passengerIds" to array(cargo.arrayAt("passengerIds").let { listOf(it[0], it[0], it[2], it[3]) })
                                    }
                                    JsonObject(unit + ("transport" to JsonObject(cargo + alteration)))
                                }
                            }
                            JsonObject(row + ("observation" to JsonObject(sample + ("units" to array(units)))))
                        }
                    }
                    assertTrue(TraceComparator.evaluate(readObject(scenario), readObject(contract), changed).exitCode != 0, fault)
                }
            }
        }
    }
}
