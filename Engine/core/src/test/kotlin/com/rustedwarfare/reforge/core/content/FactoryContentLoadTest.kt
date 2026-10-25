package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactoryContentLoadTest {
    private data class BadFactoryValue(val from: String, val to: String, val field: String, val code: ContentErrorCode)
    private fun factoryPath(): Path = inputs().parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input/units/landFactory.properties",
    )

    @Test
    fun reads_explicit_native_factory_definition_without_a_dummy_weapon() {
        val factory = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(factoryPath())).value
        assertEquals("landFactory", factory.id)
        assertEquals(30.0, factory.radius)
        assertEquals(1200.0, factory.maximumHealth)
        assertNull(factory.combat)
        assertNull(factory.build)
        assertEquals(DirectHitTargetKind.NATIVE_BUILDING, factory.directHitTargetKind)
        assertEquals(MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), factory.movement)
        assertEquals(BuildingFootprint(-1, -1, 1, 1), assertNotNull(factory.building).footprint)
        assertEquals("c_tank", assertNotNull(factory.production).unitTypeId)
        assertEquals(15, factory.fogOfWarSightRange)
        assertTrue(listOf(factory.imagePath, assertNotNull(factory.building.backImagePath), assertNotNull(factory.durability).deadImagePath)
            .all { it.isAbsolute && Files.isRegularFile(it) })
    }

    @Test
    fun tank_cost_and_rate_are_consumed_from_real_content_but_legacy_variant_stays_weaponless() {
        val tank = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadCombatUnit(factoryPath().resolveSibling("tank.ini"))).value
        assertEquals(BuildProfile(350L, 0.002f), assertNotNull(tank.build))
        assertTrue("price" !in tank.retainedUnconsumed && "buildSpeed" !in tank.retainedUnconsumed)
        assertEquals(210.0, tank.maximumHealth)
        assertEquals(DirectHitTargetKind.ORDINARY, tank.directHitTargetKind)
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadUnitVariant(unitPath())).value
        assertNull(legacy.maximumHealth)
        assertNull(legacy.build)
        assertNull(legacy.building)
        assertNull(legacy.production)
    }

    @Test
    fun factory_rejects_bad_numbers_missing_fields_and_unimplemented_capabilities() {
        for ((from, to, field, code) in listOf(
            BadFactoryValue("maxHp=1200", "maxHp=NaN", "maxHp", ContentErrorCode.MALFORMED_SYNTAX),
            BadFactoryValue("maxHp=1200", "maxHp=0", "maxHp", ContentErrorCode.MALFORMED_SYNTAX),
            BadFactoryValue("radius=30", "radius=Infinity", "radius", ContentErrorCode.MALFORMED_SYNTAX),
            BadFactoryValue("fogOfWarSightRange=15", "fogOfWarSightRange=-1", "fogOfWarSightRange", ContentErrorCode.MALFORMED_SYNTAX),
            BadFactoryValue("movementType=NONE", "movementType=LAND", "movementType", ContentErrorCode.UNSUPPORTED_FEATURE),
            BadFactoryValue("productionUnit=c_tank", "productionUnit=unknown", "productionUnit", ContentErrorCode.UNSUPPORTED_FEATURE),
            BadFactoryValue("footprint=-1,-1,1,1", "footprint=-1,-1,1,3", "footprint", ContentErrorCode.UNSUPPORTED_FEATURE),
            BadFactoryValue("name=landFactory", "name=landFactory\ncanShoot=true", "canShoot", ContentErrorCode.UNSUPPORTED_FIELD),
            BadFactoryValue("maxHp=1200", "maxHp=1200\nmaxHp=1200", "maxHp", ContentErrorCode.MALFORMED_SYNTAX),
            BadFactoryValue("maxHp=1200", "", "maxHp", ContentErrorCode.MISSING_REQUIRED_FIELD),
        )) {
            withFactoryText({ it.replace(from, to) }) { path ->
                val failure = assertIs<ContentResult.Failure>(ContentLoader.loadFactory(path)).error
                assertEquals(code, failure.code)
                assertEquals(field, failure.field)
                assertEquals(path.toAbsolutePath().normalize().toString(), failure.file)
            }
        }
    }

    @Test
    fun factory_missing_graphic_fails_instead_of_publishing_a_partial_definition() {
        withFactoryText({ it.replace(Regex("(?m)^image_dead=.*$"), "image_dead=p005-missing-factory-dead.png") }) { path ->
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadFactory(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, failure.code)
            assertEquals("image_dead", failure.field)
        }
    }

    @Test
    fun consumed_tank_cost_and_rate_refuse_invalid_or_unrepresentable_values() {
        for ((field, value) in listOf("price" to "-1", "price" to "350.5", "price" to "9223372036854775808",
                "buildSpeed" to "0", "buildSpeed" to "NaN", "buildSpeed" to "1e-50")) {
            withTankText({ it.replace(Regex("(?m)^$field:.*$"), "$field: $value") }) { path ->
                val failure = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, failure.code)
                assertEquals("core", failure.section)
                assertEquals(field, failure.field)
            }
        }
    }

    private fun withFactoryText(change: (String) -> String, check: (Path) -> Unit) {
        val source = factoryPath()
        val text = Files.readString(source).replace(Regex("(?m)^(image|image_back|image_dead)=(.*)$")) {
            "${it.groupValues[1]}=${source.parent.resolve(it.groupValues[2]).toAbsolutePath().normalize()}"
        }
        withText(text, ".properties", change, check)
    }

    private fun withTankText(change: (String) -> String, check: (Path) -> Unit) {
        val source = factoryPath().resolveSibling("tank.ini")
        val text = Files.readString(source).replace(Regex("(?m)^(image|image_wreak|image_turret):\\s*(.*)$")) {
            "${it.groupValues[1]}: ${source.parent.resolve(it.groupValues[2]).toAbsolutePath().normalize()}"
        }
        withText(text, ".ini", change, check)
    }

    private fun withText(text: String, suffix: String, change: (String) -> String, check: (Path) -> Unit) {
        val file = Files.createTempFile("p005-factory-content-", suffix)
        try {
            Files.writeString(file, change(text))
            check(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
