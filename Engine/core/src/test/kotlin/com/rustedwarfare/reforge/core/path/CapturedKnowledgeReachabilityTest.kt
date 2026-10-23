package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

/** Captured public-planner inputs from TEST-P002-024, not a hidden truth map. */
class CapturedKnowledgeReachabilityTest {
    @Test fun previously_rejected_targets_remain_reachable_with_the_original_radius_and_knowledge() {
        val knownBlocked = setOf(44,74,104,110,111,112,113,114,134,140,144,164,170,174,194,200,204,224,230,231,254,374)
        val start = Position(526.1723287351451,86.49821124585615)
        val targets = listOf(
            10308 to Position(437.0927429199219,101.6015625),
            10337 to Position(452.9504089355469,115.02386474609375),
            10362 to Position(446.851318359375,91.22979736328125),
            10528 to Position(453.5603332519531,108.92279052734375),
            10563 to Position(453.5603332519531,108.92279052734375),
            10582 to Position(453.5603332519531,108.92279052734375),
        )
        for ((sequence,target) in targets) assertReachable(knownBlocked,start,target,"captured $sequence")
        assertReachable(setOf(113,140,174,204,231),start,targets.last().second,"minimal five-cell diagnostic")
    }

    private fun assertReachable(blocked:Set<Int>,start:Position,target:Position,label:String) {
        val terrain=PlanningTerrain(30,20,20,20,BooleanArray(600) { it in blocked })
        val route=assertNotNull(PathPlanner.findPath(terrain,start,target,11.0),label)
        assertTrue(route.isNotEmpty(),label)
        assertEquals(target,route.last(),label)
        var previous=start
        for(point in route) {
            assertTrue(PlanningSegmentClearance.allows(terrain,previous,point,11.0),"$label: $previous -> $point")
            previous=point
        }
    }
}
