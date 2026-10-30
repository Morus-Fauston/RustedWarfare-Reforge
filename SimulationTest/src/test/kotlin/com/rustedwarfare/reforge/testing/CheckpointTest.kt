package com.rustedwarfare.reforge.testing

import kotlin.test.*
import kotlinx.serialization.json.*

/** Independent literal traces exercise the public comparator, without running a second simulation. */
class CheckpointTest {
    private val fixture = ComparisonTest()

    private fun contract(condition: JsonObject, x: Int): JsonObject = JsonObject(fixture.contract() +
        ("rules" to array(listOf(obj("ruleId" to s("fixed.checkpoint"), "when" to condition,
            "assertions" to array(listOf(obj("path" to s("units.u1.position.x"),
                "operator" to s("equals"), "value" to n(x)))))))))

    @Test fun `fixed checkpoint binds one action object and exact simulation boundary`() {
        val condition = obj("event" to s("action-accepted"), "actionId" to s("a1"),
            "unitId" to s("u1"), "stepIndex" to n(0), "phase" to s("after-advance"))
        val result = TraceComparator.evaluate(fixture.scenario(), contract(condition, 1), fixture.controlled())
        assertEquals(0, result.exitCode, result.document.toString())
    }

    @Test fun `exact sample distinguishes initial observation from after step zero`() {
        for ((phase, x) in listOf("initial" to 0, "after-advance" to 1)) {
            val result = TraceComparator.evaluate(fixture.scenario(),
                contract(obj("phase" to s(phase), "stepIndex" to n(0)), x), fixture.controlled())
            assertEquals(0, result.exitCode, result.document.toString())
        }
    }

    @Test fun `wrong expectation reports the fixed checkpoint and actual sample`() {
        val result = TraceComparator.evaluate(fixture.scenario(),
            contract(obj("phase" to s("after-advance"), "stepIndex" to n(1)), 999), fixture.controlled())
        assertEquals(2, result.exitCode)
        assertEquals(s("fixed.checkpoint"), result.document["ruleId"])
        assertEquals(n(2), result.document["actual"])
    }

    @Test fun `old accepted action cannot satisfy a later checkpoint`() {
        val result = TraceComparator.evaluate(fixture.scenario(), contract(obj("event" to s("action-accepted"),
            "actionId" to s("a1"), "stepIndex" to n(1)), 2), fixture.controlled())
        assertEquals(3, result.exitCode)
        assertTrue(result.document.text("reason").contains("fixed.checkpoint"))
    }

    @Test fun `missing sample truncated capture and execution failure never pass`() {
        val rows = fixture.controlled()
        val condition = obj("phase" to s("after-advance"), "stepIndex" to n(1))
        for (partial in listOf(rows.dropLast(1), rows.filterIndexed { index, _ -> index != 4 })) {
            assertEquals(3, TraceComparator.evaluate(fixture.scenario(), contract(condition, 2), partial).exitCode)
        }
        val crashed = rows.dropLast(1) + JsonObject(rows.last() + mapOf(
            "status" to s("execution-failed"), "exitCode" to n(4), "complete" to b(false)))
        assertEquals(4, TraceComparator.evaluate(fixture.scenario(), contract(condition, 2), crashed).exitCode)
    }

    @Test fun `unknown and ambiguous checkpoint declarations are input errors`() {
        for (condition in listOf(obj(), obj("stepIndex" to n(0)),
            obj("phase" to s("initial"), "stepIndex" to n(1)),
            obj("phase" to s("after-advance"), "stepIndex" to n(2)),
            obj("event" to s("action-accepted"), "actionId" to s("unknown")),
            obj("event" to s("action-accepted"), "unitId" to s("unknown")))) {
            assertEquals(64, TraceComparator.evaluate(fixture.scenario(), contract(condition, 1), fixture.controlled()).exitCode)
        }
    }
}
