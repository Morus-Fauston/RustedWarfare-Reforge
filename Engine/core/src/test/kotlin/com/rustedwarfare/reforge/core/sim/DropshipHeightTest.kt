package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

internal object DropshipScene {
    fun definition(): UnitDefinition {
        val craft = LandingCraftScene.definitions().getValue("hovercraft")
        return UnitDefinition("dropship", "NativeDropship", 20.0, 20.0, 1, craft.imagePath, 1.0,
            MovementProfile(MovementType.AIR, 2.3, .03, .05, 1.4, 0.0, false, false, .6), emptySet(),
            durability = DurabilityProfile(500.0, craft.durability!!.deadImagePath), build = BuildProfile(800L, .001f),
            shadowImagePath = craft.imagePath, transport = TransportProfile.NATIVE_DROPSHIP)
    }
    fun scenario(map: MapDefinition, spawns: List<UnitSpawn> = listOf(
        UnitSpawn("air", "p1", "dropship", Position(190.0, 150.0), 90.0))): Scenario =
        LandingCraftScene.scenario(map, spawns = spawns).let {
            it.copy(scenarioId = "air-transport", unitDefinitions = it.unitDefinitions + ("dropship" to definition()))
        }
    fun open(input: Scenario): SimulationContext {
        val result = SimulationContext.initialize(input)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }
}

class DropshipHeightTest {
    @Test fun exactly_four_height_waits_for_the_next_tick_before_decrementing_the_unload_clock() = WaterTestScene.withMap { map ->
        val context = DropshipScene.open(DropshipScene.scenario(map, listOf(
            UnitSpawn("air", "p1", "dropship", Position(190.0, 150.0), 90.0),
            UnitSpawn("worker", "p1", "builder", Position(200.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "worker", "air", LoadDirection.INTO, context.world)))
            // A fixed ordinary command sequence reaches exactly 4f using native float increments.
            // No initial height, variable delta or mutable simulation state is injected.
            val commands = "MMMSSMMMMMMMMMMMMMMMMMMMMSMSSSSSSSSSSSS"
            for ((tick, mode) in commands.withIndex()) {
                val action = if (mode == 'M') MoveAction("move-$tick", "p1", "air", Position(340.0, 150.0), context.world)
                    else StopAction("stop-$tick", "p1", "air", context.world)
                assertIs<ActionResult.Accepted>(context.submit(action))
                context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
            }
            assertEquals(4.0, context.observe().units.single { it.unitId == "air" }.height)
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "air", true, context.world)))
            val first = context.advance(Step(commands.length.toLong(), 1.0, "sim-tick")).units.single { it.unitId == "air" }
            assertEquals(3.6, first.height, .000001)
            assertEquals(30f, first.transport!!.unloadRemaining)
            val second = context.advance(Step(commands.length + 1L, 1.0, "sim-tick")).units.single { it.unitId == "air" }
            assertEquals(29f, second.transport!!.unloadRemaining)
        } finally { context.close() }
    }

    @Test fun idle_air_transport_lands_on_dry_ground_but_flies_while_a_real_move_is_active() = WaterTestScene.withMap { map ->
        val context = DropshipScene.open(DropshipScene.scenario(map))
        try {
            assertEquals(0.0, context.observe().units.single().height)
            repeat(5) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(2.0, context.observe().units.single().height)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("fly", "p1", "air", Position(340.0, 150.0), context.world)))
            repeat(10) { context.advance(Step(it + 5L, 1.0, "sim-tick")) }
            val airborne = context.observe().units.single()
            assertTrue(airborne.height in 5.49..5.51)
            assertTrue(airborne.tasks.any { it.kind == UnitTaskKind.MOVE })
            assertIs<ActionResult.Accepted>(context.submit(StopAction("land", "p1", "air", context.world)))
            repeat(10) { context.advance(Step(it + 15L, 1.0, "sim-tick")) }
            assertEquals(2.0, context.observe().units.single().height)
            assertNull(context.observe().units.single().cooldown)
        } finally { context.close() }
    }
}
