package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AaBeamProductionTest {
    @TempDir lateinit var temporary: Path

    @Test fun three_formats_build_and_upgrade_a_paid_factory_before_producing_the_6000_credit_gunship() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("amphibious-production/$format/production-package")
            val root = temporary.resolve("factory-$format")
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = root.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            val factoryInput = root.resolve("units/airFactoryT2.$format")
            val original = Files.readString(factoryInput)
            val modified = original.replace("helicopter,amphibiousJet", "aaBeamGunship")
            assertNotEquals(original, modified)
            Files.writeString(factoryInput, modified)
            val loaded = ContentLoader.loadContentPacks(listOf(root, AaBeamContentFixture.write(temporary.resolve("aa-$format"), format)))
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, loaded.toString()).value
            val context = AaBeamScene.open(AmphibiousJetScene.scenario(format).copy(unitDefinitions = content.units))
            try {
                var step = 0L
                fun until(predicate: (Observation) -> Boolean) {
                    while (!predicate(context.observe()) && step < 10000L) context.advance(Step(step++, 1.0, "sim-tick"))
                    assertTrue(predicate(context.observe()), "$format stopped at $step")
                }
                val factory = "constructed:p1:${"a".repeat(32)}"
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "airFactory",
                    Position(270.0, 150.0), "a".repeat(32), context.world)))
                until { it.units.any { unit -> unit.unitId == factory && unit.constructionProgress == 1f } }
                assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Rejected>(context.submit(ProduceAction("early", "p1", factory, "aaBeamGunship", "b".repeat(32), context.world)))
                assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", factory, "upgrade_t2", context.world)))
                assertEquals(7500L, context.observePlayer(context.world, "p1").balance)
                until { it.units.any { unit -> unit.unitId == factory && unit.typeId == "airFactoryT2" } }
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce", "p1", factory, "aaBeamGunship", "c".repeat(32), context.world)))
                assertEquals(1500L, context.observePlayer(context.world, "p1").balance)
                val id = "produced:p1:${"c".repeat(32)}"
                until { it.units.any { unit -> unit.unitId == id } }
                val birth = context.observe().units.single { it.unitId == id }
                assertEquals(2500.0, birth.health)
                assertEquals(Position(270.0, 155.0), birth.position)
                assertEquals(0.0, birth.height)
                assertEquals(EnergyObservation(6f, 6f, false), birth.energy)
                assertEquals(UnitTaskKind.MOVE, birth.tasks.single().kind)
            } finally { context.close() }
        }
    }
}
