package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

internal object NautilusSandboxScene {
    fun scenario(format: String = "ini"): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("nautilus-production/$format")
        val loaded = ContentLoader.loadContentPack(root.resolve("production-package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
        return contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
    }
}

class NautilusSandboxTest {
    @Test fun a_normal_command_center_start_pays_for_the_entire_mobile_production_chain_and_reopens_cleanly() {
        for (format in listOf("ini", "json", "hjson")) {
            val scene = NautilusSandboxScene.scenario(format)
            SandboxSession(scene).use { s ->
                fun until(limit: Int, ready: () -> Boolean) {
                    var steps = 0
                    while (!ready() && steps++ < limit) s.tick()
                    assertTrue(ready(), "$format: ${s.feedback}")
                }
                s.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(s.produce("builder"))
                until(600) { s.observation.units.any { it.typeId == "builder" } }
                s.select(s.observation.units.single { it.typeId == "builder" }.position)
                val beforeFactory = assertNotNull(s.observation.credits)
                val buildTime = s.observation.simulationTime
                assertIs<ActionResult.Accepted>(s.build("seaFactory", Position(450.0, 150.0)))
                until(1800) { s.observation.units.any { it.typeId == "seaFactory" } }
                val payments = (s.observation.simulationTime / 10.0).toLong() - (buildTime / 10.0).toLong()
                assertEquals(beforeFactory - 1000.0 + payments * 4.5, s.observation.credits)
                until(3000) { s.observation.units.any { it.typeId == "seaFactory" && it.constructionProgress == 1f } }
                s.select(Position(450.0, 150.0))
                val beforeUpgrade = assertNotNull(s.observation.balance)
                assertIs<ActionResult.Accepted>(s.upgrade("upgrade_t2"))
                assertEquals(beforeUpgrade - 2000L, s.observation.balance)
                until(2600) { s.observation.units.any { it.typeId == "seaFactoryT2" } }
                val card = s.productionActions.single { it.typeId == "nautilusSubmarine" }
                assertEquals("Nautilus", card.title)
                assertEquals(18000L, card.price)
                val beforeMother = assertNotNull(s.observation.balance)
                assertIs<ActionResult.Accepted>(s.produce(card.typeId))
                assertEquals(beforeMother - 18000L, s.observation.balance)
                until(3400) { s.observation.units.any { it.typeId == "nautilusSubmarine" } }
                val mother = s.observation.units.single { it.typeId == "nautilusSubmarine" }
                s.select(mother.position)
                assertEquals(4000.0, mother.health)
                assertNull(s.actionMenu.transportAction)
                val beforeChild = assertNotNull(s.observation.balance)
                assertIs<ActionResult.Accepted>(s.produce("hoverTank"))
                assertEquals(beforeChild - 450L, s.observation.balance)
                assertIs<ActionResult.Accepted>(s.move(Position(650.0, 250.0)))
                until(260) { s.observation.units.single { it.unitId == mother.unitId }.transport!!.passengerCount == 1 }
                assertEquals(1, s.observation.units.single { it.unitId == mother.unitId }.transport!!.passengerCount)
                assertTrue(s.observation.units.none { it.typeId == "hoverTank" })
                assertTrue(s.observation.units.single { it.unitId == mother.unitId }.position.x > mother.position.x)
            }
            SandboxSession(scene).use { reopened ->
                assertEquals(30000L, reopened.observation.balance)
                assertEquals(listOf("commandCenter"), reopened.observation.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
                assertTrue(reopened.observation.factoryQueues.all { it.items.isEmpty() })
            }
        }
    }
    @Test fun all_formats_offer_a_normal_command_center_start_for_the_paid_sea_factory_technology_chain() {
        for (format in listOf("ini", "json", "hjson")) {
            val scene = NautilusSandboxScene.scenario(format)
            assertTrue(NativeActionPanel.supports(scene.scenarioId), "Normal production needs the native action cards")
            assertEquals("p009-nautilus-production", scene.scenarioId)
            assertEquals(listOf("commandCenter"), scene.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            SandboxSession(scene).use { s ->
                s.select(Position(130.0, 130.0))
                assertEquals(listOf("builder"), s.productionActions.map { it.typeId })
                assertIs<ActionResult.Accepted>(s.produce("builder"))
                var steps = 0
                while (s.observation.units.none { it.typeId == "builder" } && steps++ < 600) s.tick()
                assertEquals(1, s.observation.units.count { it.typeId == "builder" })
                assertTrue(s.observation.units.none { it.typeId == "nautilusSubmarine" })
                assertEquals(30000L, scene.economyContract!!.initialBalances.getValue("p1"))
            }
        }
    }
}
