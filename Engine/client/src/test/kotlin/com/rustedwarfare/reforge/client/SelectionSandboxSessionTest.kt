package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class SelectionSandboxSessionTest {
    @Test fun two_real_products_attack_damage_and_public_death_remove_saved_members() {
        val input = ControlledScenario.load(root())
        SandboxSession(input).use { s ->
            s.select(Position(310.0, 210.0))
            repeat(2) { assertIs<ActionResult.Accepted>(s.produce()) }
            repeat(501) { s.tick() }
            val first = s.observation.units.single { it.unitId.startsWith("produced:") }
            s.select(first.position); s.move(Position(180.0, 330.0))
            repeat(501) { s.tick() }
            val products = s.observation.units.filter { it.unitId.startsWith("produced:") }
            assertEquals(2, products.size)
            s.select(products[0].position); s.select(products[1].position, SelectionMode.ADD)
            assertEquals(products.map { it.unitId }.toSet(), s.selectedIds)
            s.saveGroup(1)
            val enemy = s.observation.units.single { it.unitId == "b" }
            assertIs<ActionResult.Accepted>(s.command(enemy.position))
            assertEquals(products.map { it.unitId }.sorted(), s.lastCommandBatch!!.members.map { it.unitId })
            assertTrue(s.observation.units.filter { it.unitId in s.selectedIds }.all { it.attackTargetId == "b" })
            var damaged = false
            repeat(1800) {
                s.tick()
                damaged = damaged || s.observation.units.single { it.unitId == "b" }.health!! < enemy.health!!
            }
            assertTrue(damaged, "Genuine produced units must cause actual enemy damage")
        }
        SandboxSession(input).use { s ->
            s.select(Position(430.0, 210.0)); s.saveGroup(2)
            assertEquals(1, s.groupCount(2))
            s.select(Position(110.0, 210.0)); s.command(Position(430.0, 210.0))
            repeat(2000) { if (s.observation.units.single { it.unitId == "b" }.alive) s.tick() }
            assertFalse(s.observation.units.single { it.unitId == "b" }.alive, "Death must be public and real")
            assertEquals(0, s.groupCount(2)); assertNull(s.recallGroup(2)); assertTrue(s.selectedIds.isEmpty())
        }
    }
    @Test fun group_recall_uses_per_group_strict_clock_current_members_and_additive_group_last() {
        for (additive in listOf(false, true)) {
            var now = 10000L
            SandboxSession(ControlledScenario.load(root()), clockMillis = { now }).use { s ->
                s.select(Position(110.0, 210.0)); s.select(Position(310.0, 210.0), SelectionMode.ADD); s.saveGroup(1)
                s.select(Position(430.0, 210.0)); s.saveGroup(2)
                assertEquals(2, s.groupCount(1))
                assertNull(s.recallGroup(1))
                now = 10100; assertNull(s.recallGroup(2))
                now = 10200; assertEquals(Position(310.0, 210.0), s.recallGroup(1, additive))
                assertEquals(if (additive) setOf("b", "a", "f") else setOf("a", "f"), s.selectedIds)
            }
        }
        for (delta in listOf(699L, 700L)) {
            var now = 10000L
            SandboxSession(ControlledScenario.load(root()), clockMillis = { now }).use { s ->
                s.select(Position(110.0, 210.0)); s.saveGroup(1); assertNull(s.recallGroup(1))
                now += delta
                assertEquals(if (delta == 699L) Position(110.0, 210.0) else null, s.recallGroup(1))
            }
        }
        var now = 10000L
        SandboxSession(ControlledScenario.load(root()), clockMillis = { now }).use { s ->
            s.select(Position(110.0, 210.0)); s.saveGroup(1); assertNull(s.recallGroup(1))
            now = 10100; s.select(Position(310.0, 210.0)); s.saveGroup(1)
            now = 10200; assertEquals(Position(310.0, 210.0), s.recallGroup(1))
            for (index in 0..9) { s.saveGroup(index); assertEquals(1, s.groupCount(index)) }
            s.select(Position(20.0, 20.0)); s.saveGroup(1); assertEquals(0, s.groupCount(1))
            assertNull(s.recallGroup(1)); assertTrue(s.selectedIds.isEmpty())
            s.close(); assertEquals(0, s.groupCount(2)); assertNull(s.recallGroup(2))
            assertNull(s.move(Position(100.0, 100.0)))
        }
        SandboxSession(ControlledScenario.load(root())).use { s ->
            for (index in 0..9) assertEquals(0, s.groupCount(index))
        }
    }

    @Test fun box_uses_inclusive_centers_controllable_smart_candidates_and_the_press_snapshot() {
        SandboxSession(ControlledScenario.load(root())).use { session ->
            session.selectBox(Position(110.0, 210.0), Position(430.0, 210.0))
            assertEquals(setOf("a"), session.selectedIds, "Mobile candidate excludes factory and enemy")
            session.selectBox(Position(320.0, 220.0), Position(300.0, 200.0))
            assertEquals(setOf("f"), session.selectedIds, "Building-only reversed box remains selectable")
            session.select(Position(430.0, 210.0), SelectionMode.ADD)
            val pressed = session.selectedIds
            session.select(Position(20.0, 20.0))
            session.selectBox(Position(110.0, 210.0), Position(110.0, 210.0), SelectionMode.ADD, pressed)
            assertEquals(setOf("f", "b", "a"), session.selectedIds)
            session.selectBox(Position(110.0, 210.0), Position(110.0, 210.0), SelectionMode.TOGGLE, pressed)
            assertEquals(setOf("f", "b", "a"), session.selectedIds)
            session.selectBox(Position(20.0, 20.0), Position(30.0, 30.0))
            assertTrue(session.selectedIds.isEmpty())
        }
    }

    @Test fun overlapping_display_circles_choose_nearest_then_public_id_without_leaking_old_snapshots() {
        val base = ControlledScenario.load(root())
        val tank = base.units.first()
        val scene = base.copy(units = listOf(tank, tank.copy(unitId = "z", position = Position(118.0, 210.0))),
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L, "z" to 11L), nextObjectId = 12L))
        SandboxSession(scene).use { session ->
            session.select(Position(110.0, 210.0))
            assertEquals("a", session.selectedId)
            session.select(Position(114.0, 210.0))
            assertEquals("a", session.selectedId)
            val snapshot = session.selectedIds
            session.select(Position(118.0, 210.0), SelectionMode.ADD)
            assertEquals(setOf("a", "z"), session.selectedIds)
            assertEquals(setOf("a"), snapshot)
            assertFailsWith<UnsupportedOperationException> { (snapshot as MutableSet).clear() }
            session.select(Position(118.0, 210.0), SelectionMode.TOGGLE)
            assertEquals(setOf("a"), session.selectedIds)
            session.select(Position(20.0, 20.0), SelectionMode.ADD)
            session.select(Position(20.0, 20.0), SelectionMode.TOGGLE)
            assertEquals(setOf("a"), session.selectedIds)
            session.select(Position(20.0, 20.0))
            assertTrue(session.selectedIds.isEmpty())
        }
    }

    @Test fun mixed_selection_does_not_enable_factory_spending_even_when_factory_is_primary() {
        SandboxSession(ControlledScenario.load(root())).use { session ->
            session.select(Position(110.0, 210.0))
            session.select(Position(310.0, 210.0), SelectionMode.ADD)
            assertEquals("f", session.selectedId)
            assertNull(session.selectedFactoryQueue)
            assertNull(session.productionPrice)
            assertNull(session.produce())
            assertNull(session.cancelProduction())
            assertEquals(1750L, session.observation.balance)
            assertTrue(session.observation.factoryQueues.single().items.isEmpty())
        }
    }

    @Test fun building_rejection_does_not_prevent_later_tank_and_batch_has_distinct_action_ids() {
        val base = ControlledScenario.load(root())
        val scene = base.copy(units = base.units.map { it.copy(unitId = when (it.unitId) {
            "a" -> "z"; "f" -> "a"; else -> it.unitId
        }) }, combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("z" to 8L, "a" to 10L, "b" to 9L)))
        SandboxSession(scene).use { session ->
            session.select(Position(110.0, 210.0))
            session.select(Position(430.0, 210.0), SelectionMode.ADD)
            session.select(Position(310.0, 210.0), SelectionMode.ADD)
            val oldSelection = session.selectedIds
            val target = Position(180.0, 330.0)
            assertIs<ActionResult.Accepted>(session.move(target))
            val batch = assertNotNull(session.lastCommandBatch)
            assertEquals(listOf("a", "z"), batch.members.map { it.unitId })
            assertEquals(1, batch.accepted)
            assertEquals(1, batch.rejected)
            assertEquals(1, batch.skipped)
            assertEquals("immobile-unit", assertIs<ActionResult.Rejected>(batch.members.first().result).error.code)
            assertEquals(2, batch.members.map { it.result.actionId }.toSet().size)
            assertEquals(target, session.observation.units.single { it.unitId == "z" }.moveTarget)
            assertEquals("Accepted 1, rejected 1, skipped 1; immobile-unit", session.feedback)
            session.tick()
            assertEquals("Accepted 1, rejected 1, skipped 1; immobile-unit", session.feedback)
            session.select(Position(20.0, 20.0))
            assertEquals(setOf("z", "b", "a"), oldSelection)
            assertTrue(session.selectedIds.isEmpty())
        }
    }

    @Test fun evidence_failure_stops_later_members_and_preserves_the_first_accepted_command() {
        val base = ControlledScenario.load(root())
        val tank = base.units.single { it.unitId == "a" }
        val scene = base.copy(units = base.units + tank.copy(unitId = "z", position = Position(180.0, 110.0)),
            combatContract = base.combatContract!!.copy(
                unitObjectIds = base.combatContract!!.unitObjectIds + ("z" to 11L), nextObjectId = 12L))
        val firstFailure = IllegalStateException("first external evidence failure")
        var armed = false
        val session = SandboxSession(scene, ClientEvidence { kind, values ->
            if (armed && kind == "action" && values["type"] == "MoveAction") throw firstFailure
        })
        try {
            session.select(Position(180.0, 110.0))
            session.select(Position(110.0, 210.0), SelectionMode.ADD)
            armed = true
            val target = Position(180.0, 330.0)
            assertSame(firstFailure, assertFailsWith<IllegalStateException> { session.move(target) })
            assertEquals(target, session.observation.units.single { it.unitId == "a" }.moveTarget)
            assertNull(session.observation.units.single { it.unitId == "z" }.moveTarget)
        } finally { armed = false; session.close() }
    }

    @Test fun mixed_selection_moves_owned_tank_and_keeps_core_building_rejection() {
        SandboxSession(ControlledScenario.load(root())).use { session ->
            session.select(Position(110.0, 210.0))
            session.select(Position(310.0, 210.0), SelectionMode.ADD)
            session.select(Position(430.0, 210.0), SelectionMode.ADD)
            val target = Position(180.0, 330.0)
            session.move(target)
            assertEquals(target, session.observation.units.single { it.unitId == "a" }.moveTarget)
            assertNull(session.observation.units.single { it.unitId == "f" }.moveTarget)
            assertNull(session.observation.units.single { it.unitId == "b" }.moveTarget)
        }
    }

    @Test fun two_real_products_selected_additively_both_stop_and_stay_stopped() {
        SandboxSession(ControlledScenario.load(root())).use { session ->
            session.select(Position(310.0, 210.0))
            repeat(2) { assertIs<ActionResult.Accepted>(session.produce()) }
            assertEquals(1050L, session.observation.balance)
            repeat(501) { session.tick() }
            val first = session.observation.units.single { it.unitId.startsWith("produced:") }
            session.select(first.position)
            assertIs<ActionResult.Accepted>(session.move(Position(180.0, 330.0)))
            repeat(501) { session.tick() }
            val products = session.observation.units.filter { it.unitId.startsWith("produced:") }
            assertEquals(2, products.size)
            val second = products.single { it.unitId != first.unitId }
            val before = products.associate { it.unitId to it.position }
            products.sortedBy { if (it.unitId == first.unitId) 0 else 1 }.forEachIndexed { index, unit ->
                session.select(unit.position)
                assertEquals(unit.unitId, session.selectedId)
                assertIs<ActionResult.Accepted>(session.move(Position(50.0 + index * 500.0, 350.0)))
            }
            repeat(70) { session.tick() }
            val moving = session.observation.units.filter { it.unitId in before }
            assertTrue(moving.all { it.speed > 0.0 && it.position != before.getValue(it.unitId) },
                "Both genuine products must actually move before testing Stop: $moving; before=$before")
            session.select(moving.single { it.unitId == first.unitId }.position)
            session.select(moving.single { it.unitId == second.unitId }.position, SelectionMode.ADD)
            assertIs<ActionResult.Accepted>(session.stop())
            assertTrue(session.observation.units.filter { it.unitId in before }.all { it.moveTarget == null },
                "Stop must clear both real products' commands immediately")
            // The core preserves velocity when cancelling a route, then decelerates.
            repeat(30) { session.tick() }
            val stopped = session.observation.units.filter { it.unitId in before }
            assertTrue(stopped.all { it.speed == 0.0 && it.moveTarget == null },
                "Stop must settle both actual products selected through the public Session: $stopped")
            val positions = stopped.associate { it.unitId to it.position }
            repeat(30) { session.tick() }
            assertEquals(positions, session.observation.units.filter { it.unitId in before }.associate { it.unitId to it.position })
            assertEquals(setOf(first.unitId, second.unitId), session.selectedIds)
        }
    }

    private fun root(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/production") } ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-006/20261026-e-selection-contract-01/input"))
}
