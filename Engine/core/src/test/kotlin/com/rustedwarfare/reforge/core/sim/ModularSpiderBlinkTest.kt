package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.VisionCell
import java.nio.file.Path
import kotlin.test.*

class ModularSpiderBlinkTest {
    @Test fun paid_blink_queues_during_construction_then_writes_the_parent_and_six_slots() {
        for (format in listOf("ini", "json", "hjson")) withSpider(format) { battle ->
            val target = Position(620.0, 200.0)
            val origin = battle.parent.position
            val identities = battle.slots.map { it.unitId }
            battle.action("selectSlot2")
            val balance = battle.balance
            battle.action("buildBlink")
            assertEquals(balance - 15000L, battle.balance)
            battle.tick()
            assertEquals("modularSpider_blink", battle.slots[1].typeId)
            assertTrue(battle.slots[1].progress < 1f)
            battle.action("blink", target)
            assertEquals("duplicate-unit-action", battle.reject("blink", target))
            repeat(10) { battle.tick() }
            assertEquals(origin, battle.parent.position)
            assertEquals(0f, battle.actions.queue.single { it.name == "blink" }.progress)
            battle.until(1050) { battle.actions.queue.any { it.name == "blinkQueued" } }
            assertTrue(battle.actions.actions.single { it.name == "blink" }.cooldownRemaining > 0)
            assertEquals(origin, battle.parent.position)
            assertEquals("unit-action-not-cancellable", battle.reject("blinkQueued", cancel = true))
            assertIs<ActionResult.Accepted>(battle.context.submit(
                StopAction("stop", "p1", "spider", battle.context.world)))
            battle.until(215) { battle.parent.position == target }
            assertEquals(identities, battle.slots.map { it.unitId })
            assertTrue(battle.context.observe().events.any { it.kind == "teleported" && it.unitId == "spider" })
            val attached = battle.context.observe().units.filter { it.attachment?.parentUnitId == "spider" }
            assertEquals(6, attached.size)
            assertEquals(Position(652.0, 211.0), attached.single { it.unitId == identities[1] }.position)
        }
    }

    @Test fun illegal_points_leave_the_queue_and_cooldown_clear_and_cancellation_does_not_message_parent() =
        withSpider { battle ->
            battle.action("selectSlot2")
            battle.action("buildBlink")
            battle.tick()
            assertEquals("action-target-impassable", battle.reject("blink", Position(-1.0, 180.0)))
            assertEquals("action-target-out-of-range", battle.reject("blink", Position(950.0, 180.0)))
            assertEquals("invalid-action-target", battle.reject("blink", Position(Double.NaN, 180.0)))
            assertEquals("missing-action-target", battle.reject("blink"))
            assertTrue(battle.actions.queue.isEmpty())
            val balance = battle.balance
            battle.action("blink", Position(620.0, 200.0))
            battle.action("blink", cancel = true)
            assertEquals(balance, battle.balance)
            battle.until(1050) { battle.slots[1].progress == 1f }
            repeat(215) { battle.tick() }
            assertTrue(battle.actions.queue.isEmpty())
            assertEquals(0, battle.actions.actions.single { it.name == "blink" }.cooldownRemaining)
            assertEquals(Position(180.0, 160.0), battle.parent.position)
        }

    @Test fun a_second_module_can_spend_its_cooldown_without_replacing_a_charging_parent_target() =
        withSpider { battle ->
            for (slot in 1..2) {
                battle.action("selectSlot$slot")
                battle.action("buildBlink")
                battle.tick()
            }
            battle.until(1050) { battle.slots.take(2).all { it.progress == 1f } }
            val destination = Position(620.0, 200.0)
            battle.action("selectSlot1")
            battle.action("blink", destination)
            battle.tick()
            battle.action("selectSlot2")
            battle.action("blink", Position(230.0, 220.0))
            battle.tick()
            assertTrue(battle.actions.actions.single { it.name == "blink" }.cooldownRemaining > 0)
            assertEquals(destination, battle.actions.queue.single { it.name == "blinkQueued" }.target)
            assertIs<ActionResult.Accepted>(battle.context.submit(
                MoveAction("move-after-blink", "p1", "spider", Position(650.0, 220.0), battle.context.world)))
            val path = battle.parent.pathPoints
            val tasks = battle.parent.tasks
            val origin = battle.parent.position
            assertFalse(VisionCell(31, 10) in battle.context.visionSnapshot(battle.context.world, "p1").effectiveCells)
            repeat(30) { battle.tick() }
            assertEquals(origin, battle.parent.position)
            assertEquals(path, battle.parent.pathPoints)
            assertEquals(tasks, battle.parent.tasks)
            battle.until(210) { battle.parent.position == destination }
            assertEquals(path, battle.parent.pathPoints)
            assertEquals(tasks, battle.parent.tasks)
            val sight = battle.context.visionSnapshot(battle.context.world, "p1")
            assertTrue(VisionCell(31, 10) in sight.effectiveCells)
        }

    @Test fun changing_the_external_step_label_does_not_expire_the_simulated_blink_cooldown() =
        withSpider { battle ->
            battle.action("selectSlot2")
            battle.action("buildBlink")
            battle.tick()
            battle.until(1050) { battle.slots[1].progress == 1f }
            battle.action("blink", Position(620.0, 200.0))
            battle.tick()
            val remaining = battle.actions.actions.single { it.name == "blink" }.cooldownRemaining
            battle.skipLabels(10_000)
            battle.tick()
            val next = battle.actions.actions.single { it.name == "blink" }.cooldownRemaining
            assertTrue(remaining - next in 16..17)
            assertEquals("unit-action-cooldown", battle.reject("blink", Position(230.0, 220.0)))
        }

    private fun withSpider(format: String = "ini", run: (Battle) -> Unit) = WaterTestScene.withMap { map ->
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val loaded = ContentLoader.loadContentPack(root.resolve("modular-spider-start/$format/opening-package"))
        val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
        val scene = WaterTestScene.scenario(map, MovementType.OVER_CLIFF_WATER, Position(180.0, 160.0)).copy(
            unitDefinitions = definitions,
            units = listOf(UnitSpawn("spider", "p1", "modularSpider", Position(180.0, 160.0), 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 45000L, "p2" to 0L)),
            combatContract = CombatContract(1245, -1, mapOf("spider" to 8L), 9L),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.ORIGINAL_COMPATIBILITY, false),
        )
        val context = NuclearScene.open(scene)
        try { run(Battle(context)) } finally { context.close() }
    }

    private class Battle(val context: SimulationContext) {
        private var step = 0L
        private var command = 0
        val parent get() = context.observe().units.single { it.unitId == "spider" }
        val slots get() = checkNotNull(parent.attachmentOverview).slots
        val actions get() = checkNotNull(parent.unitActions)
        val balance get() = checkNotNull(context.observe().economy).balances.getValue("p1")

        fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
        fun skipLabels(count: Long) { step += count }
        fun action(name: String, target: Position? = null, cancel: Boolean = false) =
            assertIs<ActionResult.Accepted>(request(name, target, cancel), "Expected $name to be accepted")
        fun reject(name: String, target: Position? = null, cancel: Boolean = false) =
            assertIs<ActionResult.Rejected>(request(name, target, cancel)).error.code
        private fun request(name: String, target: Position?, cancel: Boolean) =
            context.submit(UnitAction("blink-${command++}", "p1", "spider", name, context.world, cancel, target = target))

        fun until(maximum: Int, done: () -> Boolean) {
            repeat(maximum) { if (done()) return else tick() }
            assertTrue(done(), "Expected state within $maximum steps")
        }
    }
}
