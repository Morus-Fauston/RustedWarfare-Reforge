package com.rustedwarfare.reforge.client

import kotlin.test.*

class OfflineStepClockTest {
    @Test fun upshift_preserves_fraction_and_rejected_frame_time_never_changes_the_existing_budget() {
        val clock = OfflineStepClock()
        assertEquals(OfflinePacingFrame(0, .5), clock.advance(1.0 / 120) {})
        clock.setRate(OfflineRate.QUADRUPLE)
        assertEquals(OfflinePacingFrame(2, .5), clock.advance(1.0 / 120) {})
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { clock.advance(invalid) { fail("Invalid time reached a simulation tick") } }
        }
        assertEquals(OfflinePacingFrame(1, 0.0), clock.advance(1.0 / 480) {})
    }

    @Test fun platform_catchup_discard_preserves_the_players_pause_and_rate() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        clock.setPaused(true)
        clock.discardCatchup()
        assertEquals(OfflineRate.QUADRUPLE, clock.rate)
        assertTrue(clock.isPaused)
        assertEquals(OfflinePacingFrame(0, 0.0), clock.advance(120.0) { fail("Suspended world advanced") })
        clock.discardCatchup()
        assertTrue(clock.isPaused)
        clock.setPaused(false)
        assertEquals(OfflinePacingFrame(4, 0.0), clock.advance(1.0 / 60) {})
    }

    @Test fun a_failed_callback_is_never_counted_as_a_completed_step() {
        val clock = OfflineStepClock()
        val original = IllegalStateException("failed complete step")
        assertSame(original, assertFailsWith<IllegalStateException> { clock.advance(1.0 / 60) { throw original } })
        assertEquals(OfflinePacingFrame(1, 0.0), clock.advance(0.0) {})
    }

    @Test fun measured_rate_reports_completed_steps_and_exposes_slow_frames_instead_of_claiming_requested_speed() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        assertNull(clock.actualMultiplier)
        repeat(64) { clock.advance(1.0 / 128) {} }
        assertEquals(4.0, clock.actualMultiplier)
        clock.setPaused(true)
        assertEquals(0.0, clock.actualMultiplier)
        clock.reset()
        assertNull(clock.actualMultiplier)
        clock.setRate(OfflineRate.QUADRUPLE)
        clock.advance(.5) {}
        assertEquals(8.0 / 30.0, clock.actualMultiplier)
        assertTrue(checkNotNull(clock.actualMultiplier) < clock.rate.multiplier)
    }

    @Test fun pausing_keeps_the_rate_without_scheduling_paused_wall_time_or_a_resume_catchup() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        var completed = 0
        assertEquals(OfflinePacingFrame(8, 112.0), clock.advance(.5) { completed++ })
        clock.setPaused(true)
        assertEquals(OfflinePacingFrame(0, 112.0), clock.advance(60.0) { completed++ })
        assertEquals(8, completed)
        assertEquals(OfflineRate.QUADRUPLE, clock.rate)
        clock.setPaused(false)
        assertEquals(OfflinePacingFrame(0, 0.0), clock.advance(0.0) { completed++ })
        clock.reset()
        assertEquals(OfflineRate.NORMAL, clock.rate)
        assertFalse(clock.isPaused)
        assertEquals(OfflinePacingFrame(1, 0.0), clock.advance(1.0 / 60) { completed++ })
    }

    @Test fun ordinary_frames_keep_bounded_backlog_but_slowing_discards_old_wall_catchup() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        assertEquals(OfflinePacingFrame(8, 112.0), clock.advance(.5) {})
        assertEquals(OfflinePacingFrame(8, 104.0), clock.advance(0.0) {})
        clock.setRate(OfflineRate.NORMAL)
        assertEquals(OfflinePacingFrame(0, 0.0), clock.advance(0.0) {})
        assertEquals(OfflinePacingFrame(1, 0.0), clock.advance(1.0 / 60) {})
    }

    @Test fun rates_schedule_more_complete_steps_without_changing_the_step_callback() {
        for ((rate, expected) in listOf(OfflineRate.NORMAL to 10, OfflineRate.DOUBLE to 20, OfflineRate.QUADRUPLE to 40)) {
            val clock = OfflineStepClock()
            clock.setRate(rate)
            var completed = 0
            repeat(10) {
                val frame = clock.advance(1.0 / 60) { completed++ }
                assertEquals(rate.multiplier, frame.executedSteps)
                assertEquals(0.0, frame.pendingSteps)
            }
            assertEquals(expected, completed)
        }
    }

    @Test fun multi_substep_4x_frame_projects_observation_only_on_last_substep_while_preserving_events() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        val flags4 = mutableListOf<Boolean>()
        val frame4 = clock.advanceWithLastFlag(1.0 / 60.0) { isLast -> flags4 += isLast }
        assertEquals(4, frame4.executedSteps)
        assertEquals(listOf(false, false, false, true), flags4)

        val flags8 = mutableListOf<Boolean>()
        val frame8 = clock.advanceWithLastFlag(0.5) { isLast -> flags8 += isLast }
        assertEquals(8, frame8.executedSteps)
        assertEquals(listOf(false, false, false, false, false, false, false, true), flags8)

        val fixtures = java.nio.file.Path.of(
            System.getProperty("p001.inputs.dir")
                ?: java.nio.file.Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
                    .resolve("content-movement-knowledge/P001-移动沙盒输入").toString()
        )
        val loaded = ControlledScenario.load(fixtures)
        val lightweightEvents = mutableListOf<String>()
        val lightweightSink = object : ClientEvidence {
            override val capturesObservations: Boolean get() = false
            override fun record(kind: String, values: Map<String, Any?>) {
                lightweightEvents += kind
            }
        }
        SandboxSession(loaded, lightweightSink, SandboxOptions(deploymentEnabled = true)).use { session ->
            val tankType = loaded.unitDefinitions.keys.first { loaded.unitDefinitions.getValue(it).movement.type != com.rustedwarfare.reforge.core.content.MovementType.NONE }
            val spawnEntries = (0 until 4).map { i ->
                com.rustedwarfare.reforge.core.sim.SandboxCommand.Deployment(
                    "t$i",
                    com.rustedwarfare.reforge.core.sim.SandboxCommand.Spawn(
                        tankType,
                        com.rustedwarfare.reforge.core.sim.Position(60.0 + i * 24.0, 60.0),
                        "p1",
                    ),
                )
            }
            session.submitSandboxBatch(com.rustedwarfare.reforge.core.sim.SandboxCommand.SpawnBatch(spawnEntries))
            session.tick()

            val framesBefore = session.publishedFrameCount
            // Select all friendly units in box -> lightweight sink must batch into 1 publishFrame!
            session.selectBox(
                com.rustedwarfare.reforge.core.sim.Position(0.0, 0.0),
                com.rustedwarfare.reforge.core.sim.Position(2000.0, 2000.0),
            )
            assertTrue(session.selectedIds.size >= 4, "Expected >= 4 selected units, got ${session.selectedIds.size}")
            assertEquals(framesBefore + 1L, session.publishedFrameCount,
                "Multi-unit selectBox with non-observation lightweight sink must publish only once")

            val beforeMoveFrames = session.publishedFrameCount
            session.move(com.rustedwarfare.reforge.core.sim.Position(180.0, 120.0))
            assertEquals(beforeMoveFrames + 1L, session.publishedFrameCount,
                "Multi-unit move dispatch with non-observation lightweight sink must publish only once")

            session.saveGroup(1)
            val beforeRecallFrames = session.publishedFrameCount
            session.recallGroup(1)
            assertEquals(beforeRecallFrames + 1L, session.publishedFrameCount,
                "Multi-unit recallGroup with non-observation lightweight sink must publish only once")

            // Simulate a 4x render frame (4 substeps): only the last substep should materialize & publish,
            // while events emitted on substep 0 (e.g. movement-started) remain visible in the end-of-frame observation!
            val pubBefore4x = session.publishedFrameCount
            val matBefore4x = session.materializedObservationCountForTest
            val stepBefore = session.nextStep
            val stepClock = OfflineStepClock().apply { setRate(OfflineRate.QUADRUPLE) }
            stepClock.advanceWithLastFlag(1.0 / 60.0) { isLast ->
                session.tick(publishRenderFrame = isLast || session.requiresPerStepObservation(false))
            }
            assertEquals(stepBefore + 4L, session.nextStep, "All 4 simulation substeps must advance")
            assertEquals(stepBefore + 3L, session.observation.stepIndex, "Final published frame must reflect end-of-frame step")
            assertEquals(1L, session.publishedFrameCount - pubBefore4x,
                "4x render frame must execute publishFrame() only ONCE on the final substep")
            assertEquals(1L, session.materializedObservationCountForTest - matBefore4x,
                "4x render frame must materialize full sorted Observation only ONCE on the final substep")
            assertTrue(session.observation.events.any { it.kind == "movement-started" },
                "Events emitted on intermediate substeps must be preserved in end-of-frame PlayerObservation, got=${session.observation.events}")
            assertFalse("observation" in lightweightEvents,
                "Lightweight non-observation sink must never serialize per-step observations")
        }
    }

    @Test fun frame_budget_guard_guarantees_forward_progress_and_carries_remaining_substeps() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        val seen = mutableListOf<Int>()
        val frame1 = clock.advanceWithLastFlag(
            realSeconds = 1.0 / 60.0,
            canContinueFrame = { completed -> completed < 2 },
        ) { _ ->
            seen += 1
        }
        assertEquals(2, frame1.executedSteps, "Should stop after budget limit while advancing >= 1 step")
        assertEquals(2.0, frame1.pendingSteps, 1e-9, "Remaining substeps must stay in pending backlog")
        assertEquals(2, seen.size)

        val frame2 = clock.advanceWithLastFlag(realSeconds = 1.0 / 60.0) { _ -> seen += 1 }
        assertEquals(6, frame2.executedSteps, "Subsequent cheap frame should drain carried backlog")
        assertEquals(0.0, frame2.pendingSteps, 1e-9)
        assertEquals(8, seen.size)
    }
}
