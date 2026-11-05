package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.*

class NavalBuilderContentTest {
    @Test fun three_formats_load_the_owned_native_builder_with_only_t1_sea_construction() {
        val root=System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini","json","hjson")) {
            val pack=assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                root.resolve("naval-production/$format/production-package"))).value
            val builder=pack.units.getValue("builder")
            assertEquals(listOf("seaFactory"),builder.builder!!.buildingTypeIds)
            assertEquals(MovementType.LAND,builder.movement.type)
            assertEquals(BuildProfile(500L,.002f),builder.build)
            assertEquals(170.0,builder.maximumHealth)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
