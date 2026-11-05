package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechFactoryProfileTest {
    @Test
    fun a_custom_exit_cannot_be_attached_to_an_unrelated_factory_or_a_relaxed_radius_variant() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("mech-production/ini")
        val definitions = assertIs<ContentResult.Success<LoadedContentSet>>(
            ContentLoader.loadContentPacks(listOf(root.resolve("production-package")))).value.units
        val source = definitions.getValue("mechFactory")
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))).value
        for (variant in listOf("foreign", "radius", "defense", "upgrade", "explosion", "native-target", "damage-cap")) {
            val bad = UnitDefinition(if (variant == "foreign") "otherFactory" else source.id, source.className,
                if (variant in listOf("foreign", "radius")) 30.0 else 35.0, source.displayRadius, source.totalFrames, source.imagePath, source.imageScale,
                source.movement, source.retainedUnconsumed, source.fogOfWarSightRange,
                durability = if (variant == "damage-cap") source.durability!!.copy(maxDamagePerHit = 10.0) else source.durability,
                building = source.building, production = source.production,
                build = source.build, construction = source.construction,
                directHitTargetKind = if (variant == "native-target") DirectHitTargetKind.NATIVE_BUILDING else source.directHitTargetKind,
                pointDefense = if (variant == "defense") PointDefenseProfile() else null,
                upgrades = if (variant == "upgrade") UpgradeProfile(listOf(UpgradeActionProfile("upgrade", "mechFactory", 100L, .001f))) else null,
                deathExplosion = if (variant == "explosion") DeathExplosionProfile(100f, 50f, 10f) else null)
            val scene = Scenario("p008-mech-profile", map, definitions + (bad.id to bad),
                listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
                listOf(UnitSpawn("worker", "p1", "builder", Position(190.0, 150.0), 90.0)),
                CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
                TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
                combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
            val result = SimulationContext.initialize(scene)
            if (result is InitResult.Accepted) result.context.close()
            assertEquals(when (variant) {
                "foreign", "radius" -> "unsupported-production"
                "upgrade" -> "unsupported-upgrade"
                else -> "unsupported-construction"
            },
                assertIs<InitResult.Rejected>(result, variant).error.code, variant)
        }
    }
}
