package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class MechFlameContentTest {
    @field:TempDir
    lateinit var directory: Path

    private val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    @Test fun the_normal_package_loads_one_inherited_flame_profile_in_all_three_formats() {
        for (format in listOf("ini", "json", "hjson")) {
            val manifest = fixtures.resolve("flame-mech-production/$format/production-package/pack.json")
            val result = ContentLoader.loadContentPack(manifest)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, (result as? ContentResult.Failure)?.error?.describe()).value
            val flame = pack.units.getValue("mechFlame")
            assertEquals(BuildProfile(12000L, .0005f), flame.build, format)
            assertEquals(4500.0, flame.maximumHealth, format)
            assertEquals(25.0, flame.radius, format)
            assertEquals(2, flame.transportSlots, format)
            assertEquals(.06f, flame.selfRegenRate, format)
            assertEquals(MovementType.OVER_CLIFF, flame.movement.type, format)
            assertEquals(listOf(-20.0, 20.0), assertNotNull(flame.mech).legs.map { it.x }, format)
            assertTrue(pack.sources.any { it.path.fileName.toString() == "mech-large-common.$format" }, format)
            val combat = assertNotNull(flame.combat)
            assertEquals(170.0, combat.attackRange, format)
            val weapons = assertNotNull(combat.namedWeapons).ordered
            assertEquals(listOf("1", "2", "3"), weapons.map { it.name }, format)
            assertFalse(weapons.first().canShoot, format)
            assertEquals(listOf(TurretMount(15.0, 30.0), TurretMount(-15.0, 30.0)), weapons.drop(1).map { it.mount }, format)
            assertEquals(listOf(0.0, 1.0), weapons.drop(1).map { it.warmup }, format)
            for (weapon in weapons.drop(1)) {
                assertTrue(weapon.slave, format)
                assertEquals("1", weapon.attachedTo, format)
                assertEquals(45.0, weapon.limitingAngle, format)
                assertEquals(5.0, weapon.shootDelay, format)
                assertTrue(weapon.projectile.nonDeflectable, format)
                assertEquals(70.0, weapon.projectile.life, format)
                assertEquals(LinearProjectileAcceleration(2.5, .3), weapon.projectile.acceleration, format)
                val ground = assertNotNull(weapon.projectile.ground)
                assertEquals(listOf(12f, 60f, 25f), listOf(ground.areaDamage, ground.areaRadius, ground.spread), format)
            }
        }
    }

    @Test fun inherited_resources_unknown_fields_and_bad_mounts_report_their_sources() {
        for (format in listOf("ini", "json", "hjson")) {
            val original = fixtures.resolve("flame-mech-production/$format/production-package")
            val isolated = directory.resolve(format)
            Files.walk(original).use { files ->
                files.forEach { source ->
                    val target = isolated.resolve(original.relativize(source))
                    if (Files.isDirectory(source)) Files.createDirectories(target)
                    else Files.copy(source, target)
                }
            }
            val root = isolated.resolve("units/flame/flame.$format")
            val common = root.resolveSibling("mech-large-common.$format")
            val base = Files.readString(root)
            val template = Files.readString(common)
            val changes = listOf(
                Triple("attachedTo", base.replace(if (format == "ini") "attachedTo: 1" else "\"attachedTo\": \"1\"",
                    if (format == "ini") "attachedTo: 9" else "\"attachedTo\": \"9\""), ContentErrorCode.BAD_REFERENCE),
                Triple("projectile", base.replace(if (format == "ini") "projectile: fireProjectile1" else "\"projectile\": \"fireProjectile1\"",
                    if (format == "ini") "projectile: unknown" else "\"projectile\": \"unknown\""), ContentErrorCode.BAD_REFERENCE),
                Triple("unhandledFlame", base.replace("flameWeapon", "unhandledFlame"), ContentErrorCode.UNSUPPORTED_FIELD),
            )
            for ((field, changed, code) in changes) {
                assertNotEquals(base, changed, format)
                Files.writeString(root, changed)
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(isolated)).error
                assertEquals(code, error.code, error.describe())
                assertEquals(field, error.field, error.describe())
                assertEquals(root.toAbsolutePath().normalize().toString(), error.file)
                assertNotNull(error.section)
            }
            Files.writeString(root, base)
            Files.writeString(common, template.replace("foot.png", "missing-foot.png"))
            val missing = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(isolated)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, missing.code, missing.describe())
            assertEquals("image_foot", missing.field)
            assertEquals("leg_1", missing.section)
            assertEquals(common.toAbsolutePath().normalize().toString(), missing.file)
        }
    }
}
