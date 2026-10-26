package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PatrolCommandTest {
    @Test
    fun a_manual_attack_ending_on_real_death_cannot_pass_its_dead_target_to_the_appended_patrol_weapon() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(unarmedTarget())).context
        try {
            context.submit(AttackAction("manual", "p1", "a", "b", context.world))
            context.submit(PatrolAction("after", "p1", "a", Position(410.0, 210.0), true, context.world))
            var ended = false
            for (step in 0L..199L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                if (frame.events.any { it.kind == "attack-ended" && it.unitId == "a" }) {
                    assertEquals(false, frame.units.single { it.unitId == "b" }.alive)
                    val unit = frame.units.single { it.unitId == "a" }
                    assertEquals(UnitTaskKind.PATROL, unit.tasks.single().kind)
                    assertNull(unit.attackTargetId, "Patrol must validate the previous manual T on the exact head-transition frame")
                    assertTrue(frame.events.none { it.kind == "projectile-fired" && it.unitId == "a" })
                    println("P005-007 attack-ended-patrol step=$step unit=$unit events=${frame.events}")
                    ended = true; break
                }
            }
            assertTrue(ended, "The real projectile must kill the unique enemy and end the manual command")
        } finally { context.close() }
    }

    @Test
    fun birth_end_replans_an_unchanged_far_patrol_point_with_the_owners_known_obstacle() {
        for (retention in listOf(TerrainRetention.MEMORY, TerrainRetention.HARDCORE_FORGET)) {
            val base = BuildingTestContent.scenario()
            val blocked = BooleanArray(600).also { it[10 * 30 + 20] = true; it[11 * 30 + 20] = true }
            val input = base.copy(
                map = MapDefinition(30, 20, 20, 20, base.map.tileSets, base.map.groundGids, BlockingGrid(30, 20, blocked)),
                units = listOf(base.units.single { it.unitId == "f" }),
                combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L),
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            val id = "produced:p1:${"0".repeat(32)}"
            val point = Position(510.0, 215.0)
            try {
                context.submit(ProduceAction("produce", "p1", "f", "c_tank", "0".repeat(32), context.world))
                for (step in 0L..500L) context.advance(Step(step, 1.0, "sim-tick"))
                context.submit(PatrolAction("far-patrol", "p1", id, point, false, context.world))
                for (step in 501L..529L) context.advance(Step(step, 1.0, "sim-tick"))
                val before = context.observe().units.single { it.unitId == id }
                assertNull(before.attackTargetId)
                assertEquals(point, before.moveTarget)
                assertTrue(before.pathPoints.isNotEmpty())
                assertEquals(1f, checkNotNull(context.observe().production).units.single().birthRemaining)
                assertEquals(true, checkNotNull(context.observePlayer(context.world, "p1").knowledge).cell(20, 10).lastKnownBlocked)
                context.enablePathDiagnostics(context.world)
                val after = context.advance(Step(530, 1.0, "sim-tick"))
                val searches = context.drainPathDiagnostics(context.world).filter { it.unitId == id }
                assertTrue(searches.isNotEmpty(), "Birth end must replace the temporary direct route even when its target is unchanged")
                assertTrue(searches.all { it.searchTarget == point && it.usedPlayerKnowledge &&
                    it.reason == com.rustedwarfare.reforge.core.path.PathSearchReason.USER_MOVE })
                val unit = after.units.single { it.unitId == id }
                println("P005-007 unchanged-birth-point retention=$retention before=$before searches=$searches after=$unit")
                assertEquals(point, unit.moveTarget)
                assertTrue(unit.pathPoints.any { it.y != point.y }, "The known blocking tile must require a detour")
                assertEquals(listOf(point), unit.tasks.map { it.target })
                assertEquals(0f, checkNotNull(after.production).units.single().birthRemaining)
                println("P005-007 unchanged-birth-point retention=$retention before=$before searches=$searches after=$unit")
            } finally { context.close() }
        }
    }

    @Test
    fun newborn_with_a_real_target_loses_scout_vision_at_birth_end_and_recovers_its_owner_patrol_point() {
        for (retention in listOf(TerrainRetention.MEMORY, TerrainRetention.HARDCORE_FORGET)) {
            val base = BuildingTestContent.scenario()
            val tank = base.unitDefinitions.getValue("c_tank")
            val locked = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
                tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 3,
                checkNotNull(tank.combat).copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0), build = tank.build)
            val unarmed = UnitDefinition("unarmed", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
                tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 3,
                durability = DurabilityProfile(210.0, checkNotNull(tank.combat).deadImagePath))
            val input = base.copy(unitDefinitions = base.unitDefinitions.mapValues { (_, d) ->
                if (d.id == tank.id) locked else BuildingTestContent.variant(d, sight = 3) } + (unarmed.id to unarmed),
                units = listOf(base.units.single { it.unitId == "f" },
                    UnitSpawn("b", "p2", "unarmed", Position(510.0, 215.0), 0.0),
                    UnitSpawn("scout", "p1", "unarmed", Position(550.0, 215.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "b" to 9L, "scout" to 8L), 11L),
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            val id = "produced:p1:${"0".repeat(32)}"
            try {
                context.submit(ProduceAction("produce", "p1", "f", "c_tank", "0".repeat(32), context.world))
                for (step in 0L..500L) context.advance(Step(step, 1.0, "sim-tick"))
                val point = Position(310.0, 315.0)
                context.submit(PatrolAction("home", "p1", id, point, false, context.world))
                context.submit(MoveAction("scout-leave", "p1", "scout", Position(585.0, 215.0), context.world, 505))
                context.enablePathDiagnostics(context.world); context.enableMotionDiagnostics(context.world)
                var lost: Long? = null
                for (step in 501L..530L) {
                    val host = context.advance(Step(step, 1.0, "sim-tick"))
                    val owner = context.observePlayer(context.world, "p1")
                    if (owner.units.none { it.unitId == "b" }) {
                        val unit = host.units.single { it.unitId == id }
                        assertEquals("b", unit.attackTargetId); assertEquals(Position(510.0, 215.0), unit.moveTarget)
                        assertNull(owner.units.single { it.unitId == id }.moveTarget)
                        assertTrue(checkNotNull(host.production).units.single().birthRemaining <= 1f)
                        lost = step; break
                    }
                }
                val at = checkNotNull(lost) { "The scout must lose vision adjacent to birth completion" }
                context.drainPathDiagnostics(context.world); context.drainMotionDiagnostics(context.world)
                val host = context.advance(Step(at + 1, 1.0, "sim-tick"))
                val unit = host.units.single { it.unitId == id }
                assertNull(unit.attackTargetId); assertEquals(point, unit.moveTarget)
                assertEquals(0f, checkNotNull(host.production).units.single().birthRemaining)
                val searches = context.drainPathDiagnostics(context.world).filter { it.unitId == id }
                assertTrue(searches.isNotEmpty())
                assertTrue(searches.all { it.searchTarget == point && it.usedPlayerKnowledge &&
                    it.reason != com.rustedwarfare.reforge.core.path.PathSearchReason.ROUTE_REPAIR })
                println("P005-007 newborn-loss retention=$retention step=$at searches=$searches")
            } finally { context.close() }
        }
    }

    @Test
    fun real_visibility_loss_and_new_blocked_knowledge_share_a_commit_before_pursuit_consumers() {
        for (retention in listOf(TerrainRetention.MEMORY, TerrainRetention.HARDCORE_FORGET)) {
            var represented = false
            val revealY = 290.0
            val base = unarmedTarget()
            val blocked = BooleanArray(600).also { it[10 * 30 + 12] = true; it[9 * 30 + 12] = true }
            val defs = base.unitDefinitions.mapValues { (_, d) -> UnitDefinition(d.id, d.className, d.radius, d.displayRadius,
                d.totalFrames, d.imagePath, d.imageScale, d.movement, d.retainedUnconsumed, 3,
                d.combat?.copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0), d.durability) }
            val input = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, base.map.groundGids, BlockingGrid(30, 20, blocked)),
                unitDefinitions = defs, units = listOf(base.units.first().copy(heading = 0.0),
                    base.units.last().copy(position = Position(310.0, 210.0)),
                    UnitSpawn("scout", "p1", "unarmed", Position(350.0, 210.0), 90.0),
                    UnitSpawn("reveal", "p1", "unarmed", Position(250.0, revealY), 0.0),
                    UnitSpawn("witness", "p1", "c_tank", Position(110.0, 190.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "scout" to 10L, "reveal" to 11L, "witness" to 12L), 13L),
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                context.enablePathDiagnostics(context.world); context.enableMotionDiagnostics(context.world)
                val point = Position(30.0, 210.0)
                context.submit(PatrolAction("home", "p1", "a", point, false, context.world))
                context.submit(AttackAction("witness-chase", "p1", "witness", "b", context.world))
                context.submit(MoveAction("scout-away", "p1", "scout", Position(550.0, 210.0), context.world))
                context.submit(MoveAction("reveal-block", "p1", "reveal", Position(250.0, 250.0), context.world))
                var previous = context.observePlayer(context.world, "p1")
                for (step in 0L..149L) {
                    val host = context.advance(Step(step, 1.0, "sim-tick"))
                    val owner = context.observePlayer(context.world, "p1")
                    val discovered = previous.knowledge!!.cell(12, 10).lastKnownBlocked != true &&
                        owner.knowledge!!.cell(12, 10).lastKnownBlocked == true
                    val lost = previous.units.any { it.unitId == "b" } && owner.units.none { it.unitId == "b" }
                    if (discovered || lost) println("loss-knowledge reach revealY=$revealY step=$step discovered=$discovered lost=$lost")
                    if (discovered && lost) {
                        assertEquals(Position(310.0, 210.0), host.units.single { it.unitId == "a" }.moveTarget)
                        context.drainPathDiagnostics(context.world); context.drainMotionDiagnostics(context.world)
                        context.advance(Step(step + 1, 1.0, "sim-tick"))
                        val searches = context.drainPathDiagnostics(context.world)
                        assertTrue(searches.any { it.unitId == "witness" &&
                            it.reason == com.rustedwarfare.reforge.core.path.PathSearchReason.KNOWLEDGE_REPLAN },
                            "The pending knowledge consumer must really execute for the manual witness")
                        assertTrue(searches.filter { it.unitId == "a" }.all { it.searchTarget == point })
                        assertEquals(point, context.drainMotionDiagnostics(context.world).single { it.unitId == "a" }.commandTargetBefore)
                        println("P005-007 loss-and-pending-knowledge step=$step revealY=$revealY searches=$searches")
                        represented = true; break
                    }
                    previous = owner
                }
            } finally { context.close() }
            assertTrue(represented, "The fixed public scout/reveal combination must reach the same loss/discovery commit: $retention")
        }
    }

    @Test
    fun visibility_cleanup_and_return_roll_back_when_the_second_real_birth_overflows_then_public_retry_matches() {
        for (retention in listOf(TerrainRetention.MEMORY, TerrainRetention.HARDCORE_FORGET)) {
        val base = BuildingTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val unarmed = UnitDefinition("unarmed", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 3,
            durability = DurabilityProfile(210.0, checkNotNull(tank.combat).deadImagePath))
        val locked = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 3,
            checkNotNull(tank.combat).copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0), build = tank.build)
        val input = base.copy(unitDefinitions = base.unitDefinitions + (unarmed.id to unarmed) + (locked.id to locked),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 0.0),
                UnitSpawn("b", "p2", "unarmed", Position(310.0, 210.0), 0.0),
                UnitSpawn("scout", "p1", "unarmed", Position(350.0, 210.0), 90.0),
                UnitSpawn("f1", "p2", "landFactory", Position(430.0, 330.0), 0.0),
                UnitSpawn("f2", "p2", "landFactory", Position(530.0, 330.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "scout" to 10L, "f1" to 11L, "f2" to 12L), Long.MAX_VALUE - 1),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 1750L)),
            automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val contexts = listOf(0, 1).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        try {
            contexts.forEach { c ->
                c.enablePathDiagnostics(c.world); c.enableMotionDiagnostics(c.world)
                c.submit(PatrolAction("home", "p1", "a", Position(30.0, 210.0), false, c.world))
                c.submit(MoveAction("scout-leave", "p1", "scout", Position(550.0, 210.0), c.world, 475))
                c.submit(ProduceAction("first-birth", "p2", "f1", "c_tank", "1".repeat(32), c.world))
                c.submit(ProduceAction("second-birth", "p2", "f2", "c_tank", "2".repeat(32), c.world))
                for (step in 0L..499L) c.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(c.observePlayer(c.world, "p1").units.none { it.unitId == "b" })
                assertEquals("b", c.observe().units.single { it.unitId == "a" }.attackTargetId)
                c.drainPathDiagnostics(c.world); c.drainMotionDiagnostics(c.world)
            }
            val failed = contexts[0]; val control = contexts[1]
            val before = failed.observe(); val owner = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(500, 1.0, "sim-tick")) }
            assertSame(before, failed.observe()); assertSame(owner, failed.observePlayer(failed.world, "p1"))
            assertEquals(Long.MAX_VALUE - 1, checkNotNull(failed.observe().combat).nextObjectId)
            assertTrue(checkNotNull(failed.observe().production).units.isEmpty())
            assertEquals(before.economy, failed.observe().economy)
            assertTrue(failed.drainPathDiagnostics(failed.world).isEmpty()); assertTrue(failed.drainMotionDiagnostics(failed.world).isEmpty())
            contexts.forEach { c -> c.submit(CancelProductionAction("correct-second", "p2", "f2", "c_tank", c.world)) }
            for (step in 500L..509L) {
                val actual = failed.advance(Step(step, 1.0, "sim-tick")); val expected = control.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(expected.units, actual.units); assertEquals(expected.combat, actual.combat)
                assertEquals(expected.economy!!.balances, actual.economy!!.balances)
                assertEquals(expected.economy.queuedProduction, actual.economy.queuedProduction)
                assertEquals(expected.production!!.units, actual.production!!.units)
                assertEquals(expected.events, actual.events); assertEquals(expected.actionResults, actual.actionResults)
                val expectedOwner = control.observePlayer(control.world, "p1")
                val actualOwner = failed.observePlayer(failed.world, "p1")
                assertEquals(expectedOwner.units, actualOwner.units)
                assertEquals(expectedOwner.events, actualOwner.events)
                assertEquals(expectedOwner.actionResults, actualOwner.actionResults)
                assertEquals(expectedOwner.vision!!.epoch, actualOwner.vision!!.epoch)
                assertEquals(expectedOwner.vision.basicCells, actualOwner.vision.basicCells)
                assertEquals(expectedOwner.vision.effectiveCells, actualOwner.vision.effectiveCells)
                assertEquals(expectedOwner.knowledge!!.revision, actualOwner.knowledge!!.revision)
                assertEquals(expectedOwner.knowledge.cells, actualOwner.knowledge.cells)
                assertEquals(expectedOwner.projectiles, actualOwner.projectiles)
                assertEquals(expectedOwner.projectileEvents, actualOwner.projectileEvents)
                assertEquals(expectedOwner.balance, actualOwner.balance)
                assertEquals(expectedOwner.factoryQueues, actualOwner.factoryQueues)
                assertEquals(expectedOwner.upgradeQueues, actualOwner.upgradeQueues)
                val unit = actual.units.single { it.unitId == "a" }
                assertNull(unit.attackTargetId); assertEquals(Position(30.0, 210.0), unit.moveTarget)
                assertEquals(listOf("home"), unit.tasks.map { it.actionId })
            }
            assertEquals("b", before.units.single { it.unitId == "a" }.attackTargetId)
        } finally { contexts.forEach { it.close() } }
        }
    }

    @Test
    fun patrol_equal_distance_keeps_first_public_spawn_order_and_a_weapon_with_no_damage_is_not_retaliation() {
        val base = unarmedTarget()
        val tank = base.unitDefinitions.getValue("c_tank")
        val inert = UnitDefinition("inert", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, tank.fogOfWarSightRange,
            checkNotNull(tank.combat).let { it.copy(projectile = it.projectile.copy(directDamage = 0.0)) })
        for (inertFirst in listOf(false, true)) {
            val input = base.copy(unitDefinitions = base.unitDefinitions + (inert.id to inert),
                units = listOf(base.units.first(), base.units.last().copy(typeId = if (inertFirst) inert.id else "c_tank",
                    position = Position(210.0, 210.0)),
                    UnitSpawn("c", "p2", if (inertFirst) "unarmed" else "c_tank",
                        Position(if (inertFirst) 160.0 else 110.0, if (inertFirst) 210.0 else 310.0), 0.0)),
                combatContract = checkNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                context.submit(PatrolAction("home", "p1", "a", Position(110.0, 210.0), false, context.world))
                val unit = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.unitId == "a" }
                assertEquals(if (inertFirst) "c" else "b", unit.attackTargetId)
            } finally { context.close() }
        }
    }

    @Test
    fun hidden_manual_attack_identity_cannot_leak_through_the_new_owner_task_projection() {
        val input = unarmedTarget().let { base -> base.copy(
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = base.unitDefinitions.mapValues { (_, d) -> UnitDefinition(d.id, d.className, d.radius,
                d.displayRadius, d.totalFrames, d.imagePath, d.imageScale, d.movement, d.retainedUnconsumed, 3,
                d.combat?.copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0), d.durability) },
            units = base.units.map { if (it.unitId == "b") it.copy(position = Position(150.0, 210.0), heading = 90.0) else it.copy(heading = 0.0) },
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false)) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("manual", "p1", "a", "b", context.world)))
            context.submit(PatrolAction("later", "p1", "a", Position(410.0, 210.0), true, context.world))
            context.submit(MoveAction("leave", "p2", "b", Position(510.0, 210.0), context.world))
            var checked = false
            for (step in 0L..99L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                val player = context.observePlayer(context.world, "p1")
                if (player.units.none { it.unitId == "b" }) {
                    val mine = player.units.single { it.unitId == "a" }
                    assertNull(mine.attackTargetId)
                    assertNull(mine.tasks.first().targetUnitId)
                    assertEquals(UnitTaskKind.ATTACK, mine.tasks.first().kind)
                    assertEquals(Position(410.0, 210.0), mine.tasks.last().target)
                    assertEquals("b", context.observe().units.single { it.unitId == "a" }.tasks.first().targetUnitId)
                    checked = true; break
                }
            }
            assertTrue(checked, "The publicly commanded enemy move must actually lose visibility")
        } finally { context.close() }
    }

    @Test
    fun manual_attack_reselection_updates_the_shared_head_before_patrol_may_run() {
        val base = unarmedTarget()
        val input = base.copy(units = base.units + UnitSpawn("c", "p2", "c_tank", Position(220.0, 260.0), 0.0),
            combatContract = checkNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.submit(AttackAction("manual", "p1", "a", "b", context.world))
            context.submit(PatrolAction("later", "p1", "a", Position(410.0, 210.0), true, context.world))
            var reached = false
            for (step in 0L..99L) {
                val sample = context.advance(Step(step, 1.0, "sim-tick"))
                val unit = sample.units.single { it.unitId == "a" }
                if (unit.attackTargetId == "c") {
                    assertEquals(listOf(UnitTaskKind.ATTACK, UnitTaskKind.PATROL), unit.tasks.map { it.kind })
                    assertEquals("c", unit.tasks.first().targetUnitId)
                    assertTrue(sample.events.none { it.kind == "attack-ended" && it.unitId == "a" })
                    reached = true; break
                }
            }
            assertTrue(reached, "The original target must die and the in-range target must be reselected")
        } finally { context.close() }
    }

    @Test
    fun scheduled_patrol_points_apply_in_order_and_recheck_real_death_before_execution() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(alone())).context
        try {
            context.submit(PatrolAction("first", "p1", "a", Position(310.0, 210.0), false, context.world, 2))
            context.submit(PatrolAction("second", "p1", "a", Position(410.0, 210.0), true, context.world, 2))
            assertTrue(context.observe().units.single().tasks.isEmpty())
            context.advance(Step(0, 1.0, "sim-tick")); context.advance(Step(1, 1.0, "sim-tick"))
            val applied = context.advance(Step(2, 1.0, "sim-tick"))
            assertEquals(listOf("first", "second"), applied.actionResults.map { it.actionId })
            assertEquals(listOf("first", "second"), applied.units.single().tasks.map { it.actionId })
            assertEquals(listOf(2L, 2L), applied.units.single().tasks.map { it.atStep })
        } finally { context.close() }
        val input = unarmedTarget().let { it.copy(players = it.players.map { p -> p.copy(control = PlayerControl.LOCAL) }) }
        val dying = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(dying.submit(PatrolAction("after-death", "p2", "b",
                Position(410.0, 210.0), false, dying.world, 40)))
            for (step in 0L..39L) dying.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(false, dying.observe().units.single { it.unitId == "b" }.alive)
            val applied = dying.advance(Step(40, 1.0, "sim-tick"))
            assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(applied.actionResults.single { it.actionId == "after-death" }).error.code)
            assertTrue(applied.units.single { it.unitId == "b" }.tasks.isEmpty())
        } finally { dying.close() }
    }

    @Test
    fun patrol_capture_uses_the_original_float_coordinate_rounding_at_the_strict_boundary() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(alone())).context
        try {
            val point = Position(190.0 - 0.00000001, 210.0)
            context.submit(PatrolAction("float-boundary", "p1", "a", point, false, context.world))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(point, context.observe().units.single().moveTarget,
                "Float coordinates round this point to distance 80, which is not inside the strict gate")
        } finally { context.close() }
    }

    @Test
    fun failed_manual_pursuit_still_counts_as_the_previous_logical_request_when_patrol_replaces_it() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            CombatTestContent.scenario("p005-sealed.tmx"))).context
        try {
            context.submit(AttackAction("pursue", "p1", "a", "b", context.world))
            for (step in 0L..2L) context.advance(Step(step, 1.0, "sim-tick"))
            val before = context.observe().units.single { it.unitId == "a" }
            assertNull(before.moveTarget)
            assertEquals(0.0, before.speed)
            val point = Position(before.position.x + 50.0, before.position.y)
            context.submit(PatrolAction("replace", "p1", "a", point, false, context.world))
            context.advance(Step(3, 1.0, "sim-tick"))
            assertEquals(point, context.observe().units.single { it.unitId == "a" }.moveTarget,
                "A failed path must not turn a real logical request into the expanded 80 capture gate")
        } finally { context.close() }
    }

    @Test
    fun real_two_point_patrol_repeats_both_directions_and_reopen_discards_tasks_and_scheduled_old_work() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(alone())).context
        try {
            context.submit(PatrolAction("east", "p1", "a", Position(410.0, 210.0), false, context.world))
            context.submit(PatrolAction("west", "p1", "a", Position(110.0, 210.0), true, context.world))
            val visits = mutableListOf<String>()
            val positions = mutableListOf<Double>()
            for (step in 0L..1599L) {
                val unit = context.advance(Step(step, 1.0, "sim-tick")).units.single()
                val head = unit.tasks.first().actionId
                if (visits.lastOrNull() != head) visits += head
                positions += unit.position.x
            }
            assertTrue(visits.size >= 4, "Both points must repeat: $visits")
            assertEquals(listOf("east", "west", "east", "west"), visits.take(4))
            assertTrue(positions.max() > 375.0 && positions.min() < 145.0)
            val old = context.world
            context.submit(PatrolAction("queued-old", "p1", "a", Position(310.0, 310.0), true, old, 1600))
            context.close(); assertIs<InitResult.Accepted>(context.reopen(alone()))
            assertTrue(context.observe().units.single().tasks.isEmpty())
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(
                PatrolAction("stale", "p1", "a", Position(310.0, 210.0), false, old))).error.code)
            context.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(context.observe().units.single().tasks.isEmpty())
            assertNull(context.observe().units.single().attackTargetId)
        } finally { context.close() }
    }

    @Test
    fun legal_unreachable_patrol_retains_the_head_and_does_not_rotate_or_stop_its_successor() {
        val base = CombatTestContent.scenario("p005-sealed.tmx")
        val input = base.copy(units = base.units.take(1),
            combatContract = checkNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.enablePathDiagnostics(context.world)
            context.submit(PatrolAction("blocked", "p1", "a", Position(290.0, 210.0), false, context.world))
            context.submit(PatrolAction("next", "p1", "a", Position(110.0, 310.0), true, context.world))
            for (step in 0L..19L) context.advance(Step(step, 1.0, "sim-tick"))
            val unit = context.observe().units.single()
            assertEquals(listOf("blocked", "next"), unit.tasks.map { it.actionId })
            assertEquals(Position(110.0, 210.0), unit.position)
            assertNull(unit.moveTarget)
            val searches = context.drainPathDiagnostics(context.world)
            assertTrue(searches.isNotEmpty())
            assertTrue(searches.all { it.searchTarget == Position(290.0, 210.0) &&
                it.outcome == com.rustedwarfare.reforge.core.path.PathSearchOutcome.UNREACHABLE })
        } finally { context.close() }
    }

    @Test
    fun a_real_newborn_patrol_keeps_its_task_and_does_not_resume_the_old_pursuit_route_at_birth_end() {
        val base = BuildingTestContent.scenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            units = listOf(base.units.single { it.unitId == "f" }, base.units.single { it.unitId == "b" }.copy(position = Position(510.0, 215.0))),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "b" to 9L), 11L)))).context
        val id = "produced:p1:${"0".repeat(32)}"
        try {
            context.submit(ProduceAction("produce", "p1", "f", "c_tank", "0".repeat(32), context.world))
            for (step in 0L..500L) context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(30f, checkNotNull(context.observe().production).units.single().birthRemaining)
            val point = Position(310.0, 315.0)
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("newborn", "p1", id, point, false, context.world)))
            for (step in 501L..529L) context.advance(Step(step, 1.0, "sim-tick"))
            val before = context.observe().units.single { it.unitId == id }
            assertEquals("b", before.attackTargetId)
            assertEquals(Position(510.0, 215.0), before.moveTarget)
            assertEquals(1f, checkNotNull(context.observe().production).units.single().birthRemaining)
            context.enablePathDiagnostics(context.world)
            context.advance(Step(530, 1.0, "sim-tick"))
            assertEquals(0f, checkNotNull(context.observe().production).units.single().birthRemaining)
            val searches = context.drainPathDiagnostics(context.world).filter { it.unitId == id }
            assertTrue(searches.isNotEmpty(), "An unchanged visible T also requires a normal route at birth end")
            assertTrue(searches.all { it.reason == com.rustedwarfare.reforge.core.path.PathSearchReason.ATTACK_PURSUIT &&
                it.searchTarget == Position(510.0, 215.0) }, "Birth completion must use the normal patrol stage")
            assertEquals(listOf(point), context.observe().units.single { it.unitId == id }.tasks.map { it.target })
        } finally { context.close() }
    }

    @Test
    fun real_second_shot_failure_rolls_back_patrol_rotation_then_public_same_step_retry_matches_control() {
        val base = CombatTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val witness = UnitDefinition("witness", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, tank.fogOfWarSightRange,
            checkNotNull(tank.combat).copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
        val input = base.copy(unitDefinitions = base.unitDefinitions + (witness.id to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(base.units.first(), base.units.last().copy(position = Position(220.0, 210.0)),
                UnitSpawn("c", "p1", "witness", Position(220.0, 330.0), 180.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), Long.MAX_VALUE - 1))
        val contexts = listOf(0, 1).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        fun values(view: PlayerObservation): List<Any?> = listOf(view.sampleId, view.worldId, view.scenarioId,
            view.playerId, view.stepIndex, view.simulationTime, view.units, view.events, view.actionResults,
            view.projectiles, view.projectileEvents, view.balance, view.factoryQueues, view.upgradeQueues,
            view.vision?.epoch, view.vision?.basicCells, view.vision?.effectiveCells, view.knowledge?.revision, view.knowledge?.cells)
        try {
            contexts.forEach { c ->
                c.enablePathDiagnostics(c.world); c.enableMotionDiagnostics(c.world)
                c.submit(PatrolAction("first", "p1", "a", Position(110.0, 210.0), false, c.world))
                c.submit(PatrolAction("second", "p1", "a", Position(110.0, 210.0), true, c.world))
                c.submit(AttackAction("near", "p2", "b", "a", c.world))
                c.submit(SelectAction("queued", "p1", "c", c.world, 0))
            }
            val failed = contexts[0]; val control = contexts[1]
            val host = failed.observe(); val player = failed.observePlayer(failed.world, "p1")
            control.observePlayer(control.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(host, failed.observe()); assertSame(player, failed.observePlayer(failed.world, "p1"))
            assertEquals(listOf("first", "second"), host.units.single { it.unitId == "a" }.tasks.map { it.actionId })
            assertTrue(failed.drainPathDiagnostics(failed.world).isEmpty())
            assertTrue(failed.drainMotionDiagnostics(failed.world).isEmpty())
            contexts.forEach { c -> c.submit(AttackAction("side", "p2", "b", "c", c.world)) }
            // The successful retry legitimately exhausts IDs. Compare subsequent non-firing
            // updates before the side target can rotate into aim and demand another ID.
            for (step in 0L..9L) {
                val actual = failed.advance(Step(step, 1.0, "sim-tick"))
                val expected = control.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(expected.units, actual.units); assertEquals(expected.combat, actual.combat)
                assertEquals(expected.projectiles, actual.projectiles); assertEquals(expected.projectileEvents, actual.projectileEvents)
                assertEquals(expected.events, actual.events); assertEquals(expected.actionResults, actual.actionResults)
                assertEquals(values(control.observePlayer(control.world, "p1")), values(failed.observePlayer(failed.world, "p1")))
                if (step == 0L) {
                    assertEquals(listOf("second", "first"), actual.units.single { it.unitId == "a" }.tasks.map { it.actionId })
                    assertEquals(Long.MAX_VALUE, checkNotNull(actual.combat).nextObjectId)
                    assertEquals(1, actual.projectiles.size)
                }
            }
            assertEquals(listOf("first", "second"), host.units.single { it.unitId == "a" }.tasks.map { it.actionId })
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun fresh_patrol_129_gate_controls_the_logical_request_and_130_outside_does_not_fire() {
        for (distance in listOf(128.999, 129.0, 129.001, 130.0, 130.001)) {
            val input = unarmedTarget().let { it.copy(units = it.units.map { u ->
                if (u.unitId == "b") u.copy(position = Position(110.0 + distance, 210.0)) else u }) }
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                context.enableMotionDiagnostics(context.world)
                context.submit(PatrolAction("home", "p1", "a", Position(110.0, 210.0), false, context.world))
                context.advance(Step(0, 1.0, "sim-tick"))
                context.advance(Step(1, 1.0, "sim-tick"))
                val motion = context.drainMotionDiagnostics(context.world).last { it.unitId == "a" }
                assertEquals(distance >= 129.0, motion.steeringInvoked, "distance=$distance: $motion")
                assertEquals(distance >= 129.0, context.observe().units.single { it.unitId == "a" }.moveTarget != null)
                if (distance >= 130.0) assertTrue(context.observe().projectileEvents.none { it.kind == "born" })
            } finally { context.close() }
        }
    }

    @Test
    fun loss_of_scout_vision_crops_the_batch_end_projection_then_discards_old_pursuit_before_next_motion() {
        for (retention in listOf(TerrainRetention.MEMORY, TerrainRetention.HARDCORE_FORGET)) {
            val base = unarmedTarget()
            val defs = base.unitDefinitions.mapValues { (_, d) -> UnitDefinition(d.id, d.className, d.radius, d.displayRadius,
                d.totalFrames, d.imagePath, d.imageScale, d.movement, d.retainedUnconsumed, 3, d.combat, d.durability) }
            val input = base.copy(unitDefinitions = defs, units = listOf(base.units.first(),
                base.units.last().copy(position = Position(310.0, 210.0)),
                UnitSpawn("scout", "p1", "unarmed", Position(350.0, 210.0), 90.0)),
                combatContract = checkNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "scout" to 10L)),
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                context.enablePathDiagnostics(context.world)
                context.enableMotionDiagnostics(context.world)
                val point = Position(30.0, 210.0)
                context.submit(PatrolAction("home", "p1", "a", point, false, context.world))
                context.submit(MoveAction("scout-away", "p1", "scout", Position(550.0, 210.0), context.world))
                var lostAt: Long? = null
                for (step in 0L..249L) {
                    val host = context.advance(Step(step, 1.0, "sim-tick"))
                    val owner = context.observePlayer(context.world, "p1")
                    val unit = host.units.single { it.unitId == "a" }
                    if (owner.units.none { it.unitId == "b" }) {
                        assertEquals("b", unit.attackTargetId, "Loss must occur at batch end before the next cleanup")
                        assertEquals(Position(310.0, 210.0), unit.moveTarget, "The old pursuit must really exist")
                        val cropped = owner.units.single { it.unitId == "a" }
                        assertNull(cropped.attackTargetId)
                        assertNull(cropped.moveTarget)
                        assertTrue(cropped.pathPoints.isEmpty())
                        assertEquals(listOf(point), cropped.tasks.map { it.target })
                        lostAt = step
                        break
                    }
                }
                val lost = checkNotNull(lostAt) { "The public scout move must actually lose vision" }
                context.drainPathDiagnostics(context.world)
                context.drainMotionDiagnostics(context.world)
                val next = context.advance(Step(lost + 1, 1.0, "sim-tick")).units.single { it.unitId == "a" }
                assertNull(next.attackTargetId)
                assertEquals(point, next.moveTarget)
                assertTrue(context.drainPathDiagnostics(context.world).filter { it.unitId == "a" }.all { it.searchTarget == point })
                assertEquals(point, context.drainMotionDiagnostics(context.world).single { it.unitId == "a" }.commandTargetBefore)
                assertEquals(listOf(point), next.tasks.map { it.target })
            } finally { context.close() }
        }
    }

    @Test
    fun patrol_chases_only_an_old_target_really_damages_it_then_returns_to_the_stored_point() {
        val input = unarmedTarget().let { it.copy(units = it.units.map { u ->
            if (u.unitId == "b") u.copy(position = Position(310.0, 210.0)) else u }) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val point = Position(30.0, 210.0)
            context.submit(PatrolAction("home", "p1", "a", point, false, context.world))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.unitId == "a" }
            assertEquals("b", first.attackTargetId)
            assertEquals(point, first.moveTarget)
            val second = context.advance(Step(1, 1.0, "sim-tick")).units.single { it.unitId == "a" }
            assertEquals(Position(310.0, 210.0), second.moveTarget)
            val history = (2L..699L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(history.any { it.projectileEvents.any { event -> event.kind == "impact" && event.damage == 25.0 } })
            assertEquals(false, context.observe().units.single { it.unitId == "b" }.alive)
            val returned = context.observe().units.single { it.unitId == "a" }
            assertEquals(listOf(UnitTask(UnitTaskKind.PATROL, "home", point)), returned.tasks)
            assertNull(returned.attackTargetId)
            assertNull(returned.moveTarget)
            assertEquals(0.0, returned.speed)
            assertTrue(history.any { frame -> frame.units.single { it.unitId == "a" }.moveTarget == point })
            assertTrue(history.maxOf { frame -> frame.units.single { it.unitId == "a" }.position.x } > 170.0)
            assertTrue(returned.position.x < 110.0, "The real pursuit must return within the approved capture gate: ${returned.position}")
            assertTrue(history.none { it.events.any { event -> event.kind == "movement-arrived" && event.unitId == "a" } })
        } finally { context.close() }
    }

    @Test
    fun float_144_192_target_is_acquired_then_fails_strict_retention_without_immediate_reacquisition() {
        val input = unarmedTarget().let { it.copy(units = it.units.map { u ->
            if (u.unitId == "b") u.copy(position = Position(254.0, 18.0)) else u }) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.submit(PatrolAction("station", "p1", "a", Position(110.0, 210.0), false, context.world))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.unitId == "a" }
            assertEquals("b", first.attackTargetId)
            assertNull(first.moveTarget)
            val second = context.advance(Step(1, 1.0, "sim-tick")).units.single { it.unitId == "a" }
            assertNull(second.attackTargetId, "240 squared is outside strict retention, and U is still positive")
            assertNull(second.moveTarget)
            for (step in 2L..19L) {
                assertNull(context.advance(Step(step, 1.0, "sim-tick")).units.single { it.unitId == "a" }.attackTargetId)
            }
            assertEquals("b", context.advance(Step(20, 1.0, "sim-tick")).units.single { it.unitId == "a" }.attackTargetId)
            assertEquals("b", first.attackTargetId, "Old observations cannot change when retention clears T")
        } finally { context.close() }
    }

    @Test
    fun patrol_acquisition_uses_the_two_pass_armed_100_unarmed_50_and_armed_200_unarmed_50_rules() {
        for ((armedDistance, expected) in listOf(100.0 to "b", 200.0 to "c")) {
            val base = unarmedTarget()
            val input = base.copy(units = listOf(base.units.first(),
                base.units.last().copy(typeId = "c_tank", position = Position(110.0 + armedDistance, 210.0)),
                UnitSpawn("c", "p2", "unarmed", Position(160.0, 210.0), 270.0)),
                combatContract = checkNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                context.submit(PatrolAction("point", "p1", "a", Position(410.0, 210.0), false, context.world))
                context.advance(Step(0, 1.0, "sim-tick"))
                val unit = context.observePlayer(context.world, "p1").units.single { it.unitId == "a" }
                assertEquals(expected, unit.attackTargetId, "armed distance=$armedDistance")
                assertEquals(Position(410.0, 210.0), unit.moveTarget, "A newly acquired target must not chase in the same update")
                assertEquals(listOf("point"), unit.tasks.map { it.actionId })
            } finally { context.close() }
        }
    }

    @Test
    fun capture_uses_strict_30_70_80_boundaries_and_retains_the_previous_real_move_request() {
        data class Case(val count: Int, val warmSteps: Int, val distance: Double, val captured: Boolean)
        val cases = listOf(
            Case(1, 0, 79.999, true), Case(1, 0, 80.0, false), Case(1, 0, 80.001, false),
            Case(2, 0, 69.999, true), Case(2, 0, 70.0, false), Case(2, 0, 70.001, false),
            Case(1, 1, 50.0, true), // Initial ac=0 is recent at bu=1, despite a real old request.
            Case(1, 3, 29.999, true), Case(1, 3, 30.0, false), Case(1, 3, 30.001, false),
            Case(1, 3, 50.0, false), Case(2, 3, 29.999, true), Case(2, 3, 30.0, false),
            Case(2, 3, 50.0, false))
        for (case in cases) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(alone())).context
            try {
                if (case.warmSteps > 0) {
                    context.submit(MoveAction("warm", "p1", "a", Position(510.0, 210.0), context.world))
                    for (step in 0 until case.warmSteps) context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                }
                val start = context.observe().units.single().position
                val point = Position(start.x + case.distance, start.y)
                context.submit(PatrolAction("first", "p1", "a", point, false, context.world))
                if (case.count == 2) context.submit(PatrolAction("second", "p1", "a", Position(510.0, 410.0), true, context.world))
                context.advance(Step(case.warmSteps.toLong(), 1.0, "sim-tick"))
                val unit = context.observe().units.single()
                if (case.count == 1) assertEquals(case.captured, unit.moveTarget == null, "$case: $unit")
                else assertEquals(if (case.captured) listOf("second", "first") else listOf("first", "second"),
                    unit.tasks.map { it.actionId }, "$case")
            } finally { context.close() }
        }
    }

    @Test
    fun real_death_clears_the_whole_shared_queue_and_old_snapshots_remain_unchanged() {
        val input = unarmedTarget().let { it.copy(players = it.players.map { p -> p.copy(control = PlayerControl.LOCAL) }) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.submit(PatrolAction("dying-first", "p2", "b", Position(220.0, 210.0), false, context.world))
            context.submit(PatrolAction("dying-next", "p2", "b", Position(410.0, 210.0), true, context.world))
            val before = context.observe().units.single { it.unitId == "b" }
            for (step in 0L..99L) context.advance(Step(step, 1.0, "sim-tick"))
            val dead = context.observe().units.single { it.unitId == "b" }
            assertEquals(false, dead.alive)
            assertTrue(dead.tasks.isEmpty())
            assertNull(dead.moveTarget)
            assertTrue(dead.pathPoints.isEmpty())
            assertEquals(listOf("dying-first", "dying-next"), before.tasks.map { it.actionId })
        } finally { context.close() }
    }

    @Test
    fun immobile_patrol_checks_ownership_then_rejects_immediate_and_scheduled_points_without_mutation() {
        val base = unarmedTarget()
        val target = base.unitDefinitions.getValue("unarmed")
        val stationary = UnitDefinition(target.id, target.className, target.radius, target.displayRadius,
            target.totalFrames, target.imagePath, target.imageScale,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = target.durability)
        val input = base.copy(unitDefinitions = base.unitDefinitions + (stationary.id to stationary),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val before = context.observe().units.single { it.unitId == "b" }
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                PatrolAction("enemy", "p1", "b", Position(300.0, 210.0), false, context.world))).error.code)
            for ((i, at) in listOf(null, 5L).withIndex()) {
                assertEquals("immobile-unit", assertIs<ActionResult.Rejected>(context.submit(
                    PatrolAction("own-$i", "p2", "b", Position(300.0, 210.0), false, context.world, at))).error.code)
                assertEquals(before, context.observe().units.single { it.unitId == "b" })
            }
        } finally { context.close() }
    }

    private fun alone(): Scenario = CombatTestContent.scenario().let { it.copy(units = it.units.take(1),
        combatContract = checkNotNull(it.combatContract).copy(unitObjectIds = mapOf("a" to 8L))) }

    private fun unarmedTarget(): Scenario {
        val base = CombatTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val target = UnitDefinition("unarmed", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed,
            durability = DurabilityProfile(25.0, checkNotNull(tank.combat).deadImagePath))
        return base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            units = base.units.map { if (it.unitId == "b") it.copy(typeId = target.id, position = Position(220.0, 210.0)) else it })
    }

    @Test
    fun appended_patrol_does_not_replace_attack_and_runs_only_after_its_legal_end() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(unarmedTarget())).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "a", "b", context.world)))
            context.submit(PatrolAction("after-attack", "p1", "a", Position(410.0, 210.0), true, context.world))
            val initial = context.observe().units.single { it.unitId == "a" }
            assertEquals(listOf(UnitTaskKind.ATTACK, UnitTaskKind.PATROL), initial.tasks.map { it.kind })
            assertEquals("b", initial.attackTargetId)
            val history = (0L..599L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            val units = context.observe().units
            assertEquals(false, units.single { it.unitId == "b" }.alive)
            val unit = units.single { it.unitId == "a" }
            assertEquals(listOf("after-attack"), unit.tasks.map { it.actionId })
            assertTrue(unit.position.x > 370.0, "Patrol must execute after the target dies: ${unit.position}")
            assertEquals(1, history.sumOf { frame -> frame.events.count { it.kind == "attack-ended" && it.unitId == "a" } })
        } finally { context.close() }
    }

    @Test
    fun stop_removes_patrol_and_its_route_but_preserves_existing_inertia() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(alone())).context
        try {
            context.submit(PatrolAction("patrol", "p1", "a", Position(410.0, 210.0), false, context.world))
            for (step in 0L..29L) context.advance(Step(step, 1.0, "sim-tick"))
            val moving = context.observe().units.single()
            assertTrue(moving.speed > 0.0)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "a", context.world)))
            val stopped = context.observe().units.single()
            assertEquals(emptyList(), stopped.tasks)
            assertNull(stopped.moveTarget)
            assertTrue(stopped.pathPoints.isEmpty())
            assertEquals(moving.speed, stopped.speed)
            for (step in 30L..229L) context.advance(Step(step, 1.0, "sim-tick"))
            val later = context.observe().units.single()
            assertEquals(0.0, later.speed)
            assertNull(later.moveTarget)
            assertTrue(later.position.x < moving.position.x + 30.0)
        } finally { context.close() }
    }

    @Test
    fun appended_patrol_waits_for_a_move_to_finish_then_really_executes() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(alone())).context
        try {
            context.submit(MoveAction("move-first", "p1", "a", Position(210.0, 210.0), context.world))
            context.submit(PatrolAction("patrol-next", "p1", "a", Position(410.0, 210.0), true, context.world))
            assertEquals(Position(210.0, 210.0), context.observe().units.single().moveTarget)
            val history = (0L..599L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            val unit = context.observePlayer(context.world, "p1").units.single()
            assertEquals(listOf("patrol-next"), unit.tasks.map { it.actionId })
            assertTrue(unit.position.x > 370.0, "The appended patrol must run after Move: ${unit.position}")
            assertEquals(1, history.sumOf { frame -> frame.events.count { it.kind == "movement-arrived" } })
        } finally { context.close() }
    }

    @Test
    fun short_repeated_patrol_points_rotate_once_and_a_full_queue_loses_only_the_first_head() {
        for (count in listOf(2, 28, 29)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(alone())).context
            try {
                for (i in 1..count) context.submit(PatrolAction("point-$i", "p1", "a",
                    Position(110.0, 210.0), true, context.world))
                context.advance(Step(0, 1.0, "sim-tick"))
                val afterFirst = (2..count).map { "point-$it" } + if (count == 29) emptyList() else listOf("point-1")
                assertEquals(afterFirst, context.observePlayer(context.world, "p1").units.single().tasks.map { it.actionId })
                context.advance(Step(1, 1.0, "sim-tick"))
                assertEquals(afterFirst.drop(1) + afterFirst.first(),
                    context.observePlayer(context.world, "p1").units.single().tasks.map { it.actionId })
            } finally { context.close() }
        }
    }

    @Test
    fun a_single_patrol_task_really_moves_then_stations_without_consuming_the_task() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(alone())).context
        try {
            context.submit(PatrolAction("station", "p1", "a", Position(310.0, 210.0), false, context.world))
            val history = (0L..349L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            val unit = context.observePlayer(context.world, "p1").units.single()
            assertTrue(unit.position.x in 280.0..311.0, "Patrol must advance to its actual stored point: ${unit.position}")
            assertEquals(listOf("station"), unit.tasks.map { it.actionId })
            assertEquals(0.0, unit.speed)
            assertNull(unit.moveTarget)
            assertTrue(history.none { frame -> frame.events.any { it.kind == "movement-arrived" } })
        } finally { context.close() }
    }

    @Test
    fun nonfinite_patrol_replacement_is_rejected_without_erasing_an_accepted_move() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(CombatTestContent.scenario())).context
        try {
            context.submit(MoveAction("move", "p1", "a", Position(310.0, 210.0), context.world))
            val before = context.observePlayer(context.world, "p1").units.single { it.unitId == "a" }
            for ((i, bad) in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).withIndex()) {
                assertEquals("invalid-target", assertIs<ActionResult.Rejected>(context.submit(
                    PatrolAction("bad-$i", "p1", "a", Position(bad, 210.0), false, context.world))).error.code)
                assertEquals(before, context.observePlayer(context.world, "p1").units.single { it.unitId == "a" })
            }
        } finally { context.close() }
    }

    @Test
    fun move_and_patrol_share_the_29_effective_task_capacity() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(CombatTestContent.scenario())).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "a", Position(310.0, 210.0), context.world)))
            for (i in 1..30) assertIs<ActionResult.Accepted>(context.submit(
                PatrolAction("point-$i", "p1", "a", Position(310.0 + i, 210.0), true, context.world)))
            val unit = context.observePlayer(context.world, "p1").units.single { it.unitId == "a" }
            assertEquals(listOf("move") + (1..28).map { "point-$it" }, unit.tasks.map { it.actionId })
            assertEquals(UnitTaskKind.MOVE, unit.tasks.first().kind)
            assertEquals(Position(310.0, 210.0), unit.moveTarget)
        } finally { context.close() }
    }

    @Test
    fun other_players_cannot_observe_owner_tasks_even_without_fog() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(CombatTestContent.scenario())).context
        try {
            context.submit(PatrolAction("private", "p1", "a", Position(310.0, 210.0), false, context.world))
            assertEquals(emptyList(), context.observePlayer(context.world, "p2").units.single { it.unitId == "a" }.tasks)
        } finally { context.close() }
    }

    @Test
    fun appended_points_preserve_order_and_the_old_owner_snapshot() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(CombatTestContent.scenario())).context
        try {
            context.submit(PatrolAction("first", "p1", "a", Position(310.0, 210.0), false, context.world))
            val before = context.observePlayer(context.world, "p1").units.single { it.unitId == "a" }
            assertIs<ActionResult.Accepted>(context.submit(
                PatrolAction("second", "p1", "a", Position(410.0, 210.0), true, context.world)))
            assertEquals(listOf("first", "second"),
                context.observePlayer(context.world, "p1").units.single { it.unitId == "a" }.tasks.map { it.actionId })
            assertEquals(listOf("first"), before.tasks.map { it.actionId })
        } finally { context.close() }
    }

    @Test
    fun a_public_patrol_point_is_accepted_as_an_owner_task() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(CombatTestContent.scenario())).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(
                PatrolAction("patrol-one", "p1", "a", Position(310.0, 210.0), false, context.world)))
            assertEquals(listOf(UnitTask(UnitTaskKind.PATROL, "patrol-one", Position(310.0, 210.0))),
                context.observePlayer(context.world, "p1").units.single { it.unitId == "a" }.tasks)
        } finally { context.close() }
    }
}
