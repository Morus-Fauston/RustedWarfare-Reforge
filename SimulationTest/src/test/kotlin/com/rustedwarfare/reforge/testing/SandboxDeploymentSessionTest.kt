package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class SandboxDeploymentSessionTest {
    @Test fun fixed_and_event_deployments_bind_aliases_used_by_later_normal_intents() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val root = TestArchives.directory("RWR_DEPLOYMENT_ARCHIVE_ROOT", javaClass, "rwr-deployment-")
        Files.createDirectories(root)
        val plan = public.resolve("platform/scenarios/sandbox-batch-deployment.json")
        val run = root.resolve("run")
        PlatformSessionRunner.run(public.resolve("inputs/content-movement-knowledge/P001-移动沙盒输入"), plan, run)
        val rows = Files.readAllLines(run.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val batches = rows.filter { it["kind"] == s("sandbox-command") && it["status"] == s("APPLIED") }
        assertEquals(listOf(2L, 3L), batches.map { it.integer("stepIndex") })
        val reinforcement = batches.last().objectAt("generated").id("reinforcement")
        val move = rows.single { it["kind"] == s("action") && it["type"] == s("MoveAction") }
        assertEquals(reinforcement, move.id("unitId"))
        assertEquals(b(true), move.objectAt("result")["accepted"])
    }

    @Test fun a_missing_observed_event_stops_after_its_simulation_budget() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val root = TestArchives.directory("RWR_DEPLOYMENT_ARCHIVE_ROOT", javaClass, "rwr-deployment-expiry-")
        Files.createDirectories(root)
        val plan = root.resolve("timeout-deployment.json")
        Files.writeString(plan, """
            {"schemaVersion":1,"deploymentEnabled":true,"intents":[
              {"intentId":"bounded","kind":"deploy","afterEvent":{"kind":"unit-produced"},"maxWaitSteps":2,
                "entries":[{"alias":"escort","typeId":"tank","x":190,"y":110,"owner":"p1"}]}
            ]}
        """.trimIndent())
        val run = root.resolve("timeout-run")
        val error = assertFailsWith<InputError> {
            PlatformSessionRunner.run(public.resolve("inputs/content-movement-knowledge/P001-移动沙盒输入"), plan, run)
        }
        assertEquals("Deployment event did not arrive within 2 steps", error.message)
        val rows = Files.readAllLines(run.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        assertEquals(2, rows.count { it["kind"] == s("observation") && it["phase"] == s("step") })
        assertTrue(rows.none { it["kind"] == s("sandbox-command") })
    }

    @Test fun an_ordinary_plan_cannot_deploy_units() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val root = TestArchives.directory("RWR_DEPLOYMENT_ARCHIVE_ROOT", javaClass, "rwr-deployment-disabled-")
        val plan = root.resolve("disabled.json")
        val enabled = public.resolve("platform/scenarios/sandbox-batch-deployment.json")
        Files.writeString(plan, Files.readString(enabled).replace("\"deploymentEnabled\": true", "\"deploymentEnabled\": false"))
        val run = root.resolve("disabled-run")
        val error = assertFailsWith<InputError> {
            PlatformSessionRunner.run(public.resolve("inputs/content-movement-knowledge/P001-移动沙盒输入"), plan, run)
        }
        assertEquals("Explicit offline deployment is required for deployment or alias intents", error.message)
        assertFalse(Files.exists(run))
    }
}
