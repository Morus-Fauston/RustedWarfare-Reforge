package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MultiBuilderConstructionTest {
    @Test fun stopping_one_builder_preserves_the_other_and_a_reclaim_tail_rebuilds_only_on_the_next_update() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            var step = 0L
            fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
            for ((index, worker) in listOf("a", "b").withIndex()) assertIs<ActionResult.Accepted>(context.submit(
                BuildAction("build-$worker", "p1", worker, "landFactory", Position(190.0, 170.0),
                    (index + 1).toString(16).padStart(32, '0'), context.world)))
            val target = tick().units.single { it.typeId == "landFactory" }.unitId
            repeat(100) { tick() }
            val beforeStop = context.observe().units.single { it.unitId == target }.constructionProgress
            assertTrue(beforeStop > 0f && beforeStop < 1f)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-a", "p1", "a", context.world)))
            val continuing = tick()
            assertTrue(continuing.units.single { it.unitId == "a" }.tasks.isEmpty())
            assertEquals(target, continuing.units.single { it.unitId == "b" }.tasks.single().targetUnitId)
            assertTrue(continuing.units.single { it.unitId == target }.constructionProgress > beforeStop)
            assertEquals(1050.0, context.observePlayer(context.world, "p1").credits)
            assertIs<ActionResult.Accepted>(context.submit(ResumeConstructionAction("resume-a", "p1", "a", target, context.world)))
            while (context.observe().units.single { it.unitId == target }.constructionProgress < 1f && step < 1200L) tick()
            assertEquals(1f, context.observe().units.single { it.unitId == target }.constructionProgress)
            for (worker in listOf("a", "b")) assertIs<ActionResult.Accepted>(context.submit(StopAction("done-$worker", "p1", worker, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("reclaim", "p1", "a", target, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("rebuild", "p1", "a", "landFactory", Position(190.0, 170.0),
                "3".repeat(32), context.world, append = true)))
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("tail", "p1", "a", Position(450.0, 290.0), true, context.world)))
            var terminal: Observation? = null
            while (terminal == null && step < 3000L) {
                val frame = tick()
                if (frame.units.none { it.unitId == target }) terminal = frame
            }
            val removed = assertNotNull(terminal)
            val worker = removed.units.single { it.unitId == "a" }
            assertEquals(listOf("rebuild", "tail"), worker.tasks.map { it.actionId })
            assertNull(worker.moveTarget)
            assertTrue(removed.events.none { it.kind == "construction-created" })
            assertEquals(1, removed.events.count { it.kind == "building-reclaimed" })
            assertEquals(1610.0000083446503, context.observePlayer(context.world, "p1").credits)
            val rebuilt = tick()
            assertEquals(1, rebuilt.events.count { it.kind == "construction-created" })
            val next = rebuilt.units.single { it.typeId == "landFactory" }
            assertNotEquals(target, next.unitId)
            assertEquals(0f, next.constructionProgress)
            assertEquals(Position(190.0, 170.0), next.position)
            assertEquals(listOf(UnitTaskKind.CONSTRUCT, UnitTaskKind.PATROL), rebuilt.units.single { it.unitId == "a" }.tasks.map { it.kind })
            assertEquals(910.0000083446503, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun two_builders_complete_and_reclaim_one_project_once_then_keep_their_queued_patrols() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            for ((index, worker) in listOf("a", "b").withIndex())
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build-$worker", "p1", worker,
                    "landFactory", Position(190.0, 170.0), (index + 1).toString(16).padStart(32, '0'), context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            val first = context.observePlayer(context.world, "p1")
            val target = first.units.single { it.typeId == "landFactory" }.unitId
            assertEquals(1050.0, first.credits)
            var created = first.events.count { it.kind == "construction-created" }
            var completed = first.events.count { it.kind == "construction-completed" }
            var reclaimed = first.events.count { it.kind == "building-reclaimed" }
            for (worker in listOf("a", "b")) {
                assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("reclaim-$worker", "p1", worker,
                    target, context.world, append = true)))
                assertIs<ActionResult.Accepted>(context.submit(PatrolAction("patrol-$worker", "p1", worker,
                    Position(450.0, 290.0), true, context.world)))
            }
            var terminalStep: Long? = null
            for (step in 1L..5000L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                val owner = context.observePlayer(context.world, "p1")
                created += owner.events.count { it.kind == "construction-created" }
                completed += owner.events.count { it.kind == "construction-completed" }
                reclaimed += owner.events.count { it.kind == "building-reclaimed" }
                if (owner.units.none { it.unitId == target }) {
                    terminalStep = step
                    assertEquals(1, created)
                    assertEquals(1, completed)
                    assertEquals(1, reclaimed)
                    assertEquals(1610.0000083446503, owner.credits)
                    for (worker in owner.units) {
                        assertEquals("patrol-${worker.unitId}", worker.tasks.last().actionId)
                        assertEquals(UnitTaskKind.PATROL, worker.tasks.last().kind)
                        assertTrue(worker.tasks.dropLast(1).all { it.kind == UnitTaskKind.RECLAIM &&
                            it.actionId == "reclaim-${worker.unitId}" && it.targetUnitId == null },
                            "A dead target is redacted from the owner task view: ${worker.tasks}")
                        assertTrue(context.observe().units.single { it.unitId == worker.unitId }.tasks.dropLast(1)
                            .all { it.kind == UnitTaskKind.RECLAIM && it.targetUnitId == target },
                            "The trusted host retains the real stale target until this worker updates")
                        assertNull(worker.moveTarget, "The newly exposed tail must not run in the reclaim terminal update")
                    }
                    assertTrue(owner.units.any { it.tasks.size == 1 }, "The terminal worker must pop its own reclaim head")
                    break
                }
            }
            val terminal = assertNotNull(terminalStep, "The public construction and reclaim chain must really reach its terminal")
            for (step in terminal + 1..terminal + 10) {
                context.advance(Step(step, 1.0, "sim-tick"))
                val owner = context.observePlayer(context.world, "p1")
                assertEquals(1610.0000083446503, owner.credits)
                assertTrue(owner.events.none { it.kind == "construction-completed" || it.kind == "building-reclaimed" })
                assertTrue(owner.units.all { it.tasks.size == 1 && it.tasks.single().kind == UnitTaskKind.PATROL },
                    "Earlier workers observe the dead target on their next update, preserving their tails")
            }
        } finally { context.close() }
    }

    @Test fun the_normal_builder_panel_keeps_owner_queue_counts_and_current_work_beside_the_menu() {
        SandboxSession(scene()).use { session ->
            assertNull(session.selectedWorkerQueueText)
            session.select(Position(120.0, 170.0))
            assertEquals("Tasks 0\nIdle", session.selectedWorkerQueueText)
            repeat(29) { session.build("landFactory", Position(190.0, 170.0), true) }
            assertEquals("Tasks 29\nBuild Land factory", session.selectedWorkerQueueText)
            session.build("landFactory", Position(450.0, 170.0), true)
            assertEquals("Tasks 29\nBuild Land factory", session.selectedWorkerQueueText, "Do not expose the spare slot as a thirtieth task")
            session.select(Position(120.0, 210.0))
            repeat(28) { session.build("landFactory", Position(190.0, 170.0), true) }
            session.select(Position(120.0, 170.0), SelectionMode.ADD)
            assertEquals("Tasks 28-29 each\nBuild x2", session.selectedWorkerQueueText)
            assertEquals(3, session.constructionActions.size, "Queue feedback and common actions coexist")
            session.stop()
            assertEquals("Tasks 0 each\nIdle x2", session.selectedWorkerQueueText)
            session.build("landFactory", Position(190.0, 170.0))
            assertTrue(session.selectedIds.isEmpty())
            assertNull(session.selectedWorkerQueueText)
        }
    }

    @Test fun a_card_held_before_patrol_cannot_replace_the_new_mode_on_release() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false })
            var choices = 0
            val card = ConstructionButtonInput({ session }, { true }, "landFactory", {
                choices++; assertTrue(input.beginConstruction(it))
            }, modeRevision = { input.modeRevision })
            val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 54f); addListener(card) }
            fun fire(down: Boolean) = actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
                type = if (down) com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown else com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp
                button = com.badlogic.gdx.Input.Buttons.LEFT; pointer = 0; stageX = 10f; stageY = 10f; touchFocus = false
            })
            fire(true)
            assertTrue(input.keyDown(com.badlogic.gdx.Input.Keys.P))
            assertTrue(input.patrolMode)
            fire(false)
            assertEquals(0, choices, "A release owned by the old mode must not choose construction")
            assertTrue(input.patrolMode)
            assertNull(input.constructionTypeId)
            assertTrue(session.observation.units.all { it.tasks.isEmpty() })
            fire(true); fire(false)
            assertEquals(1, choices, "A fresh click can switch from patrol to construction")
            assertFalse(input.patrolMode)
            assertEquals("landFactory", input.constructionTypeId)
        }
    }

    @Test fun a_construction_card_dragged_out_and_back_never_revives_the_old_press() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            var choices = 0
            val card = ConstructionButtonInput({ session }, { true }, "landFactory", { choices++ })
            val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 54f); addListener(card) }
            fun fire(kind: com.badlogic.gdx.scenes.scene2d.InputEvent.Type, x: Float) = actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
                type = kind; button = com.badlogic.gdx.Input.Buttons.LEFT; pointer = 0
                stageX = x; stageY = 10f; touchFocus = false
            })
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown, 10f)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDragged, 100f)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDragged, 10f)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp, 10f)
            assertEquals(0, choices)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown, 10f)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp, 10f)
            assertEquals(1, choices)
        }
    }

    @Test fun a_partially_full_batch_distinguishes_the_new_item_from_the_full_worker() {
        val points = mutableListOf<Map<String, Any?>>()
        val evidence = ClientEvidence { kind, values -> if (kind == "construction-point") points += values }
        SandboxSession(scene(), evidence).use { session ->
            session.select(Position(120.0, 170.0))
            repeat(29) { session.build("landFactory", Position(190.0, 170.0), true) }
            session.select(Position(120.0, 210.0))
            repeat(28) { session.build("landFactory", Position(190.0, 170.0), true) }
            session.select(Position(120.0, 170.0), SelectionMode.ADD)
            val firstTasks = session.observation.units.single { it.unitId == "a" }.tasks
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { key -> key == com.badlogic.gdx.Input.Keys.SHIFT_LEFT }, evidence = evidence)
            assertTrue(input.beginConstruction("landFactory"))
            val x = map.x(450.0).toInt(); val y = map.y(170.0).toInt()
            input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
            input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
            assertEquals("Added 1, queue full 1, rejected 0, skipped 0", session.playerFeedback)
            assertEquals(firstTasks, session.observation.units.single { it.unitId == "a" }.tasks)
            assertEquals(29, session.observation.units.single { it.unitId == "b" }.tasks.size)
            assertEquals(Position(450.0, 170.0), session.observation.units.single { it.unitId == "b" }.tasks.last().target)
            assertEquals(2, points.single()["accepted"])
            assertEquals(1, points.single()["added"])
            assertEquals(1, points.single()["queueFull"])
            assertEquals("landFactory", input.constructionTypeId)
        }
    }

    @Test fun a_single_full_builder_receives_the_same_player_facing_capacity_notice() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            repeat(29) { session.build("landFactory", Position(190.0, 170.0), true) }
            val before = session.observation.units.single { it.unitId == "a" }.tasks
            assertIs<ActionResult.Accepted>(session.build("landFactory", Position(450.0, 170.0), true))
            assertEquals("Construction queue is full", session.playerFeedback)
            assertEquals(before, session.observation.units.single { it.unitId == "a" }.tasks)
            assertEquals(1, assertNotNull(session.lastCommandBatch).queueFull)
            assertEquals(0, session.lastCommandBatch?.added)
        }
    }

    @Test fun a_shift_click_on_two_full_queues_reports_no_added_item_and_keeps_the_intents_unchanged() {
        val points = mutableListOf<Map<String, Any?>>()
        val evidence = ClientEvidence { kind, values -> if (kind == "construction-point") points += values }
        SandboxSession(scene(), evidence).use { session ->
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 210.0), SelectionMode.ADD)
            repeat(29) { assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 170.0), true)) }
            val before = session.observation.units.associate { it.unitId to it.tasks }
            assertTrue(before.values.all { it.size == 29 })
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { key -> key == com.badlogic.gdx.Input.Keys.SHIFT_LEFT }, evidence = evidence)
            assertTrue(input.beginConstruction("landFactory"))
            val x = map.x(450.0).toInt(); val y = map.y(170.0).toInt()
            input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
            input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
            assertEquals(before, session.observation.units.associate { it.unitId to it.tasks })
            assertEquals("landFactory", input.constructionTypeId)
            assertEquals(setOf("a", "b"), session.selectedIds)
            assertEquals("Construction queue is full", session.playerFeedback)
            assertEquals(2, assertNotNull(session.lastCommandBatch).accepted, "Core full append remains an Accepted command")
            assertEquals(2, points.single()["accepted"])
            assertEquals(0, points.single()["added"])
            assertEquals(2, points.single()["queueFull"])
            assertEquals(1750.0, session.observation.credits)
        }
    }

    @Test fun two_selected_workers_reclaim_real_health_then_both_repair_the_same_building() {
        val base = scene()
        val withFactory = base.copy(units = base.units +
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0),
            combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L, "factory" to 3L), 4L))
        SandboxSession(withFactory).use { session ->
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 210.0), SelectionMode.ADD)
            assertTrue(session.canMaintain, "The common builder menu includes maintenance")
            assertIs<ActionResult.Accepted>(session.reclaim("factory"))
            assertEquals(2, assertNotNull(session.lastCommandBatch).accepted)
            assertTrue(session.observation.units.filter { it.typeId == "builder" }.all {
                it.tasks.single().kind == UnitTaskKind.RECLAIM && it.tasks.single().targetUnitId == "factory" })
            repeat(80) { session.tick() }
            val injured = session.observation.units.single { it.unitId == "factory" }.health!!
            assertTrue(injured < 1200.0 && injured > 0.0, "Use actual reclaim work, never injected health")
            session.stop()
            assertIs<ActionResult.Accepted>(session.command(Position(190.0, 170.0)))
            assertEquals(2, assertNotNull(session.lastCommandBatch).accepted)
            assertTrue(session.observation.units.filter { it.typeId == "builder" }.all {
                it.tasks.single().kind == UnitTaskKind.CONSTRUCT && it.tasks.single().targetUnitId == "factory" })
            repeat(300) { session.tick() }
            assertEquals(1200.0, session.observation.units.single { it.unitId == "factory" }.health)
            assertTrue(session.observation.units.filter { it.typeId == "builder" }.all { it.tasks.isEmpty() })
            assertEquals(1750.0, session.observation.credits)
        }
    }

    @Test fun a_placement_press_cannot_be_transferred_across_selection_sessions_or_a_viewport_exit() {
        for (change in listOf("selection", "session", "outside")) {
            val sessions = mutableListOf<SandboxSession>()
            var current = SandboxSession(scene()).also { sessions += it }
            fun selectBoth() {
                current.select(Position(120.0, 170.0))
                current.select(Position(120.0, 210.0), SelectionMode.ADD)
            }
            selectBoth()
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ current }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false }, insideViewport = { x, y -> x in 24 until 624 && y in 101..500 })
            val x = map.x(190.0).toInt(); val y = map.y(170.0).toInt()
            try {
                assertTrue(input.beginConstruction("landFactory"))
                assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
                when (change) {
                    "selection" -> selectBoth()
                    "session" -> { current.close(); current = SandboxSession(scene()).also { sessions += it }; selectBoth() }
                    else -> { input.touchDragged(700, y, 0); input.touchDragged(x, y, 0) }
                }
                input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
                assertTrue(current.observation.units.all { it.tasks.isEmpty() }, "Stale placement after $change")
                assertTrue(input.beginConstruction("landFactory"))
                input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
                input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
                assertTrue(current.observation.units.all { it.tasks.size == 1 }, "A fresh owned click still works after $change")
            } finally { sessions.forEach { it.close() } }
        }
    }

    @Test fun a_construction_card_press_keeps_its_session_and_whole_selection_revision() {
        val sessions = mutableListOf<SandboxSession>()
        var current = SandboxSession(scene()).also { sessions += it }
        fun selectBoth() {
            current.select(Position(120.0, 170.0))
            current.select(Position(120.0, 210.0), SelectionMode.ADD)
        }
        selectBoth()
        var choices = 0
        val listener = ConstructionButtonInput({ current }, { true }, "landFactory", { choices++ })
        val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 54f); addListener(listener) }
        fun fire(down: Boolean) = actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
            type = if (down) com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown else com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp
            button = com.badlogic.gdx.Input.Buttons.LEFT; pointer = 0; stageX = 10f; stageY = 10f; touchFocus = false
        })
        try {
            fire(true); selectBoth(); fire(false)
            assertEquals(0, choices, "Changing selection away and back invalidates the old card press")
            fire(true); current.close(); current = SandboxSession(scene()).also { sessions += it }; selectBoth(); fire(false)
            assertEquals(0, choices, "Identical IDs in a new session cannot inherit a card press")
            fire(true); fire(false)
            assertEquals(1, choices)
        } finally { sessions.forEach { it.close() } }
    }

    @Test fun a_blocked_plain_click_after_a_successful_shift_click_keeps_the_pending_placement() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            var shift = true
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { key -> shift && key == com.badlogic.gdx.Input.Keys.SHIFT_LEFT })
            fun click(point: Position) {
                input.touchDown(map.x(point.x).toInt(), map.y(point.y).toInt(), 0, com.badlogic.gdx.Input.Buttons.LEFT)
                input.touchUp(map.x(point.x).toInt(), map.y(point.y).toInt(), 0, com.badlogic.gdx.Input.Buttons.LEFT)
            }
            assertTrue(input.beginConstruction("landFactory"))
            click(Position(190.0, 170.0))
            val before = session.observation.units.single { it.unitId == "a" }.tasks
            assertEquals(PlacementState.BLOCKED, session.previewConstruction("landFactory", Position(330.0, 170.0))?.state)
            shift = false; click(Position(330.0, 170.0))
            assertEquals("landFactory", input.constructionTypeId)
            assertEquals(setOf("a"), session.selectedIds)
            assertEquals(before, session.observation.units.single { it.unitId == "a" }.tasks)
            assertEquals("Cannot build here", session.playerFeedback)
        }
    }

    @Test fun shift_at_press_appends_and_keeps_placement_until_an_ordinary_click_replaces_it() {
        val actions = mutableListOf<Map<String, Any?>>()
        SandboxSession(scene(), ClientEvidence { kind, values -> if (kind == "action") actions += values }).use { session ->
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 210.0), SelectionMode.ADD)
            val map = MapProjection(600.0, 400.0)
            var shift = false
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { key -> shift && key == com.badlogic.gdx.Input.Keys.SHIFT_LEFT })
            assertTrue(input.beginConstruction("landFactory"))
            fun click(point: Position, append: Boolean) {
                assertEquals(PlacementState.CLEAR, session.previewConstruction("landFactory", point)?.state)
                val x = map.x(point.x).toInt(); val y = map.y(point.y).toInt()
                shift = append
                assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
                shift = !append
                assertTrue(input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            }
            click(Position(190.0, 170.0), true)
            assertEquals("landFactory", input.constructionTypeId)
            assertEquals(setOf("a", "b"), session.selectedIds)
            assertTrue(session.observation.units.all { it.tasks.size == 1 })
            click(Position(450.0, 170.0), true)
            assertEquals("landFactory", input.constructionTypeId)
            assertTrue(session.observation.units.all { it.tasks.size == 2 })
            val firstTargets = session.observation.units.first().tasks.map { it.target }
            assertEquals(listOf(Position(190.0, 170.0), Position(450.0, 170.0)), firstTargets)
            click(Position(450.0, 290.0), false)
            assertNull(input.constructionTypeId)
            assertTrue(session.selectedIds.isEmpty())
            assertTrue(session.observation.units.all { it.tasks.single().target == Position(450.0, 290.0) })
            assertEquals(listOf(true, true, true, true, false, false),
                actions.filter { it["type"] == "BuildAction" }.map { it["append"] })
            assertEquals(1750.0, session.observation.credits, "Intent submission must not pay before activity dispatch")
        }
    }

    @Test fun a_shared_builder_menu_dispatches_each_worker_to_one_paid_project() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            session.select(Position(120.0, 210.0), SelectionMode.ADD)
            assertEquals(setOf("a", "b"), session.selectedIds)
            assertEquals(setOf("landFactory", "extractorT1", "fabricatorT1"),
                session.constructionActions.map { it.typeId }.toSet())
            assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 170.0)))
            val batch = assertNotNull(session.lastCommandBatch)
            assertEquals(2, batch.accepted)
            assertEquals(0, batch.rejected)
            assertEquals(0, batch.skipped)
            assertTrue(session.selectedIds.isEmpty())
            session.tick()
            val factory = session.observation.units.single { it.typeId == "landFactory" }
            assertEquals(1050.0, session.observation.credits)
            val workers = session.observation.units.filter { it.typeId == "builder" }
            assertEquals(2, workers.size)
            assertTrue(workers.all { it.tasks.single().targetUnitId == factory.unitId })
            assertTrue(workers.all { it.tasks.single().kind == UnitTaskKind.CONSTRUCT })
            assertEquals(1, session.observation.events.count { it.kind == "construction-created" })
        }
    }

    private fun scene(): Scenario {
        val base = ControlledScenario.load(inputRoot())
        return base.copy(units = listOf(
            UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L), 3L))
    }

    private fun inputRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")
}
