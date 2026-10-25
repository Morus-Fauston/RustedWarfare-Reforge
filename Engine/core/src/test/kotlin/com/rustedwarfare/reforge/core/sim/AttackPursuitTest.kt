package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.*

class AttackPursuitTest {
    @Test
    fun out_of_range_manual_target_produces_a_real_route_and_movement() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(CombatTestContent.scenario())).context
        try {
            context.enablePathDiagnostics(context.world)
            context.submit(AttackAction("attack", "p1", "a", "b", context.world))
            val sample = context.advance(Step(0, 1.0, "sim-tick"))
            val attacker = sample.units.single { it.unitId == "a" }
            assertTrue(attacker.position.x > 110.0)
            assertEquals("b", attacker.attackTargetId)
            assertEquals(Position(510.0, 210.0), attacker.moveTarget)
            assertEquals(Position(510.0, 210.0), attacker.pathPoints.last())
            assertEquals("ATTACK_PURSUIT", context.drainPathDiagnostics(context.world).single().reason.name)
        } finally { context.close() }
    }

    @Test
    fun known_unreachable_target_keeps_attack_intent_and_attempts_only_one_logical_search_per_step() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(CombatTestContent.scenario("p005-sealed.tmx"))).context
        try {
            context.enablePathDiagnostics(context.world)
            context.submit(AttackAction("attack", "p1", "a", "b", context.world))
            repeat(5) { step ->
                val unit = context.advance(Step(step.toLong(), 1.0, "sim-tick")).units.single { it.unitId == "a" }
                assertEquals("b", unit.attackTargetId)
                assertNull(unit.moveTarget)
                assertEquals(Position(110.0, 210.0), unit.position)
                val samples = context.drainPathDiagnostics(context.world)
                assertEquals(1, samples.size)
                assertEquals("UNREACHABLE", samples.single().outcome.name)
                assertEquals(step.toLong(), samples.single().executionStep)
            }
        } finally { context.close() }
    }

    @Test
    fun pursuit_plans_from_owner_knowledge_even_when_an_allied_spotter_reveals_a_target_behind_unknown_wall() {
        fun scene(mapName: String): Scenario {
            val base = CombatTestContent.scenario(mapName, sight = 3,
                rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, true))
            val original = base.unitDefinitions.getValue("c_tank")
            val spotter = UnitDefinition("spotter", original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed, 1)
            return base.copy(unitDefinitions = base.unitDefinitions + ("spotter" to spotter),
                players = base.players + Player("p3", PlayerControl.NONE, "one"),
                units = base.units + UnitSpawn("spotter", "p3", "spotter", Position(530.0, 210.0), 0.0),
                combatContract = checkNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "spotter" to 10L)))
        }
        val scenes = listOf("p001-controlled.tmx", "p005-sealed.tmx").map(::scene)
        val contexts = scenes.map { assertIs<InitResult.Accepted>(SimulationContext.initialize(it)).context }
        try {
            for (context in contexts) {
                context.enablePathDiagnostics(context.world)
                assertTrue(context.observePlayer(context.world, "p1").units.any { it.unitId == "b" })
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("pursue", "p1", "a", "b", context.world)))
            }
            for (step in 0L..29L) {
                val hosts = contexts.map { it.advance(Step(step, 1.0, "sim-tick")) }
                assertEquals(hosts[0].units, hosts[1].units)
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(views[0].units, views[1].units)
                assertEquals(views[0].sampleId, views[1].sampleId)
                assertEquals(views[0].knowledge?.cells, views[1].knowledge?.cells)
                assertEquals("b", views[0].units.single { it.unitId == "a" }.attackTargetId)
                if (step == 0L) {
                    for (context in contexts) {
                        val sample = context.drainPathDiagnostics(context.world).single()
                        assertTrue(sample.usedPlayerKnowledge)
                        assertEquals(Position(510.0, 210.0), sample.searchTarget)
                    }
                }
            }
            val truth = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenes[1].copy(automaticVision = null))).context
            try {
                truth.enablePathDiagnostics(truth.world)
                truth.submit(AttackAction("pursue", "p1", "a", "b", truth.world))
                val unit = truth.advance(Step(0, 1.0, "sim-tick")).units.single { it.unitId == "a" }
                assertNull(unit.moveTarget)
                assertEquals("b", unit.attackTargetId)
                val sample = truth.drainPathDiagnostics(truth.world).single()
                assertTrue(!sample.usedPlayerKnowledge)
                assertEquals("UNREACHABLE", sample.outcome.name)
            } finally { truth.close() }
        } finally { contexts.forEach { it.close() } }
    }
}
