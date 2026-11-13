package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.*

class DropshipPackContentTest {
    @Test fun all_three_public_packs_load_the_finite_tier_two_air_transport_menu() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPacks(listOf(fixtures.resolve("air-transport-production/$format/production-package")))
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(result, "$format: $result").value
            assertEquals(listOf("helicopter"), content.units.getValue("airFactory").production!!.unitTypeIds)
            assertEquals(listOf("helicopter", "amphibiousJet", "dropship"),
                content.units.getValue("airFactoryT2").production!!.unitTypeIds)
            assertEquals(TransportProfile.NATIVE_DROPSHIP, content.units.getValue("dropship").transport)
            assertIs<ContentResult.Success<Unit>>(content.verifyStable())
        }
    }
}
