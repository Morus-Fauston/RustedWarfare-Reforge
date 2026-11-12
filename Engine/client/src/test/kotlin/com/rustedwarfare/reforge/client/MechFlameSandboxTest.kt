package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class MechFlameSandboxTest {
    @Test fun three_formats_produce_the_two_legged_flame_mech_from_the_normal_upgrade_menu() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = load(format)
            assertEquals("p009-flame-mech-production", loaded.scenario.scenarioId)
            assertEquals(listOf("builder"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            SandboxSession(loaded.scenario).use { session ->
                assertEquals(18000L, session.observation.balance)
                session.select(Position(190.0, 150.0))
                assertIs<ActionResult.Accepted>(session.build("mechFactory", Position(270.0, 150.0)))
                repeat(1200) { session.tick() }
                assertEquals(17000L, session.observation.balance)
                val factory = session.observation.units.single { it.typeId == "mechFactory" }
                session.select(factory.position)
                assertTrue(session.productionActions.none { it.typeId == "mechFlame" })
                assertNull(session.produce("mechFlame"))
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"))
                repeat(2600) { session.tick() }
                val upgraded = session.observation.units.single { it.unitId == factory.unitId }
                assertEquals("mechFactoryT2", upgraded.typeId)
                assertEquals(factory.position, upgraded.position)
                assertEquals(13000L, session.observation.balance)
                val card = session.productionActions.single { it.typeId == "mechFlame" }
                assertEquals("Flame mech", card.title)
                assertEquals(12000L, card.price)
                assertIs<ActionResult.Accepted>(session.produce(card.typeId))
                assertEquals(1000L, session.observation.balance)
                assertIs<ActionResult.Rejected>(session.produce(card.typeId))
                repeat(2550) { session.tick() }
                val produced = session.observation.units.single { it.typeId == "mechFlame" }
                assertEquals(2, assertNotNull(produced.mechPose).legs.size)
                assertTrue(produced.position.y > 250.0)
                session.select(produced.position)
                assertEquals(produced.unitId, session.selectedId)
                assertIs<ActionResult.Accepted>(session.move(Position(410.0, 270.0)))
                var crossed = false
                repeat(430) {
                    session.tick()
                    val unit = session.observation.units.single { it.unitId == produced.unitId }
                    if (unit.position.x > 380.0) crossed = true
                }
                assertTrue(crossed, format)
                assertIs<ActionResult.Accepted>(session.stop())
            }
            SandboxSession(load(format).scenario).use { reopened ->
                assertNull(reopened.selectedId)
                assertEquals(18000L, reopened.observation.balance)
                assertEquals(listOf("builder"), reopened.observation.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            }
        }
    }

    private fun load(format: String): LoadedSandbox {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("flame-mech-production/$format")
        val result = ContentLoader.loadContentPack(root.resolve("production-package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "builder"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("The normal flame-mech sandbox did not load")
        }
    }
}
