package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class ProductionCommandEvidenceTest {
    @Test fun actual_hidden_501_birth_does_not_change_any_player_field_sink_or_actor_probe() {
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(true))).context }
        val rows = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
        val sinks = rows.map { output -> ClientEvidence { kind, values -> output += kind to values } }
        val nonce = "0".repeat(32)
        val id = "produced:p2:$nonce"
        fun sample() {
            val views = contexts.mapIndexed { index, c -> c.observePlayer(c.world, "p1").also {
                sinks[index].record("player", it.fields())
                assertTrue(it.units.none { unit -> unit.unitId == id })
                assertTrue(it.factoryQueues.isEmpty())
            } }
            assertEquals(full(views[0]), full(views[1]))
            assertEquals(rows[0].last(), rows[1].last())
            noPrivateQueueFields(rows[1].last().second)
        }
        fun probe(label: String, scheduled: Long?) {
            for (kind in listOf("select", "move", "stop", "attack", "produce", "cancel", "target")) {
                val results = contexts.map { c ->
                    val actionId = "$label-$kind"
                    val action = when (kind) {
                        "select" -> SelectAction(actionId, "p1", id, c.world, scheduled)
                        "move" -> MoveAction(actionId, "p1", id, Position(110.0, 110.0), c.world, scheduled)
                        "stop" -> StopAction(actionId, "p1", id, c.world, scheduled)
                        "attack" -> AttackAction(actionId, "p1", id, "a", c.world, scheduled)
                        "produce" -> ProduceAction(actionId, "p1", id, "c_tank", nonce, c.world, scheduled)
                        "cancel" -> CancelProductionAction(actionId, "p1", id, "c_tank", c.world, scheduled)
                        else -> AttackAction(actionId, "p1", "a", id, c.world, scheduled)
                    }
                    assertIs<ActionResult.Rejected>(c.submit(action))
                }
                assertEquals(results[0], results[1])
                assertEquals(if (kind == "target") "invalid-attack-target" else "unit-unavailable", results[1].error.code)
                sample()
            }
        }
        try {
            sample()
            val paid = contexts[1]
            assertIs<ActionResult.Accepted>(paid.submit(ProduceAction("enemy-production", "p2", "f", "c_tank", nonce, paid.world)))
            assertEquals(0L, paid.observePlayer(paid.world, "p2").balance)
            probe("unborn", null)
            for (step in 0L..500L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                sample()
            }
            assertTrue(contexts[0].observe().production!!.units.isEmpty())
            assertEquals(id, paid.observe().production!!.units.single().unitId)
            assertEquals(30f, paid.observe().production!!.units.single().birthRemaining)
            assertTrue(paid.observePlayer(paid.world, "p2").factoryQueues.single().items.isEmpty())
            assertTrue(paid.observePlayer(paid.world, "p2").units.any { it.unitId == id })
            probe("born", null)
            probe("future-submit", 531)
            for (step in 501L..530L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                sample()
            }
            assertEquals(0f, paid.observe().production!!.units.single().birthRemaining)
            probe("zero", null)
            assertEquals(rows[0], rows[1])
            assertIs<ActionResult.Accepted>(paid.submit(SelectAction("owner-select", "p2", id, paid.world)))
            assertIs<ActionResult.Accepted>(paid.submit(MoveAction("owner-move", "p2", id, Position(370.0, 300.0), paid.world)))
            assertIs<ActionResult.Accepted>(paid.submit(StopAction("owner-stop", "p2", id, paid.world)))
        } finally { contexts.forEach { it.close() } }
    }

    private fun scene(hidden: Boolean): Scenario {
        val repo = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent
        val base = ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/combat") } ?: repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-pc-representative-01/input")))
        val input = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/production") } ?: repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input"))
        fun sight(unit: UnitDefinition) = UnitDefinition(unit.id, unit.className, unit.radius, unit.displayRadius,
            unit.totalFrames, unit.imagePath, unit.imageScale, unit.movement, unit.retainedUnconsumed, 1,
            unit.combat, unit.durability, unit.building, unit.production, unit.build, unit.directHitTargetKind)
        val tank = sight(assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(input.resolve("units/tank.ini"))).value)
        val factory = sight(assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(input.resolve("units/landFactory.properties"))).value)
        return base.copy(unitDefinitions = mapOf(tank.id to tank, factory.id to factory),
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0,210.0),90.0),
                UnitSpawn("f", "p2", factory.id, Position(310.0,210.0),0.0),
                UnitSpawn("b", "p2", tank.id, Position(510.0,330.0),270.0)),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            automaticVision = if (hidden) KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false) else null,
            combatContract = CombatContract(12345,-1,mapOf("a" to 8L,"b" to 9L,"f" to 10L),11L),
            economyContract = EconomyContract(mapOf("p1" to 1750L,"p2" to 350L)))
    }

    @Test fun actual_hidden_and_visible_enemy_queue_changes_leave_the_complete_player_output_equal() {
        for (hidden in listOf(false, true)) {
            val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(hidden))).context }
            val rows = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
            val sinks = rows.map { list -> ClientEvidence { kind, values -> list += kind to values } }
            fun sample() {
                val views = contexts.mapIndexed { index, c -> c.observePlayer(c.world,"p1").also { view ->
                    sinks[index].record("player", view.fields())
                    assertTrue(view.factoryQueues.isEmpty())
                    assertEquals(!hidden, view.units.any { it.unitId == "f" })
                } }
                assertEquals(full(views[0]), full(views[1]))
                assertEquals(rows[0], rows[1])
            }
            try {
                sample()
                val paid = contexts[1]
                assertIs<ActionResult.Accepted>(paid.submit(ProduceAction("enemy-real-payment","p2","f","c_tank","0".repeat(32),paid.world)))
                assertEquals(0L, paid.observePlayer(paid.world,"p2").balance)
                assertEquals(1, paid.observePlayer(paid.world,"p2").factoryQueues.single().items.size)
                assertEquals(350L, contexts[0].observePlayer(contexts[0].world,"p2").balance)
                sample()
                contexts.forEach { it.advance(Step(0,1.0,"sim-tick")) }
                sample()
                assertIs<ActionResult.Accepted>(paid.submit(CancelProductionAction("enemy-real-cancel","p2","f","c_tank",paid.world)))
                assertEquals(350L, paid.observePlayer(paid.world,"p2").balance)
                assertTrue(paid.observePlayer(paid.world,"p2").factoryQueues.single().items.isEmpty())
                sample()
                rows.forEach { output -> output.forEach { (_, values) -> noPrivateQueueFields(values) } }
            } finally { contexts.forEach { it.close() } }
        }
    }

    private fun full(v: PlayerObservation): List<Any?> = listOf(v.sampleId,v.worldId,v.scenarioId,v.playerId,
        v.stepIndex,v.simulationTime,v.units,v.events,v.actionResults,v.projectiles,v.projectileEvents,v.balance,v.factoryQueues,
        v.vision?.let { listOf(it.worldId,it.playerId,it.epoch,it.basicCells,it.effectiveCells) },
        v.knowledge?.let { listOf(it.worldId,it.playerId,it.width,it.height,it.revision,it.cells) })

    private fun noPrivateQueueFields(value: Any?) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                assertTrue(key !in setOf("unitNonce","cost","rate","balances","queuedProduction","producerId",
                    "birthSerial","lastBirthSerial","birthRemaining","updateKey","production","objectId"))
                noPrivateQueueFields(child)
            }
            is Iterable<*> -> value.forEach(::noPrivateQueueFields)
        }
    }
}
