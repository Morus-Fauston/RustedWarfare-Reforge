package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TurretContentLoadTest {
    private fun source(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261026-e-turret-contract-01/input/units")

    @Test
    fun reads_real_two_layer_definition_defaults_images_and_every_retained_field() {
        val tower = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(source().resolve("turret_t1.ini"))).value
        assertEquals("c_turret_t1", tower.id); assertEquals("CustomUnitMetadata", tower.className)
        assertEquals(700.0, tower.maximumHealth); assertEquals(16.0, tower.radius); assertEquals(16.0, tower.displayRadius)
        assertEquals(15, tower.fogOfWarSightRange); assertEquals(1, tower.totalFrames)
        assertEquals(1.0, tower.imageScale); assertEquals(DirectHitTargetKind.ORDINARY, tower.directHitTargetKind)
        assertEquals(MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), tower.movement)
        assertEquals(BuildingFootprint(0, 0, 1, 1), tower.building!!.footprint)
        assertEquals(FootprintAnchor(20f, 20f), tower.building.anchor); assertNull(tower.building.backImagePath)
        assertNull(tower.production); assertNull(tower.build)
        val weapon = tower.combat!!
        assertEquals(listOf(700.0, 165.0, 30.0, 22.0, 6.0, 0.0, 1.0),
            listOf(weapon.maxHp, weapon.attackRange, weapon.shootDelay, weapon.turretSize,
                weapon.turretTurnSpeed, weapon.turretTurnAcceleration, weapon.turretImageScale))
        assertEquals(TurretMount(0.0, 6.0), weapon.mount); assertEquals(WeaponRecoil(-.5, 6.0, 12.0), weapon.recoil)
        assertEquals(IdleSweepProfile(20.0, 210f, .2f, 20), weapon.idleSweep)
        assertEquals(ProjectileProfile(41.0, 240.0, 6.0), weapon.projectile)
        assertEquals(DurabilityProfile(700.0, weapon.deadImagePath), tower.durability)
        assertTrue(listOf(tower.imagePath, weapon.turretImagePath, weapon.deadImagePath).all { it.isAbsolute && Files.isRegularFile(it) })
        val common = setOf("core.price", "core.buildSpeed", "core.mass", "core.techLevel", "core.isBio",
            "core.displayLocaleKey", "core.buildingSelectionOffset", "graphics.image_shadow", "graphics.shadowOffsetX",
            "graphics.shadowOffsetY", "turret_1.shoot_sound", "turret_1.shoot_sound_vol", "turret_1.shoot_flame",
            "turret_1.shoot_light", "projectile_1.frame", "movement.moveAccelerationSpeed", "movement.moveDecelerationSpeed",
            "movement.turnAcceleration", "ai.upgradedFrom", "ai.buildPriority", "ai.noneInBaseExtraPriority", "ai.maxEachBase")
        val actions = listOf("gun", "artillery", "flame", "lightning").flatMap { action ->
            listOf("convertTo", "price", "text", "description", "descriptionAddUnitStats", "displayType", "buildSpeed",
                "iconExtraImage", "iconExtraColor").map { "action_upgrade_$action.$it" }
        }.toSet()
        assertEquals(common + actions, tower.retainedUnconsumed)
        assertFailsWith<UnsupportedOperationException> { (tower.retainedUnconsumed as MutableSet<String>).clear() }
    }

    @Test
    fun rejects_duplicate_unknown_malformed_missing_and_nonfinite_values_at_their_source() {
        data class Case(val parent: Boolean, val from: String, val to: String, val code: ContentErrorCode)
        for ((index, case) in listOf(
            Case(false, "maxHp: 700", "maxHp: 700\nmaxHp: 700", ContentErrorCode.MALFORMED_SYNTAX),
            Case(false, "[graphics]", "[core]\n[graphics]", ContentErrorCode.MALFORMED_SYNTAX),
            Case(false, "maxHp: 700", "unknown: 1", ContentErrorCode.UNSUPPORTED_FIELD),
            Case(false, "[projectile_1]", "[projectile_2]", ContentErrorCode.UNSUPPORTED_FIELD),
            Case(false, "maxHp: 700", "maxHp 700", ContentErrorCode.MALFORMED_SYNTAX),
            Case(false, "maxHp: 700", "", ContentErrorCode.MISSING_REQUIRED_FIELD),
            Case(false, "maxHp: 700", "maxHp: NaN", ContentErrorCode.MALFORMED_SYNTAX),
            Case(false, "recoilOutTime: 6", "recoilOutTime: 0", ContentErrorCode.MALFORMED_SYNTAX),
            Case(false, "idleSweepSpeed:0.2", "idleSweepSpeed:1e-50", ContentErrorCode.MALFORMED_SYNTAX),
            Case(false, "x: 0", "x: Infinity", ContentErrorCode.MALFORMED_SYNTAX),
            Case(false, "y: 6", "y: 6\nturnSpeedAcceleration:2\nturnSpeedDeceleration:3", ContentErrorCode.UNSUPPORTED_FEATURE),
            Case(false, "name: c_turret_t1", "name: c_turret_t1\ndont_load:true", ContentErrorCode.UNSUPPORTED_FEATURE),
            Case(true, "movementType: NONE", "movementType: LAND", ContentErrorCode.UNSUPPORTED_FEATURE),
            Case(true, "moveSpeed: 0", "moveSpeed: 1", ContentErrorCode.UNSUPPORTED_FEATURE),
            Case(true, "maxTurnSpeed: 0", "maxTurnSpeed: 1", ContentErrorCode.UNSUPPORTED_FEATURE),
            Case(true, "footprint: 0,0,1,1", "footprint: 1,0,1,1", ContentErrorCode.MALFORMED_SYNTAX),
            Case(true, "constructionFootprint:0,0,1,1", "constructionFootprint:0,0,2,1", ContentErrorCode.UNSUPPORTED_FEATURE),
            Case(true, "buildingToFootprintOffsetX:20", "buildingToFootprintOffsetX:1e99", ContentErrorCode.MALFORMED_SYNTAX),
            Case(true, "dont_load:true", "dont_load:true\ncopyFrom:turret_common_land.ini", ContentErrorCode.UNSUPPORTED_FEATURE)
        ).withIndex()) withCopy { root ->
            val file = root.resolve(if (case.parent) "turret_common_land.ini" else "turret_t1.ini")
            val text = Files.readString(file); assertTrue(text.contains(case.from), "fixture=$index")
            Files.writeString(file, text.replace(case.from, case.to))
            assertEquals(case.code, assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(root.resolve("turret_t1.ini")),
                "case=$index").error.code, "case=$index")
        }
    }

    @Test
    fun rejects_bad_paths_missing_images_and_corrupt_png_instead_of_falling_back() {
        for (reference in listOf("../turret_common_land.ini", "SHARED:turret_common_land.ini", "C:/turret_common_land.ini")) withCopy { root ->
            val file = root.resolve("turret_t1.ini")
            Files.writeString(file, Files.readString(file).replace("copyFrom: turret_common_land.ini", "copyFrom: $reference"))
            assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(file))
        }
        for (name in listOf("turret_base.png", "turret_base_dead.png", "turret_top.png")) for (missing in listOf(true, false)) withCopy { root ->
            if (missing) Files.delete(root.resolve(name)) else Files.write(root.resolve(name), byteArrayOf(1, 2, 3))
            assertEquals(ContentErrorCode.BAD_REFERENCE, assertIs<ContentResult.Failure>(
                ContentLoader.loadTurretUnit(root.resolve("turret_t1.ini"))).error.code)
        }
        assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(source().resolve("turret_common_land.ini")))
        assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(source().resolve("turret_t1.ini")))
    }

    private fun withCopy(check: (Path) -> Unit) {
        val root = Files.createTempDirectory("p005-turret-loader-")
        try {
            Files.list(source()).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { Files.copy(it, root.resolve(it.fileName)) } }
            check(root)
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
    }
}
