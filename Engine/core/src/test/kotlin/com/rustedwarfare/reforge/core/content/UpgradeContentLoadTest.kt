package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class UpgradeContentLoadTest {
    private fun source(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/turret-upgrades/units") } ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-004/20261026-e-upgrade-input-01/input/units"))

    @Test fun reads_both_real_first_branch_definitions_and_float_seconds_through_the_public_loader() {
        val gun = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadTurretUnit(source().resolve("turret_t2_gun.ini"))).value
        assertEquals("c_turret_t2_gun", gun.id)
        assertEquals(1100.0, gun.maximumHealth)
        assertEquals(185.0, gun.combat!!.attackRange)
        assertEquals(20.0, gun.combat.shootDelay)
        assertEquals(44.0, gun.combat.projectile.directDamage)
        assertEquals(-3.0, gun.combat.barrelX)
        assertEquals(6.0, gun.combat.oddShotOffset)
        assertNull(gun.combat.projectile.ground)
        assertNull(gun.upgrades)
        val artillery = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadTurretUnit(source().resolve("turret_t1_artillery.ini"))).value
        assertEquals("c_turret_t1_artillery", artillery.id)
        assertEquals(1000.0, artillery.maximumHealth)
        assertEquals(350.0, artillery.combat!!.attackRange)
        assertEquals(220.20001220703125, artillery.combat.shootDelay)
        assertEquals(1.7, artillery.combat.turretTurnSpeed)
        assertNull(artillery.combat.idleSweep)
        assertEquals(GroundProjectileProfile(80f, 55f, 10f, 1.2f, .18f), artillery.combat.projectile.ground)
        assertEquals(0.0, artillery.combat.projectile.directDamage)
        assertNull(artillery.upgrades)
    }

    @Test fun activation_is_explicit_local_immutable_and_consumes_only_six_gameplay_fields() {
        val path = source().resolve("turret_t1.ini")
        val implicit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(path)).value
        val disabled = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(path, false)).value
        assertNull(implicit.upgrades); assertNull(disabled.upgrades)
        assertEquals(implicit.combat, disabled.combat)
        assertEquals(implicit.retainedUnconsumed, disabled.retainedUnconsumed)
        val enabled = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(path, true)).value
        assertEquals(listOf(UpgradeActionProfile("upgrade_gun", "c_turret_t2_gun", 1000L, .001f),
            UpgradeActionProfile("upgrade_artillery", "c_turret_t1_artillery", 1600L, .0004f)), enabled.upgrades!!.actions)
        assertEquals(implicit.combat, enabled.combat)
        val consumed = listOf("gun", "artillery").flatMap { action ->
            listOf("convertTo", "price", "buildSpeed").map { "action_upgrade_$action.$it" } }.toSet()
        assertEquals(implicit.retainedUnconsumed - consumed, enabled.retainedUnconsumed)
        assertFailsWith<UnsupportedOperationException> { (enabled.upgrades.actions as MutableList).clear() }
        assertNull(assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(path)).value.upgrades)
        for (name in listOf("turret_t2_gun.ini", "turret_t1_artillery.ini", "turret_common_land.ini"))
            assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(source().resolve(name), true))
        withCopy { root ->
            Files.delete(root.resolve("turret_t2_gun.ini")); Files.delete(root.resolve("turret_t1_artillery.ini"))
            assertNull(assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(root.resolve("turret_t1.ini"))).value.upgrades)
            assertEquals(enabled.upgrades.actions, assertIs<ContentResult.Success<UnitDefinition>>(
                ContentLoader.loadTurretUnit(root.resolve("turret_t1.ini"), true)).value.upgrades!!.actions)
        }
    }

    @Test fun rejects_malformed_activation_values_and_nonfinite_ground_time_or_barrels() {
        data class Case(val file: String, val from: String, val to: String, val enabled: Boolean = false)
        for (case in listOf(
            Case("turret_t1.ini", "price:  1000", "price: -1", true),
            Case("turret_t1.ini", "price:  1600", "price: 999999999999999999999", true),
            Case("turret_t1.ini", "buildSpeed: 0.001", "buildSpeed: NaN", true),
            Case("turret_t1.ini", "buildSpeed: 0.001", "buildSpeed: 1e-50", true),
            Case("turret_t1.ini", "convertTo: c_turret_t2_gun", "convertTo: arbitrary", true),
            Case("turret_t1.ini", "price:  1000", "", true),
            Case("turret_t2_gun.ini", "barrelX: -3", "barrelX: Infinity"),
            Case("turret_t1_artillery.ini", "shootDelay: 3.67s", "shootDelay: 1e38s"),
            Case("turret_t1_artillery.ini", "shootDelay: 3.67s", "shootDelay: -1s"),
            Case("turret_t1_artillery.ini", "targetGround: true", "targetGround: false"),
            Case("turret_t1_artillery.ini", "areaRadius:55", "areaRadius:NaN"),
            Case("turret_t1_artillery.ini", "areaDamage: 80", "areaDamage: 81"),
            Case("turret_t1_artillery.ini", "gravity:0.18", "gravity:0.19"),
        )) withCopy { root ->
            val path = root.resolve(case.file)
            val text = Files.readString(path); assertTrue(text.contains(case.from))
            Files.writeString(path, text.replace(case.from, case.to))
            assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path, case.enabled), case.to)
        }
    }

    private fun withCopy(check: (Path) -> Unit) {
        val root = Files.createTempDirectory("p005-upgrade-loader-")
        try {
            Files.list(source()).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { Files.copy(it, root.resolve(it.fileName)) } }
            check(root)
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
    }
}
