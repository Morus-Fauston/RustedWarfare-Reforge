package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class NautilusAcquisitionTest {
    @Test fun the_native_sea_factory_requires_its_real_paid_t2_upgrade_before_the_18000_credit_mobile_producer_can_be_born() = WaterTestScene.withMap { map ->
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPack(root.resolve("nautilus-production/$format/production-package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
            val scene = SeaFactoryScene.scenario(map, format).copy(unitDefinitions = pack.units,
                economyContract = EconomyContract(mapOf("p1" to 30000L, "p2" to 0L)))
            val c = NautilusScene.open(scene)
            try {
                assertIs<ActionResult.Rejected>(c.submit(ProduceAction("early", "p1", "factory", "nautilusSubmarine", "a".repeat(32), c.world)))
                assertEquals(30000L, c.observePlayer(c.world, "p1").balance)
                assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("t2", "p1", "factory", "upgrade_t2", c.world)))
                var step = 0L
                while (c.observe().units.single().typeId != "seaFactoryT2" && step < 2600) c.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals("seaFactoryT2", c.observe().units.single().typeId)
                assertEquals(28000L, c.observePlayer(c.world, "p1").balance)
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("mother", "p1", "factory", "nautilusSubmarine", "b".repeat(32), c.world)))
                assertEquals(10000L, c.observePlayer(c.world, "p1").balance)
                val id = "produced:p1:${"b".repeat(32)}"
                while (c.observe().units.none { it.unitId == id } && step < 6000) c.advance(Step(step++, 1.0, "sim-tick"))
                val born = c.observe().units.single { it.unitId == id }
                assertEquals(4000.0, born.health)
                assertEquals(EnergyObservation(6f, 6f, false), born.energy)
                assertEquals(3, born.transport!!.capacity)
                assertEquals(0, born.transport.passengerCount)
                assertEquals(180.0, born.heading)
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("child", "p1", id, "hoverTank", "c".repeat(32), c.world)))
                repeat(250) { c.advance(Step(step++, 1.0, "sim-tick")) }
                assertEquals(1, c.observe().units.single { it.unitId == id }.transport!!.passengerCount)
                assertEquals(9550L, c.observePlayer(c.world, "p1").balance)
            } finally { c.close() }
        }
    }
}
