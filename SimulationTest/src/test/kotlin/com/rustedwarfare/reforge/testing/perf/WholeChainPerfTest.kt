package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.testing.MotionCenterGeometry
import kotlin.test.*

class WholeChainPerfTest {
    @Test fun indexed_pairing_rejects_duplicate_or_missing_previous_ids_and_accepts_reordering() {
        val run = WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 10, TerrainRetention.MEMORY))
        fun initial(units: List<UnitObservation>): WholeChainRun {
            val old = run.initial
            return run.copy(initial = Observation(old.sampleId, old.worldId, old.scenarioId, old.stepIndex,
                old.simulationTime, units, old.events, old.actionResults))
        }
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(initial(run.initial.units.dropLast(1) + run.initial.units.first())) }
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(initial(run.initial.units.dropLast(1))) }
        val reversed = initial(run.initial.units.reversed()).copy(steps = run.steps.map { step ->
            val old = step.observation
            step.copy(observation = Observation(old.sampleId, old.worldId, old.scenarioId, old.stepIndex,
                old.simulationTime, old.units.reversed(), old.events, old.actionResults))
        })
        WholeChainPerf.qualify(reversed)
    }
    private fun changeUnit(run:WholeChainRun,index:Int,change:(UnitObservation)->UnitObservation):WholeChainRun {
        val steps=run.steps.toMutableList();val old=steps[index];val o=old.observation
        steps[index]=old.copy(observation=Observation(o.sampleId,o.worldId,o.scenarioId,o.stepIndex,o.simulationTime,
            o.units.map(change),o.events,o.actionResults))
        return run.copy(steps=steps)
    }
    @Test fun normal_clear_rejects_residual_command_and_old_world_C_or_changed_heading_and_cg() {
        val run=WholeChainPerf.capture(WholeChainPerf.fixture("OPEN",1,TerrainRetention.MEMORY))
        val index=run.steps.indexOfFirst { s->s.observation.events.any { it.kind=="movement-arrived" } };assertTrue(index>0)
        val goal=run.fixture.goals.getValue("u0");val before=run.steps[index-1].observation.units.single()
        for(change in listOf<(UnitObservation)->UnitObservation>({it.copy(moveTarget=goal,pathPoints=listOf(goal))},{it.copy(pathPoints=listOf(goal))})) {
            val error=assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(changeUnit(run,index,change)) }
            assertTrue(error.message.orEmpty().contains("Normal clear retained target/path"),error.message)
        }
        for((change,message)in listOf<Pair<(UnitObservation)->UnitObservation,String>>(
            ({u:UnitObservation->u.copy(speed=before.speed-.02)}) to "Wrong post-command deceleration",
            ({u:UnitObservation->u.copy(heading=u.heading+1)}) to "Coasting changed heading",
            ({u:UnitObservation->u.copy(angularVelocity=u.angularVelocity+1)}) to "Wrong post-command deceleration")) {
            val error=assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(changeUnit(run,index,change)) }
            assertTrue(error.message.orEmpty().contains(message),error.message)
        }
    }
    @Test fun synthetic_single_goal_repair_still_requires_same_actual_origin_action_goal_and_zero_prefix() {
        val run=WholeChainPerf.capture(WholeChainPerf.fixture("HIDDEN",1,TerrainRetention.MEMORY))
        val index=run.steps.indexOfFirst { s->s.samples.any { it.reason!=PathSearchReason.USER_MOVE } };assertTrue(index>0)
        val rows=run.steps.toMutableList();val step=rows[index]
        val direct=step.samples.single().copy(reason=PathSearchReason.ROUTE_REPAIR,preservedPrefixPointCount=0)
        rows[index]=step.copy(samples=listOf(direct));val synthetic=run.copy(steps=rows)
        // Synthetic validator branch control, not evidence that this runtime took the private branch.
        WholeChainPerf.qualify(synthetic)
        for((bad,message)in listOf(
            direct.copy(searchStart=Position(250.0,270.0)) to "Invalid repair anchor/prefix",
            direct.copy(actionId="another-move") to "Search provenance",
            direct.copy(searchTarget=Position(510.0,250.0)) to "Search target differs",
            direct.copy(preservedPrefixPointCount=1) to "Invalid repair anchor/prefix")) {
            val changed=rows.toMutableList();changed[index]=rows[index].copy(samples=listOf(bad))
            val error=assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(synthetic.copy(steps=changed)) }
            assertTrue(error.message.orEmpty().contains(message),error.message)
        }
    }
    @Test fun fifty_entities_execute_fifty_requests_on_one_boundary_and_reach_terminal() {
        val directory = java.nio.file.Files.createTempDirectory("s08-open50-")
        println("s08-raw=$directory")
        val run = java.nio.file.Files.newBufferedWriter(directory.resolve("raw.jsonl")).use { writer ->
            WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 50, TerrainRetention.MEMORY)) { writer.write(it.toString()); writer.newLine(); writer.flush() }
        }
        assertEquals(50, run.requests)
        assertEquals(50, run.searches.count { it.reason.name == "USER_MOVE" })
        assertEquals(setOf(0L), run.searches.map { it.executionStep }.toSet())
        assertEquals(50, run.arrived.size)
        assertTrue(run.reuseEntitySteps > 0)
        WholeChainPerf.qualify(run)
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(requests = 49)) }
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = run.steps.dropLast(1))) }
        val steps = run.steps.toMutableList()
        steps[0] = steps[0].copy(samples = steps[0].samples.dropLast(1))
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = steps)) }
    }

    @Test fun empty_distributions_are_explicitly_unavailable_and_nearest_rank_is_fixed() {
        assertEquals("{\"n\":0,\"p50\":null,\"p95\":null,\"max\":null}", WholeChainPerf.stats(emptyList()).toString())
        assertEquals("{\"n\":5,\"p50\":30,\"p95\":50,\"max\":50}", WholeChainPerf.stats(listOf(50, 10, 30, 40, 20)).toString())
    }

    @Test fun qualification_rejects_nonfinite_motion_missing_required_search_and_retention_corruption() {
        val run = WholeChainPerf.capture(WholeChainPerf.fixture("HIDDEN", 1, TerrainRetention.MEMORY))
        // capture already qualified the unchanged run. Select an observed,
        // publicly necessary search, rather than assuming this fixture must repair.
        val repair = run.steps.indices.firstOrNull { index ->
            val previous=if(index==0)run.initial else run.steps[index-1].observation
            val known=if(index==0)run.initialKnown else run.steps[index-1].known
            val old=previous.units.single();val first=old.pathPoints.firstOrNull()
            val walls=known.indices.filter { known[it]==true }.map { intArrayOf(it%50,it/50) }
            first!=null && run.steps[index].samples.any { it.reason.name in setOf("ROUTE_REPAIR","KNOWLEDGE_REPLAN") } &&
                !MotionCenterGeometry.centerPath(listOf(old.position,first),walls,50,40,20,20,11.0)
        }
        assertNotNull(repair);assertTrue(repair>0)
        println("required-search-step=$repair samples=${run.steps[repair].samples}")
        val steps = run.steps.toMutableList()
        steps[repair] = steps[repair].copy(samples = emptyList())
        val missing=assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = steps)) }
        assertTrue(missing.message.orEmpty().contains("Missing pre-move repair"),missing.message)
        val corrupt = run.steps.toMutableList()
        corrupt[0] = corrupt[0].copy(known = List(2000) { true })
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = corrupt)) }
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(arrived = emptySet())) }
        val first = run.steps.first()
        val observed = first.observation
        val invalid = com.rustedwarfare.reforge.core.sim.Observation(observed.sampleId, observed.worldId,
            observed.scenarioId, observed.stepIndex, observed.simulationTime,
            observed.units.map { it.copy(heading = Double.NaN) }, observed.events, observed.actionResults)
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = listOf(first.copy(observation = invalid)) + run.steps.drop(1))) }
    }

    @Test fun altered_non_user_search_anchor_and_prefix_are_rejected() {
        val run = WholeChainPerf.capture(WholeChainPerf.fixture("HIDDEN", 1, TerrainRetention.MEMORY))
        val index = run.steps.indexOfFirst { it.samples.any { s -> s.reason.name != "USER_MOVE" } }
        assertTrue(index > 0)
        for (badPrefix in listOf(false, true)) {
            val steps = run.steps.toMutableList(); val step = steps[index]
            steps[index] = step.copy(samples = step.samples.map {
                if (badPrefix) it.copy(preservedPrefixPointCount = 999)
                else it.copy(searchStart = com.rustedwarfare.reforge.core.sim.Position(250.0, 270.0))
            })
            assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = steps)) }
        }
    }

    @Test fun loss_of_sight_counts_transitions_equally_for_identical_open_motion() {
        val memory = WholeChainPerf.summary(WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 1, TerrainRetention.MEMORY)))
        val hardcore = WholeChainPerf.summary(WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 1, TerrainRetention.HARDCORE_FORGET)))
        assertEquals(memory["lostSightCellTransitions"], hardcore["lostSightCellTransitions"])
    }
}
