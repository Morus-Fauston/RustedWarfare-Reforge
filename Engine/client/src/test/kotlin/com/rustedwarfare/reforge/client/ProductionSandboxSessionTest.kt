package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class ProductionSandboxSessionTest {
    @Test fun multi_selection_does_not_spend_factory_funds_and_two_real_products_consume_batch_commands() {
        val input = ControlledScenario.load(root())
        SandboxSession(input).use { session ->
            session.select(Position(110.0, 210.0))
            session.select(Position(310.0, 210.0), SelectionMode.ADD)
            assertNull(session.selectedFactoryQueue)
            assertNull(session.productionPrice)
            assertNull(session.produce())
            assertEquals(1750L, session.observation.balance)
            session.select(Position(310.0, 210.0))
            assertIs<ActionResult.Accepted>(session.produce())
            assertIs<ActionResult.Accepted>(session.produce())
            repeat(501) { session.tick() }
            val first = session.observation.units.single { it.unitId.startsWith("produced:") }
            session.select(first.position)
            assertEquals(1, session.moveSelected(Position(250.0, 320.0)).accepted)
            repeat(501) { session.tick() }
            val products = session.observation.units.filter { it.unitId.startsWith("produced:") }
            assertEquals(2, products.size)
            session.select(products[0].position)
            session.select(products[1].position, SelectionMode.ADD)
            assertEquals(products.map { it.unitId }.toSet(), session.selectedIds)
            assertEquals(2, session.moveSelected(Position(350.0, 300.0)).accepted)
            repeat(30) { session.tick() }
            assertTrue(session.observation.units.filter { it.unitId in session.selectedIds }.all { it.speed > 0.0 })
            assertEquals(2, session.stopSelected().accepted)
            repeat(40) { session.tick() }
            assertTrue(session.observation.units.filter { it.unitId in session.selectedIds }.all { it.speed == 0.0 && it.moveTarget == null })
            val target = session.observation.units.single { it.unitId == "b" }
            val oldHealth = target.health!!
            assertEquals(2, session.commandSelected(target.position).accepted)
            var fired = false
            repeat(250) { session.tick(); fired = fired || session.observation.projectileEvents.any { it.sourceUnitId in session.selectedIds } }
            assertTrue(fired)
            assertTrue(session.observation.units.single { it.unitId == "b" }.health!! < oldHealth)
        }
    }
    @Test fun actual_session_pays_cancels_reports_funds_and_controls_its_real_product() {
        val input = ControlledScenario.load(root())
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        SandboxSession(input, ClientEvidence { kind, values -> rows += kind to values }).use { session ->
            val old = session.frame
            session.select(Position(310.0, 210.0))
            assertEquals("f", session.selectedId)
            assertEquals(350L, session.productionPrice)
            assertTrue(session.selectedFactoryQueue!!.items.isEmpty())
            repeat(5) { assertIs<ActionResult.Accepted>(session.produce()) }
            assertEquals(0L, session.observation.balance)
            assertEquals(5, session.selectedFactoryQueue!!.items.size)
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(session.produce()).error.code)
            assertEquals("insufficient-funds", session.feedback)
            repeat(100) { session.tick() }
            assertEquals("insufficient-funds", session.feedback,
                "Ordinary combat events must not erase the player's rejected production feedback")
            assertIs<ActionResult.Accepted>(session.cancelProduction())
            assertEquals(350L, session.observation.balance)
            assertIs<ActionResult.Accepted>(session.produce())
            repeat(4) { assertIs<ActionResult.Accepted>(session.cancelProduction()) }
            assertEquals(1400L, session.observation.balance)
            repeat(400) { session.tick() }
            assertTrue(session.observation.units.none { it.unitId.startsWith("produced:") })
            assertEquals(0.9999942183494568f, session.selectedFactoryQueue!!.items.single().progress)
            session.tick()
            val born = session.observation.units.single { it.unitId.startsWith("produced:p1:") }
            assertTrue(Regex("produced:p1:[0-9a-f]{32}").matches(born.unitId))
            assertEquals(Position(310.0, 215.0), born.position)
            assertTrue(session.selectedFactoryQueue!!.items.isEmpty())
            session.select(born.position)
            assertEquals(born.unitId, session.selectedId)
            assertNull(session.productionPrice)
            assertIs<ActionResult.Accepted>(session.stop())
            assertIs<ActionResult.Accepted>(session.move(Position(350.0, 300.0)))
            repeat(200) { session.tick() }
            assertIs<ActionResult.Accepted>(session.command(Position(430.0, 210.0)))
            var fired = false
            repeat(250) {
                session.tick()
                fired = fired || session.observation.projectileEvents.any { it.sourceUnitId == born.unitId }
            }
            assertTrue(fired)
            assertTrue(session.observation.units.single { it.unitId == "b" }.health!! < 210.0)
            assertEquals(1750L, old.observation.balance)
            assertTrue(rows.any { it.first == "action" && it.second["type"] == "ProduceAction" })
            assertTrue(rows.any { it.first == "action" && it.second["type"] == "CancelProductionAction" })
            assertTrue(rows.any { it.first == "action" && it.second["type"] == "AttackAction" && it.second["unitId"] == born.unitId })
        }
        SandboxSession(input).use { fresh ->
            assertEquals(1750L, fresh.observation.balance)
            assertTrue(fresh.observation.factoryQueues.single().items.isEmpty())
            assertTrue(fresh.observation.units.none { it.unitId.startsWith("produced:") })
            assertNull(fresh.selectedId)
        }
    }

    private fun root(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input")

    @Test fun the_fixed_production_input_loads_a_real_factory_and_normal_priced_tank() {
        val result = runCatching { ControlledScenario.load(root()) }
        val scene = assertNotNull(result.getOrNull(), result.exceptionOrNull()?.toString())
        assertEquals("p005-production", scene.scenarioId)
        assertTrue(scene.units.any { it.typeId == "landFactory" && it.ownerPlayerId == "p1" })
        val factory = scene.unitDefinitions.getValue("landFactory")
        assertEquals(1200.0, factory.maximumHealth)
        assertEquals("c_tank", factory.production!!.unitTypeId)
        val tank = scene.unitDefinitions.getValue("c_tank")
        val build = assertNotNull(tank.build)
        assertEquals(350L, build.price)
        assertEquals(.002f, build.rate)
        assertEquals(1750L, scene.economyContract!!.initialBalances["p1"])
    }
}
