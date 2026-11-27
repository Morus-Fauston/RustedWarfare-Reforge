package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.client.ControlledScenario
import com.rustedwarfare.reforge.core.sim.AttackAction
import com.rustedwarfare.reforge.core.sim.BuildAction
import com.rustedwarfare.reforge.core.sim.InitResult
import com.rustedwarfare.reforge.core.sim.MoveAction
import com.rustedwarfare.reforge.core.sim.PatrolAction
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ProduceAction
import com.rustedwarfare.reforge.core.sim.SelectAction
import com.rustedwarfare.reforge.core.sim.SimulationContext
import com.rustedwarfare.reforge.core.sim.StopAction
import com.rustedwarfare.reforge.core.sim.UnitAction
import com.rustedwarfare.reforge.testing.cross.MotionDiagnosticCapture
import com.rustedwarfare.reforge.testing.cross.MotionV2CoreTraceJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestUi004OpenValidationTest {

    private class SyntheticFutureGuardAction(val actionId: String)

    @Test
    fun `MotionDiagnosticCapture and MotionV2CoreTraceJson preserve supported movement kinds, existing rejections, and open future Action fallback`() {
        val sandboxDir = listOf(
            Paths.get("public/inputs/content-movement-knowledge/P001-移动沙盒输入"),
            Paths.get("SimulationTest/public/inputs/content-movement-knowledge/P001-移动沙盒输入"),
        ).first { Files.isDirectory(it) }
        val scenario = ControlledScenario.load(sandboxDir)
        val init = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario))
        val ctx = init.context
        try {
            val world = ctx.world
            val move = MoveAction("a1", "p1", "u1", Position(12.0, 34.0), world)
            val stop = StopAction("a2", "p1", "u1", world)
            val select = SelectAction("a3", "p1", "u1", world)

            assertEquals("move", MotionDiagnosticCapture.diagnosticActionKindForType(move))
            assertEquals("stop", MotionDiagnosticCapture.diagnosticActionKindForType(stop))
            assertEquals("select", MotionDiagnosticCapture.diagnosticActionKindForType(select))

            assertEquals("move", MotionV2CoreTraceJson.traceActionKindForType(move))
            assertEquals("stop", MotionV2CoreTraceJson.traceActionKindForType(stop))
            assertEquals("select", MotionV2CoreTraceJson.traceActionKindForType(select))

            val moveJson = MotionV2CoreTraceJson.action(move) as JsonObject
            assertEquals(s("move"), moveJson["kind"])

            // Existing unsupported Action classes retain exact diagnostic messages
            val attack = AttackAction("a4", "p1", "u1", "u2", world)
            val patrol = PatrolAction("a5", "p1", "u1", Position(10.0, 20.0), false, world)
            val build = BuildAction("a6", "p1", "u1", "extractor", Position(10.0, 20.0), "n1", world, append = false)
            val produce = ProduceAction("a7", "p1", "u1", "tank", "n2", world)
            val unitAct = UnitAction("a8", "p1", "u1", "afterBurn", world, false)

            assertTrue(assertFailsWith<IllegalStateException> { MotionDiagnosticCapture.diagnosticActionKindForType(attack) }.message!!.contains("AttackAction"))
            assertTrue(assertFailsWith<IllegalStateException> { MotionDiagnosticCapture.diagnosticActionKindForType(patrol) }.message!!.contains("PatrolAction"))
            assertTrue(assertFailsWith<IllegalStateException> { MotionDiagnosticCapture.diagnosticActionKindForType(build) }.message!!.contains("construction actions"))
            assertTrue(assertFailsWith<IllegalStateException> { MotionDiagnosticCapture.diagnosticActionKindForType(produce) }.message!!.contains("economy actions"))
            assertTrue(assertFailsWith<IllegalStateException> { MotionDiagnosticCapture.diagnosticActionKindForType(unitAct) }.message!!.contains("UnitAction"))

            // Also verify `submitAndRecord` rejects unsupported actions BEFORE mutating context
            assertTrue(assertFailsWith<IllegalArgumentException> {
                MotionDiagnosticCapture.submitAndRecord(ctx, attack, obj(), 0, 0, mutableListOf())
            }.message!!.contains("AttackAction"))

            assertTrue(assertFailsWith<IllegalStateException> { MotionV2CoreTraceJson.traceActionKindForType(attack) }.message!!.contains("AttackAction"))
            assertTrue(assertFailsWith<IllegalStateException> { MotionV2CoreTraceJson.traceActionKindForType(build) }.message!!.contains("construction actions"))
            assertTrue(assertFailsWith<IllegalStateException> { MotionV2CoreTraceJson.traceActionKindForType(produce) }.message!!.contains("economy actions"))

            // Open default branch cleanly reports future Action subtypes (e.g. Phase VIII pt2 Guard / AttackMove)
            val futureAction = SyntheticFutureGuardAction("a9")
            val diagErr = assertFailsWith<IllegalStateException> {
                MotionDiagnosticCapture.diagnosticActionKindForType(futureAction)
            }
            assertTrue(diagErr.message!!.contains("SyntheticFutureGuardAction"))

        val traceErr = assertFailsWith<IllegalStateException> {
                MotionV2CoreTraceJson.traceActionKindForType(futureAction)
            }
            assertTrue(traceErr.message!!.contains("SyntheticFutureGuardAction"))
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `TraceComparator admits extended UnitTask keys and new uppercase UnitTaskKind while strictly rejecting missing keys, bad kinds, and value drift`() {
        fun baseTask(kind: String = "MOVE", autoRepair: Boolean = false): JsonObject = obj(
            "kind" to s(kind),
            "actionId" to s("act-1"),
            "target" to obj("x" to n(64.0), "y" to n(96.0)),
            "targetUnitId" to JsonNull,
            "atStep" to n(3),
            "buildingTypeId" to JsonNull,
            "buildingNonce" to JsonNull,
            "automaticRepair" to b(autoRepair),
            "extraWorkRange" to n(0.0),
        )

        // 1. Existing 9-key task and new Phase VIII pt2 kinds (`ATTACK_MOVE`, `GUARD`) + extension keys pass
        TraceComparator.validateUnitTaskSample(baseTask("MOVE"))
        TraceComparator.validateUnitTaskSample(baseTask("CONSTRUCT", autoRepair = true))
        val extendedGuardTask = JsonObject(
            baseTask("GUARD") + mapOf(
                "guardTargetId" to s("ally-1"),
                "leashRange" to n(140.0),
                "queuedOrderIndex" to n(1),
            ),
        )
        TraceComparator.validateUnitTaskSample(extendedGuardTask)
        val extendedAttackMoveTask = JsonObject(
            baseTask("ATTACK_MOVE") + mapOf("engageMove" to b(true)),
        )
        TraceComparator.validateUnitTaskSample(extendedAttackMoveTask)

        // 2. Negative: missing any of the 9 core required keys is strictly rejected
        for (missingKey in listOf("kind", "actionId", "target", "targetUnitId", "atStep", "buildingTypeId", "buildingNonce", "automaticRepair", "extraWorkRange")) {
            val incompleteTask = JsonObject(baseTask("MOVE") - missingKey)
            val err = assertFailsWith<RuntimeException>("Missing '$missingKey' must fail") {
                TraceComparator.validateUnitTaskSample(incompleteTask)
            }
            assertTrue(err.message!!.contains("Missing or invalid task fields"))
        }

        // 3. Negative: lowercase / blank / malformed `kind` or `automaticRepair=true` on non-CONSTRUCT is strictly rejected
        for (badKind in listOf("", "move", "AttackMove", "123_BAD", "GUARD-UNIT")) {
            assertFailsWith<RuntimeException>("Bad kind '$badKind' must fail") {
                TraceComparator.validateUnitTaskSample(baseTask(badKind))
            }
        }
        assertFailsWith<RuntimeException> {
            TraceComparator.validateUnitTaskSample(baseTask("GUARD", autoRepair = true))
        }
        assertFailsWith<RuntimeException> {
            TraceComparator.validateUnitTaskSample(
                JsonObject(baseTask("GUARD") + ("BadUpperExtKey" to s("x"))),
            )
        }
        assertFailsWith<RuntimeException> {
            TraceComparator.validateUnitTaskSample(
                JsonObject(baseTask("MOVE") + ("target" to obj("x" to n(10.0)))),
            )
        }
    }

    @Test
    fun `PlatformSessionRunner admits new Phase VI-B and Phase VIII unit-action names data-drivenly while rejecting malformed intents`() {
        // 1. Positive: new Phase VI-B / Phase VIII pt2 non-targeted, cancellable, and ground-targeted unit actions pass
        val instantSkill = PlatformSessionRunner.parseOpenUnitActionIntent(
            intent = obj(
                "intentId" to s("i-1"),
                "kind" to s("unit-action"),
                "actionName" to s("overchargeShield"),
                "waitSteps" to n(10),
            ),
            isTestScene = false,
            liveGroundTargeted = false,
        )
        assertEquals("overchargeShield", instantSkill.actionName)
        assertEquals(false, instantSkill.cancelQueued)
        assertNull(instantSkill.target)

        val groundSkill = PlatformSessionRunner.parseOpenUnitActionIntent(
            intent = obj(
                "intentId" to s("i-2"),
                "kind" to s("unit-action"),
                "actionName" to s("artilleryStrike"),
                "target" to obj("x" to n(320.0), "y" to n(480.0)),
                "waitSteps" to n(30),
                "coreActionId" to s("core.act.2"),
            ),
            isTestScene = true,
            liveGroundTargeted = true,
        )
        assertEquals("artilleryStrike", groundSkill.actionName)
        assertEquals(Position(320.0, 480.0), groundSkill.target)

        val cancelAmmoQueue = PlatformSessionRunner.parseOpenUnitActionIntent(
            intent = obj(
                "intentId" to s("i-3"),
                "kind" to s("unit-action"),
                "actionName" to s("buildExperimentalDrone"),
                "cancelQueued" to b(true),
                "waitSteps" to n(0),
            ),
            isTestScene = false,
            liveGroundTargeted = false,
        )
        assertEquals(true, cancelAmmoQueue.cancelQueued)

        // 2. Negative: blank/invalid actionName, missing waitSteps, unknown extra keys, bad cancelQueued, missing required target, or target on non-targeted action
        assertFailsWith<InputError> {
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b1"), "kind" to s("unit-action"), "actionName" to s(""), "waitSteps" to n(0)),
                isTestScene = false,
            )
        }
        assertFailsWith<InputError> {
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b2"), "kind" to s("unit-action"), "actionName" to s("123bad"), "waitSteps" to n(0)),
                isTestScene = false,
            )
        }
        assertFailsWith<InputError> {
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b3"), "kind" to s("unit-action"), "actionName" to s("overchargeShield")),
                isTestScene = false,
            )
        }
        assertFailsWith<InputError> {
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b4"), "kind" to s("unit-action"), "actionName" to s("overchargeShield"), "waitSteps" to n(0), "extraGarbage" to n(1)),
                isTestScene = false,
            )
        }
        assertFailsWith<InputError> {
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b5"), "kind" to s("unit-action"), "actionName" to s("buildNuke"), "cancelQueued" to s("true"), "waitSteps" to n(0)),
                isTestScene = false,
            )
        }
        assertFailsWith<InputError> {
            // Live groundTargeted = true without target must fail
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b6"), "kind" to s("unit-action"), "actionName" to s("artilleryStrike"), "waitSteps" to n(0)),
                isTestScene = false,
                liveGroundTargeted = true,
            )
        }
        assertFailsWith<InputError> {
            // Known core targeted action (`blink`) without target must fail even when liveGroundTargeted is null
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b7"), "kind" to s("unit-action"), "actionName" to s("blink"), "waitSteps" to n(0)),
                isTestScene = false,
                liveGroundTargeted = null,
            )
        }
        assertFailsWith<InputError> {
            // Live groundTargeted = false with unexpected target must fail
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b8"), "kind" to s("unit-action"), "actionName" to s("overchargeShield"), "target" to obj("x" to n(1.0), "y" to n(2.0)), "waitSteps" to n(0)),
                isTestScene = false,
                liveGroundTargeted = false,
            )
        }
        assertFailsWith<InputError> {
            // Malformed target object (missing `y`) must fail
            PlatformSessionRunner.parseOpenUnitActionIntent(
                obj("intentId" to s("b9"), "kind" to s("unit-action"), "actionName" to s("artilleryStrike"), "target" to obj("x" to n(1.0)), "waitSteps" to n(0)),
                isTestScene = false,
                liveGroundTargeted = true,
            )
        }
    }
}
