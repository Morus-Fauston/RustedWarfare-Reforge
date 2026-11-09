package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AirFactoryUpgradeContentTest {
    @Test fun three_formats_declare_the_paid_native_air_factory_upgrade_and_t2_product_menu() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("helicopter-production/json/production-package/units")
        val root = Files.createTempDirectory("reforge-air-upgrade-content-")
        try {
            for (name in listOf("air_factory.png", "air_factory_dead.png"))
                Files.copy(source.resolve(name), root.resolve(name))
            Files.copy(fixtures.resolve("amphibious-production/json/production-package/units/air_factory_t2.png"),
                root.resolve("air_factory_t2.png"))
            for (format in listOf("ini", "json", "hjson")) for (tier in listOf(1, 2)) {
                val fields = linkedMapOf("format" to "rw-reforge.native-air-factory.v2",
                    "name" to if (tier == 1) "airFactory" else "airFactoryT2", "techLevel" to "$tier",
                    "maxHp" to "1000", "radius" to "30", "footprint" to "-1,-1,1,1",
                    "placementFootprint" to "-1,-1,1,2", "movementType" to "NONE",
                    "productionUnits" to if (tier == 1) "helicopter" else "helicopter,amphibiousJet",
                    "price" to if (tier == 1) "1000" else "2500", "buildSpeed" to "0.001",
                    "fogOfWarSightRange" to "15", "image" to if (tier == 1) "air_factory.png" else "air_factory_t2.png", "image_dead" to "air_factory_dead.png")
                if (tier == 1) fields.putAll(mapOf("upgradeTo" to "airFactoryT2", "upgradePrice" to "1500",
                    "upgradeBuildSpeed" to "0.0004"))
                val file = root.resolve("factory-$tier.$format")
                Files.writeString(file, if (format == "ini") fields.entries.joinToString("\n") { "${it.key}=${it.value}" }
                    else fields.entries.joinToString(",\n", "{\n", "\n}") {
                        "\"${it.key}\":" + if (it.key in setOf("techLevel", "maxHp", "radius", "price", "buildSpeed",
                                "fogOfWarSightRange", "upgradePrice", "upgradeBuildSpeed")) it.value else "\"${it.value}\""
                    })
                val loaded = ContentLoader.loadFactory(file)
                val factory = assertIs<ContentResult.Success<UnitDefinition>>(loaded, "$format/$tier: $loaded").value
                assertEquals(if (tier == 1) "airFactory" else "airFactoryT2", factory.id)
                assertEquals(1000.0, factory.maximumHealth)
                assertEquals(FactoryExitProfile.NATIVE_AIR, factory.production!!.exitProfile)
                assertEquals(if (tier == 1) listOf("helicopter") else listOf("helicopter", "amphibiousJet"), factory.production.unitTypeIds)
                assertEquals(BuildProfile(if (tier == 1) 1000 else 2500, .001f), factory.build)
                assertEquals(if (tier == 1) listOf(UpgradeActionProfile("upgrade_t2", "airFactoryT2", 1500, .0004f))
                    else emptyList(), factory.upgrades?.actions.orEmpty())
            }
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach { path ->
                require(path.toAbsolutePath().normalize().startsWith(root)); Files.delete(path)
            } }
        }
    }
}
