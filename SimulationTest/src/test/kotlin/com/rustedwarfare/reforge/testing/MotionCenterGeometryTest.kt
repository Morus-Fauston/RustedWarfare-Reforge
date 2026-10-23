package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

class MotionCenterGeometryTest {
    @Test fun adjacent_binary64_points_have_no_epsilon_and_invalid_geometry_is_rejected() {
        val walls=listOf(intArrayOf(14,6))
        fun clear(p:Position,w:List<IntArray> = walls)=MotionCenterGeometry.centerPath(listOf(p),w,30,20,20,20,11.0)
        assertFalse(clear(Position(Math.nextDown(300.0),130.0)))
        assertTrue(clear(Position(Math.nextUp(300.0),130.0)))
        assertFalse(clear(Position(Double.NaN,130.0)))
        assertFalse(clear(Position(300.0,Double.POSITIVE_INFINITY)))
        assertFalse(clear(Position(300.0,130.0),listOf(intArrayOf(30,6))))
        assertFalse(MotionCenterGeometry.centerPath(emptyList(),walls,30,20,20,20,11.0))
    }
    @Test fun half_open_grid_edges_corners_and_map_tangency_match_literal_contract_in_both_directions() {
        val walls=listOf(intArrayOf(14,6))
        val open=listOf(
            Position(300.0,130.0) to Position(310.0,130.0),
            Position(290.0,140.0) to Position(290.0,150.0),
            Position(300.0,120.0) to Position(300.0,150.0),
            Position(280.0,140.0) to Position(310.0,140.0),
            Position(290.0,110.0) to Position(310.0,130.0),
            Position(290.0,150.0) to Position(310.0,130.0),
            Position(300.0,130.0) to Position(300.0,130.0),
            Position(11.0,11.0) to Position(589.0,11.0))
        val closed=listOf(
            Position(280.0,130.0) to Position(270.0,130.0),
            Position(290.0,120.0) to Position(290.0,110.0),
            Position(270.0,130.0) to Position(290.0,110.0),
            Position(290.0,130.0) to Position(290.0,130.0),
            Position(310.0,125.0) to Position(295.0,145.0),
            Position(10.999,11.0) to Position(20.0,11.0))
        for((pairs,expected)in listOf(open to true,closed to false))for((a,b)in pairs) {
            assertEquals(expected,MotionCenterGeometry.centerPath(listOf(a,b),walls,30,20,20,20,11.0),"$a -> $b")
            assertEquals(expected,MotionCenterGeometry.centerPath(listOf(b,a),walls,30,20,20,20,11.0),"$b -> $a")
        }
        assertTrue(MotionCenterGeometry.centerPath(listOf(Position(432.0499999832496,269.99980801378325),Position(510.0,250.0)),listOf(intArrayOf(24,13)),50,40,20,20,11.0))
    }
}
