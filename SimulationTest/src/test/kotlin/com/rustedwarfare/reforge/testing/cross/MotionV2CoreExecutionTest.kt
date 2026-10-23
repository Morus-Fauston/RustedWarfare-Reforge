package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import kotlin.test.*

/** Actual core operations on private Temp inputs only. No approval, ledger or source qualification. */
class MotionV2CoreExecutionTest {
    private fun scenario():Scenario {
        val fixtures=MotionV2CoreInputsTest().apply { directory=Files.createTempDirectory("eng017-execution-") }
        val f=fixtures.fixture()
        Files.writeString(f.tsx,Files.readString(f.tsx).replace("<properties><property name=\"trees\" value=\"\"/></properties>",""))
        fixtures.refresh(f);val loaded=MotionV2CoreInputs.load(f.manifest,f.request)
        return Scenario("private.execution",loaded.map,mapOf("tank" to loaded.unit),listOf(Player("p1",PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1","p1","tank",Position(10.0,10.0),90.0)),
            CoordinateModel("p001.coordinate.v1","2d","x-right","y-down","top-left","world-unit","double","ieee754-binary64"),
            TimeModel("p001.time.v1","sim-tick","ieee754-binary64","fixed-explicit-delta"),MovementContract(16.0,4.0))
    }
    private fun move()=MotionV2CoreExecution.Scheduled(0,0,obj("q" to n(0),"action" to s("move"),"target" to obj("x" to n(50),"y" to n(10)))) {
        MoveAction("qual.0","p1","u1",Position(50.0,10.0),it) }
    private fun stop(actor:String="p1")=MotionV2CoreExecution.Scheduled(1,4,obj("q" to n(4),"action" to s("stop"),"target" to null)) {
        StopAction("qual.1",actor,"u1",it) }
    @Test fun move_and_stop_keep_the_full_window_and_actual_step_dto_association() {
        val trace=MotionV2CoreExecution.run(MotionV2CoreExecution.Prepared(scenario(),20,listOf(move(),stop())))
        assertTrue(trace.completed);assertNull(trace.firstError);assertNotNull(trace.initial)
        assertEquals(CloseResult.Closed,trace.closeResult)
        val advances=trace.operations.filter { it.kind=="advance" };val submits=trace.operations.filter { it.kind=="submit" }
        assertEquals(20,advances.size);assertEquals(2,submits.size)
        assertEquals((0..21).toList(),trace.operations.map { it.ordinal })
        for((i,op)in advances.withIndex()) {
            assertTrue(op.attempted);assertEquals(Step(i.toLong(),1.0,"sim-tick"),op.step)
            assertEquals(i+1.0,assertNotNull(op.after).simulationTime)
            val dto=assertNotNull(op.motion).single();assertEquals(i.toLong(),dto.stepIndex)
            assertEquals(op.after.units.single().position,dto.finalPosition)
        }
        for(op in submits)assertIs<ActionResult.Accepted>(op.result)
        assertNull(submits.last().after!!.units.single().moveTarget)
        assertEquals(20.0,advances.last().after!!.simulationTime)
        assertTrue(advances.any { it.motion!!.single().candidate!=it.motion!!.single().positionBefore })
    }
    @Test fun rejected_submission_retains_the_attempt_without_becoming_consumption_or_shortening_the_run() {
        val trace=MotionV2CoreExecution.run(MotionV2CoreExecution.Prepared(scenario(),20,listOf(move(),stop("unknown"))))
        assertTrue(trace.completed);assertNull(trace.firstError)
        val rejected=trace.operations.single { it.kind=="submit" && it.scheduled!!.sequence==1 }
        assertEquals("unknown",assertNotNull(rejected.issuedAction).actorPlayerId)
        assertIs<ActionResult.Rejected>(rejected.result);assertNull(rejected.error)
        assertEquals(rejected.before!!.simulationTime,rejected.after!!.simulationTime)
        assertNotNull(rejected.after.units.single().moveTarget)
        assertEquals(20,trace.operations.count { it.kind=="advance" })
    }
    @Test fun initialization_rejection_or_preoperation_source_failure_never_fabricates_facts() {
        val base=scenario();val rejected=MotionV2CoreExecution.run(MotionV2CoreExecution.Prepared(
            base.copy(units=listOf(base.units.single().copy(typeId="not-defined"))),20,emptyList()))
        assertFalse(rejected.completed);assertNotNull(rejected.initialization!!.error)
        assertNull(rejected.initial);assertTrue(rejected.operations.isEmpty());assertNull(rejected.closeResult)
        val before=MotionV2CoreExecution.run(MotionV2CoreExecution.Prepared(base,20,listOf(move())),
            inputGuard={ stage->if(stage=="initialize")error("before initialization") })
        assertFalse(before.completed);assertNull(before.initialization);assertNull(before.initial);assertTrue(before.operations.isEmpty())
        val trace=MotionV2CoreExecution.run(MotionV2CoreExecution.Prepared(base,20,listOf(move())),
            inputGuard={ stage->if(stage=="advance:3")error("actual dependency changed") })
        assertFalse(trace.completed);assertEquals("advance:3",trace.firstError!!.stage)
        val advances=trace.operations.filter { it.kind=="advance" }
        assertEquals(3,advances.size);assertEquals(2,advances.count { it.attempted })
        assertFalse(advances.last().attempted);assertNull(advances.last().after);assertNull(advances.last().motion)
        assertEquals(CloseResult.Closed,trace.closeResult)
    }
    @Test fun both_final_guards_run_independently_and_secondary_errors_do_not_replace_the_first() {
        val checked=mutableListOf<String>()
        val trace=MotionV2CoreExecution.run(MotionV2CoreExecution.Prepared(scenario(),20,listOf(move())),
            inputGuard={ stage->checked+="input:$stage";if(stage=="advance:3")error("first source failure");if(stage=="finally")error("input postcheck") },
            runtimeGuard={ stage->checked+="runtime:$stage";if(stage=="finally")error("runtime postcheck") })
        assertEquals("first source failure",trace.firstError!!.error.message)
        assertEquals("advance:3",trace.firstError.stage)
        assertEquals(listOf("input postcheck"),trace.inputPostErrors.map { it.message })
        assertEquals(listOf("runtime postcheck"),trace.runtimePostErrors.map { it.message })
        assertContains(checked,"input:finally");assertContains(checked,"runtime:finally")
        assertEquals(CloseResult.Closed,trace.closeResult)
        assertEquals(2,trace.firstError.error.suppressed.size)
    }
}
