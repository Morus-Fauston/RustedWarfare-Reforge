package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class NautilusArchiveTest {
    @Test fun all_three_forms_archive_real_conversion_unload_and_shore_return_and_reject_forged_form_or_energy() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/nautilus-$format-forms.json")
            val contract = public.resolve("contracts/nautilus-$format-forms.json")
            val out = TestArchives.directory("RWR_NAUTILUS_ARCHIVE_ROOT", javaClass, "forms-$format")
            run {
                assertEquals(0, Runner.run(RunRequest(scenario, contract, out.resolve("run"), listOf("api:Runner.run/NautilusArchiveTest"))).exitCode)
                val rows = Files.readAllLines(out.resolve("run/reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                for (fault in listOf("form", "energy", "duplicate-passenger", "automatic-clock", "conversion-event")) {
                    val changed = rows.map { row ->
                        if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                            row.objectAt("observation")["stepIndex"] != n(250)) row else {
                            val sample = row.objectAt("observation")
                            val units = sample.arrayAt("units").map { value ->
                                val unit = value.jsonObject
                                if (unit["unitId"] != s("mother")) unit else JsonObject(unit + when (fault) {
                                    "form" -> "typeId" to s("nautilusSubmarineLand")
                                    "energy" -> "energy" to JsonObject(unit.objectAt("energy") + ("current" to n(7)))
                                    "automatic-clock" -> "unitActions" to JsonObject(unit.objectAt("unitActions") + ("automaticCooldown" to n(60)))
                                    "conversion-event" -> "typeId" to unit.required("typeId")
                                    else -> "transport" to JsonObject(unit.objectAt("transport") +
                                        ("passengerIds" to array(listOf(s("produced:p1:${1.toString().padStart(32, '0')}"),s("duplicate")))))
                                })
                            }
                            val revised = sample + ("units" to array(units))
                            JsonObject(row + ("observation" to JsonObject(if (fault == "conversion-event")
                                revised + ("events" to array(sample.arrayAt("events").filter { it.jsonObject["kind"] != s("unit-action-completed") }))
                                else revised)))
                        }
                    }
                    assertTrue(TraceComparator.evaluate(readObject(scenario), readObject(contract), changed).exitCode != 0, fault)
                }
            }
        }
    }

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
