package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.sim.CombatTestContent
import kotlin.test.*

class WeaponAssemblyTest {
    @Test fun forward_parent_and_delay_links_resolve_without_reordering_the_slots() {
        val base = checkNotNull(CombatTestContent.scenario().unitDefinitions.getValue("c_tank").combat)
        val gun = checkNotNull(base.namedWeapons).ordered.single()
        val assembly = WeaponAssembly.compile(base.copy(namedWeapons = WeaponCatalog(listOf(
            gun.copy(name = "follower", linkDelayWithTurret = "leader"),
            gun.copy(name = "child", attachedTo = "leader", slave = true),
            gun.copy(name = "leader")))))
        assertEquals(listOf("follower", "child", "leader"), assembly.slots.map { it.definition.name })
        assertEquals("leader", assembly.parent("child")?.definition?.name)
        assertNull(assembly.parent("leader"))
        assertEquals(listOf("follower"), assembly.followers("leader").map { it.definition.name })
        assertTrue(assembly.followers("follower").isEmpty())
        assertFailsWith<UnsupportedOperationException> { (assembly.followers("leader") as MutableList<*>).clear() }
    }

    @Test fun different_definitions_with_the_same_slot_name_keep_their_own_effective_values() {
        val base = checkNotNull(CombatTestContent.scenario().unitDefinitions.getValue("c_tank").combat)
        val gun = checkNotNull(base.namedWeapons).ordered.single()
        val first = base.copy(namedWeapons = WeaponCatalog(listOf(gun.copy(name = "shared", shootDelay = 7.0))))
        val replacement = first.copy(namedWeapons = WeaponCatalog(listOf(gun.copy(name = "shared", shootDelay = 17.0))))
        assertEquals(7.0, first.weaponAssembly.byName.getValue("shared").profile.shootDelay)
        assertEquals(17.0, replacement.weaponAssembly.byName.getValue("shared").profile.shootDelay)
        assertEquals(7.0, first.weaponAssembly.byName.getValue("shared").profile.shootDelay)
    }

    @Test fun two_inherited_slots_resolve_their_own_effective_profiles_in_declaration_order() {
        val base = checkNotNull(CombatTestContent.scenario().unitDefinitions.getValue("c_tank").combat)
        val source = checkNotNull(base.namedWeapons).ordered.single()
        val first = source.copy(name = "left", barrelX = -3.0, oddShotOffset = -2.0)
        val second = source.copy(name = "right", barrelX = null, oddShotOffset = null,
            mount = TurretMount(10.0, 0.0), shootDelay = 9.0)
        val assembly = WeaponAssembly.compile(base.copy(barrelX = 4.0, oddShotOffset = 6.0,
            namedWeapons = WeaponCatalog(listOf(first, second))))
        assertTrue(assembly.usesNamedState)
        assertEquals(listOf("left", "right"), assembly.slots.map { it.definition.name })
        assertEquals(-3.0, assembly.byName.getValue("left").profile.barrelX)
        assertEquals(-2.0, assembly.byName.getValue("left").profile.oddShotOffset)
        val right = assembly.byName.getValue("right").profile
        assertEquals(0.0, right.barrelX)
        assertEquals(0.0, right.oddShotOffset)
        assertEquals(TurretMount(10.0, 0.0), right.mount)
        assertEquals(9.0, right.shootDelay)
        assertEquals(source.projectile, right.projectile)
        assertTrue(assembly.slots.all { it.profile.namedWeapons == null })
        assertFailsWith<UnsupportedOperationException> { (assembly.slots as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (assembly.byName as MutableMap<*, *>).clear() }
    }
}
