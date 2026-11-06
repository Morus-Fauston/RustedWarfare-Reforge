package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import kotlin.test.*

internal object AuthorConstructionScene {
    fun load(format: String = "ini"): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("author-construction/$format")
        val units = assertIs<ContentResult.Success<LoadedContentSet>>(
            ContentLoader.loadContentPacks(listOf(root.resolve("package")))).value.units
        val map = assertIs<ContentResult.Success<MapDefinition>>(
            ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))).value
        return HelicopterFactoryScene.scenario().copy(scenarioId = "author-construction-lifecycle", map = map,
            unitDefinitions = units, units = listOf(
                UnitSpawn("a", "p1", "spbuilder", Position(110.0, 210.0), 90.0),
                UnitSpawn("helper", "p1", "spbuilder", Position(130.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "helper" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
    }
}

class AuthorConstructionLifecycleTest {
    private fun playerValues(o: PlayerObservation): List<Any?> = listOf(o.sampleId, o.worldId, o.scenarioId,
        o.playerId, o.stepIndex, o.simulationTime, o.units, o.events, o.actionResults, o.projectiles,
        o.projectileEvents, o.balance, o.credits, o.factoryQueues, o.upgradeQueues, o.weapons,
        o.vision?.epoch, o.vision?.basicCells, o.vision?.effectiveCells, o.knowledge?.revision, o.knowledge?.cells)

    @Test fun an_unseen_author_building_does_not_change_owner_construction_results_or_samples() {
        val base = AuthorConstructionScene.load()
        for (source in PathInformationSource.entries) {
            val input = base.copy(automaticVision = KnowledgeRules(TerrainRetention.MEMORY, source, false),
                units = base.units + UnitSpawn("hidden", "p2", "workshop", Position(510.0, 210.0), 0.0),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "helper" to 9L, "hidden" to 10L), 11L))
            val empty = input.copy(units = base.units, combatContract = input.combatContract!!.copy(
                unitObjectIds = mapOf("a" to 8L, "helper" to 9L)))
            val contexts = listOf(input, empty).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(it)).context }
            try {
                for (c in contexts) {
                    assertTrue(c.observePlayer(c.world, "p1").units.none { it.unitId == "hidden" })
                    assertIs<ActionResult.Rejected>(c.submit(ResumeConstructionAction("unavailable", "p1", "a", "hidden", c.world)))
                    assertIs<ActionResult.Accepted>(c.submit(BuildAction("unseen", "p1", "a", "workshop",
                        Position(510.0, 210.0), "a".repeat(32), c.world)))
                }
                fun compare() = assertEquals(playerValues(contexts[1].observePlayer(contexts[1].world, "p1")),
                    playerValues(contexts[0].observePlayer(contexts[0].world, "p1")), "$source public owner sample")
                compare()
                repeat(20) { step ->
                    contexts.forEach { it.advance(Step(step.toLong(), 1.0, "sim-tick")) }
                    compare()
                }
                for (c in contexts) assertEquals(3000L, c.observePlayer(c.world, "p1").balance)
            } finally { contexts.forEach { it.close() } }
        }
    }

    // Observation and several nested immutable DTOs intentionally have reference equality.
    private fun hostValues(o: Observation): List<Any?> = listOf(o.sampleId, o.worldId, o.scenarioId,
        o.stepIndex, o.simulationTime, o.units, o.events, o.actionResults, o.projectiles, o.combat,
        o.projectileEvents, o.economy?.let { listOf(it.balances, it.queuedProduction, it.preciseBalances) },
        o.production?.let { listOf(it.lastBirthSerial, it.units) }, o.upgrades?.queues,
        o.construction?.let { listOf(it.clock, it.buildings, it.workers, it.nonces) },
        o.incomeStates, o.reclaimedUnitIds, o.soundTriggers)

    @Test fun a_real_projectile_failure_after_author_site_creation_rolls_back_and_retries_the_same_step() {
        val base = AuthorConstructionScene.load()
        val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        val input = base.copy(unitDefinitions = base.unitDefinitions + (tank.id to tank), units = listOf(
            UnitSpawn("a", "p1", "spbuilder", Position(110.0, 170.0), 90.0),
            UnitSpawn("z", "p2", tank.id, Position(310.0, 170.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 1L, "z" to 2L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        val nonce = "a".repeat(32)
        try {
            for (c in contexts) assertIs<ActionResult.Accepted>(c.submit(BuildAction("first", "p1", "a", "workshop",
                Position(190.0, 170.0), nonce, c.world)))
            val failed = contexts[0]
            val before = failed.observe()
            val playerBefore = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0L, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(playerBefore, failed.observePlayer(failed.world, "p1"))
            assertTrue(failed.observe().construction!!.buildings.isEmpty())
            assertTrue(failed.observe().construction!!.nonces.isEmpty())
            assertEquals(3000L, failed.observePlayer(failed.world, "p1").balance)
            assertEquals(Long.MAX_VALUE - 1, failed.observe().combat!!.nextObjectId)
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "a", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("retry", "p1", "a", "workshop",
                    Position(170.0, 150.0), nonce, c.world)))
                c.advance(Step(0L, 1.0, "sim-tick"))
            }
            assertEquals(hostValues(contexts[1].observe()), hostValues(failed.observe()),
                "All public post-retry host DTO values equal a clean context")
            assertEquals(2300L, failed.observePlayer(failed.world, "p1").balance)
            assertEquals(1, failed.observe().events.count { it.kind == "construction-created" })
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun a_dead_author_site_remains_dead_and_cannot_be_resumed_or_paid_again() {
        val base = AuthorConstructionScene.load()
        val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        val guns = listOf(150.0, 190.0, 230.0, 270.0).mapIndexed { i, y ->
            UnitSpawn("gun-$i", "p2", tank.id, Position(310.0, y), 270.0)
        }
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = base.unitDefinitions + (tank.id to tank), units = base.units + guns,
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "helper" to 9L) +
                guns.mapIndexed { i, u -> u.unitId to (20L + i) }.toMap(), 24L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var q = 0L
            fun tick() = context.advance(Step(q++, 1.0, "sim-tick"))
            val id = "constructed:p1:" + "a".repeat(32)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "a", "workshop",
                Position(190.0, 210.0), "a".repeat(32), context.world)))
            tick()
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "a", context.world)))
            for (gun in guns) assertIs<ActionResult.Accepted>(context.submit(AttackAction("fire-${gun.unitId}",
                "p2", gun.unitId, id, context.world)))
            while (context.observe().units.single { it.unitId == id }.alive && q < 1600) tick()
            assertFalse(context.observe().units.single { it.unitId == id }.alive)
            assertIs<ActionResult.Rejected>(context.submit(ResumeConstructionAction("dead-resume", "p1", "a", id, context.world)))
            assertIs<ActionResult.Rejected>(context.submit(BuildAction("reused-nonce", "p1", "a", "workshop",
                Position(190.0, 210.0), "a".repeat(32), context.world)))
            tick()
            assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
            assertEquals(1, context.observe().units.count { it.typeId == "workshop" })
        } finally { context.close() }
    }

    @Test fun an_author_workers_combat_death_leaves_the_other_worker_on_the_same_paid_site() {
        val base = AuthorConstructionScene.load()
        val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = base.unitDefinitions + (tank.id to tank),
            units = base.units + listOf(UnitSpawn("gun", "p2", tank.id, Position(240.0, 170.0), 270.0),
                UnitSpawn("gun-2", "p2", tank.id, Position(240.0, 250.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "helper" to 9L, "gun" to 10L, "gun-2" to 11L), 12L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var q = 0L
            fun tick() = context.advance(Step(q++, 1.0, "sim-tick"))
            for ((worker, nonce) in listOf("a" to "a", "helper" to "b")) assertIs<ActionResult.Accepted>(context.submit(
                BuildAction("build-$worker", "p1", worker, "workshop", Position(190.0, 210.0), nonce.repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("kill-worker", "p2", "gun", "a", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("kill-worker-2", "p2", "gun-2", "a", context.world)))
            tick()
            val id = context.observe().units.single { it.typeId == "workshop" }.unitId
            while (context.observe().units.single { it.unitId == "a" }.alive && q < 1000) tick()
            val death = context.observe()
            assertFalse(death.units.single { it.unitId == "a" }.alive)
            assertTrue(death.units.single { it.unitId == "helper" }.alive)
            assertEquals(id, death.units.single { it.unitId == "helper" }.tasks.single().targetUnitId)
            val progress = death.units.single { it.unitId == id }.constructionProgress
            assertTrue(progress < 1f, "The fixture must kill the worker before completion: step=$q progress=$progress")
            tick(); tick()
            assertTrue(context.observe().units.single { it.unitId == id }.constructionProgress > progress)
            assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun insufficient_money_cancellation_and_outside_targets_leave_no_paid_or_partial_author_site() {
        val base = AuthorConstructionScene.load()
        for (mode in listOf("poor", "cancel", "outside")) {
            val input = if (mode == "poor") base.copy(economyContract = EconomyContract(mapOf("p1" to 699L, "p2" to 0L))) else base
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val initial = context.observePlayer(context.world, "p1").balance
                val action = BuildAction("build", "p1", "a", "workshop",
                    if (mode == "outside") Position(-1.0, 210.0) else Position(190.0, 210.0), "a".repeat(32), context.world)
                val result = context.submit(action)
                if (mode == "outside") assertIs<ActionResult.Rejected>(result) else assertIs<ActionResult.Accepted>(result)
                if (mode == "cancel") assertIs<ActionResult.Accepted>(context.submit(StopAction("cancel", "p1", "a", context.world)))
                repeat(20) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertEquals(initial, context.observePlayer(context.world, "p1").balance, mode)
                assertTrue(context.observe().units.none { it.typeId == "workshop" }, mode)
                assertTrue(context.observe().construction!!.buildings.isEmpty(), mode)
                assertTrue(context.observe().construction!!.nonces.isEmpty(), mode)
                if (mode != "poor") assertTrue(context.observe().units.single { it.unitId == "a" }.tasks.isEmpty())
            } finally { context.close() }
        }
    }

    @Test fun loaded_author_workers_share_one_payment_stop_independently_and_resume_the_same_site() {
        for (format in listOf("ini", "json", "hjson")) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(AuthorConstructionScene.load(format))).context
            try {
                var q = 0L
                fun tick() = context.advance(Step(q++, 1.0, "sim-tick"))
                val id = "constructed:p1:" + "a".repeat(32)
                for ((worker, nonce) in listOf("a" to "a", "helper" to "b"))
                    assertIs<ActionResult.Accepted>(context.submit(BuildAction("build-$worker", "p1", worker,
                        "workshop", Position(190.0, 210.0), nonce.repeat(32), context.world)))
                val first = tick()
                assertEquals(listOf(id), first.units.filter { it.typeId == "workshop" }.map { it.unitId })
                assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
                assertEquals(1, first.events.count { it.kind == "construction-created" })
                repeat(80) { tick() }
                fun progress() = context.observe().units.single { it.unitId == id }.constructionProgress
                assertTrue(progress() > 0f)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-a", "p1", "a", context.world)))
                val before = progress()
                repeat(40) { tick() }
                assertTrue(progress() > before, "The other worker continues")
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-helper", "p1", "helper", context.world)))
                tick()
                val stopped = progress()
                repeat(40) { tick() }
                assertEquals(stopped, progress())
                assertIs<ActionResult.Accepted>(context.submit(ResumeConstructionAction("resume", "p1", "a", id, context.world)))
                while (progress() < 1f && q < 1800L) tick()
                assertEquals(1f, progress())
                assertEquals(1200.0, context.observe().units.single { it.unitId == id }.health)
                assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
                assertEquals(1, context.observe().units.count { it.typeId == "workshop" })
            } finally { context.close() }
        }
    }
}
