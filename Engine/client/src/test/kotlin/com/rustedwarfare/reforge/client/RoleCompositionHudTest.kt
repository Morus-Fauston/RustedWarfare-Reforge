package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class RoleCompositionHudTest {
    @Test fun selected_host_keeps_its_menu_and_disables_only_the_landed_blink_card() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val base = ControlledScenario.load(fixtures.resolve("platform-basics/maintenance"))
        val loaded = ContentLoader.loadContentPack(fixtures.resolve("authored-roles/package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
        val home = Position(210.0, 230.0)
        val scene = base.copy(unitDefinitions = pack.units,
            units = listOf(UnitSpawn("support", "p1", "support_hover", home, 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("support" to 8L), 9L), maintenanceContract = null,
            economyContract = EconomyContract(base.players.associate { it.playerId to if (it.playerId == "p1") 4000L else 0L }))
        SandboxSession(scene).use { session ->
            session.select(home)
            fun card(name: String) = session.actionMenu.battleActions().single { it.id == name }
            assertTrue(card("unit-action:blink").enabled)
            assertTrue(card("unit-action:deploy").enabled)
            assertEquals(listOf("scout_hull"), session.actionMenu.production.map { it.typeId })
            assertTrue(session.actionMenu.canRepair)
            assertEquals(205.0, session.actionMenu.repairRange)
            assertEquals(5, session.actionMenu.transportAction?.capacity)
            assertIs<ActionResult.Accepted>(session.unitAction("deploy"))
            assertTrue(card("unit-action:deploy").cancelEnabled)
            assertContains(card("unit-action:deploy").hint, "Right on card: cancel")
            repeat(70) { session.tick() }
            assertFalse(card("unit-action:blink").enabled)
            assertTrue(card("unit-action:undeploy").enabled)
            assertEquals(listOf("scout_hull"), session.actionMenu.production.map { it.typeId })
            assertEquals(285.0, session.actionMenu.repairRange)
            assertEquals(5, session.actionMenu.transportAction?.capacity)
            assertIs<ActionResult.Accepted>(session.unitAction("undeploy"))
            repeat(190) { session.tick() }
            assertTrue(card("unit-action:blink").enabled)
        }
    }
}
