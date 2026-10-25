package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.*

class CombatLifecycleOrderTest {
    private fun variant(original: UnitDefinition, hp: Double = 210.0, life: Double = 60.0) =
        UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, checkNotNull(original.combat).let {
                it.copy(maxHp = hp, projectile = it.projectile.copy(life = life)) })

    @Test
    fun a_dead_manual_target_uses_an_eligible_nearby_replacement_or_ends_when_no_replacement_exists() {
        for (replacement in listOf(false, true)) {
            val base = CombatTestContent.scenario()
            val normal = base.unitDefinitions.getValue("c_tank")
            val fragile = variant(normal, hp = 25.0)
            val units = listOf(base.units.first(), base.units.last().copy(typeId = "fragile", position = Position(220.0, 210.0))) +
                if (replacement) listOf(UnitSpawn("c", "p2", "c_tank", Position(220.0, 260.0), 180.0)) else emptyList()
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
                unitDefinitions = base.unitDefinitions + ("fragile" to fragile), units = units,
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L) +
                    if (replacement) mapOf("c" to 10L) else emptyMap(), 11L)))).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("manual", "p1", "a", "b", context.world)))
                var step = 0L
                var frame = context.observe()
                while (frame.units.single { it.unitId == "b" }.alive && step < 60L)
                    frame = context.advance(Step(step++, 1.0, "sim-tick"))
                assertFalse(frame.units.single { it.unitId == "b" }.alive)
                val after = context.advance(Step(step, 1.0, "sim-tick"))
                val actor = after.units.single { it.unitId == "a" }
                assertTrue(actor.alive)
                assertEquals(if (replacement) "c" else null, actor.attackTargetId)
                assertEquals(if (replacement) 0 else 1, after.events.count { it.kind == "attack-ended" && it.unitId == "a" })
            } finally { context.close() }
        }
    }

    @Test
    fun submitted_command_order_survives_same_frame_death_fired_sources_and_later_corpse_impacts() {
        val base = CombatTestContent.scenario()
        val scene = base.copy(unitDefinitions = mapOf("c_tank" to variant(base.unitDefinitions.getValue("c_tank"), hp = 25.0)),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(135.5, 210.0), 270.0),
                UnitSpawn("c", "p1", "c_tank", Position(185.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val before = context.observe()
            val commands = listOf(StopAction("b-first", "p2", "b", context.world, 0),
                StopAction("a-later", "p1", "a", context.world, 1),
                MoveAction("a-second", "p1", "a", Position(110.0, 210.0), context.world, 0),
                StopAction("b-third", "p2", "b", context.world, 0),
                SelectAction("c-later", "p1", "c", context.world, 1),
                SelectAction("c-last", "p1", "c", context.world, 0))
            commands.forEach { assertIs<ActionResult.Accepted>(context.submit(it)) }
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(listOf("b-first", "a-second", "b-third", "c-last"), first.actionResults.map { it.actionId })
            assertTrue(first.actionResults.all { it is ActionResult.Accepted })
            assertFalse(first.units.single { it.unitId == "a" }.alive)
            assertFalse(first.units.single { it.unitId == "b" }.alive)
            assertTrue(first.units.single { it.unitId == "c" }.alive)
            assertTrue(first.projectileEvents.any { it.kind == "impact" && it.projectile.sourceUnitId == "b" && it.projectile.targetUnitId == "a" })
            assertEquals(listOf("c"), first.projectiles.map { it.sourceUnitId })
            val history = mutableListOf(first)
            for (step in 1L..7L) history += context.advance(Step(step, 1.0, "sim-tick"))
            val later = history[1]
            assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(later.actionResults.single { it.actionId == "a-later" }).error.code)
            assertIs<ActionResult.Accepted>(later.actionResults.single { it.actionId == "c-later" })
            assertEquals(1, history.sumOf { frame -> frame.events.count { it.kind == "unit-died" && it.unitId == "b" } })
            assertEquals(1, history.sumOf { frame -> frame.events.count { it.kind == "unit-damaged" && it.unitId == "b" } })
            val corpseImpact = history.flatMap { it.projectileEvents }.single {
                it.kind == "impact" && it.projectile.sourceUnitId == "c" }
            assertEquals("b", corpseImpact.projectile.targetUnitId)
            assertEquals(0.0, corpseImpact.damage)
            assertTrue(history.last().projectiles.isEmpty())
            assertEquals(0.0, history.last().units.single { it.unitId == "b" }.health)
            assertTrue(before.units.all { it.health == 25.0 && it.alive })
            assertEquals(0.0, first.units.single { it.unitId == "b" }.health)
        } finally { context.close() }
    }

    @Test
    fun life_reaching_zero_still_performs_its_final_ordinary_move_and_hit_then_removes_in_the_same_call() {
        val base = CombatTestContent.scenario()
        val scene = base.copy(unitDefinitions = mapOf("c_tank" to variant(base.unitDefinitions.getValue("c_tank"), life = 1.0)),
            units = base.units.map { if (it.unitId == "b") it.copy(position = Position(135.5, 210.0), heading = 90.0) else it })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(185.0, first.units.single { it.unitId == "b" }.health)
            assertTrue(first.projectiles.isEmpty())
            val events = first.projectileEvents.filter { it.projectile.sourceUnitId == "a" }
            assertEquals(listOf("born", "impact"), events.map { it.kind })
            assertEquals(1.0, events.first().projectile.remainingLife)
            assertEquals(0.0, events.last().projectile.remainingLife)
            assertEquals(Position(135.0, 210.0), events.last().projectile.position)
            assertEquals(25.0, events.last().damage)
            val second = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(185.0, second.units.single { it.unitId == "b" }.health)
            assertTrue(second.projectileEvents.none { it.kind == "impact" })
        } finally { context.close() }
    }
}
