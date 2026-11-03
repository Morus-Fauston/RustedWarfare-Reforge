package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NamedWeaponProjectionTest {
    @Test
    fun actual_named_births_keep_the_stable_firing_weapon_in_host_and_authorized_public_events() {
        val context = open(warmup = false)
        try {
            val host = context.advance(Step(0, 1.0, "sim-tick"))
            val hostBirths = host.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
            assertEquals(mapOf<String?, String?>("near" to "b", "far" to "c"),
                hostBirths.associate { it.projectile.sourceWeaponName to it.projectile.targetUnitId })
            val view = context.observePlayer(context.world, "p1")
            val births = view.projectileEvents.filter { it.kind == "born" && it.sourceUnitId == "a" }
            assertEquals(mapOf<String?, String?>("near" to "b", "far" to "c"),
                births.associate { it.sourceWeaponName to it.targetUnitId })
        } finally { context.close() }
    }

    @Test
    fun two_hidden_firing_weapon_names_do_not_split_an_otherwise_identical_public_projectile_class() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val enemy = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange,
            combat.copy(namedWeapons = WeaponCatalog(listOf(weapon.copy(name = "secret-left"), weapon.copy(name = "secret-right")))))
        val witness = UnitDefinition("witness", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, 1, durability = DurabilityProfile(210.0, combat.deadImagePath))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf("c_tank" to enemy, "witness" to witness), units = listOf(
                UnitSpawn("a", "p1", "witness", Position(110.0, 210.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(150.0, 210.0), 270.0)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false)))).context
        try {
            context.advance(Step(0, 1.0, "sim-tick"))
            val view = context.observePlayer(context.world, "p1")
            assertEquals(listOf("a"), view.units.map { it.unitId })
            assertTrue(view.weapons.isEmpty())
            val born = view.projectileEvents.single { it.kind == "born" }
            assertEquals(2, born.occurrences)
            assertEquals(2, view.projectiles.single().multiplicity)
            assertEquals(null, born.sourceUnitId)
            assertEquals(null, born.sourceWeaponName)
        } finally { context.close() }
    }

    @Test
    fun a_player_receives_each_visible_weapon_but_only_its_owner_receives_targets_and_warmup() {
        val context = open()
        try {
            context.advance(Step(0, 1.0, "sim-tick"))
            val owner = context.observePlayer(context.world, "p1")
            val own = owner.weapons.filter { it.unitId == "a" }
            assertEquals(listOf("near", "far"), own.map { it.weaponName })
            assertEquals(listOf("b", "c"), own.map { it.targetUnitId })
            assertEquals(listOf(1.0, 1.0), own.map { it.warmup })
            assertEquals(listOf(0.0, 74.0), own.map { it.cooldown })
            val enemyView = context.observePlayer(context.world, "p2").weapons.filter { it.unitId == "a" }
            assertEquals(listOf("near", "far"), enemyView.map { it.weaponName })
            assertTrue(enemyView.all { it.targetUnitId == null && it.warmup == null })
            assertEquals(listOf<Double?>(null, null), enemyView.map { it.cooldown },
                "Visible enemy weapons must follow the existing owner-only cooldown policy")
            assertEquals(own.map { it.muzzle }, enemyView.map { it.muzzle })
            assertEquals(own.map { it.turretHeading }, enemyView.map { it.turretHeading })
            assertFailsWith<UnsupportedOperationException> { (owner.weapons as MutableList<VisibleUnitWeapon>).clear() }
        } finally { context.close() }
    }

    @Test
    fun hidden_weapons_and_hidden_target_ids_do_not_enter_the_view_or_change_its_identity_on_hidden_stop() {
        val context = open(fog = true)
        try {
            val host = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(listOf("b", "c"), assertNotNull(host.combat).weapons.filter { it.unitId == "a" }.map { it.targetUnitId })
            val owner = context.observePlayer(context.world, "p1")
            assertEquals(listOf("a"), owner.units.map { it.unitId })
            assertEquals(listOf("near", "far"), owner.weapons.map { it.weaponName })
            assertTrue(owner.weapons.all { it.unitId == "a" && it.targetUnitId == null })
            assertIs<ActionResult.Accepted>(context.submit(StopAction("hidden-stop", "p2", "b", context.world)))
            assertSame(owner, context.observePlayer(context.world, "p1"))
        } finally { context.close() }
    }

    private fun open(fog: Boolean = false, warmup: Boolean = true): SimulationContext {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val catalog = WeaponCatalog(listOf(
            weapon.copy(name = "near", maximumRange = 100.0, warmup = if (warmup) 2.0 else 0.0),
            weapon.copy(name = "far", minimumRange = 100.0, warmup = if (warmup) 2.0 else 0.0, warmupNoReset = warmup)))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, if (fog) 1 else original.fogOfWarSightRange,
            combat.copy(namedWeapons = catalog, multiTargeting = true))
        val input = base.copy(unitDefinitions = mapOf("c_tank" to tank),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units.map { if (it.unitId == "b") it.copy(position = Position(190.0, 210.0)) else it } +
                UnitSpawn("c", "p2", "c_tank", Position(230.0, 210.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L),
            automaticVision = if (fog) KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false) else null)
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
    }
}
