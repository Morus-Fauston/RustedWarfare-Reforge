package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class MechFlameProductionContentTest {
    @field:TempDir
    lateinit var directory: Path

    @Test fun only_the_normal_t2_factory_can_offer_the_flame_mech() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val existing = fixtures.resolve("heavy-mech-production/ini/production-package/units")
        for (name in listOf("factory.png", "factory_back.png", "factory_dead.png", "factory_t2.png")) {
            Files.copy(existing.resolve(name), directory.resolve(name))
        }
        for ((name, products) in listOf(
            "mechFactory.ini" to "builder,mechGun",
            "mechFactoryT2.ini" to "builder,mechGun,mechHeavyMissile",
        )) {
            val path = directory.resolve(name)
            val source = Files.readString(existing.resolve(name))
            assertContains(source, products)
            Files.writeString(path, source.replace(products, "$products,mechFlame"))
            val result = ContentLoader.loadFactoryWithSources(path)
            if (name == "mechFactory.ini") {
                val error = assertIs<ContentResult.Failure>(result).error
                assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
                assertEquals("productionUnits", error.field)
            } else {
                val definition = assertIs<ContentResult.Success<LoadedUnitContent>>(result).value.definition
                assertEquals(listOf("builder", "mechGun", "mechHeavyMissile", "mechFlame"), definition.production?.unitTypeIds)
                assertEquals(2, definition.techLevel)
            }
        }
    }
}
