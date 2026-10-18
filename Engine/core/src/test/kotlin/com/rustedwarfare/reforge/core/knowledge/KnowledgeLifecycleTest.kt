package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class KnowledgeLifecycleTest {
    private fun context()=assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
    private fun rules(retention:TerrainRetention=TerrainRetention.MEMORY)=KnowledgeRules(retention,PathInformationSource.PLAYER_KNOWLEDGE,false)
    private fun sameDiagnostic(expected:Observation,actual:Observation) {
        assertEquals(expected.sampleId,actual.sampleId);assertEquals(expected.worldId,actual.worldId);assertEquals(expected.scenarioId,actual.scenarioId)
        assertEquals(expected.stepIndex,actual.stepIndex);assertEquals(expected.simulationTime,actual.simulationTime)
        assertEquals(expected.units,actual.units);assertEquals(expected.events,actual.events);assertEquals(expected.actionResults,actual.actionResults)
    }
    @Test fun kl02_kl03_kl14_trusted_projection_is_player_local_and_does_not_mutate_pending_diagnostics() {
        val context=context()
        try {
            val world=context.world
            context.submit(MoveAction("move","p1","u1",Position(250.0,100.0),world))
            val pending=context.observe()
            context.enableKnowledge(world,rules())
            sameDiagnostic(pending,context.observe())
            context.acceptVisibleTerrain(world,"p1",listOf(VisibleTerrainCell(0,0,true),VisibleTerrainCell(1,0,false)))
            sameDiagnostic(pending,context.observe())
            val first=context.knowledgeSnapshot(world,"p1")
            assertEquals(1L,first.revision);assertEquals(CellKnowledge(true,true),first.cell(0,0));assertEquals(CellKnowledge(true,false),first.cell(1,0))
            val other=context.knowledgeSnapshot(world,"p2");assertEquals(0L,other.revision);assertTrue(other.cells.all {it==CellKnowledge(false,null)})
            context.acceptVisibleTerrain(world,"p1",listOf(VisibleTerrainCell(1,0,false)))
            val second=context.knowledgeSnapshot(world,"p1")
            assertEquals(2L,second.revision);assertEquals(CellKnowledge(false,true),second.cell(0,0));assertEquals(CellKnowledge(true,false),second.cell(1,0))
            sameDiagnostic(pending,context.observe());assertEquals(CellKnowledge(true,true),first.cell(0,0))
        } finally {context.close()}
    }

    @Test fun kl04_hardcore_forgets_without_mutating_true_terrain_and_old_snapshot() {
        val context=context()
        try {
            val world=context.world
            context.enableKnowledge(world,rules(TerrainRetention.HARDCORE_FORGET))
            context.acceptVisibleTerrain(world,"p1",listOf(VisibleTerrainCell(0,0,true)))
            val old=context.knowledgeSnapshot(world,"p1")
            context.acceptVisibleTerrain(world,"p1",emptyList())
            val now=context.knowledgeSnapshot(world,"p1")
            assertEquals(2L,now.revision);assertTrue(now.cells.all {it==CellKnowledge(false,null)})
            assertEquals(CellKnowledge(true,true),old.cell(0,0))
        } finally {context.close()}
    }
    @Test fun kl10_kl11_close_and_reopen_require_fresh_opt_in_and_world_capability() {
        val context=context();val source=TestContentFactory.openScenario();val oldWorld=context.world
        context.enableKnowledge(oldWorld,rules())
        context.acceptVisibleTerrain(oldWorld,"p1",listOf(VisibleTerrainCell(0,0,true)))
        val old=context.knowledgeSnapshot(oldWorld,"p1")
        assertEquals(CloseResult.Closed,context.close());assertEquals(CloseResult.AlreadyClosed,context.close())
        assertEquals("closed",assertFailsWith<KnowledgeException>{context.knowledgeSnapshot(oldWorld,"p1")}.code)
        assertEquals("closed",assertFailsWith<KnowledgeException>{context.acceptVisibleTerrain(oldWorld,"p1",emptyList())}.code)
        assertEquals("closed",assertFailsWith<KnowledgeException>{context.enableKnowledge(oldWorld,rules())}.code)
        assertFailsWith<IllegalStateException>{context.world}
        assertIs<InitResult.Accepted>(context.reopen(source))
        val world=context.world
        assertNotSame(oldWorld,world)
        assertEquals("stale-world",assertFailsWith<KnowledgeException>{context.enableKnowledge(oldWorld,rules())}.code)
        assertEquals("stale-world",assertFailsWith<KnowledgeException>{context.knowledgeSnapshot(oldWorld,"p1")}.code)
        assertEquals("stale-world",assertFailsWith<KnowledgeException>{context.acceptVisibleTerrain(oldWorld,"p1",emptyList())}.code)
        assertEquals("not-enabled",assertFailsWith<KnowledgeException>{context.knowledgeSnapshot(world,"p1")}.code)
        context.enableKnowledge(world,rules(TerrainRetention.HARDCORE_FORGET))
        for(player in listOf("p1","p2")){val fresh=context.knowledgeSnapshot(world,player);assertEquals(0L,fresh.revision);assertTrue(fresh.cells.all{it==CellKnowledge(false,null)})}
        assertEquals(CellKnowledge(true,true),old.cell(0,0))
        assertFailsWith<UnsupportedOperationException>{(old.cells as MutableList<CellKnowledge>).clear()}
        context.close()
    }

    @Test fun kl15_projection_alias_and_read_only_snapshots_remain_independent() {
        val context=context()
        try {
            val world=context.world;context.enableKnowledge(world,rules())
            val cells=mutableListOf(VisibleTerrainCell(0,0,true));context.acceptVisibleTerrain(world,"p1",cells)
            val old=context.knowledgeSnapshot(world,"p1")
            cells[0]=VisibleTerrainCell(0,0,false);cells.clear()
            assertEquals(CellKnowledge(true,true),context.knowledgeSnapshot(world,"p1").cell(0,0))
            context.acceptVisibleTerrain(world,"p1",emptyList())
            assertEquals(CellKnowledge(true,true),old.cell(0,0))
            assertFailsWith<UnsupportedOperationException>{(old.cells as MutableList<CellKnowledge>)[0]=CellKnowledge(false,false)}
        } finally {context.close()}
    }

    @Test fun kl01_enable_uses_current_context_saved_players_not_a_caller_mutable_list() {
        val source=TestContentFactory.openScenario()
        val players=mutableListOf(Player("p1",PlayerControl.LOCAL),Player("declared-other",PlayerControl.NONE))
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(players=players))).context
        try {
            players.clear();players+=Player("later-injected",PlayerControl.NONE)
            val world=context.world;context.enableKnowledge(world,rules())
            assertEquals("declared-other",context.knowledgeSnapshot(world,"declared-other").playerId)
            assertEquals("unknown-player",assertFailsWith<KnowledgeException>{context.knowledgeSnapshot(world,"p2")}.code)
            assertEquals("unknown-player",assertFailsWith<KnowledgeException>{context.knowledgeSnapshot(world,"later-injected")}.code)
        } finally {context.close()}
    }

    @Test fun kl01_explicit_enable_creates_empty_declared_players_without_touching_diagnostics() {
        val context=context()
        try {
            val world=context.world;val initial=context.observe()
            assertEquals("not-enabled",assertFailsWith<KnowledgeException> {context.knowledgeSnapshot(world,"p1")}.code)
            context.enableKnowledge(world,rules())
            for(player in listOf("p1","p2")) {
                val snapshot=context.knowledgeSnapshot(world,player)
                assertEquals(player,snapshot.playerId);assertEquals(world.id,snapshot.worldId)
                assertEquals(0L,snapshot.revision);assertTrue(snapshot.cells.all {it==CellKnowledge(false,null)})
            }
            sameDiagnostic(initial,context.observe())
        } finally {context.close()}
    }
}
