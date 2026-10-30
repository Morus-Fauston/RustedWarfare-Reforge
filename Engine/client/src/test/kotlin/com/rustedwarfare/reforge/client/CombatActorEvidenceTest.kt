package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class CombatActorEvidenceTest {
    private fun input(lethal: Boolean): Scenario {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/combat") } ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-pc-representative-01/input"))
        val base = ControlledScenario.load(root)
        val original = base.unitDefinitions.getValue("c_tank")
        fun definition(id: String, damage: Double) = UnitDefinition(id, original.className, original.radius,
            original.displayRadius, original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, 8, checkNotNull(original.combat).let { it.copy(projectile = it.projectile.copy(directDamage = damage)) })
        return base.copy(unitDefinitions = mapOf("c_tank" to definition("c_tank", 25.0),
            "hidden-cannon" to definition("hidden-cannon", if (lethal) 250.0 else 25.0)),
            players = base.players + Player("p3", PlayerControl.NONE, "three"),
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(350.0, 330.0), 270.0),
                UnitSpawn("b", "p2", "c_tank", Position(510.0, 330.0), 270.0),
                UnitSpawn("c", "p3", "hidden-cannon", Position(550.0, 210.0), 198.434948822922)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
    }

    @Test
    fun actual_client_evidence_serializer_preserves_complete_hidden_alive_dead_pairing_and_rejections() {
        val results = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        for (lethal in listOf(false, true)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input(lethal))).context
            val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
            val sink = ClientEvidence { kind, values -> rows += kind to values }
            fun capture() = sink.record("observation", mapOf("sample" to context.observePlayer(context.world, "p1").fields()))
            try {
                capture()
                context.submit(SelectAction("queued", "p1", "b", context.world, 40))
                context.submit(MoveAction("leave", "p1", "a", Position(110.0, 330.0), context.world))
                repeat(40) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertEquals(!lethal, context.observe().units.single { it.unitId == "b" }.alive)
                capture()
                for (action in listOf(SelectAction("select", "p1", "b", context.world),
                    StopAction("stop", "p1", "b", context.world),
                    MoveAction("move", "p1", "b", Position(350.0, 330.0), context.world),
                    AttackAction("attack", "p1", "b", "a", context.world))) {
                    val result = assertIs<ActionResult.Rejected>(context.submit(action))
                    sink.record("action", mapOf("type" to action.javaClass.simpleName,
                        "actorPlayerId" to action.actorPlayerId, "unitId" to action.unitId, "result" to result.fields()))
                    capture()
                }
                context.advance(Step(40, 1.0, "sim-tick"))
                capture()
                val view = context.observePlayer(context.world, "p1")
                assertEquals("not-visible", assertIs<ActionResult.Rejected>(view.actionResults.single { it.actionId == "queued" }).error.code)
                assertTrue(view.units.none { it.unitId == "b" || it.unitId == "c" })
                results += rows
            } finally { context.close() }
        }
        assertEquals(results[0], results[1], "Full serializer payloads and sampleIds must agree, not just HUD text")
    }
}
