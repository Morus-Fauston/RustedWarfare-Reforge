package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class PlayerTerrainKnowledgeTest {
    private fun world(): WorldHandle {
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        return context.world.also { context.close() }
    }
    private fun rules(retention: TerrainRetention = TerrainRetention.MEMORY,
                      source: PathInformationSource = PathInformationSource.PLAYER_KNOWLEDGE,
                      sharing: Boolean = false)=KnowledgeRules(retention,source,sharing)
    private fun store(world: WorldHandle, player:String="p1", rules:KnowledgeRules=rules())=
        PlayerTerrainKnowledge.create(world,player,3,2,rules)

    @Test fun ks07_input_aliases_and_snapshot_mutation_cannot_rewrite_knowledge() {
        val world=world();val store=store(world)
        val projection=mutableListOf(VisibleTerrainCell(0,0,true),VisibleTerrainCell(1,0,false))
        store.accept(world,projection)
        val original=store.snapshot(world)
        projection[0]=VisibleTerrainCell(0,0,false);projection.clear()
        assertEquals(CellKnowledge(true,true),store.snapshot(world).cell(0,0))
        assertFailsWith<UnsupportedOperationException> { (original.cells as MutableList<CellKnowledge>)[0]=CellKnowledge(false,false) }
        assertFailsWith<UnsupportedOperationException> { (original.cells as MutableList<CellKnowledge>).clear() }
        store.accept(world,emptyList())
        assertEquals(CellKnowledge(true,true),original.cell(0,0))
        assertEquals(CellKnowledge(false,true),store.snapshot(world).cell(0,0))
        store.close()
        assertEquals(CellKnowledge(true,true),original.cell(0,0))
    }

    @Test fun ks10_ks11_only_supplied_projection_changes_each_player_store() {
        val world=world();val p1=store(world,"p1");val p2=store(world,"p2")
        p1.accept(world,listOf(VisibleTerrainCell(0,0,true)))
        p2.accept(world,listOf(VisibleTerrainCell(2,1,false)))
        p1.accept(world,emptyList())
        val one=p1.snapshot(world);val two=p2.snapshot(world)
        assertEquals(2L,one.revision);assertEquals(1L,two.revision)
        assertEquals(CellKnowledge(false,true),one.cell(0,0))
        assertEquals(CellKnowledge(false,null),one.cell(2,1))
        assertEquals(CellKnowledge(false,null),two.cell(0,0))
        assertEquals(CellKnowledge(true,false),two.cell(2,1))
        val repeat=store(world,"p1")
        // Hidden true terrain is not an API input: repeating the same projection history must agree.
        repeat.accept(world,listOf(VisibleTerrainCell(0,0,true)));repeat.accept(world,emptyList())
        assertEquals(one.cells,repeat.snapshot(world).cells)
        assertEquals(one.revision,repeat.snapshot(world).revision)
        for(sharing in listOf(false,true)) {
            val independent=store(world,"p3",rules(sharing=sharing))
            assertEquals(List(6) { CellKnowledge(false,null) },independent.snapshot(world).cells)
        }
    }

    @Test fun ks06_ks08_ks12_world_capability_and_closed_precedence_are_exact() {
        val world=world();val other=world()
        assertEquals(world.id,other.id)
        assertNotSame(world,other)
        val store=store(world)
        store.accept(world,listOf(VisibleTerrainCell(0,0,true)))
        val before=store.snapshot(world)
        assertEquals("stale-world",assertFailsWith<KnowledgeException> { store.snapshot(other) }.code)
        assertEquals("stale-world",assertFailsWith<KnowledgeException> { store.accept(other,listOf(VisibleTerrainCell(3,0,true))) }.code)
        assertEquals(before.cells,store.snapshot(world).cells)
        assertEquals(before.revision,store.snapshot(world).revision)
        store.close();store.close()
        assertEquals("closed",assertFailsWith<KnowledgeException> { store.snapshot(world) }.code)
        assertEquals("closed",assertFailsWith<KnowledgeException> { store.snapshot(other) }.code)
        assertEquals("closed",assertFailsWith<KnowledgeException> { store.accept(other,listOf(VisibleTerrainCell(3,0,true))) }.code)
        assertEquals("closed",assertFailsWith<KnowledgeException> { store.accept(world,emptyList()) }.code)
        val reopened=store(other)
        assertEquals(0L,reopened.snapshot(other).revision)
        assertEquals(List(6) { CellKnowledge(false,null) },reopened.snapshot(other).cells)
        assertEquals("stale-world",assertFailsWith<KnowledgeException> { reopened.accept(world,emptyList()) }.code)
        assertEquals(CellKnowledge(true,true),before.cell(0,0))
    }

    @Test fun ks06_and_ks12_projection_validation_is_ordered_and_rejection_is_atomic() {
        val world=world();val store=store(world)
        store.accept(world,listOf(VisibleTerrainCell(0,0,true)))
        val original=store.snapshot(world)
        fun rejected(expected:String,projection:List<VisibleTerrainCell>) {
            assertEquals(expected,assertFailsWith<KnowledgeException> { store.accept(world,projection) }.code)
            val after=store.snapshot(world)
            assertEquals(original.revision,after.revision)
            assertEquals(original.cells,after.cells)
        }
        rejected("out-of-bounds",listOf(VisibleTerrainCell(1,0,false),VisibleTerrainCell(3,0,true)))
        rejected("out-of-bounds",listOf(VisibleTerrainCell(-1,0,true)))
        rejected("out-of-bounds",listOf(VisibleTerrainCell(0,2,true)))
        rejected("duplicate-cell",listOf(VisibleTerrainCell(0,0,true),VisibleTerrainCell(0,0,true)))
        rejected("duplicate-cell",listOf(VisibleTerrainCell(1,0,false),VisibleTerrainCell(1,0,true)))
        rejected("duplicate-cell",listOf(VisibleTerrainCell(0,0,true),VisibleTerrainCell(0,0,false),VisibleTerrainCell(3,0,true)))
        rejected("out-of-bounds",listOf(VisibleTerrainCell(3,0,true),VisibleTerrainCell(3,0,false)))
        for((column,row) in listOf(3 to 0,-1 to 0,0 to 2)) {
            assertEquals("out-of-bounds",assertFailsWith<KnowledgeException> { original.cell(column,row) }.code)
            assertEquals("out-of-bounds",assertFailsWith<KnowledgeException> { original.planningBlocked(column,row) }.code)
        }
        store.accept(world,listOf(VisibleTerrainCell(0,0,true)))
        assertEquals(2L,store.snapshot(world).revision)
        store.accept(world,listOf(VisibleTerrainCell(0,0,true)))
        assertEquals(3L,store.snapshot(world).revision)
    }

    @Test fun ks05_hardcore_empty_projection_forgets_knowledge_but_old_snapshot_survives() {
        val world=world();val store=store(world,rules=rules(TerrainRetention.HARDCORE_FORGET))
        store.accept(world,listOf(VisibleTerrainCell(0,0,true),VisibleTerrainCell(1,0,false)))
        val visible=store.snapshot(world)
        store.accept(world,emptyList())
        val forgotten=store.snapshot(world)
        assertEquals(2L,forgotten.revision)
        assertEquals(List(6) { CellKnowledge(false,null) },forgotten.cells)
        assertFalse(forgotten.planningBlocked(0,0))
        assertEquals(CellKnowledge(true,true),visible.cell(0,0))
        assertTrue(visible.planningBlocked(0,0))
    }

    @Test fun ks02_ks03_ks04_memory_tracks_visible_facts_retains_hidden_and_updates_on_seeing_again() {
        val world=world();val store=store(world)
        store.accept(world,listOf(VisibleTerrainCell(0,0,true),VisibleTerrainCell(1,0,false)))
        val first=store.snapshot(world)
        assertEquals(1L,first.revision)
        assertEquals(listOf(CellKnowledge(true,true),CellKnowledge(true,false),CellKnowledge(false,null),
            CellKnowledge(false,null),CellKnowledge(false,null),CellKnowledge(false,null)),first.cells)
        assertTrue(first.planningBlocked(0,0));assertFalse(first.planningBlocked(2,1))
        store.accept(world,listOf(VisibleTerrainCell(1,0,false)))
        val second=store.snapshot(world)
        assertEquals(2L,second.revision)
        assertEquals(CellKnowledge(false,true),second.cell(0,0))
        assertEquals(CellKnowledge(true,false),second.cell(1,0))
        store.accept(world,listOf(VisibleTerrainCell(0,0,false)))
        val third=store.snapshot(world)
        assertEquals(3L,third.revision)
        assertEquals(CellKnowledge(true,false),third.cell(0,0))
        assertEquals(CellKnowledge(false,false),third.cell(1,0))
        assertEquals(CellKnowledge(true,true),first.cell(0,0))
        assertEquals(CellKnowledge(false,true),second.cell(0,0))
    }

    @Test fun ks01_and_ks09_creation_rejects_invalid_dimensions_player_and_rule_combination() {
        val world=world()
        fun code(expected:String,block:()->Unit) { assertEquals(expected,assertFailsWith<KnowledgeException>(block=block).code) }
        for((width,height) in listOf(0 to 2,-1 to 2,3 to 0,3 to -1,Int.MAX_VALUE to 2)) {
            code("invalid-dimensions") { PlayerTerrainKnowledge.create(world,"p1",width,height,rules()) }
        }
        for(player in listOf(""," ","\t\n")) code("invalid-player") { store(world,player) }
        for(sharing in listOf(false,true)) {
            code("incompatible-rules") { store(world,rules=rules(TerrainRetention.HARDCORE_FORGET,PathInformationSource.ORIGINAL_COMPATIBILITY,sharing)) }
            for((retention,source) in listOf(TerrainRetention.HARDCORE_FORGET to PathInformationSource.PLAYER_KNOWLEDGE,
                TerrainRetention.MEMORY to PathInformationSource.PLAYER_KNOWLEDGE,TerrainRetention.MEMORY to PathInformationSource.ORIGINAL_COMPATIBILITY)) {
                val explicit=rules(retention,source,sharing)
                assertEquals(explicit,store(world,rules=explicit).rules)
            }
        }
    }

    @Test fun ks01_initial_knowledge_is_unknown_and_plans_as_plain_ground() {
        val world=world()
        val snapshot=store(world).snapshot(world)
        assertEquals(world.id,snapshot.worldId)
        assertEquals("p1",snapshot.playerId)
        assertEquals(3,snapshot.width)
        assertEquals(2,snapshot.height)
        assertEquals(0L,snapshot.revision)
        assertEquals(List(6) { CellKnowledge(false,null) },snapshot.cells)
        for(row in 0..1) for(column in 0..2) {
            assertEquals(CellKnowledge(false,null),snapshot.cell(column,row))
            assertFalse(snapshot.planningBlocked(column,row))
        }
    }
}
