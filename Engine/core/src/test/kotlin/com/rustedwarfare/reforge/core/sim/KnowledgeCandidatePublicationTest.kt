package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

/** Publication seam test: the observer fails after complete, valid knowledge preparation. */
class KnowledgeCandidatePublicationTest {
    @Test fun observer_failure_releases_candidate_stores_and_retry_uses_the_original_vision_state() {
        val input = TestContentFactory.openScenario()
        val origin = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val world = origin.world
        val steps = CandidateSteps()
        val rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, true)
        val spawn = input.units.single()
        steps.current.units[spawn.unitId] = MutableUnit(spawn.unitId, spawn.ownerPlayerId, spawn.typeId,
            input.unitDefinitions.getValue(spawn.typeId), spawn.position, spawn.heading)
        fun prepare(state: StepState, epoch: Long): AutomaticKnowledgeCommit {
            val map = input.map
            val vision = AutomaticVision.project(state.automaticVisionState, world.id, epoch,
                map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight, input.players,
                state.units.values.map { VisionSource(it.ownerPlayerId, it.position, it.definition.fogOfWarSightRange) }, true)
            val gids = map.groundGids
            val stores = vision.views.mapValues { (player, view) ->
                val old = state.knowledgeStores?.get(player) ?: PlayerTerrainKnowledge.create(world, player,
                    map.widthTiles, map.heightTiles, rules)
                try {
                    old.projected(world, view.effectiveCells.map { VisibleTerrainCell(it.column, it.row,
                        map.blocking.isBlocked(it.column, it.row), gids[it.row * map.widthTiles + it.column]) }).store
                } finally { if (state.knowledgeStores == null) old.close() }
            }
            return AutomaticKnowledgeCommit(stores, vision.views, emptyMap(), vision.state)
        }
        val initial = prepare(steps.current, 0)
        steps.current.knowledgeStores = initial.stores
        steps.current.automaticVisionSnapshots = initial.views
        steps.current.automaticVisionState = initial.visionState
        val before = steps.current
        val snapshots = initial.stores.mapValues { it.value.snapshot(world) }
        var candidate: AutomaticKnowledgeCommit? = null
        fun update(step: CandidateStep) {
            step.state.units.getValue(spawn.unitId).position = Position(120.0, 100.0)
            candidate = prepare(step.state, 1)
            step.stageKnowledge(candidate)
        }
        try {
            val failure = assertFailsWith<IllegalStateException> {
                steps.advance(input, Step(0, 1.0, "sim-tick"), emptyList(),
                    { _, _, _ -> error("observer-failed-after-knowledge") }, ::update)
            }
            assertEquals("observer-failed-after-knowledge", failure.message)
            assertSame(before, steps.current)
            assertSame(initial.visionState, steps.current.automaticVisionState)
            assertEquals(spawn.position, steps.current.units.getValue(spawn.unitId).position)
            for ((player, snapshot) in snapshots) assertSame(snapshot, initial.stores.getValue(player).snapshot(world))
            for (store in checkNotNull(candidate).stores.values)
                assertEquals("closed", assertFailsWith<KnowledgeException> { store.snapshot(world) }.code)
            steps.advance(input, Step(0, 1.0, "sim-tick"), emptyList(), { index, time, _ ->
                Observation("candidate-$index", world.id, input.scenarioId, index, time,
                    origin.observe().units.map { it.copy(position = steps.current.units.getValue(it.unitId).position) }, emptyList(), emptyList())
            }, ::update)
            assertNotSame(before, steps.current)
            assertSame(checkNotNull(candidate).visionState, steps.current.automaticVisionState)
            assertEquals(Position(120.0, 100.0), steps.current.units.getValue(spawn.unitId).position)
            val fresh = AutomaticVision.project(null, world.id, 1, input.map.widthTiles, input.map.heightTiles,
                input.map.tileWidth, input.map.tileHeight, input.players,
                listOf(VisionSource(spawn.ownerPlayerId, Position(120.0, 100.0),
                    input.unitDefinitions.getValue(spawn.typeId).fogOfWarSightRange)), true)
            for (player in input.players) {
                val committed = checkNotNull(steps.current.automaticVisionSnapshots).getValue(player.playerId)
                assertEquals(fresh.views.getValue(player.playerId).effectiveCells, committed.effectiveCells)
                assertEquals(1L, committed.epoch)
                assertEquals(snapshots.getValue(player.playerId).revision + 1,
                    checkNotNull(steps.current.knowledgeStores).getValue(player.playerId).snapshot(world).revision)
            }
            for ((player, snapshot) in snapshots) assertEquals(1L, snapshot.revision)
        } finally {
            steps.current.knowledgeStores?.values?.forEach { it.close() }
            origin.close()
        }
    }
}
