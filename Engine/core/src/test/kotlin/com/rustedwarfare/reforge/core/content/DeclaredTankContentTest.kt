package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeclaredTankContentTest {
    @Test
    fun new_name_uses_existing_tank_semantics_without_relaxing_the_legacy_entry() {
        withTank { root, input ->
            val source = Files.readString(input).replace("name: c_tank", "name: amber_scout")
                .replace("overrideAndReplace: tank", "")
            Files.writeString(input, source)
            val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(
                ContentLoader.loadDeclaredTankWithSources(input)).value
            assertEquals("amber_scout", loaded.definition.id)
            assertEquals(210.0, assertNotNull(loaded.definition.combat).maxHp)
            assertEquals(25.0, loaded.definition.combat!!.projectile.directDamage)
            assertEquals(350L, assertNotNull(loaded.definition.build).price)
            assertEquals(listOf(input), loaded.sources.map { it.path })
            assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
            val legacy = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(input)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, legacy.code)
            assertEquals("name", legacy.field)
            assertTrue(loaded.definition.imagePath.startsWith(root))
        }
    }

    private fun withTank(check: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("p008-declared-tank-").toAbsolutePath().normalize()
        try {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val source = fixtures.resolve("three-format-tanks/ini/units")
            for (name in listOf("tank.ini", "tank.png", "tank_dead.png", "tank_turret.png"))
                Files.copy(source.resolve(name), root.resolve(name))
            check(root, root.resolve("tank.ini"))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(owned)
            } }
        }
    }
}
