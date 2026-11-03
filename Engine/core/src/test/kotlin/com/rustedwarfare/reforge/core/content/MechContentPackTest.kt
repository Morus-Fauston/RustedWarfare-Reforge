package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MechContentPackTest {
    @Test
    fun malformed_leg_image_path_returns_a_field_diagnostic_and_a_repaired_pack_loads_again() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("mech-production/ini/package")
        val temporary = Files.createTempDirectory("p008-mech-bad-leg-path-").toRealPath()
        require(temporary.parent == Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
        try {
            Files.walk(source).use { paths -> paths.forEach { file ->
                val destination = temporary.resolve(source.relativize(file)).normalize()
                require(destination.startsWith(temporary))
                if (Files.isDirectory(file)) Files.createDirectories(destination) else Files.copy(file, destination)
            } }
            val input = temporary.resolve("units/mech.ini")
            val valid = Files.readString(input)
            Files.writeString(input, valid.replace("image_leg: leg.png", "image_leg: broken\u0000.png"))
            val result = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(temporary))
            assertEquals(ContentErrorCode.BAD_REFERENCE, result.error.code)
            assertEquals("leg_1", result.error.section)
            assertEquals("image_leg", result.error.field)
            Files.writeString(input, valid)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(temporary))
        } finally {
            val paths = Files.walk(temporary).use { it.sorted(Comparator.reverseOrder()).toList() }
            for (path in paths) { require(path.normalize().startsWith(temporary)); Files.delete(path) }
        }
    }

    @Test
    fun declared_two_legged_mech_loads_through_the_real_package_and_preserves_combat_and_production_values() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("mech-production/ini/package"))).value
        val mech = pack.units.getValue("mechGun")
        assertEquals(BuildProfile(600L, .0012f), mech.build)
        assertEquals(500.0, mech.maximumHealth)
        assertEquals(13.0, mech.radius)
        assertEquals(.07, mech.movement.moveDecelerationSpeed)
        val combat = assertNotNull(mech.combat)
        assertEquals(130.0, combat.attackRange)
        val weapon = assertNotNull(combat.namedWeapons).byName.getValue("mainBody")
        assertEquals(7.0, weapon.warmup)
        assertEquals(16.0, weapon.turretSize)
        assertEquals(5.0, weapon.barrelX)
        assertEquals(23.0, weapon.projectile.directDamage)
        assertEquals(0.0, weapon.projectile.speed)
        assertNull(combat.turretImagePath)
        assertNull(combat.deadImagePath)
    }
}
