package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EconomyConstructionTest {
    @Test fun the_death_runtime_fixture_uses_real_combat_and_stops_fabricator_income() {
        val temp = Files.createTempDirectory("p006-economy-death-")
        try {
            Files.walk(inputRoot()).use { paths -> paths.forEach { p ->
                val target = temp.resolve(inputRoot().relativize(p))
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            } }
            Files.writeString(temp.resolve("construction.properties"),
                "format=rw-reforge.construction-sandbox.v1\nvariant=economy-death\n")
            val scene = ControlledScenario.load(temp)
            assertEquals(listOf("fabricatorT1", "builder", "c_tank", "c_tank"), scene.units.map { it.typeId })
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                var step = 0L
                while (context.observe().units.single { it.unitId == "fab" }.alive && step < 2000)
                    context.advance(Step(step++, 1.0, "sim-tick"))
                assertFalse(context.observe().units.single { it.unitId == "fab" }.alive)
                assertTrue(context.observe().projectiles.any { it.areaExpansion != null })
                val credits = context.observePlayer(context.world, "p1").credits
                repeat(80) { context.advance(Step(step++, 1.0, "sim-tick")) }
                assertEquals(credits, context.observePlayer(context.world, "p1").credits)
                assertTrue(context.observe().units.filter { it.ownerPlayerId == "p2" }.all { it.health!! < it.maxHealth!! })
                assertTrue(context.observe().projectiles.none { it.areaExpansion != null })
            } finally { context.close() }
        } finally {
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun an_older_unfinished_building_starts_income_on_the_tick_after_its_new_worker_finishes_it() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(110.0, 90.0), 0.0)),
            economyContract = EconomyContract(mapOf("p1" to 1200L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(BuildAction("build", "p1", "worker", "extractorT1", Position(190.0, 290.0), "d".repeat(32), context.world))
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            val id = advance().units.single { it.typeId == "extractorT1" }.unitId
            context.submit(StopAction("stop", "p1", "worker", context.world))
            context.submit(ProduceAction("new-worker", "p1", "factory", "builder", "e".repeat(32), context.world))
            repeat(600) { advance() }
            val worker = context.observe().units.single { it.unitId.startsWith("produced:") }.unitId
            assertIs<ActionResult.Accepted>(context.submit(ResumeConstructionAction("resume", "p1", worker, id, context.world)))
            while (context.observe().units.single { it.unitId == id }.constructionProgress < 1f && step < 2000) advance()
            assertEquals(1f, context.observe().units.single { it.unitId == id }.constructionProgress)
            assertEquals(0f, context.observe().incomeStates.single().elapsed)
            repeat(39) { advance() }
            assertEquals(0.0, context.observePlayer(context.world, "p1").credits)
            advance()
            assertEquals(8.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun one_credit_short_construction_waits_for_real_income_and_exact_price_pays_once() {
        val base = ControlledScenario.load(inputRoot())
        for ((type, price) in listOf("extractorT1" to 700L, "fabricatorT1" to 2200L)) {
            for (short in listOf(false, true)) {
                val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
                    UnitSpawn("income", "p1", "commandCenter", Position(70.0, 70.0), 0.0))
                val map = if (type == "extractorT1") base.map else
                    MapDefinition(30, 20, 20, 20, base.map.tileSets, IntArray(600) { 1 }, base.map.blocking)
                val scene = base.copy(map = map, units = spawns,
                    economyContract = EconomyContract(mapOf("p1" to price - if (short) 1L else 0L, "p2" to 0L)),
                    combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "income" to 2L), 3L))
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                try {
                    assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", type,
                        Position(190.0, 290.0), "c".repeat(32), context.world)))
                    var step = 0L
                    fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
                    var view = advance()
                    if (short) {
                        assertTrue(view.units.none { it.typeId == type })
                        assertTrue(view.events.any { it.kind == "construction-insufficient-funds" })
                        assertEquals(price - 1.0, context.observePlayer(context.world, "p1").credits)
                        while (view.units.none { it.typeId == type } && step < 300) view = advance()
                        assertTrue(step in 10..19)
                    }
                    val created = view.units.single { it.typeId == type }
                    assertEquals(0f, created.constructionProgress)
                    assertEquals(if (short) 3.5 else 0.0, context.observePlayer(context.world, "p1").credits)
                    assertEquals(1, view.construction!!.buildings.size)
                    context.submit(StopAction("stop", "p1", "worker", context.world))
                    val paidBalance = context.observePlayer(context.world, "p1").credits!!
                    repeat(40) { advance() }
                    assertEquals(paidBalance + 18.0, context.observePlayer(context.world, "p1").credits)
                    assertEquals(0f, context.observe().incomeStates.single { it.unitId == created.unitId }.elapsed)
                } finally { context.close() }
            }
        }
    }

    @Test fun an_old_projectile_starts_the_death_expansion_once_and_later_reaches_a_damaged_fabricator() {
        val base = ControlledScenario.load(inputRoot())
        val tank = base.unitDefinitions.getValue("c_tank")
        fun gun(id: String, damage: Double, speed: Double) = UnitDefinition(id, tank.className, tank.radius,
            tank.displayRadius, tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement, emptySet(),
            combat = tank.combat!!.copy(shootDelay = 10000.0, attackRange = 200.0,
                projectile = ProjectileProfile(damage, 99.0, speed)))
        val slow = gun("slow", 500.0, 10.0)
        val weak = gun("weak", 450.0, 200.0)
        val spawns = listOf(UnitSpawn("fab-a", "p2", "fabricatorT1", Position(190.0, 290.0), 0.0),
            UnitSpawn("fab-b", "p2", "fabricatorT1", Position(270.0, 290.0), 0.0),
            UnitSpawn("gun-a", "p1", slow.id, Position(70.0, 290.0), 90.0),
            UnitSpawn("gun-b", "p1", weak.id, Position(390.0, 290.0), 270.0))
        val scene = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, IntArray(600) { 1 }, base.map.blocking),
            unitDefinitions = base.unitDefinitions + listOf(slow, weak).associateBy { it.id }, units = spawns,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 5L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(AttackAction("slow", "p1", "gun-a", "fab-a", context.world))
            context.submit(AttackAction("weaken", "p1", "gun-b", "fab-b", context.world))
            var step = 0L
            var view = context.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(view.units.single { it.unitId == "fab-a" }.alive)
            assertEquals(50.0, view.units.single { it.unitId == "fab-b" }.health)
            while (view.units.single { it.unitId == "fab-a" }.alive && step < 30)
                view = context.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(view.units.single { it.unitId == "fab-a" }.alive)
            val first = view.projectiles.single { it.areaExpansion != null }
            assertEquals(34f, first.areaExpansion!!.remaining)
            var sawChain = false
            while (!sawChain && step < 80) {
                view = context.advance(Step(step++, 1.0, "sim-tick"))
                sawChain = !view.units.single { it.unitId == "fab-b" }.alive
            }
            assertTrue(sawChain)
            val chain = view.projectiles.single { it.sourceUnitId == "fab-b" }
            assertEquals(34f, chain.areaExpansion!!.remaining)
            assertNotEquals(first.objectId, chain.objectId)
            assertEquals(setOf("fab-b"), view.projectiles.single { it.sourceUnitId == "fab-a" }.areaExpansion!!.hitUnitIds)
            val after = context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(33f, after.projectiles.single { it.objectId == chain.objectId }.areaExpansion!!.remaining)
            repeat(40) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertTrue(context.observe().projectiles.isEmpty())
        } finally { context.close() }
    }

    @Test fun late_explosion_allocation_failure_rolls_back_hits_income_ids_and_events_before_retry() {
        val base = ControlledScenario.load(inputRoot())
        val tank = base.unitDefinitions.getValue("c_tank")
        val center = base.unitDefinitions.getValue("commandCenter")
        val incomeSource = UnitDefinition("income-center", center.className, center.radius, center.displayRadius,
            center.totalFrames, center.imagePath, center.imageScale, center.movement, emptySet(),
            durability = center.durability, building = center.building, income = center.income)
        val gun = UnitDefinition("gun", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(
                turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0, shootDelay = 10000.0,
                attackRange = 200.0, projectile = ProjectileProfile(500.0, 99.0, 200.0)))
        val witness = UnitDefinition("witness", tank.className, 1.0, 1.0, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), durability = DurabilityProfile(1000.0, tank.combat!!.deadImagePath))
        val spawns = listOf(UnitSpawn("a-fab", "p2", "fabricatorT1", Position(190.0, 290.0), 0.0),
            UnitSpawn("a-gun", "p1", gun.id, Position(70.0, 290.0), 90.0),
            UnitSpawn("a-witness", "p2", witness.id, Position(235.0, 290.0), 0.0),
            UnitSpawn("b-fab", "p2", "fabricatorT1", Position(330.0, 110.0), 0.0),
            UnitSpawn("b-gun", "p1", gun.id, Position(530.01, 110.0), 270.0),
            UnitSpawn("side", "p2", witness.id, Position(530.0, 210.0), 0.0),
            UnitSpawn("center", "p2", incomeSource.id, Position(70.0, 70.0), 0.0))
        val scene = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, IntArray(600) { 1 }, base.map.blocking),
            unitDefinitions = base.unitDefinitions + listOf(gun, witness, incomeSource).associateBy { it.id }, units = spawns,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), Long.MAX_VALUE - 3))
        val contexts = List(2) { val result = SimulationContext.initialize(scene)
            assertIs<InitResult.Accepted>(result, result.toString()).context }
        fun state(c: SimulationContext): List<Any?> {
            val v = c.observe()
            return listOf(v.units, v.events, v.actionResults, v.combat, v.incomeStates,
                v.economy!!.preciseBalances, v.projectiles.map { listOf(it.objectId, it.sourceUnitId, it.position,
                    it.areaExpansion?.remaining, it.areaExpansion?.radius, it.areaExpansion?.hitUnitIds) },
                v.projectileEvents.map { listOf(it.kind, it.projectile.objectId, it.projectile.areaExpansion?.remaining) })
        }
        try {
            contexts.forEach { c ->
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("first", "p1", "a-gun", "a-fab", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("enter", "p1", "b-gun", Position(500.0, 110.0), c.world)))
                c.advance(Step(0, 1.0, "sim-tick"))
                assertFalse(c.observe().units.single { it.unitId == "a-fab" }.alive)
                assertEquals(35f, c.observe().projectiles.single().areaExpansion!!.remaining)
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("hold", "p1", "b-gun", "side", c.world)))
                for (step in 1L..18L) c.advance(Step(step, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("late", "p1", "b-gun", "b-fab", c.world)))
            }
            val failed = contexts[0]
            val before = failed.observe()
            val playerBefore = failed.observePlayer(failed.world, "p2")
            assertEquals(Long.MAX_VALUE - 1, before.combat!!.nextObjectId)
            assertEquals(4.5, before.economy!!.preciseBalances!!.getValue("p2"))
            assertFailsWith<ArithmeticException> { failed.advance(Step(19, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(playerBefore, failed.observePlayer(failed.world, "p2"))
            assertEquals(setOf("a-witness"), before.projectiles.single().areaExpansion!!.hitUnitIds)
            assertEquals(state(contexts[1]), state(failed))
            contexts.forEach { c ->
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("correct", "p1", "b-gun", "side", c.world)))
                c.advance(Step(19, 1.0, "sim-tick"))
            }
            assertEquals(state(contexts[1]), state(failed))
            val after = failed.observe()
            assertEquals(setOf("a-witness"), after.projectiles.single().areaExpansion!!.hitUnitIds)
            assertEquals(16f, after.projectiles.single().areaExpansion!!.remaining)
            assertEquals(789.9999847412109, after.units.single { it.unitId == "a-witness" }.health)
            assertEquals(20f, after.incomeStates.single { it.unitId == "b-fab" }.elapsed)
            assertEquals(9.0, after.economy!!.preciseBalances!!.getValue("p2"))
            assertTrue(after.units.single { it.unitId == "b-fab" }.alive)
            assertEquals(setOf("a-witness"), before.projectiles.single().areaExpansion!!.hitUnitIds)
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun a_hidden_fabricator_explosion_does_not_change_any_observer_payload() {
        val base = ControlledScenario.load(inputRoot())
        val worker = base.unitDefinitions.getValue("builder")
        val tank = base.unitDefinitions.getValue("c_tank")
        val observer = UnitDefinition("observer", worker.className, worker.radius, worker.displayRadius,
            worker.totalFrames, worker.imagePath, worker.imageScale, worker.movement, emptySet(),
            fogOfWarSightRange = 1, durability = worker.durability)
        val gun = UnitDefinition("gun", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(
                shootDelay = 10000.0, attackRange = 200.0, projectile = ProjectileProfile(500.0, 99.0, 200.0)))
        val spawns = listOf(UnitSpawn("observer", "p1", observer.id, Position(50.0, 70.0), 0.0),
            UnitSpawn("fab", "p2", "fabricatorT1", Position(390.0, 290.0), 0.0),
            UnitSpawn("gun", "p3", gun.id, Position(510.0, 290.0), 270.0),
            UnitSpawn("victim", "p2", "builder", Position(435.0, 290.0), 0.0))
        val scene = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, IntArray(600) { 1 }, base.map.blocking),
            unitDefinitions = base.unitDefinitions + listOf(observer, gun).associateBy { it.id }, units = spawns,
            players = listOf("p1", "p2", "p3").map { Player(it, PlayerControl.LOCAL) },
            automaticVision = SandboxOptions(SandboxMode.MEMORY).knowledgeRules(),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)),
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 5L))
        val quietGun = UnitDefinition(gun.id, gun.className, gun.radius, gun.displayRadius, gun.totalFrames,
            gun.imagePath, gun.imageScale, gun.movement, emptySet(), durability = DurabilityProfile(1000.0, tank.combat!!.deadImagePath))
        val contexts = listOf(scene.copy(unitDefinitions = scene.unitDefinitions + (gun.id to quietGun)), scene)
            .map { assertIs<InitResult.Accepted>(SimulationContext.initialize(it)).context }
        try {
            val attacked = contexts[1]
            assertIs<ActionResult.Accepted>(attacked.submit(AttackAction("hidden-kill", "p3", "gun", "fab", attacked.world)))
            var sawExpansion = false
            repeat(80) { step ->
                contexts.forEach { it.advance(Step(step.toLong(), 1.0, "sim-tick")) }
                sawExpansion = sawExpansion || attacked.observe().projectiles.any { it.areaExpansion != null }
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(views[0].fields(), views[1].fields())
                assertTrue(views[1].projectiles.isEmpty())
                assertTrue(views[1].projectileEvents.isEmpty())
            }
            assertTrue(sawExpansion)
            assertFalse(attacked.observe().units.single { it.unitId == "fab" }.alive)
            assertFalse(attacked.observe().units.single { it.unitId == "victim" }.alive)
            assertTrue(contexts[0].observe().units.all { it.alive })
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun income_timers_start_on_completion_remain_private_and_reset_on_reopen() {
        val scene = extractorScene()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertTrue(context.observe().incomeStates.isEmpty())
            context.submit(BuildAction("build", "p1", "a-worker", "extractorT1", Position(190.0, 290.0),
                "a".repeat(32), context.world))
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            val created = advance().units.single { it.typeId == "extractorT1" }
            context.submit(StopAction("stop", "p1", "a-worker", context.world))
            repeat(80) { advance() }
            val stopped = context.observe()
            assertEquals(listOf(IncomeStateObservation(created.unitId, 0f)), stopped.incomeStates)
            assertEquals(492.0, context.observePlayer(context.world, "p1").credits)
            assertFalse("incomeStates" in context.observePlayer(context.world, "p1").fields())
            context.submit(ResumeConstructionAction("resume", "p1", "a-worker", created.unitId, context.world))
            while (context.observe().units.single { it.unitId == created.unitId }.constructionProgress < 1f && step < 1500)
                advance()
            assertEquals(1f, context.observe().incomeStates.single().elapsed)
            repeat(38) { advance() }
            assertEquals(39f, context.observe().incomeStates.single().elapsed)
            advance()
            assertEquals(0f, context.observe().incomeStates.single().elapsed)
            assertEquals(500.0, context.observePlayer(context.world, "p1").credits)
            assertEquals(0f, stopped.incomeStates.single().elapsed)
            val oldWorld = context.world
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            assertTrue(context.observe().incomeStates.isEmpty())
            assertEquals(1192.0, context.observePlayer(context.world, "p1").credits)
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(
                SelectAction("old", "p1", "a-worker", oldWorld))).error.code)
        } finally { context.close() }
    }

    @Test fun retained_presentation_fields_and_type_specific_references_are_validated_before_loading() {
        val root = Files.createTempDirectory("p006-economic-retained-")
        val names = listOf("fabricatorT1.ini", "fabricatorT1.png", "fabricator_dead.png")
        try {
            for (name in names) Files.copy(inputRoot().resolve("units/$name"), root.resolve(name))
            val file = root.resolve("fabricatorT1.ini")
            val original = Files.readString(file)
            for (changed in listOf(
                original.replace("[graphics]", "[graphics]\nimage_back: ../missing.png"),
                original.replace("body_0s: {frame:2}", "body_0s: {frame:999}"),
                original.replace("buildPriority: 0.03", "buildPriority: NaN"),
                original.replace("SHARED:icon_upgrade_top.png", "../missing.png"),
                original.replace("fireTurretXAtSelfOnDeath: explode", "fireTurretXAtSelfOnDeath: explode\neffectOnDeath: unknownEffect"),
            )) {
                Files.writeString(file, changed)
                assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(file))
            }
        } finally {
            for (name in names) Files.deleteIfExists(root.resolve(name))
            Files.delete(root)
        }
    }

    @Test fun unfinished_fabricator_destruction_neither_explodes_nor_keeps_earning_income() {
        val base = ControlledScenario.load(inputRoot())
        val tank = base.unitDefinitions.getValue("c_tank")
        val gun = UnitDefinition("gun", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = tank.combat!!.copy(
                shootDelay = 10000.0, attackRange = 200.0, projectile = ProjectileProfile(500.0, 99.0, 200.0)))
        val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            UnitSpawn("gun", "p2", "gun", Position(330.0, 290.0), 270.0))
        val scene = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, IntArray(600) { 1 }, base.map.blocking),
            unitDefinitions = base.unitDefinitions + (gun.id to gun), units = spawns,
            economyContract = EconomyContract(mapOf("p1" to 2200L, "p2" to 0L)),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "gun" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(BuildAction("build", "p1", "worker", "fabricatorT1", Position(190.0, 290.0), "4".repeat(32), context.world))
            val created = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.typeId == "fabricatorT1" }
            assertEquals(0f, created.constructionProgress)
            context.submit(StopAction("stop", "p1", "worker", context.world))
            context.submit(AttackAction("destroy", "p2", "gun", created.unitId, context.world))
            repeat(120) {
                val observation = context.advance(Step(it + 1L, 1.0, "sim-tick"))
                assertTrue(observation.projectiles.none { it.areaExpansion != null })
                assertTrue(observation.projectileEvents.none { it.projectile.areaExpansion != null })
            }
            assertFalse(context.observe().units.single { it.unitId == created.unitId }.alive)
            assertTrue(context.observe().units.single { it.unitId == "worker" }.alive)
            assertEquals(0.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun economic_corpses_are_drawn_only_when_the_content_and_completion_allow_it() {
        val scene = ControlledScenario.load(inputRoot())
        val resources = SandboxRenderResources.from(scene)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val sample = context.observe().units.first()
            for (type in listOf("extractorT1", "fabricatorT1")) {
                val appearance = resources.units.getValue(type)
                val alive = sample.copy(typeId = type)
                assertTrue(appearance.isDrawn(alive))
                assertFalse(appearance.isDrawn(alive.copy(alive = false, health = 0.0, constructionProgress = .5f)))
                assertEquals(type == "fabricatorT1", appearance.isDrawn(alive.copy(alive = false, health = 0.0)))
            }
            assertTrue(resources.units.getValue("commandCenter").isDrawn(sample.copy(alive = false, health = 0.0)))
        } finally { context.close() }
    }

    @Test fun each_construction_button_chooses_its_own_type_and_cancelled_presses_do_nothing() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            UnitSpawn("other", "p1", "builder", Position(120.0, 210.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "other" to 2L), 3L))
        SandboxSession(scene).use { session ->
            var active = true
            session.select(Position(120.0, 290.0))
            val input = SandboxInput({ session }, { MapProjection(600.0, 400.0) }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false })
            for (type in listOf("extractorT1", "landFactory", "fabricatorT1")) {
                val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 54f) }
                val listener = ConstructionButtonInput({ session }, { active }, type, { input.beginConstruction(it) })
                actor.addListener(listener)
                fun fire(eventType: com.badlogic.gdx.scenes.scene2d.InputEvent.Type, x: Float = 10f) {
                    actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
                        this.type = eventType; button = com.badlogic.gdx.Input.Buttons.LEFT; pointer = 0
                        stageX = x; stageY = 10f; touchFocus = false
                    })
                }
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown)
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp)
                assertEquals(type, input.constructionTypeId)
                input.cancel()
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown)
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp, 90f)
                assertNull(input.constructionTypeId)
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown)
                listener.cancel()
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp)
                assertNull(input.constructionTypeId)
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown)
                session.select(Position(120.0, 210.0))
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp)
                assertNull(input.constructionTypeId)
                session.select(Position(120.0, 290.0))
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown)
                active = false
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp)
                assertNull(input.constructionTypeId)
                active = true
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown)
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp)
                assertEquals(type, input.constructionTypeId)
                input.cancel()
            }
        }
    }

    @Test fun recalling_another_selection_cancels_construction_instead_of_reviving_it_on_return() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            UnitSpawn("other", "p1", "builder", Position(120.0, 210.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "other" to 2L), 3L))
        SandboxSession(scene).use { session ->
            session.select(Position(120.0, 290.0)); session.saveGroup(1)
            session.select(Position(120.0, 210.0)); session.saveGroup(2)
            val input = SandboxInput({ session }, { MapProjection(600.0, 400.0) },
                { x, y -> Position(x.toDouble(), y.toDouble()) }, { true }, { false }, { false })
            input.group(1)
            assertTrue(input.beginConstruction("extractorT1"))
            input.group(1)
            assertEquals("extractorT1", input.constructionTypeId)
            input.group(2)
            assertEquals("other", session.selectedId)
            assertNull(input.constructionTypeId)
            input.group(1)
            assertEquals("worker", session.selectedId)
            assertNull(input.constructionTypeId)
            assertTrue(session.observation.units.all { it.tasks.isEmpty() })
        }
    }

    @Test fun the_economic_scene_builds_a_fabricator_and_only_complete_buildings_earn_income() {
        val base = ControlledScenario.load(inputRoot())
        assertEquals(listOf("commandCenter", "c_tank", "c_tank"), base.units.map { it.typeId })
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 2200L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        SandboxSession(scene).use { session ->
            session.select(Position(120.0, 290.0))
            assertEquals(listOf("extractorT1", "landFactory", "fabricatorT1"), session.constructionActions.map { it.typeId })
            assertEquals(listOf(700L, 700L, 2200L), session.constructionActions.map { it.price })
            assertEquals(2f, session.nominalIncome("fabricatorT1"))
            assertEquals(0f, session.totalNominalIncome)
            assertEquals(PlacementState.BLOCKED, session.previewConstruction("fabricatorT1", Position(190.0, 290.0))!!.state)
            assertIs<ActionResult.Accepted>(session.build("fabricatorT1", Position(170.0, 210.0)))
            var ticks = 0
            while (session.observation.units.none { it.typeId == "fabricatorT1" } && ticks++ < 200) session.tick()
            val created = session.observation.units.single { it.typeId == "fabricatorT1" }
            assertEquals(0.0, session.observation.credits)
            assertEquals(0f, session.totalNominalIncome)
            while (session.observation.units.single { it.unitId == created.unitId }.constructionProgress < 1f && ticks++ < 2500) {
                session.tick()
                assertEquals(0.0, session.observation.credits)
            }
            assertEquals(1f, session.observation.units.single { it.unitId == created.unitId }.constructionProgress)
            assertEquals(2f, session.totalNominalIncome)
            repeat(38) { session.tick() }
            assertEquals(0.0, session.observation.credits)
            session.tick()
            assertEquals(2.0, session.observation.credits)
        }
    }

    @Test fun a_completed_fabricator_death_expands_once_and_spares_other_owners_on_its_team() {
        val base = extractorScene()
        val fabricator = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadEconomyUnit(inputRoot().resolve("units/fabricatorT1.ini"))).value
        val tank = base.unitDefinitions.getValue("c_tank")
        val gun = UnitDefinition("gun", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), combat = checkNotNull(tank.combat).copy(
                shootDelay = 10000.0, attackRange = 200.0, projectile = ProjectileProfile(500.0, 99.0, 200.0)))
        val target = UnitDefinition("witness", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), durability = DurabilityProfile(1000.0, tank.combat!!.deadImagePath))
        val defense = UnitDefinition("defense", "DefenseWitness", 10.0, 10.0, tank.totalFrames, tank.imagePath,
            tank.imageScale, MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(1000.0, tank.combat!!.deadImagePath),
            building = BuildingProfile(BuildingFootprint(0, 0, 0, 0)), pointDefense = PointDefenseProfile())
        val spawns = listOf(UnitSpawn("fab", "p1", fabricator.id, Position(190.0, 290.0), 0.0),
            UnitSpawn("gun", "p2", gun.id, Position(310.0, 290.0), 270.0),
            UnitSpawn("own", "p1", target.id, Position(235.0, 290.0), 0.0),
            UnitSpawn("ally", "p3", target.id, Position(190.0, 350.0), 0.0),
            UnitSpawn("rounded-surface", "p1", target.id, Position(130.0, 290.0), 0.0, height = -5.00000001),
            UnitSpawn("underwater", "p1", target.id, Position(190.0, 230.0), 0.0, height = -5.000000476837158),
            UnitSpawn("boundary", "p2", target.id, Position(100.0, 290.0), 0.0),
            UnitSpawn("outside", "p1", target.id, Position(99.99, 290.0), 0.0),
            UnitSpawn("defense", "p2", defense.id, Position(190.0, 190.0), 0.0),
            UnitSpawn("moving-in", "p1", target.id, Position(90.0, 290.0), 90.0),
            UnitSpawn("moving-out", "p1", target.id, Position(250.0, 290.0), 90.0))
        val scene = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, IntArray(600) { 1 }, base.map.blocking),
            unitDefinitions = base.unitDefinitions + listOf(fabricator, gun, target, defense).associateBy { it.id }, units = spawns,
            players = listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.LOCAL, "two"),
                Player("p3", PlayerControl.LOCAL, "one")),
            economyContract = EconomyContract(mapOf("p1" to 1192L, "p2" to 0L, "p3" to 0L)),
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), spawns.size + 1L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("kill", "p2", "gun", "fab", context.world)))
            var step = 0L
            var death = context.observe()
            while (death.units.single { it.unitId == "fab" }.alive && step < 30)
                death = context.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(death.units.single { it.unitId == "fab" }.alive)
            assertEquals(1000.0, death.units.single { it.unitId == "own" }.health)
            val born = death.projectiles.single().areaExpansion!!
            assertEquals(35f, born.remaining)
            assertEquals(0f, born.radius)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("enter-area", "p1", "moving-in", Position(130.0, 290.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("leave-area", "p1", "moving-out", Position(330.0, 290.0), context.world)))
            val first = context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(34f, first.projectiles.single().areaExpansion!!.remaining)
            repeat(16) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(1000.0, context.observe().units.single { it.unitId == "own" }.health)
            context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(setOf("own"), context.observe().projectiles.single().areaExpansion!!.hitUnitIds)
            assertTrue(born.hitUnitIds.isEmpty())
            val projected = context.observePlayer(context.world, "p1").projectiles.single()
            assertEquals(context.observe().projectiles.single().areaExpansion!!.radius, projected.areaRadius)
            repeat(22) { context.advance(Step(step++, 1.0, "sim-tick")) }
            val after = context.observe()
            // At distance 45, the specified binary32 falloff is .6f: damage is 210.00001525878906.
            assertEquals(789.9999847412109, after.units.single { it.unitId == "own" }.health)
            assertEquals(1000.0, after.units.single { it.unitId == "ally" }.health)
            assertTrue(after.units.single { it.unitId == "rounded-surface" }.health!! < 1000.0)
            assertEquals(1000.0, after.units.single { it.unitId == "underwater" }.health)
            assertEquals(965.0, after.units.single { it.unitId == "boundary" }.health)
            assertEquals(1000.0, after.units.single { it.unitId == "outside" }.health)
            assertEquals(1f, after.units.single { it.unitId == "defense" }.pointDefense!!.energy)
            assertTrue(after.units.single { it.unitId == "moving-in" }.health!! < 1000.0)
            assertEquals(1000.0, after.units.single { it.unitId == "moving-out" }.health)
            assertEquals(1192.0, context.observePlayer(context.world, "p1").credits)
            assertTrue(after.projectiles.isEmpty())
        } finally { context.close() }
    }

    @Test fun economic_content_rejects_changed_rules_and_invalid_template_or_image_references() {
        val root = Files.createTempDirectory("p006-economic-content-")
        val names = listOf("extractor.ini", "extractor_common.ini", "extractor.png", "extractor_back.png")
        try {
            for (name in names) Files.copy(inputRoot().resolve("units/$name"), root.resolve(name))
            val ini = root.resolve("extractor.ini")
            val original = Files.readString(ini)
            assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(ini))
            val invalid = listOf(
                original.replace("price: 700", "price: 700\nisBio: true"),
                original.replace("price: 700", "price: NaN"),
                original.replace("price: 700", "price: 700\nprice: 700"),
                original.replace("price: 700", "price: 700\nunknownEconomyRule: true"),
                original.replace("copyFrom: extractor_common.ini", "copyFrom: ../extractor_common.ini"),
                original.replace("extractor.png", "extractor_common.ini"),
            )
            for ((index, text) in invalid.withIndex()) {
                Files.writeString(ini, text)
                assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(ini), "invalid economic input $index")
            }
        } finally {
            for (name in names) Files.deleteIfExists(root.resolve(name))
            Files.delete(root)
        }
    }

    @Test fun undiscovered_resource_pools_do_not_change_previews_or_the_players_observation() {
        val base = extractorScene()
        val withPool = base.map.groundGids.also { it[14 * 30 + 24] = 5 }
        val withoutPool = withPool.copyOf().also { it[14 * 30 + 24] = 1 }
        fun scene(gids: IntArray) = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets,
            gids, base.map.blocking), units = listOf(UnitSpawn("worker", "p1", "builder", Position(50.0, 290.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        SandboxSession(scene(withPool), options = SandboxOptions(SandboxMode.MEMORY)).use { first ->
            SandboxSession(scene(withoutPool), options = SandboxOptions(SandboxMode.MEMORY)).use { second ->
                val sessions = listOf(first, second)
                sessions.forEach { it.select(Position(50.0, 290.0)) }
                val previews = sessions.map { it.previewConstruction("extractorT1", Position(490.0, 290.0)) }
                assertEquals(previews[0], previews[1])
                assertEquals(PlacementState.UNEXPLORED, previews[0]!!.state)
                sessions.forEach { assertIs<ActionResult.Accepted>(it.build("extractorT1", Position(490.0, 290.0))) }
                repeat(40) {
                    sessions.forEach { it.tick() }
                    assertEquals(first.observation.fields(), second.observation.fields())
                    assertEquals(first.frame.terrain, second.frame.terrain)
                    assertEquals(first.playerFeedback, second.playerFeedback)
                }
            }
        }
    }

    @Test fun initial_buildings_use_the_same_resource_pool_qualification_as_new_construction() {
        val base = extractorScene()
        fun initial(type: String, position: Position) = SimulationContext.initialize(base.copy(
            units = listOf(UnitSpawn("e", "p1", type, position, 0.0)),
            combatContract = CombatContract(1, -1, mapOf("e" to 1L), 2L)))
        assertIs<InitResult.Rejected>(initial("landFactory", Position(190.0, 290.0)))
        assertIs<InitResult.Rejected>(initial("extractorT1", Position(230.0, 290.0)))
        val valid = assertIs<InitResult.Accepted>(initial("extractorT1", Position(190.0, 290.0))).context
        valid.close()
    }

    @Test fun a_resource_pool_with_hard_ground_accepts_only_the_extractor_and_stopped_construction_has_no_income() {
        val base = extractorScene()
        val map = poolMap(base.map, hard = true)
        val scene = base.copy(map = map, units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        SandboxSession(scene).use { session ->
            session.select(Position(120.0, 290.0))
            assertEquals(PlacementState.CLEAR, session.previewConstruction("extractorT1", Position(190.0, 290.0))!!.state)
            assertEquals(PlacementState.BLOCKED, session.previewConstruction("landFactory", Position(190.0, 290.0))!!.state)
            assertEquals(PlacementState.BLOCKED, session.previewConstruction("extractorT1", Position(230.0, 290.0))!!.state)
            assertIs<ActionResult.Accepted>(session.build("extractorT1", Position(190.0, 290.0)))
            session.tick()
            val created = session.observation.units.single { it.typeId == "extractorT1" }
            session.select(Position(120.0, 290.0)); session.stop()
            repeat(120) { session.tick() }
            assertEquals(492.0, session.observation.credits)
            assertEquals(0f, session.observation.units.single { it.unitId == created.unitId }.constructionProgress)
            assertIs<ActionResult.Accepted>(session.command(created.position))
            repeat(1100) { session.tick() }
            assertEquals(1f, session.observation.units.single { it.unitId == created.unitId }.constructionProgress)
            assertTrue(session.observation.credits!! > 492.0)
        }
        val prebuilt = scene.copy(units = listOf(UnitSpawn("e", "p1", "extractorT1", Position(190.0, 290.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("e" to 1L), 2L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(prebuilt)).context
        try {
            repeat(40) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(1200.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
        assertIs<InitResult.Rejected>(SimulationContext.initialize(prebuilt.copy(
            units = listOf(UnitSpawn("e", "p1", "landFactory", Position(190.0, 290.0), 0.0)))))
    }

    @Test fun a_constructed_extractor_funds_the_next_builder_after_its_first_complete_income_period() {
        val scene = extractorScene()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("extractor", "p1", "a-worker",
                "extractorT1", Position(190.0, 290.0), "1".repeat(32), context.world)))
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            fun view() = context.observePlayer(context.world, "p1")
            advance()
            val created = view().units.single { it.typeId == "extractorT1" }
            assertEquals(0f, created.constructionProgress)
            assertEquals(800.0, created.health)
            assertEquals(492.0, view().credits)
            while (view().units.single { it.unitId == created.unitId }.constructionProgress < 1f && step < 1500L) {
                advance()
                assertEquals(492.0, view().credits)
            }
            assertEquals(1f, view().units.single { it.unitId == created.unitId }.constructionProgress)
            // The older worker finishes it before the new building's own slot: completion counts as tick one.
            repeat(38) { advance() }
            assertEquals(492.0, view().credits)
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("too-soon", "p1", "f", "builder", "2".repeat(32), context.world))).error.code)
            advance()
            assertEquals(500.0, view().credits)
            assertIs<ActionResult.Accepted>(context.submit(
                ProduceAction("funded", "p1", "f", "builder", "3".repeat(32), context.world)))
            assertEquals(0.0, view().credits)
            repeat(510) { advance() }
            assertEquals(2, view().units.count { it.typeId == "builder" && it.alive })
            assertTrue(view().factoryQueues.single().items.isEmpty())
        } finally { context.close() }
    }

    private fun extractorScene(): Scenario {
        val base = ControlledScenario.load(inputRoot().parent.parent.parent.resolve(
            "ENG-P006-002/20261027-contract-01/candidate-input-01"))
        val extractor = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadEconomyUnit(inputRoot().resolve("units/extractor.ini"))).value
        val oldWorker = base.unitDefinitions.getValue("builder")
        val worker = UnitDefinition(oldWorker.id, oldWorker.className, oldWorker.radius, oldWorker.displayRadius,
            oldWorker.totalFrames, oldWorker.imagePath, oldWorker.imageScale, oldWorker.movement, oldWorker.retainedUnconsumed,
            durability = oldWorker.durability, build = oldWorker.build, builder = BuilderProfile(listOf("extractorT1", "landFactory")))
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(inputRoot().resolve("maps/p001-controlled.tmx"))).value
        return base.copy(
            map = map, unitDefinitions = base.unitDefinitions + (extractor.id to extractor) + (worker.id to worker),
            units = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("f", "p1", "landFactory", Position(390.0, 290.0), 0.0)),
            economyContract = EconomyContract(mapOf("p1" to 1192L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "f" to 2L), 3L))
    }

    private fun poolMap(base: MapDefinition, hard: Boolean): MapDefinition {
        val file = Files.createTempFile("p006-economic-pool-", ".tmx")
        try {
            val image = base.tileSets.single().imagePath.toString().replace("&", "&amp;")
            val cliff = if (hard) """<property name="cliff" value=""/>""" else ""
            Files.writeString(file, """<map orientation="orthogonal" renderorder="right-down" width="30" height="20" tilewidth="20" tileheight="20">
                <tileset firstgid="1" name="economic-pool" tilewidth="20" tileheight="20" tilecount="5" columns="5">
                    <image source="$image" width="100" height="20"/>
                    <tile id="4"><properties><property name="res_pool" value=""/>$cliff</properties></tile>
                </tileset>
                <layer name="Ground" width="30" height="20"><data encoding="csv">${base.groundGids.joinToString(",")}</data></layer>
            </map>""")
            return assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(file)).value
        } finally { Files.delete(file) }
    }

    private fun inputRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-003/20261027-contract-01/candidate-input-01")
}
