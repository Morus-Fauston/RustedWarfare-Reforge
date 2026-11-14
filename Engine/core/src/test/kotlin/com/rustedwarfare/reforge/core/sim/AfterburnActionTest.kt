package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

internal object AfterburnScene {
    fun scenario(map: MapDefinition, root: Path, spawns: List<UnitSpawn> = listOf(
        UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0))): Scenario {
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(AfterburnContentFixture.write(root, "json"))).value
        val old = LandingCraftScene.scenario(map, spawns = spawns)
        return old.copy(scenarioId = "afterburn", unitDefinitions = old.unitDefinitions + pack.units)
    }
}

class AfterburnActionTest {
    @TempDir lateinit var temporary: Path

    @Test fun a_distinct_authored_gunship_switches_its_declared_forms_and_keeps_the_move_order() = WaterTestScene.withMap { map ->
        val root = AfterburnContentFixture.write(temporary, "json")
        for (name in listOf("aa.json", "afterburn.json")) {
            val path = root.resolve(name)
            val document = org.hjson.JsonValue.readJSON(Files.readString(path)
                .replace("aaBeamGunship_afterburn", "escort_boosted").replace("aaBeamGunship", "escort")).asObject()
            document.set("afterburn", org.hjson.JsonValue.readJSON(
                """{"normalForm":"escort","boostedForm":"escort_boosted"}"""))
            Files.writeString(path, document.toString())
        }
        val result = ContentLoader.loadContentPack(root)
        val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value.units
        val input = LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("aa", "p1", "escort", Position(150.0, 150.0), 90.0)))
        val context = AaBeamScene.open(input.copy(unitDefinitions = input.unitDefinitions + definitions))
        try {
            val destination = Position(480.0, 240.0)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "aa", destination, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", context.world)))
            val running = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.unitId == "aa" }
            assertEquals("escort_boosted", running.typeId)
            assertEquals(destination, running.moveTarget)
            assertEquals(6f, running.energy?.current)
            val drained = context.advance(Step(1, 1.0, "sim-tick")).units.single { it.unitId == "aa" }
            assertEquals(5.98f, drained.energy?.current)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("cancel", "p1", "aa", "cancelBurn", context.world)))
            val restored = context.advance(Step(2, 1.0, "sim-tick")).units.single { it.unitId == "aa" }
            assertEquals("escort", restored.typeId)
            assertEquals(destination, restored.moveTarget)
            assertTrue(assertNotNull(restored.unitActions).actions.single { it.name == "afterBurn" }.cooldownRemaining > 0)
        } finally { context.close() }
    }

    @Test fun zero_sweep_in_the_accelerated_form_stops_the_existing_sweep_clock_and_offset() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(AfterburnScene.scenario(map, temporary))
        try {
            repeat(120) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", context.world)))
            val converted = context.advance(Step(120, 1.0, "sim-tick")).combat!!.weapons.single().idle!!
            repeat(20) { context.advance(Step(121L + it, 1.0, "sim-tick")) }
            val accelerated = context.observe().combat!!.weapons.single().idle!!
            assertEquals(converted.timer, accelerated.timer)
            assertEquals(converted.offset, accelerated.offset)
        } finally { context.close() }
    }

    @Test fun a_retained_queue_item_uses_the_current_form_action_effect_after_conversion() = WaterTestScene.withMap { map ->
        val scene = AfterburnScene.scenario(map, temporary, listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship_afterburn", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship_afterburn", Position(350.0, 150.0), 270.0)))
        val context = AaBeamScene.open(scene)
        try {
            repeat(20) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("cancel", "p1", "aa", "cancelBurn", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", context.world)))
            val base = context.advance(Step(20, 1.0, "sim-tick"))
            assertEquals("aaBeamGunship", base.units.single { it.unitId == "aa" }.typeId)
            val second = context.advance(Step(21, 1.0, "sim-tick"))
            assertTrue(second.events.any { it.kind == "projectile-fired" && it.unitId == "aa" })
            val unit = second.units.single { it.unitId == "aa" }
            assertEquals("aaBeamGunship_afterburn", unit.typeId)
            assertEquals(6f, unit.energy!!.current, "Current base action sets energy; the old afterburn IGNORE must not survive")
        } finally { context.close() }
    }

    @Test fun owner_reads_queue_and_cooldown_while_enemy_projection_hides_them_and_reopen_resets_them() = WaterTestScene.withMap { map ->
        val scene = AfterburnScene.scenario(map, temporary)
        val context = AaBeamScene.open(scene)
        try {
            val oldWorld = context.world
            val initial = context.observePlayer(oldWorld, "p1").units.single().unitActions
            assertNotNull(initial)
            assertEquals(listOf("afterBurn"), initial.actions.map { it.name })
            assertTrue(initial.actions.single().available)
            assertNull(context.observePlayer(oldWorld, "p2").units.single().unitActions)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", oldWorld)))
            val queued = context.observePlayer(oldWorld, "p1").units.single().unitActions!!.actions.single()
            assertEquals(0, queued.queueIndex)
            assertEquals(0f, queued.progress)
            assertFalse(queued.available)
            val foreign = context.observePlayer(oldWorld, "p2")
            assertTrue(foreign.events.none { it.kind.startsWith("unit-action-") })
            context.advance(Step(0, 1.0, "sim-tick"))
            val actions = context.observePlayer(oldWorld, "p1").units.single().unitActions!!.actions
            assertEquals(listOf("afterBurn", "cancelBurn"), actions.map { it.name })
            assertEquals(25000, actions.first().cooldownRemaining)
            assertFalse(actions.first().available)
            assertTrue(actions.last().available)
            assertNull(actions.first().queueIndex)
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            val reopened = context.observe().units.single().unitActions!!
            assertEquals(initial, reopened)
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(
                UnitAction("stale", "p1", "aa", "afterBurn", oldWorld))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("new", "p1", "aa", "afterBurn", context.world)))
        } finally { context.close() }
    }

    @Test fun simulation_rejects_an_action_target_missing_from_the_registered_definitions() = WaterTestScene.withMap { map ->
        val scene = AfterburnScene.scenario(map, temporary)
        val result = SimulationContext.initialize(scene.copy(
            unitDefinitions = scene.unitDefinitions - "aaBeamGunship_afterburn"))
        if (result is InitResult.Accepted) result.context.close()
        assertEquals("invalid-unit-action-target", assertIs<InitResult.Rejected>(result).error.code)
    }

    @Test fun positive_energy_keeps_the_form_and_empty_energy_automatically_restores_it_without_waiting_for_action_cooldown() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(AfterburnScene.scenario(map, temporary))
        try {
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            for (index in 1L..299L) {
                val frame = context.advance(Step(index, 1.0, "sim-tick"))
                val unit = frame.units.single()
                assertEquals("aaBeamGunship_afterburn", unit.typeId)
                assertTrue(unit.energy!!.current > 0f)
                assertTrue(frame.projectiles.isEmpty())
            }
            var recovered = false
            for (index in 300L..305L) {
                val before = context.observe().units.single()
                val frame = context.advance(Step(index, 1.0, "sim-tick"))
                val unit = frame.units.single()
                if (unit.typeId == "aaBeamGunship") {
                    assertTrue(before.energy!!.current in 0f..0.02001f)
                    assertEquals(6f, unit.energy!!.current)
                    assertTrue(frame.events.any { it.kind == "unit-action-automatic" && it.unitId == "aa" })
                    recovered = true
                    break
                }
            }
            assertTrue(recovered, "The first eligible empty-energy update must restore the base form")
            assertEquals("unit-action-cooldown", assertIs<ActionResult.Rejected>(context.submit(
                UnitAction("still-cooling", "p1", "aa", "afterBurn", context.world))).error.code)
        } finally { context.close() }
    }

    @Test fun cancelling_the_form_keeps_the_matching_action_cooldown_until_the_integer_clock_expires() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(AfterburnScene.scenario(map, temporary))
        try {
            fun request(id: String, name: String = "afterBurn", cancel: Boolean = false) =
                context.submit(UnitAction(id, "p1", "aa", name, context.world, cancel))
            assertIs<ActionResult.Accepted>(request("burn"))
            assertEquals("duplicate-unit-action", assertIs<ActionResult.Rejected>(request("duplicate")).error.code)
            assertEquals("unit-action-not-cancellable", assertIs<ActionResult.Rejected>(request("remove", cancel = true)).error.code)
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals("unit-action-cooldown", assertIs<ActionResult.Rejected>(request("inherited")).error.code)
            assertIs<ActionResult.Accepted>(request("cancel", "cancelBurn"))
            context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(6f, context.observe().units.single().energy!!.current)
            assertEquals("unit-action-cooldown", assertIs<ActionResult.Rejected>(request("full-energy")).error.code)
            // Fixed 1-delta original float-to-int clock advances 16 each time in this range.
            // First completion at 16 adds 25000; 1563 total updates reaches only 25008.
            for (index in 2L..1562L) context.advance(Step(index, 1.0, "sim-tick"))
            assertEquals("unit-action-cooldown", assertIs<ActionResult.Rejected>(request("before-expiry")).error.code)
            context.advance(Step(1563L, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(request("expired"))
        } finally { context.close() }
    }

    @Test fun afterburn_queues_then_converts_the_same_unit_and_cancel_preserves_the_move_order() = WaterTestScene.withMap { map ->
        val scene = AfterburnScene.scenario(map, temporary)
        val context = AaBeamScene.open(scene)
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "aa", Position(300.0, 150.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", context.world)))
            assertEquals("aaBeamGunship", context.observe().units.single().typeId)
            val burn = context.advance(Step(0L, 1.0, "sim-tick")).units.single()
            assertEquals("aa", burn.unitId)
            assertEquals("aaBeamGunship_afterburn", burn.typeId)
            assertEquals(2500.0, burn.health)
            assertTrue(burn.tasks.any { it.actionId == "move" })
            assertEquals(6f, burn.energy!!.current)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("cancel", "p1", "aa", "cancelBurn", context.world)))
            val normal = context.advance(Step(1L, 1.0, "sim-tick")).units.single()
            assertEquals("aaBeamGunship", normal.typeId)
            assertEquals(6f, normal.energy!!.current)
            assertTrue(normal.tasks.any { it.actionId == "move" })
        } finally { context.close() }
    }
}
