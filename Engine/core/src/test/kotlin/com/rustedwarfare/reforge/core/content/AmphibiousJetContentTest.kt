package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.*

class AmphibiousJetContentTest {
    @Test fun three_formats_load_the_native_jet_with_two_firing_weapons_and_normal_t2_factory_reference() {
        for (format in listOf("ini", "json", "hjson")) {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
            val loaded = ContentLoader.loadContentPacks(listOf(fixtures.resolve("amphibious-production/$format/production-package")))
            val pack = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, "$format: $loaded").value
            val jet = pack.units.getValue("amphibiousJet")
            assertEquals("NativeAmphibiousJet", jet.className)
            assertEquals(530.0, jet.maximumHealth)
            assertEquals(12.0, jet.radius)
            assertEquals(13.0, jet.displayRadius)
            assertEquals(BuildProfile(2000, .001f), jet.build)
            assertEquals(MovementType.AIR, jet.movement.type)
            val weapons = jet.combat!!.namedWeapons!!.ordered.filter { it.canShoot }
            assertEquals(2, weapons.size)
            assertEquals(listOf(25.0, 35.0), weapons.map { it.warmup })
            assertTrue(weapons.all { it.projectile.instant && it.projectile.directDamage == 45.0 && it.projectile.life == 10.0 })
            assertEquals(listOf("helicopter", "amphibiousJet"), pack.units.getValue("airFactoryT2").production!!.unitTypeIds)
            assertEquals("airFactoryT2", pack.units.getValue("airFactory").upgrades!!.actions.single().targetTypeId)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
