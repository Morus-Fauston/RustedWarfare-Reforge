package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class ActionMenuTest {
    private fun scene() = ControlledScenario.load(Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        .resolve("build-economy/start"))

    @Test fun identical_selected_factories_have_distinct_primary_headings() {
        val base = scene()
        val factory = base.units.single { it.position == Position(110.0, 210.0) }
        val other = factory.copy(unitId = "second-factory", position = Position(190.0, 210.0))
        val contract = base.combatContract?.let {
            it.copy(unitObjectIds = it.unitObjectIds + (other.unitId to it.nextObjectId), nextObjectId = it.nextObjectId + 1)
        }
        val mixed = base.copy(units = base.units + other, combatContract = contract)
        SandboxSession(mixed).use { session ->
            fun heading(primary: UnitSpawn, previous: UnitSpawn): String? {
                session.select(previous.position)
                session.select(primary.position, SelectionMode.ADD)
                assertEquals(setOf(factory.unitId, other.unitId), session.selectedIds)
                val selected = session.observation.units.single { it.unitId == session.selectedId }
                return desktopPrimaryHeading(session.actionMenu, selected, session.selectedIds)
            }
            assertNotEquals(heading(factory, other), heading(other, factory))
        }
    }

    @Test fun unload_card_keeps_authorized_cargo_counts() {
        val view = ActionMenuView(transportAction = TransportUnloadActionView(true, "Unload", true, 4, 6, 2))
        val card = view.battleActions().single()
        assertEquals("transport-unload", card.id)
        assertContains(card.hint, "Cargo 4 / 6")
        assertContains(card.hint, "Passengers 2")
    }

    @Test fun a_mixed_selection_projects_production_only_for_its_own_primary() {
        val base = scene()
        val factory = base.units.single { it.position == Position(110.0, 210.0) }
        SandboxSession(base).use { session ->
            session.select(factory.position)
            assertIs<ActionResult.Accepted>(session.produce("builder"))
            repeat(700) { session.tick() }
            val worker = session.observation.units.first { it.ownerPlayerId == factory.ownerPlayerId && it.typeId == "builder" }
            session.select(worker.position)
            session.select(factory.position, SelectionMode.ADD)
            assertEquals(setOf(factory.unitId, worker.unitId), session.selectedIds)
            assertEquals(factory.unitId, session.selectedId)
            val destination = Position(200.0, 260.0)
            assertIs<ActionResult.Accepted>(session.move(destination))
            assertEquals(listOf(worker.unitId), session.lastCommandBatch?.members?.map { it.unitId })
            assertEquals(1, session.lastCommandBatch?.skipped)
            assertIs<ActionResult.Accepted>(session.patrol(destination, append = false))
            assertEquals(listOf(worker.unitId), session.lastCommandBatch?.members?.map { it.unitId })
            assertEquals(1, session.lastCommandBatch?.skipped)
            val produce = session.actionMenu.battleActions().single { it.id == "produce:builder" }
            assertTrue(produce.enabled)
            assertFalse(produce.cancelEnabled)
            assertIs<ActionResult.Accepted>(session.produce("builder"))
            assertTrue(session.actionMenu.battleActions().single { it.id == produce.id }.cancelEnabled)

            session.select(factory.position)
            session.select(worker.position, SelectionMode.ADD)
            assertEquals(worker.unitId, session.selectedId)
            assertFalse(session.actionMenu.battleActions().any { it.id == produce.id })
            assertNull(session.produce("builder"))
        }
    }

    @Test fun defense_conversion_enemy_selection_and_a_new_match_cannot_keep_an_old_action_or_queue() {
        val base = ControlledScenario.load(Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("platform-basics/upgrade"))
        SandboxSession(base).use { session ->
            session.select(Position(230.0, 150.0))
            val initial = session.actionMenu
            assertEquals(listOf("Gun T2", "Artillery"), initial.upgrades.map { it.title })
            assertEquals(listOf(1000L, 1600L), initial.upgrades.map { it.price })
            assertEquals("Gun T2\n$1000", initial.upgrades.first().cardText)
            assertIs<ActionResult.Accepted>(session.upgrade("upgrade_gun"))
            assertEquals("Gun T2\n(1) 0%", session.actionMenu.upgrades.first().cardText)
            val enemy = base.units.first { it.ownerPlayerId == "p2" }
            session.select(enemy.position)
            assertTrue(session.actionMenu.upgrades.isEmpty())
            assertTrue(session.actionMenu.production.isEmpty())
            assertTrue(session.actionMenu.construction.isEmpty())
            assertNull(session.actionMenu.upgradeQueue)
            assertFalse(session.actionMenu.canMaintain)
            session.select(Position(230.0, 150.0))
            var steps = 0
            while (session.actionMenu.upgrades.isNotEmpty() && steps++ < 2600) session.tick()
            assertEquals("Gun T2", session.actionMenu.selectedTitle)
            assertTrue(session.actionMenu.upgrades.isEmpty())
            assertNull(session.actionMenu.upgradeQueue)
            assertEquals("Turret", initial.selectedTitle)
            assertNull(initial.upgrades.first().progress)
        }
        SandboxSession(base).use { session ->
            session.select(Position(230.0, 150.0))
            assertEquals("Turret", session.actionMenu.selectedTitle)
            assertTrue(session.actionMenu.upgrades.all { it.progress == null })
            assertNull(session.actionMenu.credits) // Defense fixture retains the original integer ledger.
            assertEquals(1750L, session.actionMenu.balance)
        }
    }

    @Test fun economic_menu_uses_the_registered_upgrade_and_refreshes_payment_and_progress_without_changing_nominal_time() {
        val base = ControlledScenario.load(Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            session.select(Position(190.0, 290.0))
            val initial = session.actionMenu.upgrades.single()
            assertEquals("upgrade_t2", initial.actionKey)
            assertEquals("extractorT2", initial.targetTypeId)
            assertEquals(1400L, initial.price)
            assertEquals("Upgrade - T2 ($1400)\n27.7s\nIncome +$8 -> +$12\nLeft: upgrade\nRight: cancel one", initial.tooltip)
            assertIs<ActionResult.Accepted>(session.upgrade(initial.actionKey))
            val paid = session.actionMenu.upgrades.single()
            assertEquals(0f, paid.progress)
            assertFalse(paid.affordable)
            assertEquals("U Upgrade - T2 (1)\n0%", paid.cardText)
            assertEquals("Upgrade - T2 ($1400)\n27.7s\nIncome +$8 -> +$12\nInsufficient funds\nRight: cancel one", paid.tooltip)
            session.tick()
            assertTrue(session.actionMenu.upgrades.single().progress!! > 0f)
            assertIs<ActionResult.Accepted>(session.cancelUpgrade(initial.actionKey))
            assertNull(session.actionMenu.upgrades.single().progress)
            assertTrue(session.actionMenu.upgrades.single().affordable)
            assertNull(initial.progress)
            session.select(Position(270.0, 270.0))
            val worker = session.actionMenu
            assertTrue(worker.upgrades.isEmpty())
            assertTrue(worker.canMaintain)
            assertTrue(worker.construction.any { it.typeId == "extractorT1" })
            assertEquals("Tasks 0\nIdle", worker.workerQueueText)
            assertEquals("Builder", worker.selectedTitle)
        }
    }

    @Test fun production_menu_updates_immediately_without_a_tick_and_published_menus_remain_read_only() {
        SandboxSession(scene()).use { session ->
            assertTrue(session.actionMenu.production.isEmpty())
            session.select(Position(110.0, 210.0))
            val before = session.actionMenu
            val action = before.production.single()
            assertEquals("builder", action.typeId)
            assertEquals("Builder", action.title)
            assertEquals(500L, action.price)
            assertEquals(0, action.count)
            assertTrue(action.affordable)
            val step = session.observation.stepIndex
            assertIs<ActionResult.Accepted>(session.produce())
            val paid = session.actionMenu
            assertEquals(step, session.observation.stepIndex)
            assertEquals(1250.0, paid.credits)
            assertEquals(1, paid.production.single().count)
            assertEquals(0f, paid.production.single().progress)
            assertEquals(0, before.production.single().count)
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (paid.production as MutableList<ProductionActionView>).clear()
            }
            assertIs<ActionResult.Accepted>(session.cancelProduction())
            val refunded = session.actionMenu
            assertEquals(step, session.observation.stepIndex)
            assertEquals(1750.0, refunded.credits)
            assertEquals(0, refunded.production.single().count)
            assertEquals(1, paid.production.single().count)
            session.select(Position(600.0, 450.0))
            assertTrue(session.actionMenu.production.isEmpty())
            session.select(Position(110.0, 210.0))
            assertEquals(0, session.actionMenu.production.single().count)
            session.close()
            assertTrue(session.actionMenu.production.isEmpty())
        }
    }
}
