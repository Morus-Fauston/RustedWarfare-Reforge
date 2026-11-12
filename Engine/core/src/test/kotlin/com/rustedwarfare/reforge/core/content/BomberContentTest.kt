package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class BomberContentTest {
    @field:TempDir
    lateinit var directory: Path

    private val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    @Test fun three_formats_expose_the_two_distinct_bomber_weapons_through_a_normal_content_pack() {
        for (format in listOf("ini", "json", "hjson")) {
            val manifest = fixtures.resolve("bomber-production/$format/package/pack.json")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(manifest), format).value
            val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(
                ContentLoader.loadBomberWithSources(manifest.resolveSibling("bomber.$format"))).value
            assertEquals(listOf("bomber.$format"), loaded.sources.map { it.path.fileName.toString() }, format)
            assertEquals(setOf("bomber.$format", "bomber_body.png", "bomber_dead.png", "bomber_shadow.png"),
                pack.sources.map { it.path.fileName.toString() }.filter { it.startsWith("bomber") }.toSet(), format)
            assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
            val bomber = pack.units.getValue("bomber")
            assertEquals(BuildProfile(4000L, .00025f), bomber.build, format)
            assertEquals(2, bomber.techLevel, format)
            assertEquals(MovementType.AIR, bomber.movement.type, format)
            assertEquals(3000.0, bomber.maximumHealth, format)
            val combat = assertNotNull(bomber.combat)
            assertEquals(45.0, combat.attackRange, format)
            val weapons = assertNotNull(combat.namedWeapons)
            assertEquals(listOf("1", "2"), weapons.ordered.map { it.name }, format)
            val ground = weapons.byName.getValue("1")
            val underwater = weapons.byName.getValue("2")
            assertTrue(ground.canAttackLandUnits)
            assertFalse(ground.canAttackUnderwaterUnits)
            assertFalse(underwater.canAttackLandUnits)
            assertTrue(underwater.canAttackUnderwaterUnits)
            assertTrue(weapons.ordered.none { it.canAttackFlyingUnits })
            val impact = assertNotNull(ground.projectile.ground)
            assertEquals(370f, impact.areaDamage)
            assertEquals(65f, impact.areaRadius)
            assertNull(underwater.projectile.ground)
            assertEquals(70.0, underwater.projectile.directDamage)
            for (weapon in weapons.ordered) {
                assertEquals(30.0, weapon.shootDelay)
                assertEquals(1f, weapon.energyUsage)
                assertEquals(250.0, weapon.projectile.life)
                assertEquals(.3, weapon.projectile.speed)
                assertEquals(LinearProjectileAcceleration(1.7, .1), weapon.projectile.acceleration)
            }
        }
    }

    @Test fun malformed_bomber_content_reports_the_offending_section_and_field() {
        val original = Files.readString(fixtures.resolve("bomber-production/ini/package/bomber.ini"))
        val input = directory.resolve("bomber.ini")
        val malformed = listOf(
            Triple(original.replace("projectile: 1", "projectile: 9"), ContentErrorCode.BAD_REFERENCE, "projectile"),
            Triple(original.replace("drawSize: 1", "drawSize: 2"), ContentErrorCode.UNSUPPORTED_FEATURE, "drawSize"),
            Triple(original.replace("trailEffect: false", "unknownTrail: false"), ContentErrorCode.UNSUPPORTED_FIELD, "unknownTrail"),
            Triple(original.replace("bomber_body.png", "missing.png"), ContentErrorCode.BAD_REFERENCE, "image"),
        )
        Files.copy(fixtures.resolve("bomber-production/ini/package/bomber_dead.png"), directory.resolve("bomber_dead.png"))
        for ((text, code, field) in malformed) {
            Files.writeString(input, text)
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadBomberWithSources(input)).error
            assertEquals(code, error.code, error.describe())
            assertEquals(field, error.field)
            assertEquals(input.toString(), error.file)
            assertNotNull(error.section)
        }
    }
}
