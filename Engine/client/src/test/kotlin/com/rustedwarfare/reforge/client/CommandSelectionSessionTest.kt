package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class CommandSelectionSessionTest {
    @Test fun batch_exception_propagates_and_does_not_attempt_later_members_or_roll_back_accepted_members() {
        val attempted = mutableListOf<String>()
        val sink = ClientEvidence { kind, values ->
            if (kind == "action" && values["type"] == "MoveAction") {
                val id = values["unitId"] as String
                attempted += id
                if (id == "aa-building") error("intentional-evidence-failure")
            }
        }
        SandboxSession(scenario(withBuilding = true), sink).use { session ->
            session.selectBox(Position(0.0, 0.0), Position(80.0, 40.0))
            session.select(Position(150.0, 150.0), SelectionMode.ADD)
            val failure = assertFailsWith<IllegalStateException> { session.moveSelected(Position(170.0, 30.0)) }
            assertEquals("intentional-evidence-failure", failure.message)
            assertEquals(listOf("a", "aa-building"), attempted)
            assertEquals(Position(170.0, 30.0), session.observation.units.single { it.unitId == "a" }.moveTarget)
            assertNull(session.observation.units.single { it.unitId == "b" }.moveTarget)
        }
    }
    @Test fun hidden_members_do_not_change_minimap_or_counts_and_recall_uses_current_authorized_coordinates() {
        val base = scenario().copy(units = listOf(
            UnitSpawn("a", "p1", "tank", Position(30.0, 30.0), 90.0),
            UnitSpawn("enemy", "p2", "tank", Position(50.0, 30.0), 0.0)))
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            var millis = 10_000L
            val options = SandboxOptions(mode)
            SandboxSession(base, options = options, clockMillis = { millis }).use { session ->
                val initial = MinimapSnapshot.from(session.frame)
                assertEquals(100, initial.terrain.size)
                assertEquals(setOf("a", "enemy"), initial.units.map { it.unitId }.toSet())
                val withHidden = base.copy(units = base.units + UnitSpawn("hidden", "p2", "tank", Position(190.0, 190.0), 0.0))
                SandboxSession(withHidden, options = options).use { paired ->
                    assertEquals(initial, MinimapSnapshot.from(paired.frame))
                }
                session.select(Position(50.0, 30.0)); session.storeGroup(9)
                session.select(Position(30.0, 30.0), SelectionMode.ADD)
                session.move(Position(150.0, 150.0))
                repeat(250) { session.tick() }
                assertEquals(setOf("a"), session.selectedIds)
                assertEquals(0, session.visibleGroupSize(9))
                assertNull(session.recallGroup(9))
                assertTrue(session.selectedIds.isEmpty())
                assertEquals(listOf("a"), MinimapSnapshot.from(session.frame).units.map { it.unitId })
                session.select(session.observation.units.single().position)
                session.move(Position(30.0, 30.0))
                repeat(250) { session.tick() }
                millis = 10_200L
                assertEquals(Position(50.0, 30.0), session.recallGroup(9))
                assertEquals(setOf("enemy"), session.selectedIds)
                assertEquals(1, session.visibleGroupSize(9))
                assertEquals(setOf("a", "enemy"), initial.units.map { it.unitId }.toSet())
            }
        }
    }
    @Test fun box_selects_owned_mobile_centers_in_all_directions_and_uses_the_press_snapshot() {
        SandboxSession(scenario(withBuilding = true)).use { session ->
            session.select(Position(110.0, 30.0))
            val before = session.selectedIds
            session.selectBox(Position(150.0, 150.0), Position(30.0, 30.0))
            assertEquals(setOf("a", "b"), session.selectedIds)
            session.selectBox(Position(30.0, 150.0), Position(150.0, 30.0), SelectionMode.ADD, before)
            assertEquals(setOf("a", "b", "enemy"), session.selectedIds)
            session.selectBox(Position(150.0, 30.0), Position(30.0, 150.0), SelectionMode.TOGGLE, setOf("a", "enemy"))
            assertEquals(setOf("b", "enemy"), session.selectedIds)
            session.selectBox(Position(140.0, 140.0), Position(160.0, 160.0))
            assertEquals(setOf("aa-building"), session.selectedIds)
            session.selectBox(Position(0.0, 0.0), Position(29.0, 29.0))
            assertTrue(session.selectedIds.isEmpty())
            session.selectBox(Position(Double.NaN, 0.0), Position(80.0, 80.0))
            assertTrue(session.selectedIds.isEmpty())
        }
    }
    @Test fun batch_right_click_moves_then_stops_and_attacks_a_visible_foreign_unit() {
        SandboxSession(scenario(armed = true)).use { session ->
            session.select(Position(30.0, 30.0))
            session.select(Position(70.0, 30.0), SelectionMode.ADD)
            assertEquals(2, session.commandSelected(Position(50.0, 150.0)).accepted)
            repeat(30) { session.tick() }
            assertEquals(2, session.stopSelected().accepted)
            repeat(40) { session.tick() }
            val stopped = session.observation.units.filter { it.ownerPlayerId == "p1" }
            assertTrue(stopped.all { it.moveTarget == null && it.speed == 0.0 })
            val enemy = session.observation.units.single { it.unitId == "enemy" }
            assertEquals(2, session.commandSelected(enemy.position).accepted)
            assertTrue(session.observation.units.filter { it.ownerPlayerId == "p1" }.all { it.attackTargetId == "enemy" })
            var fired = false
            repeat(150) { session.tick(); fired = fired || session.observation.projectileEvents.isNotEmpty() }
            assertTrue(fired)
            assertTrue(session.observation.units.single { it.unitId == "enemy" }.health!! < 210.0)
            val unchanged = session.selectedIds
            assertTrue(session.commandSelected(Position(Double.NaN, 10.0)).members.isEmpty())
            assertEquals(unchanged, session.selectedIds)
        }
    }
    @Test fun batch_move_continues_after_an_immobile_member_and_skips_view_only_selection() {
        SandboxSession(scenario(withBuilding = true)).use { session ->
            session.select(Position(70.0, 30.0))
            session.select(Position(150.0, 150.0), SelectionMode.ADD)
            session.select(Position(30.0, 30.0), SelectionMode.ADD)
            session.select(Position(110.0, 30.0), SelectionMode.ADD)
            val result = session.moveSelected(Position(150.0, 30.0))
            assertEquals(listOf("a", "aa-building", "b"), result.members.map { it.unitId })
            assertEquals(3, result.members.map { it.actionId }.toSet().size)
            assertEquals(2, result.accepted)
            assertEquals(1, result.rejected)
            assertEquals("immobile-unit", assertIs<ActionResult.Rejected>(result.members[1].result).error.code)
            assertEquals(listOf("enemy"), result.skippedIds)
            repeat(30) { session.tick() }
            val units = session.observation.units.associateBy { it.unitId }
            assertTrue(units.getValue("a").position.x > 30.0)
            assertTrue(units.getValue("b").position.x > 70.0)
            assertEquals(Position(150.0, 150.0), units.getValue("aa-building").position)
            assertEquals(Position(110.0, 30.0), units.getValue("enemy").position)
        }
    }
    @Test fun real_session_recalls_groups_and_returns_only_the_current_group_focus() {
        var millis = 10_000L
        SandboxSession(scenario(), clockMillis = { millis }).use { session ->
            session.select(Position(30.0, 30.0))
            session.select(Position(70.0, 30.0), SelectionMode.ADD)
            session.storeGroup(0)
            session.select(Position(110.0, 30.0)); session.storeGroup(1)
            assertNull(session.recallGroup(0))
            assertEquals(setOf("a", "b"), session.selectedIds)
            millis = 10_100L
            assertNull(session.recallGroup(1))
            millis = 10_200L
            assertEquals(Position(70.0, 30.0), session.recallGroup(0, additive = true))
            assertEquals(setOf("a", "b", "enemy"), session.selectedIds)
            session.close()
            assertNull(session.recallGroup(0))
            assertTrue(session.selectedIds.isEmpty())
        }
    }

    private fun scenario(withBuilding: Boolean = false, armed: Boolean = false): Scenario {
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 1.0, 4.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, .07, .17, 4.1, .25, false, false), emptySet(), 1,
            combat = if (armed) CombatProfile(210.0, 165.0, 75.0, 10.0, 4.0, .25,
                ProjectileProfile(41.0, 240.0, 6.0), Path.of("unused.png"), Path.of("unused.png"), 1.0) else null)
        val building = UnitDefinition("building", "CustomUnitMetadata", 4.0, 4.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(100.0, Path.of("unused.png")),
            building = BuildingProfile(BuildingFootprint(0, 0, 0, 0), Path.of("unused.png")))
        return Scenario("selection-integration", MapDefinition(10, 10, 20, 20, emptyList(), IntArray(100) { 1 },
            BlockingGrid(10, 10, BooleanArray(100))), mapOf("tank" to unit, "building" to building),
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("a", "p1", "tank", Position(30.0, 30.0), 90.0),
                UnitSpawn("b", "p1", "tank", Position(70.0, 30.0), 90.0),
                UnitSpawn("enemy", "p2", "tank", Position(110.0, 30.0), 0.0)) +
                if (withBuilding) listOf(UnitSpawn("aa-building", "p1", "building", Position(150.0, 150.0), 0.0)) else emptyList(),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = if (armed) CombatContract(12345, -1,
                mapOf("a" to 8L, "b" to 9L, "enemy" to 10L), 11L) else null)
    }

    @Test fun additive_selection_uses_real_authorized_actions_and_preserves_view_only_members() {
        SandboxSession(scenario()).use { session ->
            session.select(Position(30.0, 30.0))
            val before = session.selectedIds
            session.select(Position(70.0, 30.0), SelectionMode.ADD)
            assertEquals(listOf("a", "b"), session.selectedIds.toList())
            assertEquals("b", session.selectedId)
            session.select(Position(110.0, 30.0), SelectionMode.ADD)
            assertEquals(setOf("a", "b", "enemy"), session.selectedIds)
            assertEquals(setOf("a"), before)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(session.stop()).error.code)
            session.select(Position(180.0, 180.0), SelectionMode.ADD)
            assertEquals(setOf("a", "b", "enemy"), session.selectedIds)
            session.select(Position(70.0, 30.0), SelectionMode.TOGGLE)
            assertEquals(setOf("a", "enemy"), session.selectedIds)
            session.select(Position(180.0, 180.0))
            assertTrue(session.selectedIds.isEmpty())
        }
    }
}
