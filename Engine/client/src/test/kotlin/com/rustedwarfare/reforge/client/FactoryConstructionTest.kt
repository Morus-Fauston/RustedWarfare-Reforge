package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FactoryConstructionTest {
    @Test fun unseen_buildings_do_not_change_unknown_site_previews_or_submitted_intents() {
        val base = ControlledScenario.load(inputRoot())
        val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(50.0, 290.0), 90.0))
        val clear = base.copy(units = spawns, combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val hidden = clear.copy(units = spawns + UnitSpawn("hidden", "p2", "landFactory", Position(490.0, 290.0), 0.0),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "hidden" to 2L), 3L))
        SandboxSession(clear, options = SandboxOptions(SandboxMode.MEMORY)).use { first ->
            SandboxSession(hidden, options = SandboxOptions(SandboxMode.MEMORY)).use { second ->
                val sessions = listOf(first, second)
                sessions.forEach { it.select(Position(50.0, 290.0)) }
                val previews = sessions.map { it.previewConstruction("landFactory", Position(490.0, 290.0)) }
                assertEquals(previews[0], previews[1])
                assertEquals(PlacementState.UNEXPLORED, previews[0]!!.state)
                sessions.forEach { assertIs<ActionResult.Accepted>(it.build("landFactory", Position(490.0, 290.0))) }
                repeat(40) {
                    sessions.forEach { it.tick() }
                    assertEquals(first.observation.fields(), second.observation.fields())
                    assertEquals(first.frame.terrain, second.frame.terrain)
                    assertEquals(first.playerFeedback, second.playerFeedback)
                }
            }
        }
    }

    @Test fun constructed_and_produced_objects_share_one_birth_sequence_without_updating_new_objects_early() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(390.0, 190.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("factory" to 1L, "worker" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(ProduceAction("produce", "p1", "factory", "builder", "0".repeat(32), context.world))
            var step = 0L
            while (context.observePlayer(context.world, "p1").factoryQueues.single().items.single().progress < .998f && step < 600L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            context.submit(BuildAction("build", "p1", "worker", "landFactory", Position(190.0, 290.0),
                "1".repeat(32), context.world))
            val frame = context.advance(Step(step, 1.0, "sim-tick"))
            val produced = frame.production!!.units.single()
            val constructed = frame.construction!!.buildings.single()
            assertEquals(2L, frame.production!!.lastBirthSerial)
            assertTrue(produced.updateKey < constructed.updateKey)
            assertTrue(produced.objectId < constructed.objectId)
            assertEquals(30f, produced.birthRemaining)
            assertEquals(0f, constructed.progress)
            assertEquals(0f, frame.construction!!.workers.single { it.unitId == "worker" }.preparation)
            assertEquals(550.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun constructed_ids_keep_colon_owners_private_and_scheduled_orders_recheck_nonce_at_execution() {
        val base = ControlledScenario.load(inputRoot())
        val owner = "p:one"
        val scene = base.copy(players = listOf(Player(owner, PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.LOCAL, "two")),
            units = listOf(UnitSpawn("a-worker", owner, "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("b-worker", owner, "builder", Position(120.0, 270.0), 90.0)),
            economyContract = EconomyContract(mapOf(owner to 1750L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "b-worker" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val nonce = "1".repeat(32)
            val id = "constructed:$owner:$nonce"
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("later", owner, "b-worker", "landFactory",
                Position(190.0, 290.0), nonce, context.world, 2L)))
            context.submit(BuildAction("now", owner, "a-worker", "landFactory", Position(190.0, 290.0), nonce, context.world))
            context.advance(Step(0, 1.0, "sim-tick"))
            context.advance(Step(1, 1.0, "sim-tick"))
            val executed = context.advance(Step(2, 1.0, "sim-tick"))
            assertEquals("duplicate-building-nonce", assertIs<ActionResult.Rejected>(
                executed.actionResults.single { it.actionId == "later" }).error.code)
            assertEquals(id, executed.units.single { it.typeId == "landFactory" }.unitId)
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("enemy-view", "p2", id, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("owned-produce", owner, id, "c_tank", "2".repeat(32), context.world)))
            fun actions(target: String, suffix: String, at: Long?): List<Action> = listOf(
                BuildAction("build-$suffix", "p2", target, "landFactory", Position(390.0, 290.0), "3".repeat(32), context.world, at),
                ResumeConstructionAction("resume-$suffix", "p2", target, id, context.world, at),
                MoveAction("move-$suffix", "p2", target, Position(350.0, 150.0), context.world, at),
                StopAction("stop-$suffix", "p2", target, context.world, at),
                PatrolAction("patrol-$suffix", "p2", target, Position(350.0, 150.0), false, context.world, at),
                AttackAction("attack-$suffix", "p2", target, "a-worker", context.world, at),
                ProduceAction("produce-$suffix", "p2", target, "c_tank", "4".repeat(32), context.world, at),
                CancelProductionAction("cancel-$suffix", "p2", target, "c_tank", context.world, at),
                UpgradeAction("upgrade-$suffix", "p2", target, "upgrade_gun", context.world, at),
                CancelUpgradeAction("cancel-upgrade-$suffix", "p2", target, "upgrade_gun", context.world, at))
            for ((index, at) in listOf(null, 5L).withIndex()) {
                val present = actions(id, "present-$index", at).map { assertIs<ActionResult.Rejected>(context.submit(it)).error }
                val absent = actions("constructed:$owner:${"5".repeat(32)}", "absent-$index", at)
                    .map { assertIs<ActionResult.Rejected>(context.submit(it)).error }
                assertEquals(present, absent)
                assertTrue(present.all { it.code == "unit-unavailable" })
            }
            assertEquals(700.0, context.observePlayer(context.world, owner).credits)
        } finally { context.close() }
    }

    @Test fun construction_actions_record_the_exact_intent_and_resume_target_for_replay() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val records = mutableListOf<Map<String, Any?>>()
        SandboxSession(scene, ClientEvidence { kind, value -> if (kind == "action") records += value }).use { session ->
            session.select(Position(120.0, 290.0))
            val action = session.constructionActions.single()
            assertEquals(1200.0, action.maximumHealth)
            assertEquals(.001f, action.rate)
            session.build("landFactory", Position(190.0, 290.0))
            val build = records.single { it["type"] == "BuildAction" }
            assertEquals(mapOf("x" to 190.0, "y" to 290.0), build["target"])
            assertEquals("landFactory", build["buildingTypeId"])
            session.tick()
            val factory = session.observation.units.single { it.typeId == "landFactory" }
            session.select(session.observation.units.single { it.unitId == "worker" }.position)
            session.stop()
            session.command(factory.position)
            assertEquals(factory.unitId, records.single { it["type"] == "ResumeConstructionAction" }["targetUnitId"])
        }
    }

    @Test fun a_failed_build_retries_after_the_clock_gate_and_preserves_the_gate_across_stop() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 699L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            fun order(id: String) = context.submit(BuildAction(id, "p1", "worker", "landFactory",
                Position(190.0, 290.0), "1".repeat(32), context.world))
            order("first")
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(16, first.construction!!.workers.single().retryTime)
            context.submit(StopAction("stop", "p1", "worker", context.world))
            order("again")
            for (step in 1L..12L) {
                val waiting = context.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(waiting.events.none { it.kind == "construction-insufficient-funds" })
                assertEquals(16, waiting.construction!!.workers.single().retryTime)
            }
            val retry = context.advance(Step(13, 1.0, "sim-tick"))
            assertTrue(retry.events.any { it.kind == "construction-insufficient-funds" })
            assertEquals(224, retry.construction!!.workers.single().retryTime)
            assertEquals(699.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun creation_uses_the_order_before_snapping_and_preparation_must_finish_before_work() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(405.0, 290.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(BuildAction("outside", "p1", "worker", "landFactory", Position(491.0, 290.0),
                "1".repeat(32), context.world))
            val outside = context.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(outside.units.none { it.typeId == "landFactory" })
            assertEquals(1750.0, context.observePlayer(context.world, "p1").credits)
            context.submit(BuildAction("inside", "p1", "worker", "landFactory", Position(490.0, 290.0),
                "2".repeat(32), context.world))
            var step = 1L
            val created = context.advance(Step(step++, 1.0, "sim-tick"))
            val factoryId = created.units.single { it.typeId == "landFactory" }.unitId
            assertEquals(0f, created.units.single { it.unitId == factoryId }.constructionProgress)
            while (context.observe().construction!!.workers.single().preparation < 29f && step < 100L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(29f, context.observe().construction!!.workers.single().preparation)
            val ready = context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(30f, ready.construction!!.workers.single().preparation)
            assertEquals(0f, ready.units.single { it.unitId == factoryId }.constructionProgress)
            val work = context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(.001f, work.units.single { it.unitId == factoryId }.constructionProgress)
            assertEquals(1200.0, work.units.single { it.unitId == factoryId }.health)
        } finally { context.close() }
    }

    @Test fun learning_about_a_new_factory_preserves_a_workers_route_to_its_work_range() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("a-worker", "p1", "builder", Position(50.0, 290.0), 90.0),
            UnitSpawn("b-worker", "p1", "builder", Position(418.0, 290.0), 90.0)),
            automaticVision = SandboxOptions(SandboxMode.MEMORY).knowledgeRules(),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "b-worker" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for ((index, worker) in listOf("a-worker", "b-worker").withIndex()) context.submit(
                BuildAction("build-$worker", "p1", worker, "landFactory", Position(490.0, 290.0),
                    index.toString().repeat(32), context.world))
            var completed = false
            var distantWorkerJoined = false
            repeat(1800) {
                val frame = context.advance(Step(it.toLong(), 1.0, "sim-tick"))
                assertTrue(frame.events.none { event -> event.kind == "path-unreachable" }, "Unexpected route failure at step $it")
                completed = completed || frame.units.any { unit -> unit.typeId == "landFactory" && unit.constructionProgress == 1f }
                distantWorkerJoined = distantWorkerJoined || frame.units.single { it.unitId == "a-worker" }.tasks.any { task ->
                    task.kind == UnitTaskKind.CONSTRUCT && task.targetUnitId == frame.units.single { unit -> unit.typeId == "landFactory" }.unitId
                }
            }
            assertTrue(completed)
            assertTrue(distantWorkerJoined, "The distant builder must reach and join the same project")
            assertEquals(1, context.observe().units.count { it.typeId == "landFactory" })
            assertEquals(1050.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun a_fixed_scenario_marker_cannot_silently_enable_or_drop_construction_capabilities() {
        val root = Files.createTempDirectory("p006-marker-")
        try {
            Files.walk(inputRoot()).use { paths -> paths.forEach { source ->
                val target = root.resolve(inputRoot().relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            } }
            val marker = root.resolve("construction.properties")
            val builder = root.resolve("units/builder.properties")
            val factoryMarker = Files.readString(marker)
            val builderV2 = Files.readString(builder)
            Files.writeString(marker, factoryMarker.replace("factory-construction", "command-center-start"))
            assertFails { ControlledScenario.load(root) }
            Files.writeString(builder, builderV2.replace("native-builder.v2", "native-builder.v1")
                .lineSequence().filterNot { it.startsWith("constructionTypes=") }.joinToString("\n"))
            assertNull(ControlledScenario.load(root).unitDefinitions.getValue("builder").builder)
            Files.writeString(marker, factoryMarker)
            assertFails { ControlledScenario.load(root) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun a_builder_accepts_a_construction_order_on_its_birth_frame_and_leaves_the_producer() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = base.units.filter { it.ownerPlayerId == "p1" },
            combatContract = CombatContract(1, -1, mapOf("h" to 1L), 2L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(ProduceAction("produce", "p1", "h", "builder", "0".repeat(32), context.world))
            var step = 0L
            while (context.observe().units.none { it.typeId == "builder" } && step < 700L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            val worker = context.observe().units.single { it.typeId == "builder" }
            assertEquals(30f, context.observe().production!!.units.single().birthRemaining)
            context.submit(BuildAction("build", "p1", worker.unitId, "landFactory", Position(190.0, 290.0),
                "1".repeat(32), context.world))
            val next = context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(UnitTaskKind.BUILD, next.units.single { it.unitId == worker.unitId }.tasks.singleOrNull()?.kind)
            assertTrue(next.events.none { it.kind == "path-unreachable" })
            repeat(1600) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(1f, context.observe().units.single { it.typeId == "landFactory" }.constructionProgress)
            assertEquals(0f, context.observe().production!!.units.single().birthRemaining)
        } finally { context.close() }
    }

    @Test fun a_delayed_construction_payment_failure_is_explained_to_the_player() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 699L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        SandboxSession(scene).use { session ->
            session.select(Position(120.0, 290.0))
            assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 290.0)))
            session.tick()
            assertEquals("Insufficient funds", session.playerFeedback)
            assertEquals(699.0, session.observation.credits)
            assertTrue(session.observation.units.none { it.typeId == "landFactory" })
        }
    }

    @Test fun a_builder_just_outside_work_range_reaches_the_order_and_starts_the_factory() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(418.0, 290.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "landFactory",
                Position(504.0, 290.0), "1".repeat(32), context.world)))
            repeat(300) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val factory = assertNotNull(context.observe().units.singleOrNull { it.typeId == "landFactory" })
            assertEquals(Position(490.0, 290.0), factory.position)
            assertTrue(factory.constructionProgress > 0f)
            assertEquals(1050.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun an_unfunded_order_keeps_retrying_until_timeout_or_real_income_pays_for_the_factory() {
        val base = ControlledScenario.load(inputRoot())
        for (hasIncome in listOf(false, true)) {
            val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0)) +
                if (hasIncome) listOf(UnitSpawn("center", "p1", "commandCenter", Position(110.0, 110.0), 0.0)) else emptyList()
            val scene = base.copy(units = spawns,
                economyContract = EconomyContract(mapOf("p1" to 699L, "p2" to 0L)),
                combatContract = CombatContract(1, -1, spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), spawns.size + 1L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                context.submit(BuildAction("build", "p1", "worker", "landFactory", Position(190.0, 290.0), "1".repeat(32), context.world))
                context.advance(Step(0, 1.0, "sim-tick"))
                assertTrue(context.observe().construction!!.buildings.isEmpty())
                assertEquals(699.0, context.observePlayer(context.world, "p1").credits)
                for (step in 1L..1100L) context.advance(Step(step, 1.0, "sim-tick"))
                val after = context.observe()
                if (hasIncome) {
                    assertEquals(1, after.construction!!.buildings.size)
                    assertTrue(after.units.single { it.typeId == "landFactory" }.constructionProgress > .9f)
                } else {
                    assertTrue(after.construction!!.buildings.isEmpty())
                    assertTrue(after.units.single().tasks.isEmpty())
                    assertEquals(0f, after.construction!!.workers.single().elapsed)
                    assertEquals(699.0, context.observePlayer(context.world, "p1").credits)
                }
            } finally { context.close() }
        }
    }

    @Test fun two_workers_queued_with_one_nonce_create_only_one_paid_project_and_clear_the_losing_work_timer() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 280.0), 90.0),
            UnitSpawn("b-worker", "p1", "builder", Position(120.0, 300.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "b-worker" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val nonce = "1".repeat(32)
            for (worker in listOf("a-worker", "b-worker")) assertIs<ActionResult.Accepted>(context.submit(
                BuildAction("build-$worker", "p1", worker, "landFactory", Position(190.0, 290.0), nonce, context.world)))
            repeat(20) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(1, context.observe().construction!!.buildings.size)
            assertEquals(1050.0, context.observePlayer(context.world, "p1").credits)
            assertTrue(context.observe().units.single { it.unitId == "b-worker" }.tasks.isEmpty())
            assertEquals(0f, context.observe().construction!!.workers.single { it.unitId == "b-worker" }.elapsed)
            assertEquals("duplicate-building-nonce", assertIs<ActionResult.Rejected>(context.submit(
                BuildAction("duplicate", "p1", "b-worker", "landFactory", Position(190.0, 290.0), nonce, context.world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("reuse", "p1", "b-worker", "landFactory",
                Position(190.0, 290.0), "2".repeat(32), context.world)))
            context.advance(Step(20, 1.0, "sim-tick"))
            assertEquals(1, context.observe().construction!!.buildings.size)
            assertEquals(setOf(nonce), context.observe().construction!!.nonces.getValue("p1"))
            assertEquals(1050.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun an_existing_factory_near_the_map_edge_does_not_break_construction_elsewhere() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("old", "p1", "landFactory", Position(110.0, 350.0), 0.0),
            UnitSpawn("worker", "p1", "builder", Position(320.0, 190.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("old" to 1L, "worker" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(BuildAction("build", "p1", "worker", "landFactory", Position(390.0, 190.0), "1".repeat(32), context.world))
            val after = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(2, after.units.count { it.typeId == "landFactory" })
            assertEquals(1050.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun a_stopped_project_keeps_its_queue_and_a_dead_factory_refunds_only_production_then_allows_rebuilding() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("enemy", "p2", "c_tank", Position(490.0, 290.0), 270.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "enemy" to 2L), 3L),
            economyContract = EconomyContract(mapOf("p1" to 2000L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val nonce = "1".repeat(32)
            context.submit(BuildAction("build", "p1", "worker", "landFactory", Position(190.0, 290.0), nonce, context.world))
            val factory = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.typeId == "landFactory" }
            context.submit(StopAction("stop", "p1", "worker", context.world))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("builder", "p1", factory.unitId, "builder", "2".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("tank", "p1", factory.unitId, "c_tank", "3".repeat(32), context.world)))
            assertEquals(450.0, context.observePlayer(context.world, "p1").credits)
            context.submit(AttackAction("attack", "p2", "enemy", factory.unitId, context.world))
            var step = 1L
            while (context.observe().units.single { it.unitId == factory.unitId }.alive && step < 4000L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(context.observe().units.single { it.unitId == factory.unitId }.alive)
            assertEquals(1300.0, context.observePlayer(context.world, "p1").credits)
            assertTrue(context.observePlayer(context.world, "p1").factoryQueues.isEmpty())
            val reused = context.submit(BuildAction("reuse-dead-nonce", "p1", "worker", "landFactory", factory.position, nonce, context.world))
            assertEquals("duplicate-building-nonce", assertIs<ActionResult.Rejected>(reused).error.code)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("replacement", "p1", "worker", "landFactory", factory.position,
                "4".repeat(32), context.world)))
            repeat(100) { if (context.observe().units.none { it.typeId == "landFactory" && it.alive }) context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(1, context.observe().units.count { it.typeId == "landFactory" && it.alive })
            assertEquals(600.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun a_second_builder_resumes_a_project_after_the_first_worker_is_killed() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("b-worker", "p1", "builder", Position(60.0, 190.0), 90.0),
                UnitSpawn("z-enemy", "p2", "c_tank", Position(230.0, 190.0), 180.0)),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "b-worker" to 2L, "z-enemy" to 3L), 4L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(BuildAction("build", "p1", "a-worker", "landFactory", Position(190.0, 290.0), "1".repeat(32), context.world))
            val factoryId = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.typeId == "landFactory" }.unitId
            context.submit(AttackAction("kill-worker", "p2", "z-enemy", "a-worker", context.world))
            var step = 1L
            while (context.observe().units.single { it.unitId == "a-worker" }.alive && step < 2000L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(context.observe().units.single { it.unitId == "a-worker" }.alive)
            assertTrue(context.observe().units.single { it.unitId == factoryId }.constructionProgress < 1f)
            context.submit(MoveAction("leave", "p2", "z-enemy", Position(550.0, 50.0), context.world))
            val balance = context.observePlayer(context.world, "p1").credits
            assertIs<ActionResult.Accepted>(context.submit(ResumeConstructionAction("resume", "p1", "b-worker", factoryId, context.world)))
            repeat(1800) { context.advance(Step(step++, 1.0, "sim-tick")) }
            val factory = context.observe().units.single { it.unitId == factoryId }
            assertTrue(factory.alive)
            assertEquals(1f, factory.constructionProgress)
            assertEquals(balance, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun a_late_projectile_failure_rolls_back_factory_payment_identity_registration_and_work_state() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            UnitSpawn("z-enemy", "p2", "c_tank", Position(290.0, 290.0), 270.0)),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "z-enemy" to 2L), Long.MAX_VALUE - 1))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val nonce = "1".repeat(32)
            context.submit(BuildAction("build", "p1", "a-worker", "landFactory", Position(190.0, 290.0), nonce, context.world))
            val before = context.observe()
            val playerBefore = context.observePlayer(context.world, "p1")
            assertFailsWith<ArithmeticException> { context.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, context.observe())
            assertSame(playerBefore, context.observePlayer(context.world, "p1"))
            assertEquals(1750.0, playerBefore.credits)
            assertTrue(before.construction!!.buildings.isEmpty())
            assertEquals(0, before.construction!!.clock)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("safe-site", "p1", "a-worker", "landFactory",
                Position(110.0, 310.0), nonce, context.world)))
            repeat(100) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val after = context.observe()
            assertEquals(1, after.construction!!.buildings.size)
            assertEquals(Long.MAX_VALUE - 1, after.construction!!.buildings.single().objectId)
            assertEquals(1050.0, context.observePlayer(context.world, "p1").credits)
            assertEquals(setOf(nonce), after.construction!!.nonces.getValue("p1"))
            assertEquals(1L, after.production!!.lastBirthSerial)
        } finally { context.close() }
    }

    @Test fun hidden_construction_does_not_change_another_players_entire_observation_or_namespace_errors() {
        val base = ControlledScenario.load(inputRoot())
        val original = base.unitDefinitions.getValue("builder")
        val observer = UnitDefinition("observer", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            fogOfWarSightRange = 1, durability = original.durability)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (observer.id to observer),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("observer", "p1", observer.id, Position(50.0, 70.0), 90.0),
                UnitSpawn("worker", "p2", "builder", Position(380.0, 250.0), 90.0)),
            automaticVision = SandboxOptions(SandboxMode.MEMORY).knowledgeRules(),
            economyContract = EconomyContract(mapOf("p1" to 1000L, "p2" to 1000L)),
            combatContract = CombatContract(1, -1, mapOf("observer" to 1L, "worker" to 2L), 3L))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        try {
            val paid = contexts[1]
            paid.submit(BuildAction("hidden-build", "p2", "worker", "landFactory", Position(450.0, 250.0), "1".repeat(32), paid.world))
            for (step in 0L..80L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(views[0].fields(), views[1].fields())
                assertNull(views[1].fields()["construction"])
            }
            assertEquals(1, paid.observe().construction!!.buildings.size)
            val id = "constructed:p2:${"1".repeat(32)}"
            for (scheduled in listOf(null, 81L)) {
                val results = contexts.map { context ->
                    context.submit(SelectAction("probe-$scheduled", "p1", id, context.world, scheduled)) }
                assertEquals(results[0], results[1])
                assertEquals("unit-unavailable", assertIs<ActionResult.Rejected>(results[1]).error.code)
            }
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun construction_work_feedback_is_published_only_to_the_owner_and_stops_with_the_order() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.submit(BuildAction("build", "p1", "worker", "landFactory", Position(190.0, 290.0), "1".repeat(32), context.world))
            repeat(40) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val own = context.observePlayer(context.world, "p1")
            val factory = own.units.single { it.typeId == "landFactory" }
            assertEquals(factory.unitId, own.units.single { it.unitId == "worker" }.constructionTargetId)
            assertNull(context.observePlayer(context.world, "p2").units.single { it.unitId == "worker" }.constructionTargetId)
            assertNotNull(context.observe().construction)
            assertEquals(1, context.observe().construction!!.buildings.size)
            context.submit(StopAction("stop", "p1", "worker", context.world))
            assertNull(context.observePlayer(context.world, "p1").units.single { it.unitId == "worker" }.constructionTargetId)
        } finally { context.close() }
    }

    @Test fun each_scene2d_production_button_keeps_its_own_type_through_press_and_release() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("factory", "p1", "landFactory", Position(190.0, 290.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("factory" to 1L), 2L))
        SandboxSession(scene).use { session ->
            session.select(Position(190.0, 290.0))
            for (type in listOf("builder", "c_tank")) {
                val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 64f) }
                actor.addListener(ProductionButtonInput({ session }, { true }, typeId = { type }))
                fun fire(eventType: com.badlogic.gdx.scenes.scene2d.InputEvent.Type, button: Int) {
                    actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
                        this.type = eventType; this.button = button; pointer = 0
                        stageX = 10f; stageY = 10f; touchFocus = false
                    })
                }
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown, com.badlogic.gdx.Input.Buttons.LEFT)
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp, com.badlogic.gdx.Input.Buttons.LEFT)
                assertEquals(type, session.selectedFactoryQueue!!.items.last().typeId)
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown, com.badlogic.gdx.Input.Buttons.RIGHT)
                fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp, com.badlogic.gdx.Input.Buttons.RIGHT)
                assertTrue(session.selectedFactoryQueue!!.items.isEmpty())
                assertEquals(1750.0, session.observation.credits)
            }
        }
    }

    @Test fun right_clicking_an_owned_project_resumes_work_instead_of_moving_into_the_building() {
        SandboxSession(ControlledScenario.load(inputRoot())).use { session ->
            session.select(Position(110.0, 210.0))
            session.produce()
            repeat(601) { session.tick() }
            val workerId = session.observation.units.single { it.typeId == "builder" }.unitId
            session.select(session.observation.units.single { it.unitId == workerId }.position)
            session.build("landFactory", Position(190.0, 290.0))
            repeat(150) { session.tick() }
            val factory = session.observation.units.single { it.typeId == "landFactory" }
            session.select(session.observation.units.single { it.unitId == workerId }.position)
            session.stop()
            repeat(80) { session.tick() }
            assertEquals(factory.constructionProgress, session.observation.units.single { it.unitId == factory.unitId }.constructionProgress)
            assertIs<ActionResult.Accepted>(session.command(factory.position))
            assertEquals(UnitTaskKind.CONSTRUCT, session.observation.units.single { it.unitId == workerId }.tasks.single().kind)
            repeat(1200) { session.tick() }
            assertEquals(1f, session.observation.units.single { it.unitId == factory.unitId }.constructionProgress)
        }
    }

    @Test fun placement_cancel_keeps_selection_invalid_click_stays_active_and_valid_click_creates_a_project() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = base.units + UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            combatContract = base.combatContract!!.copy(unitObjectIds = base.combatContract!!.unitObjectIds + ("worker" to 13L), nextObjectId = 14L))
        SandboxSession(scene).use { session ->
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false })
            fun point(position: Position) = map.x(position.x).toInt() to map.y(position.y).toInt()
            fun click(position: Position) {
                val (x, y) = point(position)
                input.mouseMoved(x, y)
                assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
                assertTrue(input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            }
            session.select(Position(120.0, 290.0))
            assertTrue(input.beginConstruction("landFactory"))
            assertTrue(input.keyDown(com.badlogic.gdx.Input.Keys.ESCAPE))
            assertEquals("worker", session.selectedId)
            assertNull(input.constructionTypeId)
            assertTrue(input.beginConstruction("landFactory"))
            click(Position(110.0, 210.0))
            assertEquals("landFactory", input.constructionTypeId)
            assertEquals(PlacementState.BLOCKED, input.constructionPreview!!.state)
            assertEquals("Cannot build here", session.playerFeedback)
            assertEquals(1750.0, session.observation.credits)
            click(Position(190.0, 290.0))
            assertNull(input.constructionTypeId)
            assertNull(session.selectedId)
            session.tick()
            assertEquals(1, session.observation.units.count { it.typeId == "landFactory" })
            assertEquals(1050.0, session.observation.credits)
        }
    }

    @Test fun the_player_builds_a_factory_and_can_choose_and_cancel_each_product_from_its_menu() {
        SandboxSession(ControlledScenario.load(inputRoot())).use { session ->
            session.select(Position(110.0, 210.0))
            assertIs<ActionResult.Accepted>(session.produce())
            repeat(601) { session.tick() }
            val worker = session.observation.units.single { it.typeId == "builder" }
            session.select(worker.position)
            assertEquals(listOf("landFactory"), session.constructionActions.map { it.typeId })
            assertEquals(700L, session.constructionActions.single().price)
            assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 290.0)))
            assertNull(session.selectedId)
            repeat(150) { session.tick() }
            val factory = session.observation.units.single { it.typeId == "landFactory" }
            session.select(factory.position)
            assertEquals(listOf("builder", "c_tank"), session.productionActions.map { it.typeId })
            assertIs<ActionResult.Accepted>(session.produce("builder"))
            assertIs<ActionResult.Accepted>(session.produce("c_tank"))
            assertEquals(listOf(1, 1), session.productionActions.map { it.count })
            assertEquals(listOf(0f, null), session.productionActions.map { it.progress })
            val balance = session.observation.credits!!
            assertIs<ActionResult.Accepted>(session.cancelProduction("c_tank"))
            assertEquals(balance + 350.0, session.observation.credits)
            assertEquals(listOf("builder"), session.selectedFactoryQueue!!.items.map { it.typeId })
            assertEquals(listOf(1, 0), session.productionActions.map { it.count })
        }
    }

    @Test fun construction_definitions_reject_invalid_cost_rate_placement_and_builder_targets_before_opening_a_world() {
        val base = ControlledScenario.load(inputRoot())
        val factory = base.unitDefinitions.getValue("landFactory")
        fun changed(construction: com.rustedwarfare.reforge.core.content.ConstructionProfile? = factory.construction,
                    building: com.rustedwarfare.reforge.core.content.BuildingProfile? = factory.building) =
            UnitDefinition(factory.id, factory.className, factory.radius, factory.displayRadius, factory.totalFrames,
                factory.imagePath, factory.imageScale, factory.movement, factory.retainedUnconsumed,
                durability = factory.durability, building = building, production = factory.production,
                build = factory.build, construction = construction)
        val originalWorker = base.unitDefinitions.getValue("builder")
        fun targets(ids: List<String>) = UnitDefinition(originalWorker.id, originalWorker.className,
            originalWorker.radius, originalWorker.displayRadius, originalWorker.totalFrames, originalWorker.imagePath,
            originalWorker.imageScale, originalWorker.movement, originalWorker.retainedUnconsumed,
            durability = originalWorker.durability, build = originalWorker.build,
            builder = com.rustedwarfare.reforge.core.content.BuilderProfile(ids))
        val candidates = listOf(
            changed(construction = factory.construction!!.copy(price = -1)),
            changed(construction = factory.construction!!.copy(rate = Float.NaN)),
            changed(construction = factory.construction!!.copy(rate = 0f)),
            changed(building = null),
            changed(building = factory.building!!.copy(placementFootprint =
                com.rustedwarfare.reforge.core.content.BuildingFootprint(-100, -1, 1, 3))),
            targets(emptyList()), targets(listOf("missing")), targets(listOf("landFactory", "landFactory")))
        for ((index, candidate) in candidates.withIndex()) {
            val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (candidate.id to candidate)))
            if (result is InitResult.Accepted) result.context.close()
            assertIs<InitResult.Rejected>(result, "Invalid construction definition $index must be rejected")
        }
    }

    @Test fun a_builder_with_insufficient_sight_cannot_enable_the_finite_construction_contract() {
        val base = ControlledScenario.load(inputRoot())
        val original = base.unitDefinitions.getValue("builder")
        val lowSight = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            fogOfWarSightRange = 1, durability = original.durability, build = original.build, builder = original.builder)
        val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (lowSight.id to lowSight)))
        if (result is InitResult.Accepted) result.context.close()
        assertEquals("unsupported-construction", assertIs<InitResult.Rejected>(result).error.code)
    }

    @Test fun an_unfinished_factory_takes_the_original_extra_damage_without_scaling_its_initial_health() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("enemy", "p2", "c_tank", Position(490.0, 290.0), 270.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "enemy" to 2L), 3L),
            economyContract = EconomyContract(mapOf("p1" to 1000L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "landFactory",
                Position(190.0, 290.0), "1".repeat(32), context.world)))
            val factory = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.typeId == "landFactory" }
            assertEquals(1200.0, factory.health)
            assertEquals(0f, factory.constructionProgress)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "worker", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p2", "enemy", factory.unitId, context.world)))
            var hit: UnitObservation? = null
            var nextStep = 1L
            for (step in 1L..500L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                nextStep = step + 1L
                if (frame.events.any { it.kind == "unit-damaged" && it.unitId == factory.unitId }) {
                    hit = frame.units.single { it.unitId == factory.unitId }
                    break
                }
            }
            assertEquals(1156.25, assertNotNull(hit).health)
            assertEquals(0f, hit.constructionProgress)
            context.submit(MoveAction("enemy-leave", "p2", "enemy", Position(550.0, 50.0), context.world))
            repeat(450) { context.advance(Step(nextStep++, 1.0, "sim-tick")) }
            val damagedHealth = context.observe().units.single { it.unitId == factory.unitId }.health
            assertIs<ActionResult.Accepted>(context.submit(ResumeConstructionAction("resume", "p1", "worker", factory.unitId, context.world)))
            var completed = false
            while (!completed && nextStep < 2500L) {
                val frame = context.advance(Step(nextStep++, 1.0, "sim-tick"))
                val project = frame.units.single { it.unitId == factory.unitId }
                assertEquals(damagedHealth, project.health)
                completed = project.constructionProgress == 1f
            }
            assertTrue(completed)
            val repairing = context.advance(Step(nextStep++, 1.0, "sim-tick"))
            assertTrue(repairing.units.single { it.unitId == factory.unitId }.health!! > damagedHealth!!)
            repeat(1000) { context.advance(Step(nextStep++, 1.0, "sim-tick")) }
            assertEquals(1200.0, context.observe().units.single { it.unitId == factory.unitId }.health)
            assertTrue(context.observe().units.single { it.unitId == "worker" }.tasks.isEmpty())
        } finally { context.close() }
    }

    @Test fun resource_pools_remain_passable_but_do_not_accept_a_factory() {
        val base = ControlledScenario.load(inputRoot())
        val image = base.map.tileSets.first().imagePath.toString().replace("&", "&amp;")
        val csv = List(600) { if (it == 16 * 30 + 9) "2" else "1" }.joinToString(",")
        val file = Files.createTempFile("p006-resource-map-", ".tmx")
        try {
            Files.writeString(file, """<map orientation="orthogonal" renderorder="right-down" width="30" height="20" tilewidth="20" tileheight="20">
              <tileset firstgid="1" name="resources" tilewidth="20" tileheight="20">
                <image source="$image" width="40" height="20"/>
                <tile id="1"><properties><property name="res_pool" value=""/></properties></tile>
              </tileset>
              <layer name="Ground" width="30" height="20"><data encoding="csv">$csv</data></layer>
            </map>""")
            val map = assertIs<ContentResult.Success<*>>(ContentLoader.loadMap(file)).value as com.rustedwarfare.reforge.core.content.MapDefinition
            assertFalse(map.blocking.isBlocked(9, 16))
            val scene = base.copy(map = map,
                units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 290.0), 90.0)),
                combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L),
                economyContract = EconomyContract(mapOf("p1" to 1000L, "p2" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("pool", "p1", "worker", "landFactory",
                    Position(190.0, 290.0), "1".repeat(32), context.world)))
                val rejected = context.advance(Step(0, 1.0, "sim-tick"))
                assertTrue(rejected.units.none { it.typeId == "landFactory" })
                assertEquals(1000L, context.observePlayer(context.world, "p1").balance)
                assertTrue(context.observePlayer(context.world, "p1").events.any { it.kind == "construction-unavailable" })
            } finally { context.close() }
        } finally { Files.delete(file) }
    }

    @Test fun stopping_leaves_the_project_and_resuming_completes_without_paying_again() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(ControlledScenario.load(inputRoot()))).context
        try {
            var step = 0L
            fun tick() { context.advance(Step(step++, 1.0, "sim-tick")) }
            fun view() = context.observePlayer(context.world, "p1")
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("worker", "p1", "h", "builder",
                "0".repeat(32), context.world)))
            repeat(601) { tick() }
            val worker = view().units.single { it.typeId == "builder" }
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", worker.unitId,
                "landFactory", Position(190.0, 290.0), "1".repeat(32), context.world)))
            repeat(150) { tick() }
            val factory = view().units.single { it.typeId == "landFactory" }
            assertTrue(factory.constructionProgress > 0f && factory.constructionProgress < 1f)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", worker.unitId, context.world)))
            repeat(100) { tick() }
            assertEquals(factory.constructionProgress, view().units.single { it.unitId == factory.unitId }.constructionProgress)
            val balance = view().credits
            assertIs<ActionResult.Accepted>(context.submit(ResumeConstructionAction("resume", "p1", worker.unitId,
                factory.unitId, context.world)))
            assertEquals(balance, view().credits)
            repeat(1200) { tick() }
            assertEquals(1f, view().units.single { it.unitId == factory.unitId }.constructionProgress)
            assertTrue(view().credits!! > balance!!)
            assertTrue(view().units.single { it.unitId == worker.unitId }.tasks.isEmpty())
        } finally { context.close() }
    }

    @Test fun unfinished_factory_accepts_both_products_and_only_produces_after_construction() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(ControlledScenario.load(inputRoot()))).context
        try {
            var step = 0L
            fun tick() { context.advance(Step(step++, 1.0, "sim-tick")) }
            fun view() = context.observePlayer(context.world, "p1")
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("worker", "p1", "h", "builder",
                "0".repeat(32), context.world)))
            repeat(601) { tick() }
            val worker = view().units.single { it.typeId == "builder" }
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", worker.unitId,
                "landFactory", Position(190.0, 290.0), "1".repeat(32), context.world)))
            repeat(300) { if (view().units.none { it.typeId == "landFactory" }) tick() }
            val factory = view().units.single { it.typeId == "landFactory" }
            repeat(100) { if (view().credits!! < 850.0) tick() }
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("second-worker", "p1", factory.unitId,
                "builder", "2".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("first-tank", "p1", factory.unitId,
                "c_tank", "3".repeat(32), context.world)))
            repeat(10) { tick() }
            assertEquals(listOf(ProductionQueueItem("builder", 0f), ProductionQueueItem("c_tank", 0f)),
                view().factoryQueues.single { it.factoryId == factory.unitId }.items)
            var completions = 0
            repeat(2600) {
                tick()
                completions += view().events.count { it.kind == "construction-completed" && it.unitId == factory.unitId }
            }
            assertEquals(1f, view().units.single { it.unitId == factory.unitId }.constructionProgress)
            assertEquals(1, completions)
            assertEquals(2, view().units.count { it.ownerPlayerId == "p1" && it.typeId == "builder" })
            assertEquals(1, view().units.count { it.ownerPlayerId == "p1" && it.typeId == "c_tank" })
            assertTrue(view().factoryQueues.single { it.factoryId == factory.unitId }.items.isEmpty())
        } finally { context.close() }
    }

    @Test fun a_produced_builder_creates_a_paid_factory_at_the_selected_site() {
        val scene = ControlledScenario.load(inputRoot())
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (simulation in listOf(context, control)) assertIs<ActionResult.Accepted>(simulation.submit(
                ProduceAction("first-builder", "p1", "h", "builder", "0".repeat(32), simulation.world)))
            var step = 0L
            repeat(601) {
                context.advance(Step(step, 1.0, "sim-tick"))
                control.advance(Step(step++, 1.0, "sim-tick"))
            }
            val worker = context.observePlayer(context.world, "p1").units.single { it.typeId == "builder" }
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("first-factory", "p1", worker.unitId,
                "landFactory", Position(190.0, 290.0), "1".repeat(32), context.world)))
            var factory: UnitObservation? = null
            repeat(300) {
                if (factory == null) {
                    context.advance(Step(step, 1.0, "sim-tick"))
                    control.advance(Step(step++, 1.0, "sim-tick"))
                    factory = context.observePlayer(context.world, "p1").units.singleOrNull { it.typeId == "landFactory" }
                }
            }
            val created = assertNotNull(factory)
            assertEquals(Position(190.0, 290.0), created.position)
            assertEquals(1200.0, created.health)
            assertEquals(0f, created.constructionProgress)
            assertEquals(control.observePlayer(control.world, "p1").credits!! - 700.0,
                context.observePlayer(context.world, "p1").credits)
        } finally { context.close(); control.close() }
    }

    @Test fun the_construction_input_loads_a_factory_menu_without_a_prebuilt_factory() {
        val scene = ControlledScenario.load(inputRoot())
        assertEquals("commandCenter", scene.units.single { it.ownerPlayerId == "p1" }.typeId)
        assertTrue(scene.units.none { it.typeId == "landFactory" || it.typeId == "builder" })
        assertEquals(1750L, scene.economyContract!!.initialBalances.getValue("p1"))
        val factory = scene.unitDefinitions.getValue("landFactory")
        assertEquals(1200.0, factory.maximumHealth)
        assertEquals("builder", factory.production!!.unitTypeId)
        assertEquals(700L, factory.build!!.price)
    }

    private fun inputRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-002/20261027-contract-01/candidate-input-01")
}
