package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class LandingCraftCapacityTest {
    @Test fun declared_three_slot_passenger_and_one_worker_fill_four_slots_without_four_people() = WaterTestScene.withMap { map ->
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("transport-production/$format/production-package")
            val root = Files.createTempDirectory("reforge-transport-slots-").toAbsolutePath().normalize()
            try {
                Files.walk(source).use { paths -> paths.forEach {
                    val target = root.resolve(source.relativize(it))
                    if (Files.isDirectory(it)) Files.createDirectories(target) else Files.copy(it, target)
                } }
                val file = root.resolve("units/land/tank.$format")
                val text = Files.readString(file)
                Files.writeString(file, if (format == "ini") text.replace("[core]", "[core]\ntransportSlotsNeeded: 3")
                    else text.replace("\"core\": {", "\"core\": {\n    \"transportSlotsNeeded\": 3,"))
                val load = ContentLoader.loadContentPack(root)
                val content = assertIs<ContentResult.Success<LoadedContentPack>>(load, load.toString()).value
                assertEquals(3, content.units.getValue("c_tank").transportSlots)
                assertTrue("transportSlotsNeeded" !in content.units.getValue("c_tank").retainedUnconsumed)
                val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
                    UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0),
                    UnitSpawn("tank", "p1", "c_tank", Position(200.0, 150.0), 90.0),
                    UnitSpawn("worker", "p1", "builder", Position(200.0, 150.0), 90.0),
                    UnitSpawn("extra", "p1", "builder", Position(200.0, 150.0), 90.0)))
                    .copy(unitDefinitions = content.units))
                try {
                    for (id in listOf("tank", "worker")) assertIs<ActionResult.Accepted>(context.submit(
                        LoadAction("load-$id", "p1", id, "craft", LoadDirection.INTO, context.world)))
                    val full = context.observe().units.single { it.unitId == "craft" }.transport!!
                    assertEquals(2, full.passengerCount)
                    assertEquals(4, full.occupiedSlots)
                    assertEquals("transport-full", assertIs<ActionResult.Rejected>(context.submit(
                        LoadAction("full", "p1", "extra", "craft", LoadDirection.INTO, context.world))).error.code)
                    assertNull(context.observe().units.single { it.unitId == "extra" }.carriedBy)
                    assertEquals(full, context.observe().units.single { it.unitId == "craft" }.transport)
                } finally { context.close() }
            } finally {
                require(root.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }
}
