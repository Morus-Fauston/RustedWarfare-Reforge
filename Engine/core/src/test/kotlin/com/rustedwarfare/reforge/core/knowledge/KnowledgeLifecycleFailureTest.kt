package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class KnowledgeLifecycleFailureTest {
    private fun context()=assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
    private val memory=KnowledgeRules(TerrainRetention.MEMORY,PathInformationSource.PLAYER_KNOWLEDGE,false)
    private val invalid=KnowledgeRules(TerrainRetention.HARDCORE_FORGET,PathInformationSource.ORIGINAL_COMPATIBILITY,false)
    private fun code(expected:String,call:()->Unit){assertEquals(expected,assertFailsWith<KnowledgeException>(block=call).code)}
    private fun diagnostic(o:Observation)=listOf(o.sampleId,o.worldId,o.scenarioId,o.stepIndex,o.simulationTime,o.units,o.events,o.actionResults)
    @Test fun kl07_kl08_kl09_exact_failure_priority_and_projection_atomicity() {
        val context=context();val other=context()
        try {
            val world=context.world;val foreign=other.world
            assertEquals(world.id,foreign.id);assertNotSame(world,foreign)
            val bad=listOf(VisibleTerrainCell(0,0,false),VisibleTerrainCell(30,0,true))
            code("stale-world"){context.enableKnowledge(foreign,invalid)}
            code("stale-world"){context.knowledgeSnapshot(foreign,"missing")}
            code("stale-world"){context.acceptVisibleTerrain(foreign,"missing",bad)}
            code("not-enabled"){context.knowledgeSnapshot(world,"missing")}
            code("not-enabled"){context.acceptVisibleTerrain(world,"missing",bad)}
            context.enableKnowledge(world,memory)
            context.acceptVisibleTerrain(world,"p1",listOf(VisibleTerrainCell(0,0,true)))
            val before=context.knowledgeSnapshot(world,"p1");val observed=diagnostic(context.observe())
            for(player in listOf("missing",""," ")) {
                code("unknown-player"){context.knowledgeSnapshot(world,player)}
                code("unknown-player"){context.acceptVisibleTerrain(world,player,bad)}
            }
            code("out-of-bounds"){context.acceptVisibleTerrain(world,"p1",bad)}
            code("duplicate-cell"){context.acceptVisibleTerrain(world,"p1",listOf(VisibleTerrainCell(1,0,false),VisibleTerrainCell(1,0,true)))}
            assertEquals(before.cells,context.knowledgeSnapshot(world,"p1").cells)
            assertEquals(before.revision,context.knowledgeSnapshot(world,"p1").revision)
            assertEquals(observed,diagnostic(context.observe()))
            assertEquals(0L,context.knowledgeSnapshot(world,"p2").revision)
            context.close()
            code("closed"){context.enableKnowledge(foreign,invalid)}
            code("closed"){context.acceptVisibleTerrain(foreign,"missing",bad)}
            code("closed"){context.knowledgeSnapshot(foreign,"missing")}
        } finally {context.close();other.close()}
    }
    @Test fun kl12_kl13_failed_and_active_reopen_preserve_the_correct_lifecycle_state() {
        val context=context();val source=TestContentFactory.openScenario();val world=context.world
        context.enableKnowledge(world,memory);context.acceptVisibleTerrain(world,"p1",listOf(VisibleTerrainCell(0,0,true)))
        val before=context.knowledgeSnapshot(world,"p1")
        assertEquals("already-open",assertIs<InitResult.Rejected>(context.reopen(source)).error.code)
        assertSame(world,context.world);assertEquals(before.cells,context.knowledgeSnapshot(world,"p1").cells)
        context.acceptVisibleTerrain(world,"p1",emptyList());assertEquals(2L,context.knowledgeSnapshot(world,"p1").revision)
        context.close()
        val bad=source.copy(units=listOf(source.units.single().copy(ownerPlayerId="missing")))
        assertEquals("bad-reference",assertIs<InitResult.Rejected>(context.reopen(bad)).error.code)
        code("closed"){context.knowledgeSnapshot(world,"p1")}
        assertIs<InitResult.Accepted>(context.reopen(source))
        val fresh=context.world
        code("stale-world"){context.enableKnowledge(world,memory)}
        code("not-enabled"){context.knowledgeSnapshot(fresh,"p1")}
        context.enableKnowledge(fresh,memory)
        assertEquals(0L,context.knowledgeSnapshot(fresh,"p1").revision)
        assertTrue(context.knowledgeSnapshot(fresh,"p1").cells.all {it==CellKnowledge(false,null)})
        context.close()
    }

    @Test fun kl05_kl06_enable_failure_is_atomic_and_second_enable_cannot_change_rules() {
        val context=context()
        try {
            val world=context.world;val initial=context.observe()
            code("incompatible-rules"){context.enableKnowledge(world,invalid)}
            code("not-enabled"){context.knowledgeSnapshot(world,"p1")}
            code("not-enabled"){context.acceptVisibleTerrain(world,"missing",emptyList())}
            context.enableKnowledge(world,memory)
            context.acceptVisibleTerrain(world,"p1",listOf(VisibleTerrainCell(0,0,true)))
            code("already-enabled"){context.enableKnowledge(world,invalid)}
            code("already-enabled"){context.enableKnowledge(world,memory)}
            assertEquals(1L,context.knowledgeSnapshot(world,"p1").revision)
            assertEquals(CellKnowledge(true,true),context.knowledgeSnapshot(world,"p1").cell(0,0))
            assertEquals(0L,context.knowledgeSnapshot(world,"p2").revision)
            assertEquals(initial.sampleId,context.observe().sampleId);assertEquals(initial.units,context.observe().units)
        } finally {context.close()}
    }
}
