package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RepairBayConstructionTest {
    @Test fun ordinary_builder_pays_1500_and_completes_the_native_bay_in_all_three_syntaxes() {
        for (format in listOf("ini", "json", "hjson")) {
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                root().resolve("repairbay-production/$format/production-package"))).value
            assertEquals(listOf("repairbay"), pack.units.getValue("builder").builder!!.buildingTypeIds)
            val base = CombatTestContent.scenario()
            val scene = base.copy(unitDefinitions = pack.units,
                units = listOf(UnitSpawn("worker", "p1", "builder", Position(110.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val id = "constructed:p1:${"a".repeat(32)}"
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "repairbay",
                    Position(170.0, 210.0), "a".repeat(32), context.world)))
                context.advance(Step(0L, 1.0, "sim-tick"))
                assertEquals(14500L, context.observePlayer(context.world, "p1").balance)
                assertEquals(0f, context.observe().units.single { it.unitId == id }.constructionProgress)
                for (step in 1L..1100L) context.advance(Step(step, 1.0, "sim-tick"))
                val bay = context.observe().units.single { it.unitId == id }
                assertEquals("repairbay", bay.typeId)
                assertEquals(1f, bay.constructionProgress)
                assertEquals(1000.0, bay.health)
                assertEquals(14500L, context.observePlayer(context.world, "p1").balance)
            } finally { context.close() }
        }
    }

    private fun root(): Path = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("SimulationTest/public")) }.resolve("SimulationTest/public/inputs")
}
