package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.IncomeProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SandboxCreditsTest {
    @Test
    fun text_requires_an_explicit_sign_and_a_finite_integer() {
        assertIs<SandboxParseResult.Parsed>(SandboxCommands.parse(" /credits p1 +17 "))
        assertIs<SandboxParseResult.Parsed>(SandboxCommands.parse("/credits p2 -9"))
        for (token in listOf("17", "+1.5", "-1e3", "+NaN", "++1", "-")) {
            assertEquals(SandboxParseResult.Rejected("invalid-signed-integer", 13),
                SandboxCommands.parse("/credits p1 $token"))
        }
        assertEquals(SandboxParseResult.Rejected("amount-out-of-range", 13),
            SandboxCommands.parse("/credits p1 +9223372036854775808"))
        assertEquals(SandboxParseResult.Rejected("unexpected-argument", 17),
            SandboxCommands.parse("/credits p1 +17 now"))
        assertTrue(SandboxCommands.HELP.contains("/credits"))
    }

    @Test
    fun hosted_credits_apply_once_at_the_next_step_and_a_failed_deduction_keeps_the_balance() {
        val scene = BuildingTestContent.scenario().copy(
            economyContract = EconomyContract(mapOf("p1" to 100L, "p2" to 90L)))
        OfflineSandboxHost(scene).use { ordinary ->
            val before = ordinary.context.observe()
            assertEquals("deployment-disabled", ordinary.submit(request(ordinary, 1, "/credits p1 +17")).code)
            assertSame(before, ordinary.context.observe())
        }
        OfflineSandboxHost(scene, deploymentEnabled = true, generation = 7L).use { host ->
            val add = request(host, 1, "/credits p2 +17")
            val queued = host.submit(add)
            assertEquals(SandboxCommandStatus.QUEUED, queued.status)
            assertEquals(queued, host.submit(add))
            assertEquals("request-conflict", host.submit(add.copy(text = "/credits p2 +18")).code)
            assertEquals(90L, host.context.observePlayer(host.context.world, "p2").balance)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/credits p2 -8")).status)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 3, "/credits p2 -100")).status)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 4, "/credits missing +1")).status)
            val frame = host.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(mapOf("p1" to 100L, "p2" to 99L), frame.economy!!.balances)
            val applied = host.receipt(1)!!
            assertEquals(SandboxCommandStatus.APPLIED, applied.status)
            assertEquals(0L, applied.stepIndex)
            assertEquals(SandboxCreditChange("p2", "90", "107"), applied.credits)
            assertEquals(applied, host.submit(add))
            assertEquals(SandboxCommandStatus.APPLIED, host.receipt(2)!!.status)
            assertEquals(SandboxCreditChange("p2", "107", "99"), host.receipt(2)!!.credits)
            assertEquals("insufficient-funds", host.receipt(3)!!.code)
            assertNull(host.receipt(3)!!.credits)
            assertEquals("unknown-owner", host.receipt(4)!!.code)
            assertEquals(99L, host.advance(Step(1, 1.0, "sim-tick")).economy!!.balances.getValue("p2"))
        }
        OfflineSandboxHost(scene, deploymentEnabled = true).use { replay ->
            val commands = listOf("/credits p2 +17", "/credits p2 -8", "/credits p2 -100", "/credits missing +1")
            for ((index, line) in commands.withIndex())
                assertEquals(SandboxCommandStatus.QUEUED, replay.submit(request(replay, index + 1L, line)).status)
            assertEquals(mapOf("p1" to 100L, "p2" to 99L), replay.advance(Step(0, 1.0, "sim-tick")).economy!!.balances)
            assertEquals(SandboxCreditChange("p2", "107", "99"), replay.receipt(2)!!.credits)
            assertEquals("insufficient-funds", replay.receipt(3)!!.code)
        }
    }

    @Test
    fun natural_income_retains_its_fraction_and_explicit_adjustments_use_the_written_delta() {
        OfflineSandboxHost(incomeScene(100L, 4.5f), deploymentEnabled = true).use { host ->
            fun tick(number: Long) = host.advance(Step(number, 1.0, "sim-tick"))
            for (step in 0L..9L) tick(step)
            val old = host.context.observe()
            assertEquals(104.5, old.economy!!.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/credits p1 +7")).status)
            assertEquals(111.5, tick(10).economy!!.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/credits p1 -111")).status)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 3, "/credits p1 -1")).status)
            assertEquals(0.5, tick(11).economy!!.preciseBalances!!.getValue("p1"))
            assertEquals("insufficient-funds", host.receipt(3)!!.code)
            assertEquals(104.5, old.economy.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCreditChange("p1", "104.5", "111.5"), host.receipt(1)!!.credits)
            for (step in 12L..18L) tick(step)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 4, "/credits p1 +2")).status)
            assertEquals(7.0, tick(19).economy!!.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCreditChange("p1", "0.5", "2.5"), host.receipt(4)!!.credits)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 5, "/credits p1 +999999993")).status)
            assertEquals(7.0, tick(20).economy!!.preciseBalances!!.getValue("p1"))
            assertEquals("amount-out-of-range", host.receipt(5)!!.code)
        }
    }

    @Test
    fun maintenance_only_keeps_fractional_storage_without_a_natural_income_ceiling() {
        OfflineSandboxHost(maintenanceScene(999999999L), deploymentEnabled = true).use { host ->
            assertEquals(999999999.0, host.context.observe().economy!!.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/credits p1 +1")).status)
            val observed = host.advance(Step(0, 1.0, "sim-tick")).economy!!
            assertEquals(1000000000.0, observed.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCommandStatus.APPLIED, host.receipt(1)!!.status)
        }
    }

    @Test
    fun maintenance_credits_reject_a_delta_that_the_current_balance_cannot_represent() {
        OfflineSandboxHost(maintenanceScene(Long.MAX_VALUE), deploymentEnabled = true).use { host ->
            val before = host.context.observe().economy!!.preciseBalances!!.getValue("p1")
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/credits p1 -1")).status)
            val observed = host.advance(Step(0, 1.0, "sim-tick")).economy!!
            assertEquals("amount-out-of-range", host.receipt(1)!!.code)
            assertEquals(before, observed.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/credits p1 -2048")).status)
            assertEquals(before - 2048.0, host.advance(Step(1, 1.0, "sim-tick")).economy!!.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCommandStatus.APPLIED, host.receipt(2)!!.status)
        }
    }

    @Test
    fun a_large_maintenance_credit_cannot_discard_reclaimed_fractional_funds() {
        OfflineSandboxHost(maintenanceScene(0L), deploymentEnabled = true).use { host ->
            val context = host.context
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction(
                "reclaim", "p1", "worker", "factory", context.world)))
            var step = 0L
            val reclaimed = Event("building-reclaimed", "factory")
            while (reclaimed !in context.observe().events && step < 4000)
                host.advance(Step(step++, 1.0, "sim-tick"))
            val frame = context.observe()
            assertTrue(reclaimed in frame.events)
            val before = frame.economy!!.preciseBalances!!.getValue("p1")
            assertEquals(560.0000083446503, before)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/credits p1 +4503599627370498")).status)
            val observed = host.advance(Step(step++, 1.0, "sim-tick")).economy!!
            assertEquals("amount-out-of-range", host.receipt(1)!!.code)
            assertEquals(before, observed.preciseBalances!!.getValue("p1"))
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/credits p1 +2")).status)
            assertEquals(before + 2.0, host.advance(Step(step, 1.0, "sim-tick")).economy!!.preciseBalances!!.getValue("p1"))
        }
    }

    @Test
    fun an_income_bound_is_checked_before_addition_can_round_away_a_fraction() {
        OfflineSandboxHost(incomeScene(0L, 1e-8f), deploymentEnabled = true).use { host ->
            for (step in 0L..9L) host.advance(Step(step, 1.0, "sim-tick"))
            val before = host.context.observe().economy!!.preciseBalances!!.getValue("p1")
            assertEquals(1e-8f.toDouble(), before)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/credits p1 +999999999")).status)
            val observed = host.advance(Step(10, 1.0, "sim-tick")).economy!!
            assertEquals("amount-out-of-range", host.receipt(1)!!.code)
            assertEquals(before, observed.preciseBalances!!.getValue("p1"))
        }
    }

    @Test
    fun integer_credits_preserve_exact_large_balances_and_leave_room_for_paid_factory_refunds() {
        val opening = BuildingTestContent.scenario()
        val scene = opening.copy(economyContract = EconomyContract(mapOf("p1" to Long.MAX_VALUE - 1L, "p2" to 0L)))
        OfflineSandboxHost(scene, deploymentEnabled = true).use { host ->
            val ctx = host.context
            val cost = checkNotNull(scene.unitDefinitions.getValue("c_tank").build).price
            assertIs<ActionResult.Accepted>(ctx.submit(ProduceAction("buy", "p1", "f", "c_tank", "a".repeat(32), ctx.world)))
            val before = Long.MAX_VALUE - 1L - cost
            assertEquals(before, ctx.observePlayer(ctx.world, "p1").balance)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/credits p1 +${cost + 1L}")).status)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 2, "/credits p1 +1")).status)
            assertEquals(Long.MAX_VALUE - cost, host.advance(Step(0, 1.0, "sim-tick")).economy!!.balances.getValue("p1"))
            assertEquals("amount-out-of-range", host.receipt(1)!!.code)
            assertEquals(SandboxCreditChange("p1", before.toString(), (before + 1L).toString()), host.receipt(2)!!.credits)
            assertIs<ActionResult.Accepted>(ctx.submit(CancelProductionAction("refund", "p1", "f", "c_tank", ctx.world)))
            assertEquals(Long.MAX_VALUE, ctx.observePlayer(ctx.world, "p1").balance)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 3, "/credits p1 +1")).status)
            assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 4, "/credits p1 -9223372036854775808")).status)
            assertEquals(Long.MAX_VALUE, host.advance(Step(1, 1.0, "sim-tick")).economy!!.balances.getValue("p1"))
            assertEquals("amount-out-of-range", host.receipt(3)!!.code)
            assertEquals("insufficient-funds", host.receipt(4)!!.code)
        }
    }

    @Test
    fun a_sandbox_without_a_ledger_rejects_funding_without_creating_an_economy() =
        WaterTestScene.withMap { map ->
            val scene = WaterTestScene.scenario(map, com.rustedwarfare.reforge.core.content.MovementType.WATER, Position(510.0, 190.0))
            OfflineSandboxHost(scene, deploymentEnabled = true).use { host ->
                assertEquals(SandboxCommandStatus.QUEUED, host.submit(request(host, 1, "/credits p1 +1")).status)
                assertNull(host.advance(Step(0, 1.0, "sim-tick")).economy)
                assertEquals("economy-unavailable", host.receipt(1)!!.code)
            }
        }

    private fun request(host: OfflineSandboxHost, sequence: Long, command: String) =
        SandboxCommandRequest(sequence, host.context.world, host.generation, command)

    private fun incomeScene(initial: Long, income: Float): Scenario {
        val opening = BuildingTestContent.scenario()
        val base = opening.unitDefinitions.getValue("landFactory")
        val earner = UnitDefinition(base.id, base.className, base.radius, base.displayRadius, base.totalFrames,
            base.imagePath, base.imageScale, base.movement, base.retainedUnconsumed, base.fogOfWarSightRange,
            combat = base.combat, durability = base.durability, building = base.building, production = base.production,
            build = base.build, directHitTargetKind = base.directHitTargetKind, income = IncomeProfile(10f, income))
        return opening.copy(unitDefinitions = opening.unitDefinitions + (earner.id to earner),
            units = opening.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to initial, "p2" to 0L)))
    }

    private fun maintenanceScene(initial: Long): Scenario {
        val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isDirectory(it.resolve("SimulationTest/public")) }
                .resolve("SimulationTest/public/inputs")
        val units = inputs.resolve("build-economy/factory/units")
        val builder = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadBuilder(units.resolve("builder.properties"))).value
        val factory = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadFactory(units.resolve("landFactory.properties"))).value
        val base = BuildingTestContent.scenario()
        return base.copy(unitDefinitions = base.unitDefinitions + mapOf(builder.id to builder, factory.id to factory),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
            maintenanceContract = MaintenanceContract(mapOf("landFactory" to 700L)),
            economyContract = EconomyContract(mapOf("p1" to initial, "p2" to 0L)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
    }
}
