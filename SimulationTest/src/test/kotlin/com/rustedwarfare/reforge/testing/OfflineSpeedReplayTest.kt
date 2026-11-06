package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.client.OfflineRate
import com.rustedwarfare.reforge.client.OfflineStepClock
import com.rustedwarfare.reforge.core.sim.ActionResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*

class OfflineSpeedReplayTest {
    @Test fun complete_fixed_nonce_chains_match_every_core_and_player_field_at_every_step() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val evidence = System.getProperty("offline.speed.evidence")?.let { Files.createDirectories(Path.of(it)) }
            ?: Files.createTempDirectory("offline-speed-replay-")
        val reports = mutableListOf<JsonObject>()
        for (family in listOf("naval", "heavy-mech")) {
            val loaded = ScenarioReader.load(root.resolve("scenarios/$family-production-ini-chain.json"),
                root.resolve("contracts/$family-production-ini-chain.json"))
            // Archive the existing authorized player projection too; do not alter the loaded core rules.
            val input = loaded.copy(scenarioDocument = JsonObject(loaded.scenarioDocument +
                ("automaticVision" to obj("observers" to array(listOf(s("p1"), s("p2")))))))
            assertEquals(0, input.steps.size % 4)
            val actions = input.actions.groupBy { it.integer("atStep") }
            var baseline: List<String>? = null
            for (rate in OfflineRate.entries) {
                val clock = OfflineStepClock().apply { setRate(rate) }
                val digests = mutableListOf<String>()
                var frames = 0
                var index = 0
                var productionStep: Long? = null
                var projectileBirths = 0
                val path = evidence.resolve("$family-${rate.multiplier}x.jsonl")
                Files.newBufferedWriter(path, CREATE_NEW).use { writer ->
                    CoreAdapter(input).use { core ->
                        core.initial()
                        while (index < input.steps.size) {
                            val frame = clock.advance(1.0 / 60) {
                                assertTrue(index < input.steps.size, "No callback beyond the fixed target")
                                val step = input.steps[index]
                                val results = actions[step.stepIndex].orEmpty().map { action ->
                                    assertIs<ActionResult.Accepted>(core.submit(action)).json()
                                }
                                core.advance(step)
                                val observation = core.observe()
                                if (observation.events.any { it.kind == "unit-produced" }) productionStep = step.stepIndex
                                projectileBirths += observation.projectileEvents.count { it.kind == "born" }
                                val row = obj("stepIndex" to n(step.stepIndex), "actions" to array(results),
                                    "core" to observation.json(), "players" to core.playerViews())
                                val serialized = row.toString()
                                writer.write(serialized); writer.newLine()
                                val digest = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
                                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                                baseline?.let { assertEquals(it[index], digest, "$family ${rate.multiplier}x step ${step.stepIndex}; full raw snapshots preserved") }
                                digests += digest
                                index++
                            }
                            assertTrue(frame.executedSteps in 1..8)
                            frames++
                        }
                    }
                }
                assertEquals(input.steps.size, index)
                assertNotNull(productionStep, "The normal paid production chain must actually complete")
                assertTrue(projectileBirths > 0, "The full combat tail must actually produce projectiles")
                assertEquals(input.steps.size / rate.multiplier, frames)
                if (baseline == null) baseline = digests
                reports += obj("family" to s(family), "rate" to n(rate.multiplier), "steps" to n(index),
                    "frames" to n(frames), "productionStep" to n(productionStep), "projectileBirths" to n(projectileBirths),
                    "scenarioSha256" to s(loaded.scenarioIdentity.sha256), "contractSha256" to s(loaded.contractIdentity.sha256),
                    "scope" to s("Complete fixed-nonce CoreAdapter plus actual host clock; exact full snapshots, no ID/numeric normalization, no native or wall-clock performance claim"))
            }
        }
        Files.writeString(evidence.resolve("summary.json"), array(reports).toString(), CREATE_NEW)
    }
}
