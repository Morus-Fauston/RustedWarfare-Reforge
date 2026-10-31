package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.test.*

class CombatTargetSelectionTest {
    private fun selected(frame: Observation) = checkNotNull(frame.combat).weapons.single { it.unitId == "a" }.targetUnitId

    @Test
    fun automatic_priority_and_equal_distance_keep_gameplay_order_instead_of_object_id_order() {
        val base = CombatTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val unarmed = UnitDefinition("unarmed", tank.className, tank.radius, tank.displayRadius,
            tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed,
            tank.fogOfWarSightRange, durability = DurabilityProfile(210.0, checkNotNull(tank.combat).deadImagePath))
        // Initialization sorts the initial IDs. Object IDs and the supplied list deliberately disagree.
        val scene = base.copy(unitDefinitions = base.unitDefinitions + ("unarmed" to unarmed), units = listOf(
            UnitSpawn("d", "p2", "c_tank", Position(210.0, 210.0), 270.0),
            UnitSpawn("c", "p2", "unarmed", Position(140.0, 210.0), 270.0),
            UnitSpawn("b", "p2", "c_tank", Position(210.0, 210.0), 270.0), base.units.first()),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 100L, "c" to 9L, "d" to 10L), 101L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try { assertEquals("b", selected(c.advance(Step(0, 1.0, "sim-tick")))) }
        finally { c.close() }
    }

    @Test
    fun ordinary_automatic_range_is_strict_in_binary64() {
        val base = CombatTestContent.scenario()
        val edge = base.units.first().position.x + checkNotNull(base.unitDefinitions.getValue("c_tank").combat).attackRange
        for ((x, expected) in listOf(Math.nextDown(edge) to "b", edge to null, Math.nextUp(edge) to null)) {
            val scene = base.copy(units = base.units.map { if (it.unitId == "b") it.copy(position = Position(x, 210.0)) else it })
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try { assertEquals(expected, selected(c.advance(Step(0, 1.0, "sim-tick"))), "targetX=$x") }
            finally { c.close() }
        }
    }

    @Test
    fun a_null_actor_team_does_not_inherit_another_players_team_membership() {
        val base = CombatTestContent.scenario()
        for ((team, expected) in listOf("one" to "c", null to "b")) {
            val scene = base.copy(players = listOf(Player("p1", PlayerControl.LOCAL, team),
                Player("p2", PlayerControl.NONE, "one"), Player("p3", PlayerControl.NONE, "three")),
                units = listOf(base.units.first(), base.units.last().copy(position = Position(160.0, 210.0)),
                    UnitSpawn("c", "p3", "c_tank", Position(190.0, 210.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try { assertEquals(expected, selected(c.advance(Step(0, 1.0, "sim-tick"))), "actorTeam=$team") }
            finally { c.close() }
        }
    }

    @Test
    fun patrol_visibility_filter_is_not_silently_added_to_ordinary_automatic_selection() {
        val base = CombatTestContent.scenario(sight = 1, rules = KnowledgeRules(
            TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val scene = base.copy(units = base.units.map { if (it.unitId == "b") it.copy(position = Position(210.0, 210.0)) else it })
        for (patrol in listOf(false, true)) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertTrue(c.observePlayer(c.world, "p1").units.none { it.unitId == "b" })
                if (patrol) assertIs<ActionResult.Accepted>(c.submit(
                    PatrolAction("patrol", "p1", "a", Position(410.0, 210.0), false, c.world)))
                assertEquals(if (patrol) null else "b", selected(c.advance(Step(0, 1.0, "sim-tick"))))
            } finally { c.close() }
        }
    }
}
