package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object BunkerScene {
    fun scenario(map: MapDefinition, spawns: List<UnitSpawn> = listOf(
        UnitSpawn("bunker", "p1", "mechBunker", Position(200.0, 200.0), 90.0))): Scenario {
        val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(inputs.resolve("bunker-production/ini/package"))).value
        val old = LandingCraftScene.scenario(map, spawns = spawns)
        return old.copy(scenarioId = "bunker", unitDefinitions = old.unitDefinitions + pack.units)
    }
}

class BunkerActionTest {
    @Test fun a_sliding_bunker_or_heavy_mech_can_reverse_toward_a_move_target_behind_its_body() = WaterTestScene.withMap { map ->
        for (type in listOf("mechBunker", "mechHeavyMissile")) {
            val base = BunkerScene.scenario(map, listOf(UnitSpawn("bunker", "p1", type, Position(200.0, 200.0), 90.0)))
            val heavy = HeavyMechTestScene.declared()
            val context = AaBeamScene.open(base.copy(unitDefinitions = base.unitDefinitions + (heavy.id to heavy)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("reverse", "p1", "bunker", Position(100.0, 200.0), context.world)))
                repeat(25) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val unit = context.observe().units.single()
                assertEquals(type == "mechBunker", unit.mechPose!!.reversing,
                    "The qualified heavy input retains its zero reverse percentage")
                assertTrue(unit.position.x < 200.0, "$type must travel toward the target behind its body")
            } finally { context.close() }
        }
    }

    @Test fun queued_deployment_locks_after_the_base_update_and_preserves_move_until_undeployed() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(BunkerScene.scenario(map, listOf(
            UnitSpawn("bunker", "p1", "mechBunker", Position(200.0, 200.0), 140.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "bunker", Position(350.0, 200.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertTrue(first.position.x > 200.0, "Submission must not retroactively lock the first base update")
            assertEquals(0.0, first.speed)
            for (i in 1L..165L) {
                val unit = context.advance(Step(i, 1.0, "sim-tick")).units.single()
                assertEquals(first.position, unit.position)
                assertTrue(unit.tasks.any { it.actionId == "east" })
            }
            val fixed = context.advance(Step(166, 1.0, "sim-tick")).units.single()
            assertEquals(first.position, fixed.position)
            assertEquals(90.0, fixed.heading, .00001)
            assertEquals("immobile-unit", assertIs<ActionResult.Rejected>(context.submit(
                MoveAction("fixed-move", "p1", "bunker", Position(350.0, 200.0), context.world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("undeploy", "p1", "bunker", "undeploy", context.world)))
            for (i in 167L..267L) context.advance(Step(i, 1.0, "sim-tick"))
            val restored = context.advance(Step(268, 1.0, "sim-tick")).units.single()
            assertTrue(restored.position.x > first.position.x)
            assertTrue(restored.tasks.any { it.actionId == "east" })
        } finally { context.close() }
    }

    @Test fun normal_deploy_is_queued_then_completes_at_167_updates_and_undeploy_at_101() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(BunkerScene.scenario(map))
        try {
            fun request(id: String, name: String, cancel: Boolean = false) =
                context.submit(UnitAction(id, "p1", "bunker", name, context.world, cancel))
            assertEquals(listOf("deploy"), context.observe().units.single().unitActions!!.actions.map { it.name })
            assertIs<ActionResult.Accepted>(request("deploy", "deploy"))
            assertEquals("mechBunker", context.observe().units.single().typeId)
            assertEquals("duplicate-unit-action", assertIs<ActionResult.Rejected>(request("duplicate", "deploy")).error.code)
            assertEquals("unit-action-not-cancellable", assertIs<ActionResult.Rejected>(request("cancel", "deploy", true)).error.code)
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(.006f, first.unitActions!!.actions.single().progress)
            for (i in 1L..165L) context.advance(Step(i, 1.0, "sim-tick"))
            assertEquals("mechBunker", context.observe().units.single().typeId)
            val deployed = context.advance(Step(166, 1.0, "sim-tick")).units.single()
            assertEquals("bunker", deployed.unitId)
            assertEquals("mechBunkerDeployed", deployed.typeId)
            assertEquals(800.0, deployed.health)
            assertEquals(0f, deployed.shield!!.current)
            assertEquals(listOf("undeploy"), deployed.unitActions!!.actions.map { it.name })
            assertEquals("unsupported-unit-action", assertIs<ActionResult.Rejected>(request("hidden", "deploy")).error.code)
            assertIs<ActionResult.Accepted>(request("undeploy", "undeploy"))
            for (i in 167L..266L) context.advance(Step(i, 1.0, "sim-tick"))
            assertEquals("mechBunkerDeployed", context.observe().units.single().typeId)
            val mobile = context.advance(Step(267, 1.0, "sim-tick")).units.single()
            assertEquals("mechBunker", mobile.typeId)
            assertNull(mobile.shield)
            assertEquals(listOf("deploy"), mobile.unitActions!!.actions.map { it.name })
        } finally { context.close() }
    }
}
