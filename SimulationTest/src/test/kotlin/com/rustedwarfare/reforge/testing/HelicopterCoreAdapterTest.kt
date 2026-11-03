package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.Step
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class HelicopterCoreAdapterTest {
    @Test fun legacy_ground_samples_keep_their_existing_unit_field_shape() {
        val repo = Path.of(System.getProperty("repo.root"))
        val input = ScenarioReader.load(repo.resolve("SimulationTest/public/scenarios/select-move-stop.json"),
            repo.resolve("SimulationTest/public/contracts/select-move-stop.json"))
        CoreAdapter(input).use { core ->
            assertTrue(core.initial().json().arrayAt("units").all {
                "flightPose" !in it.jsonObject && "height" !in it.jsonObject
            })
        }
    }

    @Test fun formal_host_and_authorized_player_samples_preserve_the_complete_flight_pose() {
        val repo = Path.of(System.getProperty("repo.root"))
        for (format in listOf("ini", "json", "hjson")) {
            val input = ScenarioReader.load(repo.resolve("SimulationTest/public/scenarios/helicopter-$format-short.json"),
                repo.resolve("SimulationTest/public/contracts/helicopter-$format-short.json"))
            CoreAdapter(input).use { core ->
                val initial = core.initial().json().arrayAt("units").single().jsonObject
                val initialPose = assertNotNull(initial["flightPose"], format).jsonObject
                assertEquals(0.0, initialPose.number("height"))
                assertEquals(.1, initialPose.number("rotorHeightOffset"))
                core.submit(obj("actionId" to s("manual-move"), "actor" to s("p1"), "unitId" to s("a"),
                    "kind" to s("move"), "target" to obj("x" to n(250.0), "y" to n(210.0))))
                core.advance(Step(0, 1.0, "sim-tick"))
                val unit = core.observe().json().arrayAt("units").single().jsonObject
                val pose = unit.objectAt("flightPose")
                assertEquals(setOf("velocity", "height", "heightPhase", "rotorPhase", "rotorHeading", "rotorHeightOffset"), pose.keys)
                assertEquals(setOf("x", "y"), pose.objectAt("velocity").keys)
                assertEquals(.0423, pose.objectAt("velocity").number("x"), 1e-10)
                assertEquals(.24, pose.number("height"), 1e-10)
                assertEquals(unit.number("height"), pose.number("height"))
                assertEquals(2.0, pose.number("heightPhase"))
                assertEquals(30.0, pose.number("rotorPhase"))
                assertEquals(120.0, pose.number("rotorHeading"))
                assertEquals(0.0, pose.number("rotorHeightOffset"))
                val views = assertNotNull(core.playerViews())
                assertEquals(pose, views.objectAt("p1").arrayAt("units").single().jsonObject.objectAt("flightPose"))
                assertTrue(views.objectAt("p2").arrayAt("units").isEmpty())
                assertEquals(0.0, initialPose.number("height"))
                assertEquals(.1, initialPose.number("rotorHeightOffset"))
            }
        }
    }
}
