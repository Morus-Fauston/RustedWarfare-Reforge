package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RepairBayWorkTest {
    @Test fun a_late_production_failure_rolls_back_native_scan_hp_queue_and_terminal_refund_then_retries() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val normal = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("repairbay-production/json/normal-package"))).value
        val base = scene()
        val input = base.copy(unitDefinitions = normal.units,
            maintenanceContract = MaintenanceContract(mapOf("commandCenter" to 3000L, "landFactory" to 700L, "repairbay" to 1500L)),
            units = listOf(UnitSpawn("a-bay", "p1", "repairbay", Position(110.0, 210.0), 270.0),
                UnitSpawn("m-worker", "p1", "builder", Position(170.0, 210.0), 90.0),
                UnitSpawn("z-factory", "p1", "landFactory", Position(450.0, 170.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("a-bay" to 1L, "m-worker" to 2L, "z-factory" to 3L), Long.MAX_VALUE - 1))
        val contexts = List(2) { val result = SimulationContext.initialize(input)
            assertIs<InitResult.Accepted>(result, result.toString()).context }
        var step = 0L
        fun command(action: (SimulationContext) -> Action) = contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(action(it))) }
        fun tick() { contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }; step++ }
        fun worker(c: SimulationContext = contexts[0]) = c.observe().units.single { it.unitId == "m-worker" }
        try {
            command { ProduceAction("use-id", "p1", "z-factory", "c_tank", "0".repeat(32), it.world) }
            while (contexts[0].observe().production!!.units.isEmpty() && step < 600L) tick()
            assertEquals(Long.MAX_VALUE, contexts[0].observe().combat!!.nextObjectId)
            command { ReclaimUnitAction("weaken", "p1", "a-bay", "m-worker", it.world) }
            while (worker().health!! > 1.02 && step < 1000L) tick()
            assertTrue(worker().health!! in 0.0..1.02)
            command { StopAction("hold", "p1", "a-bay", it.world) }
            command { ProduceAction("overflow", "p1", "z-factory", "c_tank", "1".repeat(32), it.world) }
            repeat(401) { index ->
                command { StopAction("hold-$index", "p1", "a-bay", it.world) }
                tick()
            }
            command { ReclaimUnitAction("terminal", "p1", "a-bay", "m-worker", it.world) }
            // Below half HP is guarded through V99; align that terminal work with the next birth.
            repeat(99) { tick() }
            val failed = contexts[0]
            val before = failed.observe(); val playerBefore = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(step, 1.0, "sim-tick")) }
            assertSame(before, failed.observe()); assertSame(playerBefore, failed.observePlayer(failed.world, "p1"))
            assertEquals(contexts[1].observe().units, failed.observe().units)
            command { CancelProductionAction("correct", "p1", "z-factory", "c_tank", it.world) }
            tick()
            assertEquals(contexts[1].observe().units, failed.observe().units)
            assertEquals(contexts[1].observe().economy!!.preciseBalances, failed.observe().economy!!.preciseBalances)
            assertTrue(failed.observe().units.none { it.unitId == "m-worker" })
            assertEquals(1, failed.observe().events.count { it.kind == "building-reclaimed" })
            val oldWorld = failed.world
            failed.close()
            assertIs<InitResult.Accepted>(failed.reopen(input))
            assertNotEquals(oldWorld, failed.world)
            assertTrue(failed.observe().units.single { it.unitId == "a-bay" }.tasks.isEmpty())
            assertEquals(170.0, worker(failed).health)
            assertIs<ActionResult.Rejected>(failed.submit(RepairUnitAction("stale", "p1", "a-bay", "m-worker", oldWorld)))
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun native_assistance_finishes_a_really_hit_project_before_starting_hp_repair() {
        val base = scene()
        val input = base.copy(units = base.units.map {
            if (it.unitId == "enemy") it.copy(position = Position(450.0, 210.0)) else it })
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun submit(action: Action) = assertIs<ActionResult.Accepted>(c.submit(action))
            submit(BuildAction("build", "p1", "worker", "repairbay", Position(250.0, 210.0), "a".repeat(32), c.world))
            while (c.observe().units.none { it.unitId.startsWith("constructed:") } && step < 200L) tick()
            val id = c.observe().units.single { it.unitId.startsWith("constructed:") }.unitId
            fun project() = c.observe().units.single { it.unitId == id }
            submit(StopAction("stop-builder", "p1", "worker", c.world))
            submit(AttackAction("actual-hit", "p2", "enemy", id, c.world))
            while (project().health == 1000.0 && step < 350L) tick()
            assertTrue(project().health!! in 1.0..<1000.0)
            submit(MoveAction("retreat", "p2", "enemy", Position(550.0, 350.0), c.world))
            repeat(100) { tick() }
            submit(RepairUnitAction("assist", "p1", "bay", id, c.world))
            val injured = project().health
            while (project().constructionProgress < 1f && step < 1600L) {
                tick()
                assertEquals(injured, project().health, "Construction, including its completion entry, does not heal HP")
            }
            assertEquals(1f, project().constructionProgress)
            tick()
            assertTrue(project().health!! > injured!!)
        } finally { c.close() }
    }

    @Test fun two_native_workers_remove_a_mobile_target_once_and_each_discovers_the_invalid_target() {
        val base = scene()
        val input = base.copy(units = listOf(base.units[0], base.units[1],
            UnitSpawn("bay2", "p1", "repairbay", Position(230.0, 210.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("bay" to 1L, "worker" to 2L, "bay2" to 3L), 4L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            for (bay in listOf("bay", "bay2")) assertIs<ActionResult.Accepted>(c.submit(
                ReclaimUnitAction("reclaim-$bay", "p1", bay, "worker", c.world)))
            val events = mutableListOf<Event>()
            for (step in 0L..220L) events += c.advance(Step(step, 1.0, "sim-tick")).events
            assertTrue(c.observe().units.none { it.unitId == "worker" })
            assertTrue(c.observe().units.all { it.tasks.isEmpty() })
            assertEquals(1, events.count { it.kind == "building-reclaimed" })
            assertEquals(0, events.count { it.kind == "unit-died" })
            assertEquals(16400.000005960464, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun native_explicit_repair_does_not_grant_target_control_or_enemy_reclaim() {
        val base = scene()
        val input = base.copy(units = base.units.filter { it.unitId != "enemy" }.map {
            if (it.unitId == "worker") it.copy(ownerPlayerId = "p2") else it },
            combatContract = CombatContract(1, -1, mapOf("bay" to 1L, "worker" to 2L), 3L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(c.submit(
                RepairUnitAction("self", "p1", "bay", "bay", c.world))).error.code)
            assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(c.submit(
                ReclaimUnitAction("enemy", "p1", "bay", "worker", c.world))).error.code)
            assertIs<ActionResult.Rejected>(c.submit(RepairUnitAction("foreign-actor", "p2", "bay", "worker", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(RepairUnitAction("explicit", "p1", "bay", "worker", c.world)))
            assertIs<ActionResult.Rejected>(c.submit(MoveAction("no-control", "p1", "worker", Position(210.0, 210.0), c.world)))
        } finally { c.close() }
    }

    @Test fun the_native_definition_performs_mobile_work_without_the_ordinary_building_price_registration() {
        val base = scene()
        val input = base.copy(maintenanceContract = null,
            units = base.units.filter { it.unitId != "enemy" },
            combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "worker" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("native-work", "p1", "bay", "worker", context.world)))
            for (step in 0L..200L) context.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(context.observe().units.none { it.unitId == "worker" })
            assertEquals(listOf("worker"), context.observe().reclaimedUnitIds)
            assertEquals(16400.000005960464, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun an_explicit_zero_reclaim_price_never_falls_back_to_eighty_percent_in_any_syntax() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val zero = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                root.resolve("repairbay-production/$format/reclaim-zero-package"))).value
            val base = scene()
            val input = base.copy(unitDefinitions = base.unitDefinitions + zero.units,
                units = base.units.filter { it.unitId != "enemy" }.map {
                    if (it.unitId == "worker") it.copy(typeId = "c_tank") else it },
                combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "worker" to 9L), 10L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("reclaim-zero", "p1", "bay", "worker", context.world)))
                val events = mutableListOf<Event>()
                for (step in 0L..230L) events += context.advance(Step(step, 1.0, "sim-tick")).events
                assertTrue(context.observe().units.none { it.unitId == "worker" })
                assertEquals(16000.0, context.observe().economy!!.preciseBalances!!.getValue("p1"))
                assertEquals(0, events.count { it.kind == "unit-died" })
                assertEquals(1, events.count { it.kind == "building-reclaimed" && it.unitId == "worker" })
            } finally { context.close() }
        }
    }

    @Test fun manual_work_uses_the_native_sea_factory_type_bonus_but_automatic_search_keeps_230() = WaterTestScene.withMap { map ->
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val naval = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("naval-production/ini/production-package"))).value
        val base = scene()
        val input = base.copy(map = map, unitDefinitions = naval.units + base.unitDefinitions,
            units = listOf(UnitSpawn("bay", "p1", "repairbay", Position(110.0, 210.0), 270.0),
                UnitSpawn("factory", "p1", "seaFactory", Position(450.0, 210.0), 0.0)),
            maintenanceContract = MaintenanceContract(mapOf("repairbay" to 1500L, "seaFactory" to 1000L,
                "seaFactoryT2" to 3000L)),
            combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "factory" to 9L), 10L))
        val result = SimulationContext.initialize(input)
        val context = assertIs<InitResult.Accepted>(result, result.toString()).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("damage", "p1", "bay", "factory", context.world)))
            context.advance(Step(0L, 1.0, "sim-tick"))
            assertEquals(998.97998046875, context.observe().units.single { it.unitId == "factory" }.health)
            assertIs<ActionResult.Accepted>(context.submit(RepairUnitAction("repair", "p1", "bay", "factory", context.world)))
            context.advance(Step(1L, 1.0, "sim-tick"))
            assertEquals(999.1799926757812, context.observe().units.single { it.unitId == "factory" }.health)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bay", context.world)))
            for (step in 2L..45L) context.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(context.observe().units.single { it.unitId == "bay" }.tasks.isEmpty())
            assertEquals(999.1799926757812, context.observe().units.single { it.unitId == "factory" }.health)
        } finally { context.close() }
    }

    @Test fun automatic_choice_uses_absolute_hp_and_then_forward_spatial_callbacks_rather_than_nearness_or_ids() {
        for (equal in listOf(false, true)) {
            val base = scene()
            val input = base.copy(units = listOf(
                UnitSpawn("bay", "p1", "repairbay", Position(310.0, 210.0), 270.0),
                UnitSpawn("z-left", "p1", "builder", Position(170.0, 210.0), 90.0),
                UnitSpawn("a-right", "p1", if (equal) "builder" else "c_tank", Position(350.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "z-left" to 9L, "a-right" to 10L), 11L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                var q = 0L
                fun tick() = context.advance(Step(q++, 1.0, "sim-tick"))
                for ((target, duration) in listOf("z-left" to if (equal) 1 else 20, "a-right" to if (equal) 1 else 49)) {
                    assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("damage-$target", "p1", "bay", target, context.world)))
                    repeat(duration) { tick() }
                }
                val targets = context.observe().units.associateBy { it.unitId }
                if (equal) assertEquals(targets.getValue("z-left").health, targets.getValue("a-right").health)
                else {
                    assertTrue(targets.getValue("z-left").health!! < targets.getValue("a-right").health!!)
                    assertTrue(targets.getValue("z-left").health!! / 170.0 > targets.getValue("a-right").health!! / 210.0)
                }
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bay", context.world)))
                while (context.observe().units.single { it.unitId == "bay" }.tasks.isEmpty() && q < 100L) tick()
                val automatic = context.observe().units.single { it.unitId == "bay" }.tasks.single()
                assertEquals("z-left", automatic.targetUnitId)
                assertTrue(automatic.automaticRepair)
                assertEquals(0f, automatic.extraWorkRange)
            } finally { context.close() }
        }
    }

    @Test fun an_automatic_repair_yields_to_an_appended_ordinary_order_after_its_current_work_entry() {
        val scene = scene().let { it.copy(units = it.units.filter { unit -> unit.unitId != "enemy" },
            combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "worker" to 9L), 10L)) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("damage", "p1", "bay", "worker", context.world)))
            context.advance(Step(0L, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bay", context.world)))
            for (step in 1L..40L) context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(UnitTaskKind.CONSTRUCT, context.observe().units.single { it.unitId == "bay" }.tasks.single().kind)
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("queued", "p1", "bay", "worker", context.world, append = true)))
            context.advance(Step(41L, 1.0, "sim-tick"))
            assertEquals(UnitTaskKind.RECLAIM, context.observe().units.single { it.unitId == "bay" }.tasks.single().kind)
            assertEquals(169.17999267578125, context.observe().units.single { it.unitId == "worker" }.health)
        } finally { context.close() }
    }

    @Test fun automatic_repair_waits_strictly_over_40_and_stop_preserves_the_independent_scan_timer() {
        val scene = scene().let { it.copy(units = it.units.filter { unit -> unit.unitId != "enemy" },
            combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "worker" to 9L), 10L)) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            fun tick(step: Long) = context.advance(Step(step, 1.0, "sim-tick"))
            fun bay() = context.observe().units.single { it.unitId == "bay" }
            fun worker() = context.observe().units.single { it.unitId == "worker" }
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("damage", "p1", "bay", "worker", context.world)))
            tick(0L)
            assertEquals(168.97999572753906, worker().health)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bay", context.world)))
            for (step in 1L..39L) tick(step)
            assertTrue(bay().tasks.isEmpty())
            assertEquals(168.97999572753906, worker().health)
            tick(40L)
            assertEquals(UnitTaskKind.CONSTRUCT, bay().tasks.single().kind)
            assertEquals("worker", bay().tasks.single().targetUnitId)
            assertEquals(168.97999572753906, worker().health)
            tick(41L)
            assertEquals(169.17999267578125, worker().health)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-again", "p1", "bay", context.world)))
            for (step in 42L..80L) tick(step)
            assertTrue(bay().tasks.isEmpty())
            assertEquals(169.17999267578125, worker().health)
            tick(81L)
            assertEquals("worker", bay().tasks.single().targetUnitId)
            tick(82L)
            assertEquals(169.37998962402344, worker().health)
        } finally { context.close() }
    }

    @Test fun fixed_bay_works_at_230_keeps_the_working_five_bonus_and_never_chases_a_departing_target() {
        val scene = scene().let { it.copy(units = it.units.filter { unit -> unit.unitId != "enemy" }.map { unit ->
                if (unit.unitId == "worker") unit.copy(position = Position(340.0, 210.0)) else unit },
            combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "worker" to 9L), 10L)) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var q = 0L
            fun tick() = context.advance(Step(q++, 1.0, "sim-tick"))
            fun bay() = context.observe().units.single { it.unitId == "bay" }
            fun worker() = context.observe().units.single { it.unitId == "worker" }
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("reclaim", "p1", "bay", "worker", context.world)))
            tick()
            assertEquals(168.97999572753906, worker().health)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("depart", "p1", "worker", Position(450.0, 210.0), context.world)))
            while (worker().position.x <= 340.0 && q < 80L) tick()
            assertTrue(worker().position.x in 340.0..345.0)
            tick()
            assertEquals("worker", bay().constructionTargetId)
            while (worker().position.x <= 345.0 && q < 100L) tick()
            assertTrue(worker().position.x > 345.0)
            tick()
            assertTrue(bay().tasks.isEmpty())
            assertNull(bay().moveTarget)
            assertEquals(Position(110.0, 210.0), bay().position)
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("outside", "p1", "bay", "worker", context.world)))
            tick()
            assertTrue(bay().tasks.isEmpty())
        } finally { context.close() }
    }

    @Test fun reclaiming_a_mobile_unit_preserves_work_on_stop_and_returns_the_native_terminal_price_once() {
        val scene = scene().let { it.copy(units = it.units.filter { unit -> unit.unitId != "enemy" },
            combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "worker" to 9L), 10L)) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("reclaim", "p1", "bay", "worker", context.world)))
            context.advance(Step(0L, 1.0, "sim-tick"))
            assertEquals(168.97999572753906, context.observe().units.single { it.unitId == "worker" }.health)
            assertEquals(16000L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bay", context.world)))
            assertEquals(168.97999572753906, context.observe().units.single { it.unitId == "worker" }.health)
            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("again", "p1", "bay", "worker", context.world)))
            for (step in 1L..200L) context.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(context.observe().units.none { it.unitId == "worker" })
            assertEquals(listOf("worker"), context.observe().reclaimedUnitIds)
            assertEquals(16400.000005960464, context.observe().economy!!.preciseBalances!!.getValue("p1"))
            context.advance(Step(201L, 1.0, "sim-tick"))
            assertEquals(16400.000005960464, context.observe().economy!!.preciseBalances!!.getValue("p1"))
        } finally { context.close() }
    }

    @Test fun native_bay_repairs_a_really_hit_mobile_target_immediately_without_turn_or_credit_charge() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            var q = 0L
            fun tick() = context.advance(Step(q++, 1.0, "sim-tick"))
            fun worker() = context.observe().units.single { it.unitId == "worker" }
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("hit", "p2", "enemy", "worker", context.world)))
            while (worker().health == 170.0 && q < 180L) tick()
            assertTrue(worker().health!! in 1.0..169.0, "The target must receive actual weapon damage")
            assertIs<ActionResult.Accepted>(context.submit(StopAction("cease", "p2", "enemy", context.world)))
            // Existing projectiles remain real; move the shooter out of automatic acquisition range.
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("retreat", "p2", "enemy", Position(550.0, 210.0), context.world)))
            repeat(20) { tick() }
            assertTrue(worker().alive)
            val health = worker().health!!
            val balance = context.observePlayer(context.world, "p1").balance
            assertIs<ActionResult.Accepted>(context.submit(RepairUnitAction("repair", "p1", "bay", "worker", context.world)))
            tick()
            assertEquals((health.toFloat() + .2f).toDouble(), worker().health)
            assertEquals(270.0, context.observe().units.single { it.unitId == "bay" }.heading)
            assertEquals("worker", context.observe().units.single { it.unitId == "bay" }.constructionTargetId)
            assertEquals(balance, context.observePlayer(context.world, "p1").balance)
            val stoppedHealth = worker().health
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bay", context.world)))
            assertEquals(stoppedHealth, worker().health)
            assertTrue(context.observe().units.single { it.unitId == "bay" }.tasks.isEmpty())
        } finally { context.close() }
    }

    private fun scene(): Scenario {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isDirectory(it.resolve("SimulationTest/public")) }.resolve("SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("repairbay-production/ini/production-package"))).value
        val base = CombatTestContent.scenario()
        val tank = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(
            root.resolve("production-combat/combat/units/tank.ini"))).value
        return base.copy(unitDefinitions = mapOf(tank.id to tank) + pack.units,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("bay", "p1", "repairbay", Position(110.0, 210.0), 270.0),
                UnitSpawn("worker", "p1", "builder", Position(170.0, 210.0), 90.0),
                UnitSpawn("enemy", "p2", "c_tank", Position(290.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("bay" to 8L, "worker" to 9L, "enemy" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)),
            maintenanceContract = MaintenanceContract(mapOf("repairbay" to 1500L)))
    }
}
