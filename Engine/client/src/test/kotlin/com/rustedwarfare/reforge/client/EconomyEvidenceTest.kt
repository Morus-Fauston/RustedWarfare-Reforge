package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class EconomyEvidenceTest {
    private fun scene(): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-pc-representative-01/input")
        return ControlledScenario.load(root)
    }

    @Test
    fun real_evidence_includes_only_own_balance_and_keeps_the_legacy_shape_without_accounts() {
        val base = scene()
        val recordings = mutableListOf<List<Map<String, Any?>>>()
        for (enemyBalance in listOf(0L, 234567L)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(
                base.copy(economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to enemyBalance))))).context
            val rows = mutableListOf<Map<String, Any?>>()
            val sink = ClientEvidence { kind, data -> assertEquals("observation", kind); rows += data }
            fun capture() = sink.record("observation", mapOf("sample" to context.observePlayer(context.world, "p1").fields()))
            try {
                if (enemyBalance != 0L) context.observePlayer(context.world, "p2")
                capture()
                for (step in 0L..2L) {
                    context.advance(Step(step, 1.0, "sim-tick"))
                    capture()
                }
                for (row in rows) {
                    val sample = assertIs<Map<*, *>>(row["sample"])
                    assertEquals(1750L, sample["balance"])
                    checkPrivateValues(row)
                }
                recordings += rows
            } finally { context.close() }
        }
        assertEquals(recordings[0], recordings[1], "Compare the complete actual evidence Maps and arrays")
        val legacy = assertIs<InitResult.Accepted>(SimulationContext.initialize(base)).context
        try {
            assertFalse("balance" in legacy.observePlayer(legacy.world, "p1").fields())
        } finally { legacy.close() }
    }

    private fun checkPrivateValues(value: Any?) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                assertTrue(key !in setOf("balances", "initialBalances", "economy", "enemyBalance"))
                checkPrivateValues(child)
            }
            is List<*> -> value.forEach(::checkPrivateValues)
            else -> assertNotEquals(234567L, value)
        }
    }
}
