package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructedDefenseTransactionTest {
    @Test fun a_later_real_enemy_shot_overflow_rolls_back_a_constructed_shields_recharge_and_all_published_state_before_public_correction() {
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context }
        var step = 0L
        var intercepted = false
        fun command(make: (SimulationContext) -> Action) = contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(make(it))) }
        fun tickBoth() {
            contexts.forEach { c ->
                val frame = c.advance(Step(step, 1.0, "sim-tick"))
                intercepted = intercepted || frame.projectileEvents.any { it.kind == "deflected" }
                c.observePlayer(c.world, "p1")
            }
            step++
        }
        fun state(c: SimulationContext): List<Any?> {
            val host = c.observe()
            return listOf(host.fields(), host.combat!!.frameCounter, host.combat!!.nextObjectId, host.combat!!.weapons,
                host.projectiles, host.projectileEvents, host.economy!!.preciseBalances, host.economy!!.queuedProduction,
                host.production!!.units, host.upgrades!!.queues, c.observePlayer(c.world, "p1").fields())
        }
        try {
            command { BuildAction("build", "p1", "worker", "laserDefence", Position(190.0, 170.0), "1".repeat(32), it.world) }
            while (contexts[0].observe().units.none { it.typeId == "laserDefence" } && step < 100L) tickBoth()
            val shield = contexts[0].observe().units.single { it.typeId == "laserDefence" }.unitId
            while (contexts[0].observe().units.single { it.unitId == shield }.constructionProgress < 1f && step < 2500L) tickBoth()
            assertEquals(1f, contexts[0].observe().units.single { it.unitId == shield }.constructionProgress)
            command { StopAction("stop-worker", "p1", "worker", it.world) }
            command { ProduceAction("enemy-tank", "p2", "enemy-factory", "c_tank", "2".repeat(32), it.world) }
            while (contexts[0].observe().production!!.units.isEmpty() && step < 4000L) tickBoth()
            val enemy = contexts[0].observe().production!!.units.single()
            assertTrue(contexts[0].observe().construction!!.buildings.single().updateKey < enemy.updateKey,
                "The constructed shield must update before the later produced shooter")
            command { MoveAction("approach", "p2", enemy.unitId, Position(300.0, 160.0), it.world) }
            while (contexts[0].observe().combat!!.nextObjectId != Long.MAX_VALUE && step < 6000L) tickBoth()
            assertEquals(Long.MAX_VALUE, contexts[0].observe().combat!!.nextObjectId)
            command { MoveAction("prepare-correction-target", "p1", "worker", Position(270.0, 250.0), it.world) }
            while (contexts[0].observe().combat!!.weapons.single { it.unitId == enemy.unitId }.cooldown > 0.0 && step < 6500L) tickBoth()
            assertTrue(intercepted, "A real enemy shot must already have consumed shield energy")
            val failed = contexts[0]
            val before = failed.observe()
            val player = failed.observePlayer(failed.world, "p1")
            val beforeState = state(failed)
            assertTrue(player.units.single { it.unitId == shield }.pointDefense!!.energy in 0f..<1f)
            assertEquals(0.0, before.combat!!.weapons.single { it.unitId == enemy.unitId }.cooldown)
            assertFailsWith<ArithmeticException> { failed.advance(Step(step, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(player, failed.observePlayer(failed.world, "p1"))
            assertEquals(beforeState, state(failed))
            assertEquals(state(contexts[1]), state(failed))
            val workerPosition = before.units.single { it.unitId == "worker" }.position
            val enemyPosition = before.units.single { it.unitId == enemy.unitId }.position
            assertTrue(kotlin.math.hypot(workerPosition.x - enemyPosition.x, workerPosition.y - enemyPosition.y) <= 130.0,
                "The public correction target must really be within the tank's attack range")
            // A normal explicit attack turns toward a different visible target within range.
            command { AttackAction("correct", "p2", enemy.unitId, "worker", it.world) }
            repeat(5) {
                tickBoth()
                assertEquals(state(contexts[1]), state(failed))
            }
            assertTrue(failed.observePlayer(failed.world, "p1").units.single { it.unitId == shield }.pointDefense!!.energy >
                player.units.single { it.unitId == shield }.pointDefense!!.energy)
            assertEquals(550.0, failed.observePlayer(failed.world, "p1").credits)
        } finally { contexts.forEach { it.close() } }
    }

    private fun scene(): Scenario {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))), players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(170.0, 230.0), 90.0),
                UnitSpawn("enemy-factory", "p2", "landFactory", Position(650.0, 160.0), 0.0)),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 1000L)),
            automaticVision = SandboxOptions(SandboxMode.MEMORY).knowledgeRules(),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "enemy-factory" to 2L), Long.MAX_VALUE - 3L))
    }
}
