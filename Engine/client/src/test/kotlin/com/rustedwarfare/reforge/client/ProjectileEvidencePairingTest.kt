package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class ProjectileEvidencePairingTest {
    private fun scene(kind: String, swapped: Boolean): Scenario {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/combat") } ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-pc-representative-01/input"))
        val base = ControlledScenario.load(root)
        val original = base.unitDefinitions.getValue("c_tank")
        fun variant(id: String, speed: Double = 5.0, life: Double = 60.0) = UnitDefinition(id, original.className,
            original.radius, original.displayRadius, original.totalFrames, original.imagePath, original.imageScale,
            original.movement, original.retainedUnconsumed, 2, checkNotNull(original.combat).let {
                it.copy(projectile = it.projectile.copy(speed = speed, life = life)) })
        val near = if (swapped) "y-hidden" else "x-hidden"
        val far = if (swapped) "x-hidden" else "y-hidden"
        val common = base.copy(unitDefinitions = mapOf("c_tank" to variant("c_tank")),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        if (kind == "rows") return common.copy(units = listOf(
            UnitSpawn("a", "p1", "c_tank", Position(220.0, 110.0), 0.0),
            UnitSpawn("c", "p1", "c_tank", Position(220.0, 310.0), 0.0),
            UnitSpawn(near, "p2", "c_tank", Position(110.0, 110.0), 90.0),
            UnitSpawn(far, "p2", "c_tank", Position(110.0, 310.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 10L, "c" to 11L, near to 8L, far to 9L), 12L))
        val eventOnly = kind == "event-only"
        val definitions = common.unitDefinitions + mapOf("near" to variant("near", if (eventOnly) 60.0 else 5.0, if (eventOnly) 1.0 else 60.0),
            "far" to variant("far", if (eventOnly) 70.0 else 6.0, if (eventOnly) 1.0 else 60.0))
        return common.copy(unitDefinitions = definitions, units = listOf(
            UnitSpawn("observer", "p1", "c_tank", Position(220.0, 210.0), if (eventOnly) 0.0 else 270.0),
            UnitSpawn(near, "p2", "near", Position(110.0, 210.0), 90.0),
            UnitSpawn(far, "p2", "far", Position(100.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("observer" to 10L, near to 8L, far to 9L), 11L))
    }

    private fun checkKeys(value: Any?) {
        when (value) {
            is Map<*, *> -> for ((key, child) in value) {
                assertTrue(key !in setOf("objectId", "sourceObjectId", "frameCounter", "nextObjectId", "randomCounter",
                    "remainingLife", "scatter", "offset", "pivot", "muzzle", "combat"), "Host-only key leaked: $key")
                if (key in setOf("unitId", "sourceUnitId", "targetUnitId", "attackTargetId"))
                    assertTrue(child !in setOf("x-hidden", "y-hidden"), "Hidden endpoint leaked")
                checkKeys(child)
            }
            is List<*> -> value.forEach(::checkKeys)
        }
    }

    @Test
    fun full_client_evidence_pairs_hidden_creation_order_including_damage_cluster_split_and_event_only_tokens() {
        for (kind in listOf("rows", "cluster", "event-only")) {
            val recordings = mutableListOf<List<Map<String, Any?>>>()
            for (swapped in listOf(false, true)) {
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(kind, swapped))).context
                val rows = mutableListOf<Map<String, Any?>>()
                val sink = ClientEvidence { event, values -> assertEquals("observation", event); rows += values }
                fun capture() = sink.record("observation", mapOf("sample" to context.observePlayer(context.world, "p1").fields()))
                try {
                    capture()
                    repeat(if (kind == "event-only") 1 else 18) { step ->
                        context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                        if (swapped) context.observePlayer(context.world, "p2")
                        capture()
                    }
                    recordings += rows
                    rows.forEach(::checkKeys)
                    if (kind == "cluster") {
                        val view = rows[11].getValue("sample") as Map<*, *>
                        val projectiles = assertIs<List<*>>(view["projectiles"])
                        assertTrue(projectiles.filterIsInstance<Map<*, *>>().any { it["parentToken"] != null },
                            "Actual serializer must carry split parent linkage")
                    }
                    if (kind == "event-only") {
                        val sample = rows.last().getValue("sample") as Map<*, *>
                        val events = assertIs<List<*>>(sample["projectileEvents"])
                        assertEquals(1, events.size)
                        assertEquals(2, (events.single() as Map<*, *>)["occurrences"])
                    }
                } finally { context.close() }
            }
            assertEquals(recordings[0], recordings[1], "Full serialized record arrays including tokens/sampleId: $kind")
        }
    }
}
