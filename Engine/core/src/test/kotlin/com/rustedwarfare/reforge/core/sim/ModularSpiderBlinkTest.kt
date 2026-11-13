package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.ProjectileProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
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

    @Test fun a_paid_slot_upgrade_keeps_its_own_queue_entry_during_chassis_blink() = withSpider { battle ->
        battle.action("selectSlot1")
        battle.action("buildBlink")
        battle.action("selectSlot2")
        battle.action("buildGun")
        battle.tick()
        battle.until(1050) { battle.slots.take(2).all { it.progress == 1f } }
        val slots = battle.slots.map { it.unitId }
        battle.action("selectSlot1")
        val destination = Position(310.0, 200.0)
        battle.action("blink", destination)
        battle.tick()
        battle.action("selectSlot2")
        battle.action("upgradeT2")
        val queue = battle.actions.queue
        assertEquals(listOf("blinkQueued", "upgradeT2"), queue.map { it.name })
        assertEquals(destination, queue[0].target)
        assertEquals(1, battle.actions.actions.single { it.name == "upgradeT2" }.queueIndex)
        battle.tick()
        assertTrue(battle.actions.queue[1].progress > 0f)
        battle.action("upgradeT2", cancel = true)
        assertEquals(listOf("blinkQueued"), battle.actions.queue.map { it.name })
        battle.until(220) { battle.parent.position == destination }
        assertEquals(slots, battle.slots.map { it.unitId })
        assertEquals("modularSpider_gunturret", battle.slots[1].typeId)
    }

    @Test fun a_selected_blink_module_can_cancel_its_own_reclaim_without_cancelling_blink() = withSpider { battle ->
        battle.action("selectSlot1")
        battle.action("buildBlink")
        battle.tick()
        battle.until(1050) { battle.slots.first().progress == 1f }
        battle.action("reclaim")
        val destination = Position(310.0, 200.0)
        battle.action("blink", destination)
        assertEquals(listOf("blink", "reclaim"), battle.actions.queue.map { it.name })
        assertEquals(1, battle.actions.actions.single { it.name == "reclaim" }.queueIndex)
        battle.action("reclaim", cancel = true)
        assertEquals(listOf("blink"), battle.actions.queue.map { it.name })
        battle.until(215) { battle.parent.position == destination }
        assertEquals("modularSpider_blink", battle.slots.first().typeId)
    }

    @Test fun the_same_chassis_can_buy_upgrade_replace_and_use_the_complete_normal_catalogue() =
        withSpider(startingBalance = 150000L, hostileWitness = true) { battle ->
            val empty = "modularSpider_emptySlot"
            val identities = battle.slots.map { it.unitId }
            assertEquals(6, identities.distinct().size)
            assertEquals(List(6) { empty }, battle.slots.map { it.typeId })
            val seen = mutableSetOf(battle.parent.typeId, empty)
            val buildMenu = setOf("buildSmallLaser", "buildGun", "buildAntiair", "buildLightning", "buildArtillery",
                "buildFabricator", "buildShieldGen", "buildSpeed", "buildLaserdefense", "buildAntinuke", "buildBlink")

            fun pay(slot: Int, name: String, price: Long) {
                battle.action("selectSlot${slot + 1}")
                val balance = battle.balance
                battle.action(name)
                assertEquals(balance - price, battle.balance, name)
            }

            fun forms(vararg names: String) {
                val types = names.map { "modularSpider_$it" }
                assertEquals(types, battle.slots.map { it.typeId })
                assertEquals(identities, battle.slots.map { it.unitId })
                seen.addAll(types)
            }

            fun reclaim(vararg slots: Int) {
                for (slot in slots) {
                    battle.action("selectSlot${slot + 1}")
                    battle.action("reclaim")
                }
                battle.until(181) { slots.all { battle.slots[it].typeId == empty } }
            }

            fun measureCruiseSpeed(expected: Double) {
                val origin = battle.parent.position
                assertIs<ActionResult.Accepted>(battle.context.submit(
                    MoveAction("cruise-$expected", "p1", "spider", Position(origin.x + 100, origin.y), battle.context.world)))
                repeat(78) { battle.tick() }
                val before = battle.parent.position
                battle.tick()
                assertEquals(expected, battle.parent.position.x - before.x, 1e-7)
                assertEquals(before.y, battle.parent.position.y, 1e-7)
                assertIs<ActionResult.Accepted>(battle.context.submit(
                    StopAction("stop-$expected", "p1", "spider", battle.context.world)))
            }

            battle.action("selectSlot1")
            assertEquals(buildMenu, battle.actions.actions.filter { it.name.startsWith("build") && it.available }
                .map { it.name }.toSet())
            pay(0, "buildSmallLaser", 700)
            pay(1, "buildGun", 2000)
            pay(2, "buildAntiair", 2000)
            pay(3, "buildLightning", 6000)
            pay(4, "buildArtillery", 5000)
            pay(5, "buildFabricator", 3000)
            battle.tick()
            forms("smallgunturret", "gunturret", "antiair", "lightning", "artillery", "fabricator")
            battle.until(1050) { battle.slots.all { it.progress == 1f } }
            pay(0, "upgradeT2", 3000)
            pay(1, "upgradeT2", 16000)
            pay(2, "upgradeT2", 16000)
            pay(5, "upgradeT2", 5000)
            battle.until(970) { battle.slots[2].typeId == "modularSpider_antiairT2" }
            forms("smallgunturretT2", "gunturretT2", "antiairT2", "lightning", "artillery", "fabricatorT2")
            reclaim(0, 1, 2, 3, 4, 5)
            forms(*Array(6) { "emptySlot" })

            pay(0, "buildAntiair", 2000)
            pay(1, "buildShieldGen", 6000)
            pay(2, "buildSpeed", 6000)
            pay(3, "buildLaserdefense", 3000)
            pay(4, "buildAntinuke", 7000)
            pay(5, "buildBlink", 15000)
            battle.tick()
            forms("antiair", "shieldGen", "speedIncomplete", "laserdefense", "antinuke", "blink")
            battle.until(1050) { battle.slots.all { it.progress == 1f } && battle.slots[2].typeId == "modularSpider_speed" }
            pay(0, "upgradeFlak", 10000)
            battle.until(970) { battle.slots[0].typeId == "modularSpider_antiairFlak" }
            forms("antiairFlak", "shieldGen", "speed", "laserdefense", "antinuke", "blink")
            assertEquals(setOf("modularSpider", empty, "modularSpider_smallgunturret", "modularSpider_smallgunturretT2",
                "modularSpider_gunturret", "modularSpider_gunturretT2", "modularSpider_antiair", "modularSpider_antiairT2",
                "modularSpider_antiairFlak", "modularSpider_lightning", "modularSpider_artillery", "modularSpider_fabricator",
                "modularSpider_fabricatorT2", "modularSpider_shieldGen", "modularSpider_speedIncomplete",
                "modularSpider_speed", "modularSpider_laserdefense", "modularSpider_antinuke", "modularSpider_blink"), seen)
            measureCruiseSpeed(.7)
            reclaim(2)
            measureCruiseSpeed(.4)

            val beforeCancel = battle.balance
            pay(2, "buildGun", 2000)
            battle.tick()
            val beforeRefund = battle.balance
            battle.action("cancel")
            val refundFrame = battle.tick()
            assertEquals(beforeRefund + 2000L + if ((refundFrame.stepIndex + 1) % 40L == 0L) 30L else 0L,
                battle.balance)
            assertTrue(battle.balance >= beforeCancel)
            forms("antiairFlak", "shieldGen", "emptySlot", "laserdefense", "antinuke", "blink")
            reclaim(0)
            measureCruiseSpeed(.6)

            val destination = Position(335.0, 160.0)
            battle.action("selectSlot6")
            battle.action("blink", destination)
            battle.until(215) { battle.parent.position == destination }
            forms("emptySlot", "shieldGen", "emptySlot", "laserdefense", "antinuke", "blink")
            val units = battle.context.observe().units.associateBy { it.unitId }
            assertEquals(Position(367.0, 149.0), units.getValue(identities[0]).position)
            assertEquals(Position(315.0, 171.0), units.getValue(identities[5]).position)
            assertIs<ActionResult.Accepted>(battle.context.submit(
                AttackAction("damage-mounted-shield", "p2", "witness", identities[1], battle.context.world)))
            battle.until(950) { battle.slots[1].typeId == empty }
            forms("emptySlot", "emptySlot", "emptySlot", "laserdefense", "antinuke", "blink")
            assertIs<ActionResult.Accepted>(battle.context.submit(
                AttackAction("destroy-chassis", "p2", "witness", "spider", battle.context.world)))
            battle.until(350) { !battle.parent.alive }
            val remaining = battle.context.observe().units
            assertTrue(remaining.none { it.unitId in identities })
            assertNull(battle.parent.attachmentOverview)
        }

    private fun withSpider(
        format: String = "ini",
        startingBalance: Long = 45000L,
        hostileWitness: Boolean = false,
        run: (Battle) -> Unit,
    ) = WaterTestScene.withMap { map ->
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val loaded = ContentLoader.loadContentPack(root.resolve("modular-spider-start/$format/opening-package"))
        val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
        val base = WaterTestScene.scenario(map, MovementType.OVER_CLIFF_WATER, Position(180.0, 160.0))
        val land = WaterTestScene.unit(MovementType.LAND)
        val witness = UnitDefinition("witness", "CustomUnitMetadata", 6.0, 6.0, 1, land.imagePath,
            1.0, land.movement, emptySet(), 15,
            durability = DurabilityProfile(20000.0, null),
            combat = CombatProfile(20000.0, 78.0, 20.0, 0.0, 8.0, 2.0,
                ProjectileProfile(4500.0, 80.0, 12.0), null, null, 1.0))
        val spawn = UnitSpawn("spider", "p1", "modularSpider", Position(180.0, 160.0), 90.0)
        val scene = base.copy(
            players = if (hostileWitness) base.players.map { it.copy(control = PlayerControl.LOCAL) } else base.players,
            unitDefinitions = if (hostileWitness) definitions + (witness.id to witness) else definitions,
            units = if (hostileWitness) listOf(spawn, UnitSpawn("witness", "p2", witness.id, Position(390.0, 160.0), 270.0))
                else listOf(spawn),
            economyContract = EconomyContract(mapOf("p1" to startingBalance, "p2" to 0L)),
            combatContract = if (hostileWitness) CombatContract(1245, -1, mapOf("spider" to 8L, "witness" to 9L), 10L)
                else CombatContract(1245, -1, mapOf("spider" to 8L), 9L),
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
