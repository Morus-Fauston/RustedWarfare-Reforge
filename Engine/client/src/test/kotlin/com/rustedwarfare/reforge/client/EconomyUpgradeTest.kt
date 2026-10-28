package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EconomyUpgradeTest {
    @Test fun selected_economic_tooltips_show_observed_nominal_times_and_disappear_at_max_tier() {
        val expected = mapOf(
            "extractorT1" to "Upgrade - T2 ($1400)\n27.7s\nIncome +$8 -> +$12\nLeft: upgrade\nRight: cancel one",
            "extractorT2" to "Upgrade - T3 ($4000)\n55.5s\nIncome +$12 -> +$20\nLeft: upgrade\nRight: cancel one",
            "fabricatorT1" to "Upgrade - T2 ($4400)\n65.6s\nIncome +$2 -> +$7\nLeft: upgrade\nRight: cancel one",
            "fabricatorT2" to "Upgrade - T3 ($7500)\n93.3s\nIncome +$7 -> +$14\nLeft: upgrade\nRight: cancel one",
            "extractorT3" to null, "fabricatorT3" to null)
        for ((type, tooltip) in expected) {
            val scene = economicScene(type, 10000L)
            SandboxSession(scene).use { session ->
                assertNull(session.economicUpgradeTooltip)
                session.select(scene.units.single().position)
                assertEquals(tooltip, session.economicUpgradeTooltip, type)
                session.close()
                assertNull(session.economicUpgradeTooltip)
            }
        }
    }

    private fun economicScene(type: String, money: Long): Scenario {
        val base = ControlledScenario.load(inputRoot())
        return base.copy(units = listOf(UnitSpawn("economic", "p1", type,
                if (type.startsWith("extractor")) Position(190.0, 290.0) else Position(110.0, 110.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("economic" to 1L), 2L),
            economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L)))
    }

    private fun inputRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-004/20261028-implementation-01/input")

    @Test fun ordinary_upgrade_scenario_starts_with_one_center_and_all_six_economic_definitions() {
        val scene = ControlledScenario.load(inputRoot())
        assertEquals(listOf("commandCenter"), scene.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
        assertEquals(1750L, scene.economyContract!!.initialBalances["p1"])
        assertEquals(listOf("extractorT1", "landFactory", "fabricatorT1"), scene.unitDefinitions.getValue("builder").builder!!.buildingTypeIds)
        assertEquals(setOf("extractorT1", "extractorT2", "extractorT3", "fabricatorT1", "fabricatorT2", "fabricatorT3"),
            scene.unitDefinitions.keys.filter { it.startsWith("extractor") || it.startsWith("fabricator") }.toSet())
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertEquals(1750L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observePlayer(context.world, "p1").upgradeQueues.isEmpty())
        } finally { context.close() }
    }

    @Test fun a_real_new_extractor_accepts_one_paid_upgrade_while_unfinished_and_cancellation_refunds_only_that_work() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(ControlledScenario.load(inputRoot()))).context
        try {
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("worker", "p1", "h", "builder", "1".repeat(32), context.world)))
            while (context.observe().units.none { it.typeId == "builder" } && step < 600) advance()
            val worker = context.observe().units.single { it.typeId == "builder" }.unitId
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", worker, "extractorT1",
                Position(190.0, 290.0), "2".repeat(32), context.world)))
            while (context.observe().units.none { it.typeId == "extractorT1" } && step < 1300) advance()
            val target = context.observe().units.single { it.typeId == "extractorT1" }.unitId
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-worker", "p1", worker, context.world)))
            while (context.observePlayer(context.world, "p1").credits!! < 1400.0 && step < 6000) advance()
            val before = context.observePlayer(context.world, "p1")
            assertEquals(0f, before.upgradeQueues.single { it.unitId == target }.items.firstOrNull()?.progress ?: 0f)
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", target, "upgrade_t2", context.world)))
            val paid = context.observePlayer(context.world, "p1")
            assertEquals(before.credits!! - 1400.0, paid.credits)
            assertEquals(1, paid.upgradeQueues.single { it.unitId == target }.items.size)
            assertEquals("duplicate-upgrade", assertIs<ActionResult.Rejected>(context.submit(
                UpgradeAction("duplicate", "p1", target, "upgrade_t2", context.world))).error.code)
            repeat(80) { advance() }
            val waiting = context.observePlayer(context.world, "p1")
            assertEquals(0f, waiting.upgradeQueues.single { it.unitId == target }.items.single().progress)
            assertEquals(0f, context.observe().incomeStates.single { it.unitId == target }.elapsed)
            assertTrue(waiting.units.single { it.unitId == target }.constructionProgress < 1f)
            assertIs<ActionResult.Accepted>(context.submit(CancelUpgradeAction("cancel", "p1", target, "upgrade_t2", context.world)))
            assertEquals(waiting.credits!! + 1400.0, context.observePlayer(context.world, "p1").credits)
            assertEquals("empty-upgrade-queue", assertIs<ActionResult.Rejected>(context.submit(
                CancelUpgradeAction("cancel-empty", "p1", target, "upgrade_t2", context.world))).error.code)
            assertEquals(waiting.credits!! + 1400.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun each_ordinary_upgrade_requires_its_full_price_and_refunds_once_even_after_stop() {
        for ((type, key, price) in listOf(Triple("extractorT1", "upgrade_t2", 1400L),
            Triple("extractorT2", "upgrade_t3", 4000L), Triple("fabricatorT1", "upgrade_t2", 4400L),
            Triple("fabricatorT2", "upgrade_t3", 7500L))) {
            for (short in listOf(true, false)) {
                val init = SimulationContext.initialize(economicScene(type, price - if (short) 1 else 0))
                val context = assertIs<InitResult.Accepted>(init, "$type: $init").context
                try {
                    fun pay(id: String) = context.submit(UpgradeAction(id, "p1", "economic", key, context.world))
                    fun view() = context.observePlayer(context.world, "p1")
                    val before = view()
                    if (short) {
                        assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(pay("short")).error.code)
                        assertEquals(before.credits, view().credits)
                        assertTrue(view().upgradeQueues.single().items.isEmpty())
                        repeat(40) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                    }
                    val enough = view().credits!!
                    assertIs<ActionResult.Accepted>(pay("pay"))
                    assertEquals(enough - price, view().credits)
                    assertEquals("duplicate-upgrade", assertIs<ActionResult.Rejected>(pay("repeat")).error.code)
                    assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "economic", context.world)))
                    context.advance(Step(if (short) 40L else 0L, 1.0, "sim-tick"))
                    assertEquals(1, view().upgradeQueues.single().items.size)
                    assertTrue(view().upgradeQueues.single().items.single().progress > 0f)
                    assertIs<ActionResult.Accepted>(context.submit(CancelUpgradeAction("cancel", "p1", "economic", key, context.world)))
                    assertEquals(enough, view().credits)
                    assertEquals("empty-upgrade-queue", assertIs<ActionResult.Rejected>(context.submit(
                        CancelUpgradeAction("empty", "p1", "economic", key, context.world))).error.code)
                    assertEquals(enough, view().credits)
                    assertIs<ActionResult.Accepted>(pay("requeue"))
                    assertEquals(enough - price, view().credits)
                } finally { context.close() }
            }
        }
    }

    @Test fun completion_keeps_income_phase_and_credits_the_new_tier_on_the_same_tick_once() {
        data class Boundary(val type: String, val key: String, val price: Long, val phase: Int,
            val ticks: Int, val oldIncome: Double, val newIncome: Double, val target: String)
        // Fixed delta=1 boundary cases, from the four native Float rates and the 40q period.
        for (case in listOf(Boundary("extractorT1", "upgrade_t2", 1400L, 13, 1667, 8.0, 12.0, "extractorT2"),
            Boundary("extractorT2", "upgrade_t3", 4000L, 26, 3334, 12.0, 20.0, "extractorT3"),
            Boundary("fabricatorT1", "upgrade_t2", 4400L, 24, 3936, 2.0, 7.0, "fabricatorT2"),
            Boundary("fabricatorT2", "upgrade_t3", 7500L, 1, 5599, 7.0, 14.0, "fabricatorT3"))) {
            val init = SimulationContext.initialize(economicScene(case.type, case.price))
            val context = assertIs<InitResult.Accepted>(init, "$case: $init").context
            try {
                var step = 0L
                fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
                repeat(case.phase) { tick() }
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("pay", "p1", "economic", case.key, context.world)))
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("old-future", "p1", "economic", case.key,
                    context.world, (case.phase + case.ticks).toLong())))
                repeat(case.ticks - 1) { tick() }
                val before = context.observe()
                assertEquals(case.type, before.units.single().typeId)
                assertEquals(39f, before.incomeStates.single().elapsed)
                val credits = context.observePlayer(context.world, "p1").credits!!
                assertEquals(((case.phase + case.ticks) / 40 - 1) * case.oldIncome, credits)
                val converted = tick()
                assertEquals(case.target, converted.units.single().typeId)
                assertEquals(0f, converted.incomeStates.single().elapsed)
                assertEquals(credits + case.newIncome, context.observePlayer(context.world, "p1").credits)
                assertEquals(1, converted.events.count { it.kind == "upgrade-completed" })
                assertTrue(converted.upgrades!!.queues.getValue("economic").isEmpty())
                val stale = tick().actionResults.single { it.actionId == "old-future" }
                assertEquals(if (case.target.endsWith('3')) "cannot-upgrade" else "unsupported-upgrade",
                    assertIs<ActionResult.Rejected>(stale).error.code)
                assertEquals(credits + case.newIncome, context.observePlayer(context.world, "p1").credits)
                assertEquals(1f, context.observe().incomeStates.single().elapsed)
            } finally { context.close() }
        }
    }

    @Test fun economic_card_owns_left_payment_and_right_cancel_without_retargeting_a_press() {
        val scene = economicScene("extractorT1", 10000L)
        val sessions = mutableListOf<SandboxSession>()
        fun open() = SandboxSession(scene).also { it.select(Position(190.0, 290.0)); sessions += it }
        var current = open()
        var active = true
        val input = EconomyUpgradeInput({ current }, { active })
        val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 64f); addListener(input) }
        fun fire(down: Boolean, button: Int = com.badlogic.gdx.Input.Buttons.LEFT, x: Float = 10f) {
            actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
                type = if (down) com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown else com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp
                this.button = button; pointer = 0; stageX = x; stageY = 10f; touchFocus = false
            })
        }
        fun noProject() = assertTrue(current.selectedUpgradeQueue!!.items.isEmpty())
        try {
            fire(true); fire(false)
            assertEquals(8600.0, current.observation.credits)
            assertEquals("upgrade_t2", current.selectedUpgradeQueue!!.items.single().actionKey)
            fire(true, com.badlogic.gdx.Input.Buttons.RIGHT); fire(false, com.badlogic.gdx.Input.Buttons.RIGHT)
            assertEquals(10000.0, current.observation.credits); noProject()
            fire(true); fire(false, x = 100f); noProject()
            fire(true); active = false; input.cancel(); active = true; fire(false); noProject()
            fire(true); current.select(Position(550.0, 350.0)); current.select(Position(190.0, 290.0)); fire(false); noProject()
            fire(true); current.close(); current = open(); fire(false); noProject()
            assertIs<ActionResult.Accepted>(current.upgrade("upgrade_t2"))
            fire(true)
            repeat(1667) { current.tick() }
            assertEquals("extractorT2", current.observation.units.single().typeId)
            assertEquals("economic", current.selectedId)
            fire(false); noProject()
            fire(true); fire(false)
            assertEquals("upgrade_t3", current.selectedUpgradeQueue!!.items.single().actionKey)
        } finally { sessions.forEach { it.close() } }
    }

    @Test fun late_shot_failure_restores_a_new_economic_registration_before_same_step_retry() {
        val base = ControlledScenario.load(inputRoot())
        val tank = base.unitDefinitions.getValue("c_tank")
        val witness = UnitDefinition("witness", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(),
            durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (witness.id to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("z-enemy", "p2", "c_tank", Position(290.0, 290.0), 270.0),
                UnitSpawn("far", "p1", "witness", Position(290.0, 180.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "z-enemy" to 2L, "far" to 3L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        try {
            val nonce = "7".repeat(32)
            contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(BuildAction("build", "p1", "a-worker",
                "extractorT1", Position(190.0, 290.0), nonce, it.world))) }
            val failed = contexts[0]
            val before = failed.observe()
            val player = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe()); assertSame(player, failed.observePlayer(failed.world, "p1"))
            assertTrue(before.upgrades!!.queues.isEmpty())
            contexts.forEach { c ->
                val held = c.submit(AttackAction("hold", "p2", "z-enemy", "far", c.world))
                assertIs<ActionResult.Accepted>(held, held.toString())
                c.advance(Step(0, 1.0, "sim-tick"))
                val created = c.observe().units.single { it.typeId == "extractorT1" }
                assertEquals(1050.0, c.observePlayer(c.world, "p1").credits)
                assertEquals(Long.MAX_VALUE - 1, c.observe().construction!!.buildings.single().objectId)
                assertEquals(setOf(nonce), c.observe().construction!!.nonces.getValue("p1"))
                assertEquals(created.unitId, c.observePlayer(c.world, "p1").upgradeQueues.single().unitId)
                assertEquals(1L, c.observe().production!!.lastBirthSerial)
            }
            val control = contexts[1].observe(); val retried = failed.observe()
            assertEquals(control.units, retried.units)
            assertEquals(control.combat, retried.combat)
            assertEquals(control.economy!!.preciseBalances, retried.economy!!.preciseBalances)
            assertEquals(control.upgrades!!.queues, retried.upgrades!!.queues)
            assertEquals(control.events, retried.events)
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun late_shot_failure_restores_completed_conversion_and_real_income_before_retry() {
        val base = economicScene("extractorT1", 1400L)
        val tank = base.unitDefinitions.getValue("c_tank")
        val gun = UnitDefinition("fixed-gun", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(
                turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0, shootDelay = 10000.0,
                projectile = ProjectileProfile(1.0, 99.0, 200.0)))
        val witness = UnitDefinition("witness", tank.className, 1.0, 1.0, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath))
        val spawns = base.units + listOf(UnitSpawn("a-gun", "p2", gun.id, Position(70.0, 110.0), 90.0),
            UnitSpawn("a-target", "p1", witness.id, Position(170.0, 110.0), 0.0),
            UnitSpawn("z-gun", "p2", gun.id, Position(430.0, 110.0), 270.0),
            UnitSpawn("z-forward", "p1", witness.id, Position(330.0, 110.0), 0.0),
            UnitSpawn("z-side", "p1", witness.id, Position(430.0, 210.0), 0.0))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + listOf(gun, witness).associateBy { it.id },
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) }, units = spawns,
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        fun state(c: SimulationContext): List<Any?> = c.observe().let { listOf(it.units, it.combat, it.incomeStates,
            it.economy!!.preciseBalances, it.upgrades!!.queues, it.events, it.actionResults, it.projectiles) }
        try {
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("hold", "p2", "z-gun", "z-side", c.world)))
                repeat(13) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertEquals(Long.MAX_VALUE, c.observe().combat!!.nextObjectId)
                assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("pay", "p1", "economic", "upgrade_t2", c.world)))
                for (step in 13L..1678L) c.advance(Step(step, 1.0, "sim-tick"))
                assertEquals("extractorT1", c.observe().units.single { it.unitId == "economic" }.typeId)
                assertEquals(39f, c.observe().incomeStates.single().elapsed)
                assertEquals(328.0, c.observePlayer(c.world, "p1").credits)
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("fire", "p2", "z-gun", "z-forward", c.world)))
            }
            val failed = contexts[0]; val before = failed.observe(); val player = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(1679, 1.0, "sim-tick")) }
            assertSame(before, failed.observe()); assertSame(player, failed.observePlayer(failed.world, "p1"))
            assertEquals(state(contexts[1]), state(failed))
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("correct", "p2", "z-gun", "z-side", c.world)))
                c.advance(Step(1679, 1.0, "sim-tick"))
                assertEquals("extractorT2", c.observe().units.single { it.unitId == "economic" }.typeId)
                assertEquals(340.0, c.observePlayer(c.world, "p1").credits)
                assertEquals(0f, c.observe().incomeStates.single().elapsed)
                assertTrue(c.observe().upgrades!!.queues.getValue("economic").isEmpty())
                assertEquals(1, c.observe().events.count { it.kind == "upgrade-completed" })
            }
            assertEquals(state(contexts[1]), state(failed))
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun invalid_economic_upgrade_registrations_are_rejected_before_opening_a_world() {
        val base = economicScene("extractorT1", 10000L)
        fun variant(unit: UnitDefinition, actions: List<UpgradeActionProfile>? = unit.upgrades?.actions,
            hp: Double = unit.maximumHealth!!, building: BuildingProfile? = unit.building,
            movement: MovementProfile = unit.movement) =
            UnitDefinition(unit.id, unit.className, unit.radius, unit.displayRadius, unit.totalFrames,
                unit.imagePath, unit.imageScale, movement, unit.retainedUnconsumed,
                durability = unit.durability!!.copy(maxHp = hp), building = building,
                income = unit.income, construction = unit.construction,
                upgrades = actions?.let(::UpgradeProfile), deathExplosion = unit.deathExplosion)
        for (type in listOf("extractorT1", "extractorT2", "fabricatorT1", "fabricatorT2")) {
            val original = base.unitDefinitions.getValue(type)
            val action = original.upgrades!!.actions.single()
            val badActions = listOf(emptyList(), listOf(action, action), listOf(action.copy(price = action.price + 1)),
                listOf(action.copy(rate = Float.NaN)), listOf(action.copy(rate = 0f)),
                listOf(action.copy(targetTypeId = "missing")), listOf(action.copy(targetTypeId = "fabricatorT3")),
                listOf(action.copy(targetTypeId = type.dropLast(1) + "3")),
                listOf(action.copy(actionKey = "reinforce")))
            for (actions in badActions.filter { it != listOf(action) }) {
                val candidate = variant(original, actions)
                val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (type to candidate)))
                if (result is InitResult.Accepted) result.context.close()
                assertIs<InitResult.Rejected>(result, "$type: $actions")
            }
            val target = base.unitDefinitions.getValue(action.targetTypeId)
            for (candidate in listOf(variant(target, hp = Double.NaN), variant(target, hp = 0.0),
                variant(target, building = target.building!!.copy(anchor = FootprintAnchor(20f, 20f))),
                variant(target, building = target.building!!.copy(requiresResourcePool = !target.building!!.requiresResourcePool)),
                variant(target, building = target.building!!.copy(footprint = BuildingFootprint(-2, -2, 2, 2))),
                variant(target, building = target.building!!.copy(placementFootprint = BuildingFootprint(-2, -2, 2, 2))),
                variant(target, movement = target.movement.copy(moveSpeed = .1)))) {
                val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (candidate.id to candidate)))
                if (result is InitResult.Accepted) result.context.close()
                assertIs<InitResult.Rejected>(result)
            }
            val missing = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions - action.targetTypeId))
            if (missing is InitResult.Accepted) missing.context.close()
            assertIs<InitResult.Rejected>(missing)
        }
        for (type in listOf("extractorT3", "fabricatorT3")) {
            val candidate = variant(base.unitDefinitions.getValue(type), listOf(UpgradeActionProfile("overclock", "extractorT1", 0, 1f)))
            val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (type to candidate)))
            if (result is InitResult.Accepted) result.context.close()
            assertIs<InitResult.Rejected>(result)
        }
    }

    @Test fun economic_cancel_keeps_fractional_credits_and_refunds_above_the_income_cap() {
        for (capped in listOf(false, true)) {
            val base = economicScene("extractorT1", if (capped) 999999999L else 1396L)
            val spawns = base.units + UnitSpawn("center", "p1", "commandCenter", Position(70.0, 70.0), 0.0)
            val scene = base.copy(units = spawns, combatContract = CombatContract(1, -1,
                mapOf("economic" to 1L, "center" to 2L), 3L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                if (!capped) repeat(10) { tick() }
                assertEquals(if (capped) 999999999.0 else 1400.5, c.observePlayer(c.world, "p1").credits)
                assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("pay", "p1", "economic", "upgrade_t2", c.world)))
                assertEquals(if (capped) 999998599.0 else .5, c.observePlayer(c.world, "p1").credits)
                if (capped) repeat(1500) { tick() }
                assertEquals(if (capped) 999999570.0 else .5, c.observePlayer(c.world, "p1").credits)
                assertIs<ActionResult.Accepted>(c.submit(CancelUpgradeAction("refund", "p1", "economic", "upgrade_t2", c.world)))
                assertEquals(if (capped) 1000000970.0 else 1400.5, c.observePlayer(c.world, "p1").credits)
                assertEquals("empty-upgrade-queue", assertIs<ActionResult.Rejected>(c.submit(
                    CancelUpgradeAction("empty", "p1", "economic", "upgrade_t2", c.world))).error.code)
                if (capped) { repeat(10) { tick() }; assertEquals(999999999.0, c.observePlayer(c.world, "p1").credits) }
            } finally { c.close() }
        }
    }

    @Test fun a_scheduled_economic_request_rechecks_money_at_execution() {
        val base = economicScene("fabricatorT1", 4400L)
        val spawns = base.units + UnitSpawn("extractor", "p1", "extractorT1", Position(190.0, 290.0), 0.0)
        val scene = base.copy(units = spawns,
            combatContract = CombatContract(1, -1, mapOf("economic" to 1L, "extractor" to 2L), 3L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("later", "p1", "economic", "upgrade_t2", c.world, 0)))
            assertEquals(4400.0, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("spend", "p1", "extractor", "upgrade_t2", c.world)))
            val executed = c.advance(Step(0, 1.0, "sim-tick"))
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(executed.actionResults.single {
                it.actionId == "later" }).error.code)
            assertEquals(3000.0, c.observePlayer(c.world, "p1").credits)
            assertTrue(c.observePlayer(c.world, "p1").upgradeQueues.single { it.unitId == "economic" }.items.isEmpty())
            assertEquals(1, c.observePlayer(c.world, "p1").upgradeQueues.single { it.unitId == "extractor" }.items.size)
        } finally { c.close() }
    }

    @Test fun real_fabricator_death_uses_its_current_tier_stops_income_and_refunds_only_pending_work() {
        // At the configured 90-unit edge the native falloff is 10%.
        for ((tier, damage) in listOf(1 to 35.0, 2 to 45.0, 3 to 85.0)) {
            val base = economicScene("fabricatorT1", 30000L)
            val tank = base.unitDefinitions.getValue("c_tank")
            val gun = UnitDefinition("fixed-gun", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
                tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(
                    turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0, shootDelay = 10000.0,
                    projectile = ProjectileProfile(5000.0, 99.0, 200.0)))
            val witness = UnitDefinition("witness", tank.className, 1.0, 1.0, tank.totalFrames,
                tank.imagePath, tank.imageScale, tank.movement, emptySet(), durability = DurabilityProfile(2000.0, tank.combat!!.deadImagePath))
            val spawns = base.units + listOf(UnitSpawn("z-gun", "p2", gun.id, Position(230.0, 110.0), 270.0),
                UnitSpawn("z-side", "p1", witness.id, Position(230.0, 210.0), 0.0),
                UnitSpawn("blast-witness", "p1", witness.id, Position(110.0, 200.0), 0.0))
            val scene = base.copy(unitDefinitions = base.unitDefinitions + listOf(gun, witness).associateBy { it.id },
                units = spawns, players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
                combatContract = CombatContract(1, -1, spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), spawns.size + 1L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                fun economic() = c.observe().units.single { it.unitId == "economic" }
                c.submit(AttackAction("hold", "p2", "z-gun", "z-side", c.world))
                for (targetTier in 2..tier) {
                    assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("tier-$targetTier", "p1", "economic", "upgrade_t$targetTier", c.world)))
                    while (economic().typeId != "fabricatorT$targetTier" && step < 11000) tick()
                    assertEquals("fabricatorT$targetTier", economic().typeId)
                }
                val pending = if (tier == 1) 4400.0 else if (tier == 2) 7500.0 else 0.0
                if (tier < 3) assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("pending", "p1", "economic", "upgrade_t${tier + 1}", c.world)))
                while (c.observe().incomeStates.single().elapsed != 1f) tick()
                val credits = c.observePlayer(c.world, "p1").credits!!
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("destroy", "p2", "z-gun", "economic", c.world)))
                val death = tick()
                assertFalse(economic().alive)
                assertEquals("fabricatorT$tier", economic().typeId)
                assertEquals(credits + pending, c.observePlayer(c.world, "p1").credits)
                assertEquals(if (pending > 0) 1 else 0, death.events.count { it.kind == "upgrade-refunded" })
                assertEquals(35f, death.projectiles.single { it.areaExpansion != null }.areaExpansion!!.remaining)
                assertTrue(c.observe().upgrades!!.queues.getValue("economic").isEmpty())
                repeat(40) { tick() }
                assertEquals(2000.0 - damage, c.observe().units.single { it.unitId == "blast-witness" }.health)
                assertEquals(credits + pending, c.observePlayer(c.world, "p1").credits)
                assertTrue(c.observe().projectiles.none { it.areaExpansion != null })
            } finally { c.close() }
        }
    }

    @Test fun hidden_economic_payment_cancel_conversion_and_income_keep_the_other_players_full_view_equal() {
        val base = economicScene("fabricatorT1", 10000L)
        val tank = base.unitDefinitions.getValue("c_tank")
        val observer = UnitDefinition("observer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), fogOfWarSightRange = 1,
            durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (observer.id to observer),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("observer", "p1", observer.id, Position(50.0, 350.0), 0.0),
                UnitSpawn("economic", "p2", "fabricatorT1", Position(430.0, 110.0), 0.0)),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 10000L)),
            combatContract = CombatContract(1, -1, mapOf("observer" to 1L, "economic" to 2L), 3L))
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(
                scene.copy(automaticVision = SandboxOptions(mode).knowledgeRules()))).context }
            try {
                val active = contexts[0]
                contexts.forEach { it.observePlayer(it.world, "p1") }
                val old = active.observePlayer(active.world, "p1")
                assertEquals(listOf("observer"), old.units.map { it.unitId })
                assertIs<ActionResult.Accepted>(active.submit(UpgradeAction("hidden", "p2", "economic", "upgrade_t2", active.world)))
                assertSame(old, active.observePlayer(active.world, "p1"))
                for (step in 0L..3970L) {
                    if (step == 10L) {
                        assertIs<ActionResult.Accepted>(active.submit(CancelUpgradeAction("cancel", "p2", "economic", "upgrade_t2", active.world)))
                        assertIs<ActionResult.Accepted>(active.submit(UpgradeAction("again", "p2", "economic", "upgrade_t2", active.world)))
                    }
                    contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                    val views = contexts.map { it.observePlayer(it.world, "p1") }
                    assertEquals(views[0].fields(), views[1].fields(), "$mode/$step")
                    assertEquals(views[0].vision!!.effectiveCells, views[1].vision!!.effectiveCells)
                    assertEquals(views[0].knowledge!!.revision, views[1].knowledge!!.revision)
                }
                assertEquals("fabricatorT2", active.observe().units.single { it.unitId == "economic" }.typeId)
                assertEquals("fabricatorT1", contexts[1].observe().units.single { it.unitId == "economic" }.typeId)
                assertNotEquals(active.observePlayer(active.world, "p2").credits,
                    contexts[1].observePlayer(contexts[1].world, "p2").credits)
                assertTrue(active.observePlayer(active.world, "p1").upgradeQueues.isEmpty())
            } finally { contexts.forEach { it.close() } }
        }
    }

    @Test fun an_unfinished_fabricator_with_paid_upgrade_refunds_once_without_exploding() {
        val base = ControlledScenario.load(inputRoot())
        val tank = base.unitDefinitions.getValue("c_tank")
        val gun = UnitDefinition("gun", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(
                shootDelay = 10000.0, attackRange = 200.0, projectile = ProjectileProfile(5000.0, 99.0, 200.0)))
        val scene = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, IntArray(600) { 1 }, base.map.blocking),
            unitDefinitions = base.unitDefinitions + (gun.id to gun),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("gun", "p2", gun.id, Position(330.0, 290.0), 270.0)),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            economyContract = EconomyContract(mapOf("p1" to 6600L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "gun" to 2L), 3L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            c.submit(BuildAction("build", "p1", "worker", "fabricatorT1", Position(190.0, 290.0), "8".repeat(32), c.world))
            val project = c.advance(Step(0, 1.0, "sim-tick")).units.single { it.typeId == "fabricatorT1" }
            c.submit(StopAction("stop", "p1", "worker", c.world))
            assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("pay", "p1", project.unitId, "upgrade_t2", c.world)))
            assertEquals(0.0, c.observePlayer(c.world, "p1").credits)
            c.submit(AttackAction("destroy", "p2", "gun", project.unitId, c.world))
            var refunds = 0
            repeat(120) {
                val frame = c.advance(Step(it + 1L, 1.0, "sim-tick"))
                refunds += frame.events.count { e -> e.kind == "upgrade-refunded" }
                assertTrue(frame.projectiles.none { shot -> shot.areaExpansion != null })
                assertTrue(frame.projectileEvents.none { e -> e.projectile.areaExpansion != null })
            }
            val dead = c.observe().units.single { it.unitId == project.unitId }
            assertFalse(dead.alive); assertEquals("fabricatorT1", dead.typeId); assertTrue(dead.constructionProgress < 1f)
            assertEquals(1, refunds)
            assertEquals(4400.0, c.observePlayer(c.world, "p1").credits)
            assertTrue(c.observe().upgrades!!.queues.getValue(project.unitId).isEmpty())
            assertTrue(c.observePlayer(c.world, "p1").upgradeQueues.isEmpty())
        } finally { c.close() }
    }

    @Test fun a_builder_keeps_repairing_the_same_damaged_project_after_its_upgrade() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("enemy", "p2", "c_tank", Position(490.0, 290.0), 270.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "enemy" to 2L), 3L),
            economyContract = EconomyContract(mapOf("p1" to 2100L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "extractorT1",
                Position(190.0, 290.0), "6".repeat(32), context.world)))
            val project = advance().units.single { it.typeId == "extractorT1" }
            fun target() = context.observe().units.single { it.unitId == project.unitId }
            context.submit(StopAction("stop", "p1", "worker", context.world))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p2", "enemy", project.unitId, context.world)))
            while (target().health!! > 300.0 && step < 2000) advance()
            assertTrue(target().alive && target().health!! <= 300.0)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("leave", "p2", "enemy", Position(550.0, 50.0), context.world)))
            repeat(450) { advance() }
            val damaged = target().health!!
            assertTrue(damaged > 0.0)
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", project.unitId, "upgrade_t2", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ResumeConstructionAction("resume", "p1", "worker", project.unitId, context.world)))
            while (target().constructionProgress < 1f && step < 4000) advance()
            assertEquals(1f, target().constructionProgress)
            assertEquals(damaged, target().health)
            var before = target()
            while (target().typeId == "extractorT1" && step < 6500) {
                before = target()
                advance()
            }
            val upgraded = target()
            assertEquals("extractorT2", upgraded.typeId)
            assertTrue(upgraded.health!! < 1000.0)
            assertEquals(((before.health!!.toFloat() + .2f) / 800f * 1000f).toDouble(), upgraded.health)
            assertEquals(project.position, upgraded.position)
            assertEquals(project.unitId, context.observe().units.single { it.unitId == "worker" }.tasks.single().targetUnitId)
            advance()
            assertTrue(target().health!! > upgraded.health!!, "Upgrading must not discard ongoing repair")
            while (target().health!! < 1000.0 && step < 10000) advance()
            assertEquals(1000.0, target().health)
            advance()
            assertTrue(context.observe().units.single { it.unitId == "worker" }.tasks.isEmpty())
        } finally { context.close() }
    }

    @Test fun finite_center_income_funds_both_new_buildings_through_their_ordinary_top_tier() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(ControlledScenario.load(inputRoot()))).context
        try {
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            fun until(limit: Long, condition: () -> Boolean) {
                while (!condition() && step < limit) advance()
                assertTrue(condition(), "Condition did not become true by step $step")
            }
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("worker", "p1", "h", "builder", "3".repeat(32), context.world)))
            until(600) { context.observe().units.any { it.typeId == "builder" } }
            val worker = context.observe().units.single { it.typeId == "builder" }.unitId
            for ((index, family, position) in listOf(Triple(0, "extractor", Position(190.0, 290.0)),
                Triple(1, "fabricator", Position(110.0, 110.0)))) {
                val basePrice = if (index == 0) 700.0 else 2200.0
                until(30000) { context.observePlayer(context.world, "p1").credits!! >= basePrice }
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build-$index", "p1", worker, "${family}T1",
                    position, (index + 4).toString().repeat(32), context.world)))
                until(30000) { context.observe().units.any { it.typeId == "${family}T1" } }
                val original = context.observe().units.single { it.typeId == "${family}T1" }
                val id = original.unitId
                for (tier in 2..3) {
                    val price = if (index == 0) if (tier == 2) 1400.0 else 4000.0 else if (tier == 2) 4400.0 else 7500.0
                    until(35000) { context.observePlayer(context.world, "p1").credits!! >= price }
                    assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("up-$index-$tier", "p1", id, "upgrade_t$tier", context.world)))
                    until(50000) { context.observe().units.single { it.unitId == id }.typeId == "${family}T$tier" }
                    val upgraded = context.observe().units.single { it.unitId == id }
                    assertEquals(original.position, upgraded.position)
                    assertEquals(original.ownerPlayerId, upgraded.ownerPlayerId)
                    assertEquals(1f, upgraded.constructionProgress)
                    assertEquals(if (index == 0) if (tier == 2) 1000.0 else 2000.0 else if (tier == 2) 1000.0 else 2200.0, upgraded.maxHealth)
                    assertEquals("unsupported-upgrade".takeIf { tier == 2 } ?: "cannot-upgrade",
                        assertIs<ActionResult.Rejected>(context.submit(UpgradeAction("stale-$index-$tier", "p1", id, "upgrade_t$tier", context.world))).error.code)
                }
                assertTrue(context.observePlayer(context.world, "p1").upgradeQueues.none { it.unitId == id })
            }
            val before = context.observePlayer(context.world, "p1").credits!!
            repeat(40) { advance() }
            assertEquals(before + 52.0, context.observePlayer(context.world, "p1").credits)
            assertEquals(setOf("extractorT3", "fabricatorT3"), context.observe().units.filter {
                it.typeId.startsWith("extractor") || it.typeId.startsWith("fabricator") }.map { it.typeId }.toSet())
        } finally { context.close() }
    }
}
