package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class P001LifecycleTest {
    private fun observed(world:String="w:2", speed:Double=0.0, target:Position?=null) = Observation(
        "$world.initial", world,"literal",0,0.0,listOf(UnitObservation("u1","p1","tank",Position(110.0,110.0),90.0,
            speed,0.0,target,emptyList(),MovementState.IDLE)),emptyList(),emptyList())
    @Test fun `fresh generation must be clean not only renamed`() {
        val original=observed("w:1")
        assertNull(P001Lifecycle.fresh(original,observed()))
        assertNotNull(P001Lifecycle.fresh(original,observed(speed=.07)))
        assertNotNull(P001Lifecycle.fresh(original,observed(target=Position(250.0,110.0))))
        assertNotNull(P001Lifecycle.fresh(original,observed("w:1")))
    }
    @Test fun `immediate ownership compares full unit DTO without conflating result metadata`() {
        assertNull(P001Lifecycle.unchanged(observed(),observed()))
        assertNotNull(P001Lifecycle.unchanged(observed(),observed(target=Position(250.0,110.0))))
    }
}
