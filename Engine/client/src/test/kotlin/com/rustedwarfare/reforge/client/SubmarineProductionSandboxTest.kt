package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class SubmarineProductionSandboxTest {
    @Test fun three_formats_build_a_paid_shipyard_then_produce_and_control_a_real_submarine_through_the_owner_menu() {
        for (format in listOf("ini", "json", "hjson")) {
            val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            val root = fixtures.resolve("naval-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
            val loaded = SandboxLoader().use { loader ->
                loader.requestPack(root, ContentPackChoice(pack, "attackSubmarine"))
                val deadline = System.nanoTime() + 5_000_000_000L
                var result: LoadedSandbox? = null
                while (result == null && System.nanoTime() < deadline) {
                    result = loader.pollPrepared()?.getOrThrow()
                    if (result == null) Thread.sleep(5)
                }
                assertNotNull(result, "$format did not load")
            }
            assertEquals("p009-submarine-production", loaded.scenario.scenarioId)
            assertEquals(listOf("builder"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            SandboxSession(loaded.scenario).use { session ->
                session.select(Position(390.0, 150.0))
                assertEquals(listOf("seaFactory"), session.constructionActions.map { it.typeId })
                assertIs<ActionResult.Accepted>(session.build("seaFactory", Position(450.0, 150.0)))
                repeat(2100) { session.tick() }
                val factory = assertNotNull(session.observation.units.singleOrNull { it.typeId == "seaFactory" },
                    "Factory did not appear: ${session.observation.units}; ${session.feedback}")
                assertEquals(1f, factory.constructionProgress)
                session.select(factory.position)
                assertEquals(11000L, session.observation.balance)
                val card = session.productionActions.single()
                assertEquals("attackSubmarine", card.typeId)
                assertEquals("Submarine", card.title)
                assertEquals(800L, card.price)
                assertIs<ActionResult.Accepted>(session.produce(card.typeId))
                assertEquals(10200L, session.observation.balance)
                repeat(1100) { session.tick() }
                val sub = assertNotNull(session.observation.units.singleOrNull { it.typeId == "attackSubmarine" && it.ownerPlayerId == "p1" },
                    "Submarine did not appear: ${session.observation.units}; ${session.feedback}")
                assertEquals(260.0, sub.maxHealth)
                assertTrue(sub.submarine!!.underwater)
                session.select(sub.position)
                val surface = assertNotNull(session.actionMenu.depthAction)
                assertEquals(true, surface.surface)
                assertEquals("Surface", surface.title)
                val height = sub.height
                assertIs<ActionResult.Accepted>(session.submarineDepth(surface.surface))
                assertEquals(height, session.observation.units.single { it.unitId == sub.unitId }.height)
                assertEquals("Dive", session.actionMenu.depthAction!!.title)
                assertNull(session.submarineDepth(true), "Stale menu eligibility must not dispatch another Surface")
                repeat(260) { session.tick() }
                val floating = session.observation.units.single { it.unitId == sub.unitId }
                assertEquals(1.0, floating.height)
                assertEquals(false, floating.submarine!!.underwater)
                assertTrue(session.actionMenu.selectedUnitInfo!!.contains("Surface"))
                assertIs<ActionResult.Accepted>(session.submarineDepth(false))
                repeat(260) { session.tick() }
                assertEquals(-8.0, session.observation.units.single { it.unitId == sub.unitId }.height)
                assertEquals("Surface", session.actionMenu.depthAction!!.title)
                session.select(session.observation.units.single { it.unitId == "b" }.position)
                assertNull(session.actionMenu.depthAction)
                assertNull(session.submarineDepth(true))
            }
        }
    }
}
