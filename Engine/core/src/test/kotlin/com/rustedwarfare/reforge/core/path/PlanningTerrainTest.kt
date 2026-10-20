package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class PlanningTerrainTest {
    private fun store(retention: TerrainRetention): Pair<WorldHandle,PlayerTerrainKnowledge> {
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        val world=context.world;context.close()
        return world to PlayerTerrainKnowledge.create(world,"p1",5,5,KnowledgeRules(retention,PathInformationSource.PLAYER_KNOWLEDGE,false))
    }
    @Test fun ki08_constructor_and_no_fog_adapter_reject_invalid_shapes_in_declared_order() {
        fun rejected(code:String,width:Int,height:Int,tileWidth:Int,tileHeight:Int,cells:BooleanArray) {
            assertEquals(code,assertFailsWith<PlanningException> { PlanningTerrain(width,height,tileWidth,tileHeight,cells) }.code)
        }
        for((w,h) in listOf(0 to 5,-1 to 5,5 to 0,5 to -1,Int.MAX_VALUE to 2)) rejected("invalid-dimensions",w,h,20,20,booleanArrayOf())
        for((w,h) in listOf(0 to 20,-1 to 20,20 to 0,20 to -1)) rejected("invalid-geometry",5,5,w,h,BooleanArray(25))
        rejected("invalid-blocking",5,5,20,20,BooleanArray(24))
        rejected("invalid-blocking",5,5,20,20,BooleanArray(26))
        rejected("invalid-dimensions",0,5,0,20,booleanArrayOf())
        rejected("invalid-geometry",5,5,0,20,booleanArrayOf())
        val mismatched=MapDefinition(5,5,20,20,emptyList(),IntArray(25),BlockingGrid(1,1,booleanArrayOf(false)))
        assertEquals("invalid-blocking",assertFailsWith<PlanningException> { PlanningTerrain.fromNoFog(mismatched) }.code)
        val invalidMap=MapDefinition(0,5,0,20,emptyList(),intArrayOf(),BlockingGrid(1,1,booleanArrayOf(false)))
        assertEquals("invalid-dimensions",assertFailsWith<PlanningException> { PlanningTerrain.fromNoFog(invalidMap) }.code)
        val (world,store)=store(TerrainRetention.MEMORY)
        assertEquals("invalid-geometry",assertFailsWith<PlanningException> { PlanningTerrain.fromKnowledge(store.snapshot(world),0,20) }.code)
        store.close()
    }
    @Test fun ki08_constructor_and_planner_results_have_no_mutable_aliases() {
        val cells=BooleanArray(25);cells[12]=true
        val terrain=PlanningTerrain(5,5,20,20,cells)
        cells.fill(false)
        assertTrue(terrain.isBlocked(2,2))
        for((c,r) in listOf(-1 to 0,5 to 0,0 to -1,0 to 5)) assertEquals("out-of-bounds",assertFailsWith<PlanningException> { terrain.isBlocked(c,r) }.code)
        val literal=listOf(Position(30.0,30.0),Position(70.0,30.0),Position(70.0,50.0))
        val path=assertNotNull(PathPlanner.findPath(terrain,Position(30.0,50.0),Position(70.0,50.0),1.0))
        assertEquals(literal,path)
        assertFailsWith<UnsupportedOperationException> { (path as MutableList<Position>).clear() }
        assertFailsWith<UnsupportedOperationException> { (path as MutableList<Position>)[0]=Position(0.0,0.0) }
        assertEquals(literal,PathPlanner.findPath(terrain,Position(30.0,50.0),Position(70.0,50.0),1.0))
    }

    @Test fun ki08_knowledge_adapter_projects_memory_and_forgetting_without_live_store_aliases() {
        val literalUpper=listOf(Position(30.0,30.0),Position(70.0,30.0),Position(70.0,50.0))
        val literalStraight=listOf(Position(70.0,50.0))
        for(retention in TerrainRetention.entries) {
            val (world,store)=store(retention)
            store.accept(world,listOf(VisibleTerrainCell(2,2,true)))
            val old=PlanningTerrain.fromKnowledge(store.snapshot(world),20,20)
            store.accept(world,emptyList())
            val current=PlanningTerrain.fromKnowledge(store.snapshot(world),20,20)
            store.close()
            assertTrue(old.isBlocked(2,2))
            assertEquals(literalUpper,PathPlanner.findPath(old,Position(30.0,50.0),Position(70.0,50.0),1.0))
            assertEquals(if(retention==TerrainRetention.MEMORY) literalUpper else literalStraight,
                PathPlanner.findPath(current,Position(30.0,50.0),Position(70.0,50.0),1.0))
        }
    }
}
