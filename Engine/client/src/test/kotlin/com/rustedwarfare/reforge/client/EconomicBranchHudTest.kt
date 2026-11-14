package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentSet
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class EconomicBranchHudTest {
    @Test fun branch_cards_use_real_queue_commands_and_remember_the_current_form() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val template = ControlledScenario.load(root.resolve("platform-basics/maintenance"))
        val pack = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(
            listOf(root.resolve("economy-branches/package")))).value
        val pool = Position(190.0, 290.0)
        val scene = template.copy(unitDefinitions = pack.units,
            units = listOf(UnitSpawn("mine", "p1", "extractor_form", pool, 0.0)),
            combatContract = null, maintenanceContract = null,
            economyContract = EconomyContract(template.players.associate { it.playerId to if (it.playerId == "p1") 3000L else 0L }))
        SandboxSession(scene).use { session ->
            session.select(pool)
            fun card(id: String) = session.actionMenu.battleActions().single { it.id == "upgrade:$id" }
            val overclock = card("overclock")
            assertFalse(overclock.enabled)
            assertContains(overclock.hint, "Insufficient funds")
            assertTrue(card("reinforce").enabled)
            assertIs<ActionResult.Accepted>(session.upgrade("reinforce"))
            assertTrue(card("reinforce").cancelEnabled)
            assertEquals("  Reinforce 0%", session.actionMenu.upgradeQueueText)
            assertIs<ActionResult.Accepted>(session.cancelUpgrade("reinforce"))
            assertFalse(card("reinforce").cancelEnabled)
            assertEquals(3000L, session.actionMenu.balance)
            assertIs<ActionResult.Accepted>(session.upgrade("reinforce"))
            repeat(1450) { if (session.actionMenu.upgrades.none { it.actionKey == "refund" }) session.tick() }
            assertEquals(listOf("refund"), session.actionMenu.upgrades.map { it.actionKey })
            val refund = card("refund")
            assertTrue(refund.enabled)
            assertContains(refund.hint, "Refund +\$2000 on completion")
            assertIs<ActionResult.Accepted>(session.upgrade("refund"))
            assertTrue(card("refund").cancelEnabled)
            assertEquals("  Refund 0%", session.actionMenu.upgradeQueueText)
        }
    }
}
