package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class ProductionIdentityTest {
    @Test fun hidden_real_projectiles_take_shared_ids_before_birth_without_changing_the_player_product() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(unitDefinitions = base.unitDefinitions.mapValues { (_, u) -> BuildingTestContent.variant(u, sight = 1) },
            players = listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two"),
                Player("p3", PlayerControl.NONE, "three")),
            units = listOf(UnitSpawn("f", "p1", "landFactory", Position(110.0, 210.0), 0.0),
                UnitSpawn("b", "p2", "c_tank", Position(430.0, 210.0), 90.0),
                UnitSpawn("c", "p3", "c_tank", Position(550.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "b" to 8L, "c" to 9L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 350L, "p2" to 0L, "p3" to 0L)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val quiet = scene.copy(players = scene.players.map { if (it.playerId == "p1") it else it.copy(teamId = "allied") })
        val contexts = listOf(scene, quiet).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(it)).context }
        fun values(v: PlayerObservation): List<Any?> = listOf(v.sampleId, v.worldId, v.scenarioId, v.playerId,
            v.stepIndex, v.simulationTime, v.units, v.events, v.actionResults, v.projectiles, v.projectileEvents,
            v.balance, v.factoryQueues, v.vision?.epoch, v.vision?.basicCells, v.vision?.effectiveCells,
            v.knowledge?.revision, v.knowledge?.cells)
        try {
            contexts.forEach { c -> assertIs<ActionResult.Accepted>(c.submit(
                ProduceAction("produce", "p1", "f", "c_tank", "0".repeat(32), c.world))) }
            for (step in 0L..499L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                assertEquals(values(contexts[0].observePlayer(contexts[0].world, "p1")),
                    values(contexts[1].observePlayer(contexts[1].world, "p1")))
            }
            val cursors = contexts.map { it.observe().combat!!.nextObjectId }
            assertTrue(cursors[0] > cursors[1], "Real hidden shots must have consumed shared IDs")
            assertEquals(11L, cursors[1])
            contexts.forEach { it.advance(Step(500, 1.0, "sim-tick")) }
            contexts.forEachIndexed { index, c ->
                assertEquals(cursors[index], c.observe().production!!.units.single().objectId)
                assertEquals("produced:p1:${"0".repeat(32)}", c.observe().production!!.units.single().unitId)
            }
            assertEquals(values(contexts[0].observePlayer(contexts[0].world, "p1")),
                values(contexts[1].observePlayer(contexts[1].world, "p1")))
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun every_scheduled_actor_action_rechecks_a_real_product_that_died_before_s1() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(base.units.single { it.unitId == "f" },
                UnitSpawn("b", "p2", "c_tank", Position(430.0, 210.0), 270.0),
                UnitSpawn("c", "p2", "c_tank", Position(430.0, 230.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "b" to 8L, "c" to 9L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val nonce = "0".repeat(32)
        val id = "produced:p1:$nonce"
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", "c_tank", nonce, c.world)))
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(StopAction("hold", "p1", id, c.world)))
            assertEquals("f", c.observe().combat!!.weapons.single { it.unitId == "b" }.targetUnitId)
            for (enemy in listOf("b", "c")) assertIs<ActionResult.Accepted>(c.submit(
                AttackAction("kill-$enemy", "p2", enemy, id, c.world)))
            val actions = listOf(SelectAction("select", "p1", id, c.world, 1000),
                MoveAction("move", "p1", id, Position(310.0, 300.0), c.world, 1000),
                StopAction("stop", "p1", id, c.world, 1000), AttackAction("attack", "p1", id, "b", c.world, 1000),
                ProduceAction("produce-later", "p1", id, "c_tank", "f".repeat(32), c.world, 1000),
                CancelProductionAction("cancel", "p1", id, "c_tank", c.world, 1000))
            actions.forEach { assertIs<ActionResult.Accepted>(c.submit(it)) }
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("target", "p2", "b", id, c.world, 1000)))
            for (step in 501L..999L) c.advance(Step(step, 1.0, "sim-tick"))
            assertFalse(c.observe().units.single { it.unitId == id }.alive)
            c.advance(Step(1000, 1.0, "sim-tick"))
            val results = c.observe().actionResults.associateBy { it.actionId }
            actions.forEach { assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(results.getValue(it.actionId)).error.code) }
            assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(results.getValue("target")).error.code)
            assertEquals(1400L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test fun two_owners_can_really_complete_the_same_nonce_without_sorting_gameplay_by_public_identity() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p:2", PlayerControl.LOCAL)),
            units = listOf(UnitSpawn("a", "p:2", "landFactory", Position(110.0, 210.0), 0.0),
                UnitSpawn("z", "p1", "landFactory", Position(510.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 10L, "z" to 11L), 12L),
            economyContract = EconomyContract(mapOf("p1" to 350L, "p:2" to 350L)))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val nonce = "0".repeat(32)
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("one", "p:2", "a", "c_tank", nonce, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("two", "p1", "z", "c_tank", nonce, c.world)))
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val host = c.observe()
            assertEquals(listOf("produced:p:2:$nonce", "produced:p1:$nonce"), host.production!!.units.map { it.unitId })
            assertEquals(listOf(12L, 13L), host.production.units.map { it.objectId })
            assertEquals(14L, host.combat!!.nextObjectId)
            val player = c.observePlayer(c.world, "p1")
            assertEquals(listOf("produced:p1:$nonce", "produced:p:2:$nonce"),
                player.events.filter { it.kind == "unit-produced" }.map { it.unitId })
            assertEquals(listOf("a", "produced:p1:$nonce", "produced:p:2:$nonce", "z"), player.units.map { it.unitId })
            assertEquals("unit-unavailable", assertIs<ActionResult.Rejected>(c.submit(
                StopAction("foreign", "p1", "produced:p:2:$nonce", c.world))).error.code)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("owner", "p:2", "produced:p:2:$nonce", c.world)))
            assertEquals(0L, c.observePlayer(c.world, "p1").balance)
            assertEquals(0L, c.observePlayer(c.world, "p:2").balance)
        } finally { c.close() }
    }

    @Test fun a_visible_product_select_and_attack_target_are_rechecked_when_s1_executes_after_loss_of_sight() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(unitDefinitions = base.unitDefinitions.mapValues { (_, u) -> BuildingTestContent.variant(u, sight = 3) },
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(250.0, 210.0), 90.0),
                UnitSpawn("f", "p2", "landFactory", Position(310.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "f" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 350L)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val id = "produced:p2:${"0".repeat(32)}"
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p2", "f", "c_tank", "0".repeat(32), c.world)))
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(c.observePlayer(c.world, "p1").units.any { it.unitId == id })
            assertIs<ActionResult.Accepted>(c.submit(SelectAction("later-select", "p1", id, c.world, 900)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("later-target", "p1", "a", id, c.world, 900)))
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("leave", "p1", "a", Position(110.0, 210.0), c.world)))
            for (step in 501L..899L) c.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(c.observe().units.single { it.unitId == id }.alive)
            assertTrue(c.observePlayer(c.world, "p1").units.none { it.unitId == id })
            c.advance(Step(900, 1.0, "sim-tick"))
            val results = c.observePlayer(c.world, "p1").actionResults.associateBy { it.actionId }
            assertEquals("unit-unavailable", assertIs<ActionResult.Rejected>(results.getValue("later-select")).error.code)
            assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(results.getValue("later-target")).error.code)
            assertTrue(c.observe().units.single { it.unitId == "a" }.attackTargetId == null)
        } finally { c.close() }
    }
}
