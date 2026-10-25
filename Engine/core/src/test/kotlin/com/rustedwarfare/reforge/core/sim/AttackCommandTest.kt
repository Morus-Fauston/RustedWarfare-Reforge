package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttackCommandTest {
    private fun scenario(mapName: String = "p001-controlled.tmx"): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-input-01/input",
        )
        val tank = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(root.resolve("units/tank.ini"))).value
        val map = assertIs<ContentResult.Success<*>>(ContentLoader.loadMap(root.resolve("maps/$mapName"))).value
            as com.rustedwarfare.reforge.core.content.MapDefinition
        return Scenario("p005-attack-command", map, mapOf("c_tank" to tank),
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(350.0, 210.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 11L))
    }

    @Test
    fun visible_enemy_attack_keeps_object_intent_and_replaces_the_old_move() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario())).context
        try {
            context.submit(MoveAction("move", "p1", "a", Position(210.0, 210.0), context.world))
            val before = context.observe()
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "a", "b", context.world)))
            val unit = context.observe().units.single { it.unitId == "a" }
            assertEquals("b", unit.attackTargetId)
            assertNull(unit.moveTarget)
            assertEquals(210.0, unit.health)
            assertNull(before.units.single { it.unitId == "a" }.attackTargetId)
        } finally {
            context.close()
        }
    }

    @Test
    fun stop_ends_the_manual_attack_intent_without_advancing_the_world() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario())).context
        try {
            context.submit(AttackAction("attack", "p1", "a", "b", context.world))
            val result = assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "a", context.world)))
            assertEquals(listOf("movement-cancelled"), result.events.map { it.kind })
            val observation = context.observe()
            assertNull(observation.units.single { it.unitId == "a" }.attackTargetId)
            assertNull(observation.units.single { it.unitId == "a" }.moveTarget)
            assertEquals(0.0, observation.simulationTime)
        } finally {
            context.close()
        }
    }

    @Test
    fun rejected_move_preserves_attack_but_accepted_unreachable_move_replaces_it() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario("p005-sealed.tmx"))).context
        try {
            context.submit(AttackAction("attack", "p1", "a", "b", context.world))
            val rejected = assertIs<ActionResult.Rejected>(context.submit(
                MoveAction("bad-move", "p1", "a", Position(Double.NaN, 210.0), context.world)))
            assertEquals("invalid-target", rejected.error.code)
            assertEquals("b", context.observe().units.single { it.unitId == "a" }.attackTargetId)
            val accepted = assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("unreachable", "p1", "a", Position(290.0, 210.0), context.world)))
            assertEquals(listOf("movement-cancelled", "path-unreachable"), accepted.events.map { it.kind })
            assertNull(context.observe().units.single { it.unitId == "a" }.attackTargetId)
            assertNull(context.observe().units.single { it.unitId == "a" }.moveTarget)
        } finally {
            context.close()
        }
    }

    private fun fogScenario(): Scenario {
        val input = scenario()
        val original = input.unitDefinitions.getValue("c_tank")
        val shortSight = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, fogOfWarSightRange = 3, combat = original.combat)
        return input.copy(unitDefinitions = mapOf("c_tank" to shortSight),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
    }

    @Test
    fun hidden_unknown_and_friendly_targets_have_the_same_safe_error_and_preserve_old_intent() {
        val input = fogScenario().let { it.copy(units = it.units + UnitSpawn("c", "p2", "c_tank", Position(150.0, 210.0), 270.0),
            combatContract = checkNotNull(it.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L))) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("legal", "p1", "a", "c", context.world)))
            val errors = listOf("b", "unknown", "a").mapIndexed { index, target ->
                assertIs<ActionResult.Rejected>(context.submit(AttackAction("bad-$index", "p1", "a", target, context.world))).error
            }
            assertEquals(1, errors.distinct().size)
            assertEquals(SimulationError("invalid-attack-target", "Attack target is not available"), errors.first())
            assertEquals("c", context.observe().units.single { it.unitId == "a" }.attackTargetId)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                AttackAction("wrong-owner", "p1", "c", "a", context.world))).error.code)
            assertEquals("actor-not-controlled", assertIs<ActionResult.Rejected>(context.submit(
                AttackAction("not-controlled", "p2", "c", "a", context.world))).error.code)
            assertEquals("duplicate-action", assertIs<ActionResult.Rejected>(context.submit(
                AttackAction("legal", "p1", "a", "c", context.world))).error.code)
            assertEquals(listOf("not-controlled"), context.observePlayer(context.world, "p2").actionResults.map { it.actionId })
        } finally { context.close() }
        val allied = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario().let { input ->
            input.copy(players = input.players.map { it.copy(teamId = "one") })
        })).context
        try {
            assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(allied.submit(
                AttackAction("allied", "p1", "a", "b", allied.world))).error.code)
        } finally { allied.close() }
    }

    @Test
    fun existing_attack_survives_loss_of_vision_while_a_new_hidden_target_request_is_rejected() {
        val input = fogScenario().let { it.copy(
            players = it.players.map { player -> player.copy(control = PlayerControl.LOCAL) },
            units = it.units.map { unit -> if (unit.unitId == "b") unit.copy(position = Position(150.0, 210.0), heading = 90.0) else unit }) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.submit(AttackAction("legal", "p1", "a", "b", context.world))
            context.submit(MoveAction("enemy-moving", "p2", "b", Position(350.0, 210.0), context.world))
            repeat(50) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals("b", context.observe().units.single { it.unitId == "a" }.attackTargetId)
            val player = context.observePlayer(context.world, "p1")
            assertTrue(player.units.none { it.unitId == "b" })
            val mine = player.units.single { it.unitId == "a" }
            assertNull(mine.attackTargetId)
            assertNull(mine.moveTarget)
            assertTrue(mine.pathPoints.isEmpty())
            assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(context.submit(
                AttackAction("new-hidden", "p1", "a", "b", context.world))).error.code)
            assertEquals("b", context.observe().units.single { it.unitId == "a" }.attackTargetId)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("enemy-returning", "p2", "b", Position(150.0, 210.0), context.world)))
            var seenAgain = false
            for (step in 50L..229L) {
                val next = context.advance(Step(step, 1.0, "sim-tick"))
                assertEquals("b", next.units.single { it.unitId == "a" }.attackTargetId)
                val returned = context.observePlayer(context.world, "p1")
                if (returned.units.any { it.unitId == "b" }) {
                    assertEquals("b", returned.units.single { it.unitId == "a" }.attackTargetId)
                    seenAgain = true
                    break
                }
            }
            assertTrue(seenAgain, "The same retained attack must regain its visible target when the enemy returns")
            assertTrue(player.units.none { it.unitId == "b" }, "Old hidden snapshots remain immutable")
        } finally { context.close() }
    }

    @Test
    fun queued_attack_rechecks_visibility_at_execution_and_does_not_stop_other_valid_commands() {
        val input = fogScenario().let { it.copy(units = it.units.map { unit ->
            if (unit.unitId == "b") unit.copy(position = Position(150.0, 210.0)) else unit }) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.submit(AttackAction("scheduled-attack", "p1", "a", "b", context.world, atStep = 150))
            context.submit(StopAction("scheduled-stop", "p1", "a", context.world, atStep = 150))
            context.submit(MoveAction("leave", "p1", "a", Position(30.0, 210.0), context.world))
            repeat(150) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "b" })
            val sample = context.advance(Step(150, 1.0, "sim-tick"))
            assertEquals(listOf("scheduled-attack", "scheduled-stop"), sample.actionResults.map { it.actionId })
            assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(sample.actionResults[0]).error.code)
            assertIs<ActionResult.Accepted>(sample.actionResults[1])
            assertNull(sample.units.single { it.unitId == "a" }.attackTargetId)
        } finally { context.close() }
    }

    @Test
    fun stale_world_rejection_is_recorded_without_claiming_the_new_world_action_id() {
        val input = scenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val old = context.world
        context.submit(AttackAction("same-id", "p1", "a", "b", old))
        context.close()
        assertIs<InitResult.Accepted>(context.reopen(input))
        try {
            val before = context.observe().units
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(
                AttackAction("same-id", "p1", "a", "b", old))).error.code)
            assertEquals(before, context.observe().units)
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.observe().actionResults.single()).error.code)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("same-id", "p1", "a", "b", context.world)))
            assertEquals("b", context.observe().units.single { it.unitId == "a" }.attackTargetId)
        } finally { context.close() }
    }
}
