package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class BuildingEvidenceTest {
    @Test
    fun safe_unborn_actor_rejections_and_full_serializer_are_independent_of_hidden_factory_presence() {
        val base = scene()
        val absent = base.copy(units = base.units.filter { it.unitId != "f" },
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L)))
        val recordings = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        for ((index, input) in listOf(base, absent).withIndex()) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
            val sink = ClientEvidence { kind, data -> rows += kind to data }
            fun capture() = sink.record("observation", mapOf("sample" to context.observePlayer(context.world, "p1").fields()))
            try {
                capture()
                val id = "produced:p2:${"0".repeat(32)}"
                val actions = listOf(SelectAction("safe-select", "p1", id, context.world),
                    MoveAction("safe-move", "p1", id, Position(110.0, 110.0), context.world),
                    StopAction("safe-stop", "p1", id, context.world),
                    AttackAction("safe-attack", "p1", id, "a", context.world),
                    ProduceAction("safe-produce", "p1", id, "c_tank", "0".repeat(32), context.world),
                    CancelProductionAction("safe-cancel", "p1", id, "c_tank", context.world))
                for (action in actions) {
                    val result = assertIs<ActionResult.Rejected>(context.submit(action))
                    assertEquals(SimulationError("unit-unavailable", "Unit is not available"), result.error)
                    sink.record("action", mapOf("type" to action.javaClass.simpleName, "unitId" to action.unitId,
                        "actorPlayerId" to action.actorPlayerId, "result" to result.fields()))
                    if (index == 1) context.observePlayer(context.world, "p2")
                    capture()
                }
                val target = assertIs<ActionResult.Rejected>(context.submit(AttackAction("safe-target", "p1", "a", id, context.world)))
                assertEquals("invalid-attack-target", target.error.code)
                sink.record("action", mapOf("result" to target.fields()))
                capture()
                context.advance(Step(0, 1.0, "sim-tick"))
                capture()
                recordings += rows
            } finally { context.close() }
        }
        assertEquals(recordings[0], recordings[1], "Complete actual evidence, including rejected results and player revisions")
    }

    private fun scene(lowFactoryHp: Boolean = false): Scenario {
        val repo = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent
        val root = repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input")
        val base = ControlledScenario.load(repo.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-pc-representative-01/input"))
        fun smallSight(unit: UnitDefinition) = UnitDefinition(unit.id, unit.className, unit.radius, unit.displayRadius,
            unit.totalFrames, unit.imagePath, unit.imageScale, unit.movement, unit.retainedUnconsumed, 1,
            unit.combat, unit.durability?.let { if (lowFactoryHp) it.copy(maxHp = 25.0) else it },
            unit.building, unit.production, unit.build, unit.directHitTargetKind)
        val tank = smallSight(assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(root.resolve("units/tank.ini"))).value)
        val factory = smallSight(assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(root.resolve("units/landFactory.properties"))).value)
        return base.copy(unitDefinitions = mapOf(tank.id to tank, factory.id to factory),
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 90.0),
                UnitSpawn("f", "p2", factory.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("b", "p2", tank.id, Position(510.0, 330.0), 270.0)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "f" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L)))
    }

    @Test
    fun complete_serializer_payloads_agree_before_contact_with_hidden_allied_factory_present_or_absent() {
        val base = scene().let { it.copy(players = it.players.map { player -> player.copy(teamId = "allied") }) }
        val absent = base.copy(units = base.units.filter { it.unitId != "f" },
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L)))
        val results = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        for ((index, input) in listOf(base, absent).withIndex()) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
            val sink = ClientEvidence { kind, data -> rows += kind to data }
            fun capture() = sink.record("observation", mapOf("sample" to context.observePlayer(context.world, "p1").fields()))
            try {
                capture()
                val result = context.submit(MoveAction("same-move", "p1", "a", Position(510.0, 210.0), context.world))
                sink.record("action", mapOf("result" to result.fields()))
                capture()
                repeat(100) { step ->
                    context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    if (index == 1) context.observePlayer(context.world, "p2")
                    capture()
                }
                assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "f" })
                results += rows
            } finally { context.close() }
        }
        assertEquals(results[0], results[1], "Compare every actual serializer field and nested array, including funds and sample IDs")
    }

    @Test
    fun hidden_enemy_factory_actual_death_and_other_player_observation_order_do_not_change_own_evidence() {
        val results = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        for (lowHp in listOf(false, true)) {
            val base = scene(lowHp)
            val input = base.copy(players = base.players + Player("p3", PlayerControl.NONE, "three"),
                units = base.units.map { if (it.unitId == "b") it.copy(ownerPlayerId = "p3", position = Position(430.0, 210.0)) else it },
                economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
            val sink = ClientEvidence { kind, data -> rows += kind to data }
            fun capture() = sink.record("observation", mapOf("sample" to context.observePlayer(context.world, "p1").fields()))
            try {
                capture()
                for (step in 0L..60L) {
                    context.advance(Step(step, 1.0, "sim-tick"))
                    if (lowHp) { context.observePlayer(context.world, "p3"); context.observePlayer(context.world, "p2") }
                    capture()
                }
                assertEquals(!lowHp, context.observe().units.single { it.unitId == "f" }.alive)
                assertEquals(lowHp, context.visionSnapshot(context.world, "p2").basicCells.isEmpty(),
                    "The only p2 vision source is the live factory; its corpse must contribute no vision")
                assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "f" || it.unitId == "b" })
                results += rows
            } finally { context.close() }
        }
        assertEquals(results[0], results[1], "Hidden death must not invalidate another owner's public projection")
    }
}
