package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.client.ContentPackChoice
import com.rustedwarfare.reforge.client.SandboxLoader
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.Step
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class MechCoreAdapterTest {
    @Test
    fun actual_core_and_player_archives_keep_mech_pose_without_changing_the_legacy_unit_schema() {
        val repo = Path.of(System.getProperty("repo.root"))
        val base = ScenarioReader.load(repo.resolve("SimulationTest/public/scenarios/select-move-stop.json"),
            repo.resolve("SimulationTest/public/contracts/select-move-stop.json"))
        CoreAdapter(base).use { old ->
            assertTrue(old.initial().json().arrayAt("units").all { "mechPose" !in it.jsonObject })
        }
        for (format in listOf("ini", "json", "hjson")) {
            val root = repo.resolve("SimulationTest/public/inputs/mech-production/$format")
            val set = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root.resolve("package")))).value
            val scene = SandboxLoader().use { loader ->
                loader.requestPack(root, ContentPackChoice(set, "mechGun"))
                val deadline = System.nanoTime() + 5_000_000_000L
                var loaded = loader.pollPrepared()
                while (loaded == null && System.nanoTime() < deadline) { Thread.sleep(5); loaded = loader.pollPrepared() }
                checkNotNull(loaded).getOrThrow().scenario
            }
            val input = base.copy(core = scene, scenarioDocument = JsonObject(base.scenarioDocument +
                ("automaticVision" to obj("observers" to array(listOf(s("p1"), s("p2")))))))
            CoreAdapter(input).use { core ->
                val initial = core.initial().json().arrayAt("units").first { it.jsonObject["unitId"] == s("a") }.jsonObject
                assertTrue("mechPose" in initial)
                assertEquals(2, initial.objectAt("mechPose").arrayAt("legs").size)
                core.submit(obj("actionId" to s("move"), "actor" to s("p1"), "unitId" to s("a"), "kind" to s("move"),
                    "target" to obj("x" to n(250.0), "y" to n(210.0))))
                repeat(10) { core.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val unit = core.observe().json().arrayAt("units").first { it.jsonObject["unitId"] == s("a") }.jsonObject
                val pose = unit.objectAt("mechPose")
                assertEquals(setOf("forwardHeading", "angularVelocity", "reversing", "legs"), pose.keys)
                val leg = pose.arrayAt("legs").first().jsonObject
                assertEquals(2.05, leg.getValue("height").jsonPrimitive.double, 1e-5)
                assertEquals(s("SWINGING"), leg["phase"])
                for (player in listOf("p1", "p2")) {
                    val published = core.playerViews()!!.objectAt(player).arrayAt("units")
                        .first { it.jsonObject["unitId"] == s("a") }.jsonObject
                    assertEquals(pose, published.objectAt("mechPose"))
                }
                assertEquals(.1, initial.objectAt("mechPose").arrayAt("legs").first().jsonObject.getValue("height").jsonPrimitive.double)
            }
        }
    }
}
