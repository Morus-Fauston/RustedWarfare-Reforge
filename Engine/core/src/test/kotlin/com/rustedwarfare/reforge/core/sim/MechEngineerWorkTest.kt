package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechEngineerWorkTest {
    @Test fun automatic_range_release_preserves_the_queued_manual_successor() {
        val base = scene()
        val input = base.copy(units = base.units.filter { it.unitId != "enemy" }.map {
            if (it.unitId == "target") it.copy(position = Position(249.0, 210.0)) else it
        }, combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "target" to 9L), 10L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun actor() = c.observe().units.single { it.unitId == "engineer" }
            fun target() = c.observe().units.single { it.unitId == "target" }
            assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("weaken", "p1", "engineer", "target", c.world)))
            while (target().health!! > 70.0 && step < 200L) tick()
            assertTrue(target().health!! < 70.0)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("auto", "p1", "engineer", c.world)))
            tick()
            assertTrue(actor().tasks.single().automaticRepair)
            tick()
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("depart", "p1", "target", Position(550.0, 210.0), c.world)))
            while (target().position.x <= 255.0 && step < 300L) tick()
            assertTrue(target().position.x > 255.0)
            assertTrue(actor().tasks.single().automaticRepair)
            assertIs<ActionResult.Accepted>(c.submit(RepairUnitAction("queued-manual", "p1", "engineer", "target",
                c.world, append = true)))
            tick()
            assertEquals("queued-manual", actor().tasks.single().actionId)
            assertFalse(actor().tasks.single().automaticRepair)
            assertNull(actor().moveTarget, "Dropping the automatic head does not run its successor immediately")
            tick()
            assertEquals("queued-manual", actor().tasks.single().actionId)
            assertNotNull(actor().moveTarget, "The queued manual repair can approach normally on its next update")
        } finally { c.close() }
    }

    @Test fun automatic_work_keeps_the_exact_145_tangent_then_releases_beyond_it() {
        val base = scene()
        val input = base.copy(units = base.units.filter { it.unitId != "enemy" }.map {
            if (it.unitId == "target") it.copy(position = Position(249.52800000000002, 210.0)) else it
        }, combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "target" to 9L), 10L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun actor() = c.observe().units.single { it.unitId == "engineer" }
            fun target() = c.observe().units.single { it.unitId == "target" }
            assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("weaken", "p1", "engineer", "target", c.world)))
            while (target().health!! > 70.0 && step < 200L) tick()
            assertTrue(target().health!! < 70.0)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("auto", "p1", "engineer", c.world)))
            tick()
            assertTrue(actor().tasks.single().automaticRepair)
            tick()
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("depart", "p1", "target", Position(550.0, 210.0), c.world)))
            // Fixed initial placement and eighteen ordinary updates reach the declared tangent.
            repeat(18) { tick() }
            assertEquals(255.0, target().position.x)
            assertEquals(210.0, target().position.y)
            val before = target().health!!
            tick()
            assertTrue(actor().tasks.single().automaticRepair, "Equality remains inside the effective work range")
            assertEquals((before.toFloat() + .3f).toDouble(), target().health)
            assertTrue(target().position.x > 255.0)
            tick()
            assertTrue(actor().tasks.isEmpty())
            assertEquals(Position(110.0, 210.0), actor().position)
        } finally { c.close() }
    }

    @Test fun automatic_repair_releases_a_target_leaving_work_range_while_manual_repair_can_approach() {
        val base = scene()
        val input = base.copy(units = base.units.filter { it.unitId != "enemy" }.map {
            if (it.unitId == "target") it.copy(position = Position(249.0, 210.0)) else it
        }, combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "target" to 9L), 10L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun actor() = c.observe().units.single { it.unitId == "engineer" }
            fun target() = c.observe().units.single { it.unitId == "target" }
            assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("weaken", "p1", "engineer", "target", c.world)))
            while (target().health!! > 70.0 && step < 200L) tick()
            assertTrue(target().health!! < 70.0)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("select-auto", "p1", "engineer", c.world)))
            tick()
            assertTrue(actor().tasks.single().automaticRepair)
            val beforeTreatment = target().health!!
            tick()
            assertEquals((beforeTreatment.toFloat() + .3f).toDouble(), target().health)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("leave", "p1", "target", Position(550.0, 210.0), c.world)))
            while (target().position.x <= 255.0 && step < 300L) {
                tick()
                assertEquals(Position(110.0, 210.0), actor().position)
            }
            assertTrue(target().position.x > 255.0)
            tick()
            assertTrue(actor().tasks.isEmpty(), "Automatic work must release a target outside its 140+5 working range")
            assertNull(actor().moveTarget)
            val health = target().health
            repeat(45) { tick(); assertEquals(health, target().health); assertTrue(actor().tasks.isEmpty()) }
            assertIs<ActionResult.Accepted>(c.submit(RepairUnitAction("manual", "p1", "engineer", "target", c.world)))
            tick()
            assertEquals("manual", actor().tasks.single().actionId)
            assertFalse(actor().tasks.single().automaticRepair)
            assertNotNull(actor().moveTarget, "Manual repair retains its normal approach behavior")
        } finally { c.close() }
    }

    @Test fun reclaim_terminal_retains_the_task_until_the_next_update_before_auto_selection_or_queued_work() {
        for (queued in listOf(false, true)) {
            val base = scene()
            val input = base.copy(units = base.units.filter { it.unitId != "enemy" } +
                UnitSpawn("candidate", "p1", "builder", Position(170.0, 230.0), 90.0),
                combatContract = CombatContract(12345, -1,
                    mapOf("engineer" to 8L, "target" to 9L, "candidate" to 10L), 11L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                var step = 0L
                val events = mutableListOf<Event>()
                fun tick() { c.advance(Step(step++, 1.0, "sim-tick")); events += c.observe().events }
                fun actor() = c.observe().units.single { it.unitId == "engineer" }
                fun candidate() = c.observe().units.single { it.unitId == "candidate" }
                assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("weaken", "p1", "engineer", "candidate", c.world)))
                while (candidate().health!! > 155.0 && step < 50L) tick()
                assertTrue(candidate().health!! < 170.0)
                val injured = candidate().health
                assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("terminal", "p1", "engineer", "target", c.world)))
                if (queued) assertIs<ActionResult.Accepted>(c.submit(RepairUnitAction("after-reclaim", "p1", "engineer",
                    "candidate", c.world, append = true)))
                while (c.observe().units.any { it.unitId == "target" } && step < 250L) tick()
                assertFalse(c.observe().units.any { it.unitId == "target" })
                assertTrue(step > 40L)
                assertEquals("terminal", actor().tasks.first().actionId,
                    "Terminal removal retains the reclaim task for the next invalid-target entry")
                assertEquals(UnitTaskKind.RECLAIM, actor().tasks.first().kind)
                assertEquals(if (queued) 2 else 1, actor().tasks.size)
                assertEquals(injured, candidate().health)
                assertEquals(1, events.count { it.kind == "building-reclaimed" && it.unitId == "target" })
                tick()
                val next = actor().tasks.single()
                if (queued) {
                    assertEquals("after-reclaim", next.actionId)
                    assertFalse(next.automaticRepair)
                } else {
                    assertEquals("candidate", next.targetUnitId)
                    assertTrue(next.automaticRepair)
                    assertEquals(injured, candidate().health, "Automatic selection does not execute treatment")
                    tick()
                    assertEquals((checkNotNull(injured).toFloat() + .3f).toDouble(), candidate().health)
                }
                assertEquals(1, events.count { it.kind == "building-reclaimed" && it.unitId == "target" })
            } finally { c.close() }
        }
    }

    @Test fun mobile_construction_handles_funds_duplicate_nonce_and_unfinished_reclaim_without_extra_births() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-engineer-production/ini/production-package"))).value
        val base = scene()
        for (funds in listOf(0L, 600L)) {
            val input = base.copy(unitDefinitions = pack.units, units = base.units.filter { it.unitId == "engineer" },
                combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to funds, "p2" to 0L)))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                val nonce = "e".repeat(32)
                assertIs<ActionResult.Rejected>(c.submit(BuildAction("bad", "p1", "engineer", "mechGun",
                    Position(Double.NaN, 210.0), nonce, c.world)))
                assertEquals(funds, c.observePlayer(c.world, "p1").balance)
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "engineer", "mechGun",
                    Position(210.0, 210.0), nonce, c.world)))
                tick()
                if (funds == 0L) {
                    assertTrue(c.observe().events.any { it.kind == "construction-insufficient-funds" })
                    assertEquals(1, c.observe().units.size)
                    assertTrue(c.observe().construction!!.nonces.isEmpty())
                    assertEquals(0L, c.observePlayer(c.world, "p1").balance)
                    assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-waiting", "p1", "engineer", c.world)))
                    repeat(10) { tick() }
                    assertEquals(1, c.observe().units.size)
                    continue
                }
                val id = "constructed:p1:$nonce"
                fun project() = assertNotNull(c.observe().units.singleOrNull { it.unitId == id },
                    "Missing paid project at step $step with funds $funds: ${c.observe().units}")
                assertEquals(0L, c.observePlayer(c.world, "p1").balance)
                val duplicate = c.submit(BuildAction("duplicate", "p1", "engineer", "mechGun",
                    Position(250.0, 210.0), nonce, c.world, append = true))
                assertEquals("duplicate-building-nonce", assertIs<ActionResult.Rejected>(duplicate).error.code)
                repeat(20) { tick() }
                val progress = project().constructionProgress
                assertTrue(progress > 0f && progress < .5f)
                assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("unfinished", "p1", "engineer", id, c.world)))
                val events = mutableListOf<Event>()
                repeat(99) { tick(); events += c.observe().events }
                assertEquals(progress, project().constructionProgress)
                tick(); events += c.observe().events
                assertEquals(progress - .001f * 5.1f, project().constructionProgress)
                while (c.observe().units.any { it.unitId == id } && step < 180L) { tick(); events += c.observe().events }
                assertFalse(c.observe().units.any { it.unitId == id })
                assertEquals(1, events.count { it.kind == "building-reclaimed" && it.unitId == id })
                assertFalse(events.any { it.kind == "unit-died" && it.unitId == id })
                assertEquals(600.0 * .8f.toDouble() + (step / 40).toDouble(), c.observePlayer(c.world, "p1").credits)
                assertEquals(setOf(nonce), c.observe().construction!!.nonces.getValue("p1"))
            } finally { c.close() }
        }
    }

    @Test fun really_injured_engineer_suspends_self_repair_in_transport_resumes_on_exit_and_reopens_cleanly() {
        val base = scene()
        val input = base.copy(unitDefinitions = base.unitDefinitions + ("dropship" to DropshipScene.definition()),
            units = base.units.filter { it.unitId != "target" } +
                UnitSpawn("z-air", "p1", "dropship", Position(110.0, 230.0), 90.0),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "enemy" to 9L, "z-air" to 10L), 11L))
        val opened = SimulationContext.initialize(input)
        val c = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun engineer() = c.observe().units.single { it.unitId == "engineer" }
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("injure", "p2", "enemy", "engineer", c.world)))
            while (engineer().health == 2000.0 && step < 80L) tick()
            assertTrue(engineer().health!! in 1.0..<2000.0)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("retreat", "p2", "enemy", Position(550.0, 210.0), c.world)))
            repeat(200) { tick() }
            assertIs<ActionResult.Accepted>(c.submit(LoadAction("board", "p1", "engineer", "z-air", LoadDirection.INTO, c.world)))
            assertEquals("z-air", engineer().carriedBy)
            assertEquals(3, c.observe().units.single { it.unitId == "z-air" }.transport!!.occupiedSlots)
            val held = engineer().health
            repeat(100) { tick(); assertEquals(held, engineer().health) }
            assertIs<ActionResult.Accepted>(c.submit(UnloadAction("exit", "p1", "z-air", true, c.world)))
            while (engineer().carriedBy != null && step < 700L) tick()
            assertNull(engineer().carriedBy)
            val before = engineer().health!!
            tick()
            assertEquals((before.toFloat() + .08f).toDouble(), engineer().health)
            val oldWorld = c.world
            c.close()
            assertIs<InitResult.Accepted>(c.reopen(input))
            assertNotEquals(oldWorld, c.world)
            assertEquals(2000.0, engineer().health)
            assertNull(engineer().carriedBy)
            assertTrue(engineer().tasks.isEmpty())
            assertIs<ActionResult.Rejected>(c.submit(StopAction("stale", "p1", "engineer", oldWorld)))
            step = 0L
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("destroy", "p2", "enemy", "engineer", c.world)))
            // The fixed tank deals 25/75 while the engineer restores .08 per tick.
            while (engineer().alive && step < 9000L) tick()
            assertFalse(engineer().alive, "Normal ground weapon damage must actually destroy the engineer")
            assertEquals(0.0, engineer().health)
            val creditsAtDeath = c.observePlayer(c.world, "p1").credits
            repeat(100) { tick(); assertEquals(0.0, engineer().health) }
            assertEquals(creditsAtDeath, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun busy_mobile_scan_accumulates_and_selects_lowest_absolute_hp_then_forward_spatial_ties() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val normal = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("repairbay-production/ini/normal-package"))).value
        for (equal in listOf(false, true)) {
            val base = scene()
            val input = base.copy(unitDefinitions = base.unitDefinitions + ("c_tank" to normal.units.getValue("c_tank")), units = listOf(
                UnitSpawn("engineer", "p1", "mechEngineer", Position(310.0, 210.0), 270.0),
                UnitSpawn("z-left", "p1", "builder", Position(230.0, 210.0), 90.0),
                UnitSpawn("a-right", "p1", if (equal) "builder" else "c_tank", Position(350.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "z-left" to 9L, "a-right" to 10L), 11L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                fun unit(id: String) = c.observe().units.single { it.unitId == id }
                for ((id, limit) in listOf("z-left" to if (equal) 169.0 else 161.0,
                    "a-right" to if (equal) 169.0 else 171.0)) {
                    val result = c.submit(ReclaimUnitAction("weaken-$id", "p1", "engineer", id, c.world))
                    assertIs<ActionResult.Accepted>(result, result.toString())
                    while (unit(id).health!! > limit && step < 300L) tick()
                    assertTrue(unit(id).health!! <= limit)
                }
                assertTrue(step > 40, "Nano rotation and work keep the actor busy across the scan period")
                val left = unit("z-left").health!!
                val right = unit("a-right").health!!
                if (equal) assertEquals(left, right) else {
                    assertTrue(left < right)
                    assertTrue(left / 170.0 > right / 210.0)
                }
                assertIs<ActionResult.Accepted>(c.submit(StopAction("clear", "p1", "engineer", c.world)))
                tick()
                val task = unit("engineer").tasks.single()
                assertTrue(task.automaticRepair)
                assertEquals("z-left", task.targetUnitId)
                assertEquals(left, unit("z-left").health, "Selection does not also execute work")
            } finally { c.close() }
        }
    }

    @Test fun manual_mobile_work_includes_the_native_sea_factory_bonus_without_walking_into_water() = WaterTestScene.withMap { map ->
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val naval = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("naval-production/ini/production-package"))).value
        val base = scene()
        val input = base.copy(map = map, unitDefinitions = naval.units + base.unitDefinitions,
            units = listOf(UnitSpawn("engineer", "p1", "mechEngineer", Position(210.0, 210.0), 90.0),
                UnitSpawn("factory", "p1", "seaFactory", Position(450.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "factory" to 9L), 10L))
        val opened = SimulationContext.initialize(input)
        val c = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("reclaim", "p1", "engineer", "factory", c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(998.469970703125, c.observe().units.single { it.unitId == "factory" }.health)
            val worker = c.observe().units.single { it.unitId == "engineer" }
            assertEquals(Position(210.0, 210.0), worker.position)
            assertNull(worker.moveTarget)
        } finally { c.close() }
    }

    @Test fun automatic_search_excludes_exactly_140_then_accepts_a_target_that_moves_inside() {
        val base = scene()
        val input = base.copy(units = base.units.map {
            when (it.unitId) {
                "target" -> it.copy(position = Position(250.0, 210.0), heading = 270.0)
                "enemy" -> it.copy(position = Position(370.0, 210.0))
                else -> it
            }
        })
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun actor() = c.observe().units.single { it.unitId == "engineer" }
            fun target() = c.observe().units.single { it.unitId == "target" }
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("hit", "p2", "enemy", "target", c.world)))
            while (target().health == 170.0 && step < 30L) tick()
            assertTrue(target().health!! < 170.0)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("retreat", "p2", "enemy", Position(550.0, 210.0), c.world)))
            while (step < 41L) tick()
            assertTrue(actor().tasks.isEmpty())
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("inside", "p1", "target", Position(230.0, 210.0), c.world)))
            while (step < 81L) { tick(); assertTrue(actor().tasks.isEmpty()) }
            assertTrue(target().position.x < 250.0, target().toString())
            tick()
            assertTrue(actor().tasks.single().automaticRepair)
            assertEquals("target", actor().tasks.single().targetUnitId)
            val injured = target().health
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-auto", "p1", "engineer", c.world)))
            repeat(40) { tick() }
            assertEquals(injured, target().health)
            tick()
            assertTrue(actor().tasks.single().automaticRepair)
            assertEquals(injured, target().health)
            tick()
            assertEquals((checkNotNull(injured).toFloat() + .3f).toDouble(), target().health)
        } finally { c.close() }
    }

    @Test fun a_paid_mobile_nano_project_stays_inactive_until_completed_then_can_move() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val loaded = ContentLoader.loadContentPack(root.resolve("mech-engineer-production/ini/production-package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
        val base = scene()
        val input = base.copy(unitDefinitions = pack.units, units = base.units.filter { it.unitId == "engineer" },
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            val id = "constructed:p1:${"d".repeat(32)}"
            fun project() = c.observe().units.single { it.unitId == id }
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("mech", "p1", "engineer", "mechGun",
                Position(210.0, 210.0), "d".repeat(32), c.world)))
            tick()
            assertEquals(15400L, c.observePlayer(c.world, "p1").balance)
            assertEquals(0f, project().constructionProgress)
            assertEquals(Position(210.0, 210.0), project().position)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("queued-move", "p1", id, Position(390.0, 210.0), c.world)))
            repeat(20) { tick(); assertEquals(Position(210.0, 210.0), project().position) }
            assertEquals(.048f, project().constructionProgress, .000001f)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("cancel-move", "p1", id, c.world)))
            while (project().constructionProgress < 1f && step < 1800L) tick()
            assertEquals(1f, project().constructionProgress)
            assertEquals(500.0, project().health)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("move", "p1", id, Position(390.0, 210.0), c.world)))
            repeat(20) { tick() }
            assertTrue(project().position.x > 210.0)
        } finally { c.close() }
    }

    @Test fun constructing_a_tier_two_factory_applies_the_targets_half_rate_before_worker_multiplier() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-engineer-production/ini/production-package"))).value
        val base = scene()
        val input = base.copy(unitDefinitions = pack.units, units = base.units.filter { it.unitId == "engineer" },
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("t2", "p1", "engineer", "mechFactoryT2",
                Position(210.0, 210.0), "c".repeat(32), c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(11000L, c.observePlayer(c.world, "p1").balance)
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(.0005f, c.observe().units.single { it.typeId == "mechFactoryT2" }.constructionProgress)
        } finally { c.close() }
    }

    @Test fun repairing_turns_the_main_nano_turret_without_turning_the_mobile_body() {
        val base = scene()
        val input = base.copy(units = base.units.map {
            when (it.unitId) {
                "target" -> it.copy(position = Position(170.0, 150.0))
                "enemy" -> it.copy(position = Position(290.0, 150.0))
                else -> it
            }
        })
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun target() = c.observe().units.single { it.unitId == "target" }
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("hit", "p2", "enemy", "target", c.world)))
            while (target().health == 170.0 && step < 30L) tick()
            assertTrue(target().health!! < 170.0)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("retreat", "p2", "enemy", Position(550.0, 150.0), c.world)))
            val injured = target().health!!
            assertIs<ActionResult.Accepted>(c.submit(RepairUnitAction("repair", "p1", "engineer", "target", c.world)))
            var healed = false
            repeat(35) {
                tick()
                assertEquals(90.0, c.observe().units.single { it.unitId == "engineer" }.heading)
                if (target().health!! > injured) healed = true
            }
            assertTrue(healed)
            val nano = c.observe().combat!!.weapons.single { it.unitId == "engineer" && it.weaponName == "nanoTurret" }
            assertTrue(checkNotNull(nano.turretHeading) < 65.0)
            assertEquals(0.0, nano.warmup)
            assertFalse(c.observe().projectiles.any { it.sourceUnitId == "engineer" })
        } finally { c.close() }
    }

    @Test fun four_feet_keep_their_end_offsets_and_limit_ordinary_swing_starts_to_two() {
        val base = scene()
        val input = base.copy(units = base.units.filter { it.unitId == "engineer" },
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            fun legs() = c.observe().units.single().mechPose!!.legs
            assertEquals(listOf(225.0, 315.0, 135.0, 45.0), legs().map { it.heading })
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("move", "p1", "engineer", Position(380.0, 210.0), c.world)))
            val phases = mutableSetOf<MechLegPhase>()
            repeat(220) { i ->
                c.advance(Step(i.toLong(), 1.0, "sim-tick"))
                phases += legs().map { it.phase }
                assertTrue(legs().count { it.phase in setOf(MechLegPhase.WARMING_UP, MechLegPhase.LIFTING,
                    MechLegPhase.SWINGING) } <= 2)
                assertEquals(listOf(225.0, 315.0, 135.0, 45.0), legs().map { it.heading })
            }
            assertTrue(MechLegPhase.SWINGING in phases)
        } finally { c.close() }
    }

    @Test fun reclaim_uses_mobile_repair_rate_and_refunds_the_target_price_once() {
        val base = scene()
        val input = base.copy(units = base.units.filter { it.unitId != "enemy" },
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "target" to 9L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            val events = mutableListOf<Event>()
            fun tick() { c.advance(Step(step++, 1.0, "sim-tick")); events += c.observe().events }
            fun target() = c.observe().units.singleOrNull { it.unitId == "target" }
            assertIs<ActionResult.Accepted>(c.submit(ReclaimUnitAction("reclaim", "p1", "engineer", "target", c.world)))
            tick()
            assertEquals(168.47000122070312, checkNotNull(target()).health)
            while (target()?.alive == true && step < 160L) tick()
            assertNull(target())
            // The below-half-HP guard waits until elapsed 100; removal is at 155.
            assertEquals(155L, step)
            // Three 1/40 income payments, plus builder's default 80% of 500.
            assertEquals(16403L, c.observePlayer(c.world, "p1").balance)
            repeat(40) { tick() }
            assertEquals(16404L, c.observePlayer(c.world, "p1").balance)
            assertEquals(1, events.count { it.kind == "building-reclaimed" && it.unitId == "target" })
            assertFalse(events.any { it.kind == "unit-destroyed" && it.unitId == "target" })
        } finally { c.close() }
    }

    @Test fun paid_construction_uses_the_engineer_build_multiplier_without_changing_the_target_rate() {
        val base = scene()
        val input = base.copy(units = base.units.filter { it.unitId != "enemy" },
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "target" to 9L), 11L))
        val opened = SimulationContext.initialize(input)
        val c = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "engineer", "repairbay",
                Position(170.0, 210.0), "a".repeat(32), c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            fun project() = c.observe().units.single { it.unitId.startsWith("constructed:") }
            assertEquals(14500L, c.observePlayer(c.world, "p1").balance)
            assertEquals(0f, project().constructionProgress)
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(.002f, project().constructionProgress)
            assertEquals(.001f, input.unitDefinitions.getValue("repairbay").construction!!.rate)
        } finally { c.close() }
    }

    @Test fun idle_mobile_auto_repair_waits_strictly_past_forty_and_does_not_heal_on_selection() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun actor() = c.observe().units.single { it.unitId == "engineer" }
            fun target() = c.observe().units.single { it.unitId == "target" }
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("hit", "p2", "enemy", "target", c.world)))
            while (target().health == 170.0 && step < 40L) tick()
            assertTrue(target().health!! < 170.0)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("retreat", "p2", "enemy", Position(550.0, 210.0), c.world)))
            while (step < 40L) { tick(); assertTrue(actor().tasks.isEmpty()) }
            val injured = target().health
            tick()
            assertEquals("target", actor().tasks.single().targetUnitId)
            assertTrue(actor().tasks.single().automaticRepair)
            assertEquals(injured, target().health)
            tick()
            assertEquals((checkNotNull(injured).toFloat() + .3f).toDouble(), target().health)
        } finally { c.close() }
    }

    @Test fun the_mobile_constructor_repairs_a_really_hit_unit_at_point_three_without_a_builder_warmup() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun target() = c.observe().units.single { it.unitId == "target" }
            fun submit(action: Action) = assertIs<ActionResult.Accepted>(c.submit(action))
            submit(AttackAction("hit", "p2", "enemy", "target", c.world))
            while (target().health == 170.0 && step < 150L) tick()
            assertTrue(target().health!! in 1.0..<170.0)
            submit(StopAction("cease", "p2", "enemy", c.world))
            submit(MoveAction("retreat", "p2", "enemy", Position(550.0, 210.0), c.world))
            repeat(20) { tick() }
            submit(StopAction("stop-auto", "p1", "engineer", c.world))
            val health = checkNotNull(target().health)
            submit(RepairUnitAction("repair", "p1", "engineer", "target", c.world))
            tick()
            assertEquals((health.toFloat() + .3f).toDouble(), target().health)
            assertEquals("target", c.observe().units.single { it.unitId == "engineer" }.constructionTargetId)
            val stopped = target().health
            submit(StopAction("stop", "p1", "engineer", c.world))
            assertEquals(stopped, target().health)
            assertTrue(c.observe().units.single { it.unitId == "engineer" }.tasks.isEmpty())
        } finally { c.close() }
    }

    private fun scene(): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val engineer = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-engineer-production/ini/package"))).value
        val builder = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("repairbay-production/ini/production-package"))).value
        val base = CombatTestContent.scenario()
        return base.copy(unitDefinitions = base.unitDefinitions + builder.units + engineer.units,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("engineer", "p1", "mechEngineer", Position(110.0, 210.0), 90.0),
                UnitSpawn("target", "p1", "builder", Position(170.0, 210.0), 90.0),
                UnitSpawn("enemy", "p2", "c_tank", Position(290.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "target" to 9L, "enemy" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)))
    }
}
