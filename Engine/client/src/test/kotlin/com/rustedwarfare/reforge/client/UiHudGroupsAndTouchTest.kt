package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.rustedwarfare.reforge.core.sim.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UiHudGroupsAndTouchTest {

    private val fixturesDir: Path = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()

    @Test
    fun pcControlGroupBarSupportsAllTenGroupsAndSkipsEmptyGaps() {
        val counts = mapOf(1 to 4, 3 to 2, 7 to 1, 0 to 5)
        val slots = HudControlGroupLogic.visibleSlots(touchControls = false) { counts[it] ?: 0 }
        assertEquals(listOf(1, 3, 7, 0), slots.map { it.groupIndex })
        assertEquals(listOf(4, 2, 1, 5), slots.map { it.memberCount })
        assertTrue(slots.none { it.empty })

        // All 10 non-empty groups appear in canonical 1..9, 0 order
        val allTen = HudControlGroupLogic.visibleSlots(touchControls = false) { it + 1 }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0), allTen.map { it.groupIndex })
        assertEquals(10, allTen.size)

        // All empty => PC bar hides completely
        assertTrue(HudControlGroupLogic.visibleSlots(touchControls = false) { 0 }.isEmpty())
    }

    @Test
    fun touchControlGroupBarPinsFirstThreeSlotsAndAppendsExtraNonEmptySlots() {
        val emptyTouch = HudControlGroupLogic.visibleSlots(touchControls = true) { 0 }
        assertEquals(listOf(1, 2, 3), emptyTouch.map { it.groupIndex })
        assertTrue(emptyTouch.all { it.empty && it.memberCount == 0 })

        val mixedCounts = mapOf(1 to 4, 2 to 2, 5 to 3, 0 to 1)
        val mixedTouch = HudControlGroupLogic.visibleSlots(touchControls = true) { mixedCounts[it] ?: 0 }
        assertEquals(listOf(1, 2, 3, 5, 0), mixedTouch.map { it.groupIndex })
        assertEquals(listOf(false, false, true, false, false), mixedTouch.map { it.empty })
        assertEquals(listOf(4, 2, 0, 3, 1), mixedTouch.map { it.memberCount })
    }

    @Test
    fun touchThreeStageHoldTimingsAndBoundaryReleasesFollowDecP011001Thresholds() {
        // Negative / cancelled hold returns NONE
        assertEquals(TouchGroupHoldStage.NONE, HudControlGroupLogic.holdStage(-0.01f))
        assertEquals(
            TouchGroupReleaseOutcome.NONE,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = -1f, wasEmpty = false, hasSelection = true),
        )

        // Exact 0.83s / 1.67s boundaries
        assertEquals(TouchGroupHoldStage.SHORT, HudControlGroupLogic.holdStage(0.0f))
        assertEquals(TouchGroupHoldStage.SHORT, HudControlGroupLogic.holdStage(0.82f))
        assertEquals(TouchGroupHoldStage.APPEND, HudControlGroupLogic.holdStage(0.83f))
        assertEquals(TouchGroupHoldStage.APPEND, HudControlGroupLogic.holdStage(1.25f))
        assertEquals(TouchGroupHoldStage.APPEND, HudControlGroupLogic.holdStage(1.67f))
        assertEquals(TouchGroupHoldStage.REPLACE, HudControlGroupLogic.holdStage(1.68f))

        // Hint keys match 4 visual states
        assertEquals("holdGroup", HudControlGroupLogic.hintKeyForStage(TouchGroupHoldStage.NONE))
        assertEquals("groupHintHold", HudControlGroupLogic.hintKeyForStage(TouchGroupHoldStage.SHORT))
        assertEquals("groupHintAppend", HudControlGroupLogic.hintKeyForStage(TouchGroupHoldStage.APPEND))
        assertEquals("groupHintReplace", HudControlGroupLogic.hintKeyForStage(TouchGroupHoldStage.REPLACE))

        // Release outcomes across non-empty & empty slots including exact 0.83f / 1.67f boundaries
        assertEquals(
            TouchGroupReleaseOutcome.RECALL,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 0.2f, wasEmpty = false, hasSelection = false),
        )
        assertEquals(
            TouchGroupReleaseOutcome.SAVE_EMPTY,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 0.2f, wasEmpty = true, hasSelection = true),
        )
        assertEquals(
            TouchGroupReleaseOutcome.NONE,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 0.2f, wasEmpty = true, hasSelection = false),
        )
        assertEquals(
            TouchGroupReleaseOutcome.APPEND_AND_RECALL,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 0.83f, wasEmpty = false, hasSelection = true),
        )
        assertEquals(
            TouchGroupReleaseOutcome.APPEND_AND_RECALL,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 1.67f, wasEmpty = true, hasSelection = true),
        )
        assertEquals(
            TouchGroupReleaseOutcome.NONE,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 1.0f, wasEmpty = true, hasSelection = false),
        )
        assertEquals(
            TouchGroupReleaseOutcome.RECALL,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 1.0f, wasEmpty = false, hasSelection = false),
        )
        assertEquals(
            TouchGroupReleaseOutcome.REPLACE,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 1.68f, wasEmpty = false, hasSelection = true),
        )
        assertEquals(
            TouchGroupReleaseOutcome.REPLACE,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 2.0f, wasEmpty = true, hasSelection = true),
        )
        assertEquals(
            TouchGroupReleaseOutcome.NONE,
            HudControlGroupLogic.resolveTouchRelease(heldSeconds = 2.0f, wasEmpty = false, hasSelection = false),
        )
    }

    @Test
    fun selectionStateAndSandboxInputSupportCtrlSaveShiftAdditiveRecallAndCancelClearsModifiers() {
        var nowMs = 10_000L
        val state = SelectionState(clockMillis = { nowMs })
        val aliveIds = setOf("u1", "u2", "u3")

        state.select(listOf("u1"))
        state.saveGroup(0)
        assertEquals(setOf("u1"), state.groupMembers(0))

        state.select(listOf("u2"))
        state.appendGroup(0)
        assertEquals(setOf("u1", "u2"), state.groupMembers(0))

        state.select(listOf("u3"))
        state.saveGroup(9)

        assertNull(state.recallGroup(0, aliveIds, additive = false))
        assertEquals(setOf("u1", "u2"), state.selectedIds)

        // Double tap within 700ms returns primary unit id for camera centering
        nowMs += 400L
        assertEquals("u2", state.recallGroup(0, aliveIds, additive = false))

        // After > 700ms, next recall does NOT center camera
        nowMs += 800L
        assertNull(state.recallGroup(0, aliveIds, additive = false))

        // Additive recall merges group 9 into current selection
        state.recallGroup(9, aliveIds, additive = true)
        assertEquals(setOf("u1", "u2", "u3"), state.selectedIds)

        // Real SandboxInput keyboard modifier tracking (Ctrl+0..9 save, Shift+0..9 additive recall, cancel() clears heldModifiers)
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            val p1Units = session.observation.units.filter { it.ownerPlayerId == "p1" && it.alive }
            assertTrue(p1Units.size >= 2, "Fixture needs at least 2 player units")
            val projection = MapProjection(600.0, 400.0)
            val input = SandboxInput(
                session = { session },
                projection = { projection },
                uiPoint = { x, y -> Position(x.toDouble(), y.toDouble()) },
                active = { true },
                editing = { false },
                pressed = { false },
            )

            // Ctrl+1 via keyDown/keyUp saves p1Units[0] into group 1
            session.select(p1Units[0].position)
            assertFalse(input.keyDown(Input.Keys.CONTROL_LEFT))
            assertTrue(input.keyDown(Input.Keys.NUM_1))
            input.keyUp(Input.Keys.NUM_1)
            input.keyUp(Input.Keys.CONTROL_LEFT)
            assertEquals(1, session.groupCount(1))

            // Ctrl+0 saves p1Units[1] into group 0 (10th group)
            session.select(p1Units[1].position)
            input.keyDown(Input.Keys.CONTROL_LEFT)
            assertTrue(input.keyDown(Input.Keys.NUM_0))
            input.keyUp(Input.Keys.NUM_0)
            input.keyUp(Input.Keys.CONTROL_LEFT)
            assertEquals(1, session.groupCount(0))

            // Plain 1 recalls only group 1
            assertTrue(input.keyDown(Input.Keys.NUM_1))
            input.keyUp(Input.Keys.NUM_1)
            assertEquals(setOf(p1Units[0].unitId), session.selectedIds)

            // Shift+0 additively recalls group 0 so both units are selected
            input.keyDown(Input.Keys.SHIFT_LEFT)
            assertTrue(input.keyDown(Input.Keys.NUM_0))
            input.keyUp(Input.Keys.NUM_0)
            input.keyUp(Input.Keys.SHIFT_LEFT)
            assertEquals(setOf(p1Units[0].unitId, p1Units[1].unitId), session.selectedIds)

            // cancel() clears stuck heldModifiers so pressing 1 recalls instead of saving
            session.select(p1Units[1].position)
            input.keyDown(Input.Keys.CONTROL_LEFT)
            input.cancel()
            assertTrue(input.keyDown(Input.Keys.NUM_1))
            input.keyUp(Input.Keys.NUM_1)
            assertEquals(setOf(p1Units[0].unitId), session.selectedIds)

            // SandboxSession.appendGroup merges without overwriting existing group members
            session.select(p1Units[1].position)
            session.appendGroup(1)
            assertEquals(2, session.groupCount(1))
        }
    }

    @Test
    fun touchActionPreviewFirstTapPreviewsSecondTapExecutesOnRealFactoryAndConstructionExecutesImmediately() {
        val preview = TouchActionPreviewState()

        // Real SandboxSession with production factory + builder
        val base = ControlledScenario.load(fixturesDir.resolve("production-combat/production"))
        SandboxSession(base).use { session ->
            val factory = session.observation.units.first { it.ownerPlayerId == "p1" && it.alive && it.typeId.contains("Factory", ignoreCase = true) }
            session.select(factory.position)
            val snap = HudActionProjection.projectActionList(session, null, false, false, false, null, true, UiLanguage.ZH)
            val prodKeys = snap.exclusiveKeys.filter { it.id.startsWith("produce:") && it.enabled }
            assertTrue(prodKeys.isNotEmpty(), "Factory needs at least 1 enabled production key, saw ${prodKeys.map { it.id }}")
            val firstKey = prodKeys[0]
            val secondKey = prodKeys.getOrNull(1) ?: firstKey.copy(id = "produce:alternate", title = "Alternate")
            assertFalse(firstKey.isTargetAction, "Production key must not be target action")

            // 1. First tap -> PREVIEW; must NOT enqueue any production in real SandboxSession
            assertEquals(TouchActionTapOutcome.PREVIEW, preview.onActionTap(factory.unitId, firstKey))
            assertTrue(preview.active)
            assertTrue(preview.isPreviewing(factory.unitId, firstKey.id))
            assertEquals(0, session.actionMenu.factoryQueue?.items?.size ?: 0, "First tap must only preview, not produce")

            // 2. Second tap on same factory + same key -> EXECUTE; enqueues 1 unit in real SandboxSession
            assertEquals(TouchActionTapOutcome.EXECUTE, preview.onActionTap(factory.unitId, firstKey))
            val prodType = firstKey.id.removePrefix("produce:")
            assertTrue(session.produce(prodType) is ActionResult.Accepted)
            assertEquals(1, session.actionMenu.factoryQueue?.items?.size ?: 0)

            // 3. Tapping a different production key switches preview instead of executing
            assertEquals(TouchActionTapOutcome.PREVIEW, preview.onActionTap(factory.unitId, secondKey))
            assertTrue(preview.isPreviewing(factory.unitId, secondKey.id))
            assertFalse(preview.isPreviewing(factory.unitId, firstKey.id))

            // 4. Switching primary unit clears preview so tapping same action on another unit previews first
            preview.reconcile("other-factory", snap.exclusiveKeys.map { it.id }.toSet())
            assertFalse(preview.active)
            assertEquals(TouchActionTapOutcome.PREVIEW, preview.onActionTap("other-factory", secondKey))
            assertTrue(preview.isPreviewing("other-factory", secondKey.id))

            // 5. Removing action from available set clears preview
            preview.reconcile("other-factory", setOf(firstKey.id))
            assertFalse(preview.active)

            // 6. Explicit outside-card dismissal clears preview
            assertEquals(TouchActionTapOutcome.PREVIEW, preview.onActionTap(factory.unitId, firstKey))
            preview.clear()
            assertFalse(preview.active)

            // 7. Disabled / short-funds key: first tap previews tooltip (to inspect why disabled), second tap hits disabled branch
            val disabledKey = firstKey.copy(id = "produce:expensive", enabled = false, state = HudActionKeyState.SHORT_FUNDS)
            assertEquals(TouchActionTapOutcome.PREVIEW, preview.onActionTap(factory.unitId, disabledKey))
            assertTrue(preview.isPreviewing(factory.unitId, disabledKey.id))
            assertEquals(TouchActionTapOutcome.EXECUTE, preview.onActionTap(factory.unitId, disabledKey))
            assertFalse(disabledKey.enabled)
        }

        // Real SandboxSession builder: all choose-construction:* keys execute immediately on 1st tap & clear any preview
        val maintBase = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(maintBase).use { session ->
            val builder = session.observation.units.first { it.typeId == "builder" && it.ownerPlayerId == "p1" }
            session.select(builder.position)
            val snap = HudActionProjection.projectActionList(session, null, false, false, false, null, true, UiLanguage.ZH)
            val buildKeys = snap.exclusiveKeys.filter { it.id.startsWith("choose-construction:") }
            assertTrue(buildKeys.isNotEmpty(), "Builder must have choose-construction:* keys")
            assertTrue(buildKeys.all { it.isTargetAction }, "All choose-construction:* keys must be target actions")

            val nonTarget = HudActionKeyModel(
                id = "unit-action:dummy", cancelId = null, title = "Dummy", unitIconTypeId = null,
                commandIconName = "rw-info", priceText = null, noteText = null, hotkeyLabel = null,
                queueBadgeCount = 0, progress = null, state = HudActionKeyState.READY,
                enabled = true, cancelEnabled = false, isTargetAction = false, attachmentContext = null,
                tooltip = HudActionTooltipModel("Dummy", null, "desc", emptyList(), "hint"),
            )
            assertEquals(TouchActionTapOutcome.PREVIEW, preview.onActionTap(builder.unitId, nonTarget))
            assertTrue(preview.active)
            assertEquals(TouchActionTapOutcome.EXECUTE, preview.onActionTap(builder.unitId, buildKeys.first()))
            assertFalse(preview.active, "Target action 1st tap must clear active preview and immediately execute")
        }
    }
}
