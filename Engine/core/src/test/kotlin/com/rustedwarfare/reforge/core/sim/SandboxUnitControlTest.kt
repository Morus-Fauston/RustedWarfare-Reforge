package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxUnitControlTest {
    @Test
    fun text_commands_accept_one_unit_and_a_finite_signed_health_delta() {
        assertIs<SandboxParseResult.Parsed>(SandboxCommands.parse("/destroy alpha-1"))
        assertIs<SandboxParseResult.Parsed>(SandboxCommands.parse("/health produced:p2:one -1.5"))
        assertEquals("unexpected-argument", assertIs<SandboxParseResult.Rejected>(
            SandboxCommands.parse("/destroy alpha-1 beta")).code)
        for (amount in listOf("NaN", "+Infinity", "+1e309", "1", "+abc")) {
            assertIs<SandboxParseResult.Rejected>(SandboxCommands.parse("/health alpha $amount"))
        }
        assertTrue(SandboxCommands.HELP.contains("/destroy") && SandboxCommands.HELP.contains("/health"))
    }

    @Test
    fun ordinary_shell_destroy_and_zero_health_share_factory_refunds_and_cleanup() = WaterTestScene.withMap { map ->
        val base = SeaFactoryScene.scenario(map, tier = "seaFactoryT2")
        val scene = base.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            units = base.units + UnitSpawn("enemy", "p2", "heavyBattleship", Position(650.0, 150.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L, "enemy" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 18000L, "p2" to 0L)))
        for (command in listOf(null, "/destroy factory", "/health factory -1000")) {
            OfflineSandboxHost(scene, deploymentEnabled = true).use { host ->
                val context = host.context
                val nonce = "a".repeat(32)
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("paid", "p1", "factory",
                    "heavyBattleship", nonce, context.world)))
                assertEquals(12000L, context.observePlayer(context.world, "p1").balance)
                val mutation = command?.let { request(host, 1L, it) }
                if (mutation == null) {
                    assertIs<ActionResult.Accepted>(context.submit(
                        AttackAction("attack", "p2", "enemy", "factory", context.world)))
                } else {
                    val queued = host.submit(mutation)
                    assertEquals(SandboxCommandStatus.QUEUED, queued.status)
                    assertEquals(queued, host.submit(mutation))
                    assertTrue(context.observe().unit("factory").alive)
                }
                var step = 0L
                while (context.observe().unit("factory").alive && step < 1200)
                    host.advance(Step(step++, 1.0, "sim-tick"))
                val death = context.observe()
                val factory = death.unit("factory")
                assertFalse(factory.alive)
                assertEquals(0.0, factory.health)
                assertEquals(18000L, death.economy!!.balances.getValue("p1"))
                assertTrue(death.economy.queuedProduction.getValue("factory").isEmpty())
                assertTrue(factory.tasks.isEmpty())
                assertEquals(1, death.events.count { it == Event("production-refunded", "factory") })
                assertEquals(1, death.events.count { it == Event("unit-died", "factory") })
                if (mutation != null) {
                    assertEquals(SandboxCommandStatus.APPLIED, host.receipt(1)!!.status)
                    assertEquals(SandboxUnitChange("factory", 1000.0, 0.0, true), host.receipt(1)!!.unitChange)
                    assertEquals(host.receipt(1), host.submit(mutation))
                    assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/destroy factory")).status)
                }
                while (step <= 2550) {
                    val frame = host.advance(Step(step++, 1.0, "sim-tick"))
                    assertFalse(Event("production-refunded", "factory") in frame.events)
                    assertTrue(frame.units.none { it.unitId == "produced:p1:$nonce" })
                }
                if (mutation != null) assertEquals("inactive-unit", host.receipt(2)!!.code)
                assertEquals(18000L, context.observePlayer(context.world, "p1").balance)
                println("Factory death using ${command ?: "shell"} at ${death.stepIndex}; refund and queue matched")
            }
        }
    }

    @Test
    fun hostile_health_can_be_lowered_and_restored_only_to_its_maximum() = WaterTestScene.withMap { map ->
        val scene = DropshipScene.scenario(map, listOf(
            UnitSpawn("hostile", "p2", "builder", Position(150.0, 200.0), 90.0)))
        OfflineSandboxHost(scene, deploymentEnabled = true, generation = 8L).use { host ->
            val start = host.context.observe().unit("hostile")
            val maximum = requireNotNull(start.maxHealth)
            assertEquals(maximum, start.health)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/health hostile -1.5")).status)
            assertEquals(maximum - 1.5, host.advance(Step(0, 1.0, "sim-tick")).unit("hostile").health)
            assertEquals(SandboxUnitChange("hostile", maximum, maximum - 1.5, false), host.receipt(1)!!.unitChange)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/health hostile +999999999")).status)
            assertEquals(maximum, host.advance(Step(1, 1.0, "sim-tick")).unit("hostile").health)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 3, "/destroy missing")).status)
            assertTrue(host.advance(Step(2, 1.0, "sim-tick")).unit("hostile").alive)
            assertEquals("unknown-unit", host.receipt(3)!!.code)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 4, "/destroy hostile")).status)
            assertFalse(host.advance(Step(3, 1.0, "sim-tick")).unit("hostile").alive)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 5, "/health hostile +10")).status)
            assertFalse(host.advance(Step(4, 1.0, "sim-tick")).unit("hostile").alive)
            assertEquals("inactive-unit", host.receipt(5)!!.code)
        }
        OfflineSandboxHost(scene).use { ordinary ->
            val before = ordinary.context.observe()
            assertEquals("deployment-disabled", ordinary.submit(request(ordinary, 1, "/destroy hostile")).code)
            assertEquals(before, ordinary.context.observe())
        }
    }

    @Test
    fun destruction_removes_control_from_a_legacy_unit_without_a_health_pool() = WaterTestScene.withMap { map ->
        val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(510.0, 190.0))
        OfflineSandboxHost(scene, deploymentEnabled = true).use { host ->
            val context = host.context
            val initial = context.observe().units.single()
            assertNull(initial.health)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/health ${initial.unitId} -1")).status)
            assertTrue(host.advance(Step(0, 1.0, "sim-tick")).unit(initial.unitId).alive)
            assertEquals("health-unavailable", host.receipt(1)!!.code)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/destroy ${initial.unitId}")).status)
            val death = host.advance(Step(1, 1.0, "sim-tick"))
            assertFalse(death.unit(initial.unitId).alive)
            assertEquals(SandboxUnitChange(initial.unitId, null, null, true), host.receipt(2)!!.unitChange)
            assertTrue(Event("unit-died", initial.unitId) in death.events)
            assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(context.submit(MoveAction(
                "move", "p1", initial.unitId, Position(550.0, 190.0), context.world))).error.code)
        }
    }

    @Test
    fun positive_fractional_health_keeps_an_installed_module_until_the_command_reaches_zero() = WaterTestScene.withMap { map ->
        val scene = moduleScene(map)
        OfflineSandboxHost(scene, deploymentEnabled = true).use { host ->
            val (module, nextStep) = installGun(host)
            val regeneration = scene.unitDefinitions.getValue(module.typeId).selfRegenRate
            val amount = 0.5 - requireNotNull(module.health)
            val damage = request(host, 1, "/health ${module.unitId} $amount")
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(damage).status)
            val injured = host.advance(Step(nextStep, 1.0, "sim-tick"))
            assertEquals(SandboxUnitChange(module.unitId, module.health, 0.5, false), host.receipt(1)!!.unitChange)
            assertEquals(module.typeId, injured.unit(module.unitId).typeId)
            val firstHealth = 0.5f + regeneration
            assertTrue(firstHealth in 0f..<1f)
            assertEquals(firstHealth.toDouble(), injured.unit(module.unitId).health)
            assertEquals(host.receipt(1), host.submit(damage))
            val secondHealth = (firstHealth + regeneration).toDouble()
            assertTrue(secondHealth in 0.0..<1.0)
            assertEquals(secondHealth, host.advance(Step(nextStep + 1, 1.0, "sim-tick")).unit(module.unitId).health)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/health ${module.unitId} -$secondHealth")).status)
            val destroyed = host.advance(Step(nextStep + 2, 1.0, "sim-tick"))
            assertEquals(SandboxUnitChange(module.unitId, secondHealth, 0.0, true), host.receipt(2)!!.unitChange)
            assertEquals("modularSpider_emptySlot", destroyed.unit(module.unitId).typeId)
            assertTrue(Event("attachment-slot-destroyed", module.unitId) in destroyed.events)
        }
    }

    @Test
    fun a_second_command_cannot_edit_an_attachment_when_its_parent_died_in_the_same_step() = WaterTestScene.withMap { map ->
        OfflineSandboxHost(moduleScene(map), deploymentEnabled = true).use { host ->
            val (module, step) = installGun(host)
            val destroy = request(host, 1, "/destroy spider")
            val health = request(host, 2, "/health ${module.unitId} +1")
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(destroy).status)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(health).status)
            val frame = host.advance(Step(step, 1.0, "sim-tick"))
            assertFalse(frame.unit("spider").alive)
            assertTrue(frame.units.none { it.unitId == module.unitId })
            assertEquals(SandboxCommandStatus.APPLIED, host.receipt(1)!!.status)
            assertEquals("inactive-unit", host.receipt(2)!!.code)
            assertNull(host.receipt(2)!!.unitChange)
            assertEquals(host.receipt(2), host.submit(health))
            assertTrue(Event("attachment-removed", module.unitId) in frame.events)
        }
    }

    private fun moduleScene(map: MapDefinition): Scenario {
        val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            inputs.resolve("modular-spider-start/ini/opening-package"))).value
        val origin = Position(80.0, 170.0)
        return WaterTestScene.scenario(map, MovementType.OVER_CLIFF_WATER, origin).copy(
            unitDefinitions = pack.units,
            units = listOf(UnitSpawn("spider", "p1", "modularSpider", origin, 90.0)),
            nukesEnabled = false,
            combatContract = CombatContract(173, -1, mapOf("spider" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)))
    }

    private fun installGun(host: OfflineSandboxHost): Pair<UnitObservation, Long> {
        val context = host.context
        val slot = requireNotNull(context.observe().unit("spider").attachmentOverview).slots.single { it.number == 2 }
        assertIs<ActionResult.Accepted>(context.submit(UnitAction("slot", "p1", "spider", "selectSlot2", context.world)))
        assertIs<ActionResult.Accepted>(context.submit(UnitAction("install", "p1", "spider", "buildGun", context.world)))
        var step = 0L
        do {
            val module = host.advance(Step(step++, 1.0, "sim-tick")).unit(slot.unitId)
            if (module.typeId != "modularSpider_emptySlot" && module.constructionProgress == 1f) return module to step
        } while (step < 1200)
        error("The selected slot did not finish building its module")
    }

    @Test
    fun normal_boarding_followed_by_either_sandbox_death_enters_the_dropship_cargo_cleanup() = WaterTestScene.withMap { map ->
        val scene = DropshipScene.scenario(map, listOf(
            UnitSpawn("z-air", "p1", "dropship", Position(100.0, 200.0), 90.0),
            UnitSpawn("a-crew", "p1", "builder", Position(110.0, 200.0), 90.0)))
        for (command in listOf("/destroy z-air", "/health z-air -9999")) {
            OfflineSandboxHost(scene, deploymentEnabled = true).use { host ->
                val context = host.context
                assertIs<ActionResult.Accepted>(context.submit(
                    LoadAction("board", "p1", "a-crew", "z-air", LoadDirection.INTO, context.world)))
                var step = 0L
                while (context.observe().unit("a-crew").carriedBy == null && step < 120)
                    host.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals("z-air", context.observe().unit("a-crew").carriedBy)
                assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, command)).status)
                val frame = host.advance(Step(step++, 1.0, "sim-tick"))
                assertFalse(frame.unit("z-air").alive)
                assertEquals(0, frame.unit("z-air").transport!!.passengerCount)
                assertNull(frame.unit("a-crew").carriedBy)
                val following = host.advance(Step(step, 1.0, "sim-tick"))
                assertFalse(following.unit("a-crew").alive)
                assertEquals(0, following.unit("z-air").transport!!.passengerCount)
                assertEquals(SandboxCommandStatus.APPLIED, host.receipt(1)!!.status)
                println("Dropship death using $command, crew cleared by step ${following.stepIndex}")
            }
        }
    }

    private fun request(host: OfflineSandboxHost, sequence: Long, command: String) =
        SandboxCommandRequest(sequence, host.context.world, host.generation, command)

    private fun Observation.unit(unitId: String) = units.single { it.unitId == unitId }
}
