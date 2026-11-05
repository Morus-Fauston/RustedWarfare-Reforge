package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.*

class HeavyMechFactoryContentTest {
    @Test fun three_formats_compose_the_builder_t1_upgrade_t2_and_heavy_production_chain() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPack(fixtures.resolve("heavy-mech-production/$format/production-package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
            val content = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.composeContentPacks(listOf(pack))).value
            assertEquals(setOf("builder", "mechGun", "mechFactory", "mechFactoryT2", "mechHeavyMissile", "helicopter"), content.units.keys)
            assertNotNull(content.units.getValue("helicopter").rotor)
            val t1 = content.units.getValue("mechFactory")
            val t2 = content.units.getValue("mechFactoryT2")
            assertEquals(UpgradeActionProfile("upgrade_t2", "mechFactoryT2", 4000L, .0004f), assertNotNull(t1.upgrades).actions.single())
            assertNull(t2.upgrades)
            assertEquals(1800.0, t1.maximumHealth)
            assertEquals(3100.0, t2.maximumHealth)
            assertEquals(BuildProfile(1000L, .001f), t1.build)
            assertEquals(BuildProfile(5000L, .0005f), t2.build)
            assertEquals(ConstructionProfile(5000L, .0005f), t2.construction)
            assertEquals(listOf("builder", "mechGun"), assertNotNull(t1.production).unitTypeIds)
            assertEquals(listOf("builder", "mechGun", "mechHeavyMissile"), assertNotNull(t2.production).unitTypeIds)
            assertEquals(FactoryExitProfile.CUSTOM_MECH, t2.production!!.exitProfile)
            assertEquals(t1.building!!.footprint, t2.building!!.footprint)
            assertEquals(t1.building!!.placementFootprint, t2.building!!.placementFootprint)
            assertEquals(DirectHitTargetKind.ORDINARY, t2.directHitTargetKind)
            assertEquals(listOf("mechFactory"), content.units.getValue("builder").builder!!.buildingTypeIds)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
