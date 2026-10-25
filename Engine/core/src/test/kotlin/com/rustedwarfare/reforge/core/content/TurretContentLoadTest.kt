package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TurretContentLoadTest {
    private data class BadValue(val from: String, val to: String, val code: ContentErrorCode, val field: String)
    private fun source(): Path = inputs().parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261025-subscription-e-contract-03/input/units/turret_t1.ini")

    @Test
    fun loads_real_child_and_template_as_an_armed_ordinary_fixed_building() {
        val tower = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(source())).value
        assertEquals("c_turret_t1", tower.id)
        assertEquals(700.0, tower.maximumHealth)
        assertEquals(16.0, tower.radius)
        assertEquals(16.0, tower.displayRadius)
        assertEquals(DirectHitTargetKind.ORDINARY, tower.directHitTargetKind)
        assertEquals(MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), tower.movement)
        val building = assertNotNull(tower.building)
        assertEquals(BuildingFootprint(0, 0, 1, 1), building.footprint)
        assertEquals(FootprintAnchor(20f, 20f), building.anchor)
        assertNull(building.backImagePath)
        assertNull(tower.production)
        assertNull(tower.build)
        val weapon = assertNotNull(tower.combat)
        assertEquals(listOf(165.0, 30.0, 22.0, 6.0, 0.0), listOf(weapon.attackRange, weapon.shootDelay,
            weapon.turretSize, weapon.turretTurnSpeed, weapon.turretTurnAcceleration))
        assertEquals(ProjectileProfile(41.0, 240.0, 6.0), weapon.projectile)
        assertEquals(TurretMount(0.0, 6.0), weapon.mount)
        assertEquals(WeaponRecoil(-.5, 6.0, 12.0), weapon.recoil)
        assertEquals(IdleSweepProfile(20.0, 210f, .2f, 20), weapon.idleSweep)
        assertTrue(listOf(tower.imagePath, weapon.turretImagePath, weapon.deadImagePath).all(Files::isRegularFile))
        assertTrue(listOf("movement.moveAccelerationSpeed", "movement.moveDecelerationSpeed", "movement.turnAcceleration")
            .all { it in tower.retainedUnconsumed })
        assertTrue(tower.retainedUnconsumed.none { it.startsWith("turret_1.idleSweep") })
    }

    @Test
    fun template_is_not_a_loadable_unit_and_does_not_disable_the_child() {
        val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(source().resolveSibling("turret_common_land.ini"))).error
        assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
        assertEquals("dont_load", error.field)
        assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(source()))
    }

    @Test
    fun turret_entry_rejects_tank_without_loosening_the_existing_tank_entry() {
        val tank = source().resolveSibling("tank.ini")
        val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(tank)).error
        assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
        assertEquals("name", error.field)
        val original = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(tank)).value
        assertEquals(TurretMount(), original.combat!!.mount)
        assertEquals(WeaponRecoil(), original.combat.recoil)
        assertNull(original.combat.idleSweep)
    }

    @Test
    fun rejects_duplicate_and_unknown_fields_before_publishing_a_definition() {
        for ((from, to, code, field) in listOf(
            BadValue("maxHp: 700", "maxHp: 700\nmaxHp: 700", ContentErrorCode.MALFORMED_SYNTAX, "maxHp"),
            BadValue("directDamage: 41", "directDamage: 41\nareaDamage: 80", ContentErrorCode.UNSUPPORTED_FIELD, "areaDamage"),
        )) withText(child = { it.replace(from, to) }) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error
            assertEquals(code, error.code)
            assertEquals(field, error.field)
        }
    }

    @Test
    fun rejects_nonfinite_numbers_and_actual_motion_on_the_fixed_building() {
        for ((from, to, code, field) in listOf(
            BadValue("maxHp: 700", "maxHp: NaN", ContentErrorCode.MALFORMED_SYNTAX, "maxHp"),
            BadValue("shootDelay: 30", "shootDelay: 0", ContentErrorCode.MALFORMED_SYNTAX, "shootDelay"),
        )) withText(child = { it.replace(from, to) }) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error
            assertEquals(code, error.code)
            assertEquals(field, error.field)
        }
        withText(common = { it.replace("moveSpeed: 0", "moveSpeed: 1") }) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
            assertEquals("moveSpeed", error.field)
        }
        withText(common = { it.replace("turnAcceleration: 0.1", "turnAcceleration: Infinity") }) { path ->
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX,
                assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error.code)
        }
    }

    @Test
    fun rejects_unordered_footprints_and_unequal_turn_deceleration() {
        withText(common = { it.replace("footprint: 0,0,1,1", "footprint: 1,0,0,1") }) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
            assertEquals("footprint", error.field)
        }
        withText(child = { it.replace("[turret_1]", "[turret_1]\nturnSpeedDeceleration: 1") }) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
            assertEquals("turnSpeedDeceleration", error.field)
        }
    }

    @Test
    fun confines_inheritance_and_images_to_ordinary_files_in_the_content_directory() {
        for ((from, to, field) in listOf(
            Triple("copyFrom: turret_common_land.ini", "copyFrom: ../turret_common_land.ini", "copyFrom"),
            Triple("image_turret: turret_top.png", "image_turret: ../turret_top.png", "image_turret"),
        )) withText(child = { it.replace(Regex("(?m)^" + Regex.escape(from.substringBefore(':')) + ":.*$"), to) }) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
            assertEquals(field, error.field)
        }
        withText(common = { it.replace("[core]", "[core]\ncopyFrom: turret_t1.ini") }) { path ->
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE,
                assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error.code)
        }
    }

    @Test
    fun rejects_missing_and_corrupt_png_instead_of_publishing_a_broken_appearance() {
        withText(child = { it.replace(Regex("(?m)^image_turret:.*$"), "image_turret: missing.png") }) { path ->
            assertEquals(ContentErrorCode.BAD_REFERENCE,
                assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error.code)
        }
        withText { path ->
            val png = path.resolveSibling("turret_top.png")
            val bytes = Files.readAllBytes(png)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(png, bytes)
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
            assertEquals("image_turret", error.field)
        }
    }

    private fun withText(child: (String) -> String = { it }, common: (String) -> String = { it }, check: (Path) -> Unit) {
        val directory = Files.createTempDirectory("eng003-turret-").toRealPath()
        try {
            Files.list(source().parent).use { files -> files.filter(Files::isRegularFile).forEach {
                Files.copy(it, directory.resolve(it.fileName))
            } }
            val path = directory.resolve("turret_t1.ini")
            Files.writeString(path, child(Files.readString(path)))
            val template = path.resolveSibling("turret_common_land.ini")
            Files.writeString(template, common(Files.readString(template)))
            check(path)
        } finally {
            check(directory.parent == Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                check(it.toAbsolutePath().normalize().startsWith(directory))
                Files.delete(it)
            } }
        }
    }
}
