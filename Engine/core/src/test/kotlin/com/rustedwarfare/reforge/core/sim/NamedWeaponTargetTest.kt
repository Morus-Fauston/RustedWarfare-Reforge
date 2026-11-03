package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.TurretMount
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NamedWeaponTargetTest {
    @Test
    fun patrol_primary_is_a_whole_unit_target_even_when_the_first_weapon_cannot_attack_it() {
        val input = rangedScenario(true)
        val original = input.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val catalog = WeaponCatalog(assertNotNull(combat.namedWeapons).ordered.map {
            if (it.name == "near") it.copy(canAttackLandUnits = false) else it
        })
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(unitDefinitions = mapOf("c_tank" to tank)))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("patrol", "p1", "a", Position(410.0, 210.0), false, context.world)))
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            val unit = observation.units.single { it.unitId == "a" }
            assertEquals(UnitTaskKind.PATROL, unit.tasks.single().kind)
            assertEquals("b", unit.attackTargetId)
            val targets = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }.associate { it.weaponName to it.targetUnitId }
            assertEquals(mapOf<String?, String?>("near" to null, "far" to "c"), targets)
        } finally { context.close() }
    }

    @Test
    fun patrol_does_not_assign_hidden_targets_to_secondary_weapons_through_the_non_patrol_scan() {
        val input = rangedScenario(true)
        val original = input.unitDefinitions.getValue("c_tank")
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, 1, original.combat)
        val rules = com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
            com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
            com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE, false)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(
            unitDefinitions = mapOf("c_tank" to tank), automaticVision = rules))).context
        try {
            assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId in setOf("b", "c") })
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("hidden-patrol", "p1", "a", Position(410.0, 210.0), false, context.world)))
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(null, observation.units.single { it.unitId == "a" }.attackTargetId)
            assertTrue(assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }.all { it.targetUnitId == null })
            assertTrue(observation.projectileEvents.none { it.kind == "born" && it.projectile.sourceUnitId == "a" })
        } finally { context.close() }
    }

    @Test
    fun whole_unit_stop_clears_all_weapon_targets_immediately_and_preserves_clocks_and_automatic_reacquisition() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(rangedScenario(true))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("start", "p1", "a", "b", context.world)))
            val before = context.advance(Step(0, 1.0, "sim-tick"))
            fun weapons(observation: Observation) = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }
            val saved = weapons(before)
            assertEquals(listOf("b", "c"), saved.map { it.targetUnitId })
            assertIs<ActionResult.Rejected>(context.submit(StopAction("foreign-stop", "p2", "a", context.world)))
            assertEquals(saved, weapons(context.observe()))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "a", context.world)))
            val stopped = context.observe()
            assertEquals(listOf(null, null), weapons(stopped).map { it.targetUnitId })
            assertEquals(saved.map { it.cooldown }, weapons(stopped).map { it.cooldown })
            assertEquals(saved.map { it.warmup }, weapons(stopped).map { it.warmup })
            val unit = stopped.units.single { it.unitId == "a" }
            assertEquals(null, unit.attackTargetId)
            assertTrue(unit.tasks.isEmpty())
            assertEquals(listOf("b", "c"), weapons(context.advance(Step(1, 1.0, "sim-tick"))).map { it.targetUnitId })
            assertEquals(listOf("b", "c"), saved.map { it.targetUnitId }, "Previously returned observations remain immutable")
        } finally { context.close() }
    }

    @Test
    fun each_weapon_applies_its_own_ground_target_permission_before_assigning_or_firing() {
        val base = rangedScenario(true)
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapons = WeaponCatalog(assertNotNull(combat.namedWeapons).ordered.map {
            if (it.name == "near") it.copy(canAttackLandUnits = false, canAttackFlyingUnits = true) else it
        })
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = weapons))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            base.copy(unitDefinitions = mapOf("c_tank" to tank)))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("ground-filter", "p1", "a", "b", context.world)))
            val fired = mutableSetOf<String?>()
            var observation = context.observe()
            for (step in 0L..29L) {
                observation = context.advance(Step(step, 1.0, "sim-tick"))
                fired += observation.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
                    .map { it.projectile.targetUnitId }
            }
            assertEquals(setOf<String?>("c"), fired)
            val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }.associateBy { it.weaponName }
            assertEquals(null, states.getValue("near").targetUnitId)
            assertEquals(0.0, states.getValue("near").cooldown)
            assertEquals("c", states.getValue("far").targetUnitId)
            assertEquals(210.0, observation.units.single { it.unitId == "b" }.health)
        } finally { context.close() }
    }

    @Test
    fun disabled_multi_targeting_does_not_assign_a_second_target_to_a_disqualified_weapon() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(rangedScenario(false))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("one-primary", "p1", "a", "b", context.world)))
            val fired = mutableSetOf<String?>()
            var observation = context.observe()
            for (step in 0L..29L) {
                observation = context.advance(Step(step, 1.0, "sim-tick"))
                fired += observation.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
                    .map { it.projectile.targetUnitId }
            }
            assertEquals(setOf<String?>("b"), fired)
            assertEquals(mapOf<String?, String?>("near" to "b", "far" to null),
                assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }.associate { it.weaponName to it.targetUnitId })
            assertEquals(210.0, observation.units.single { it.unitId == "c" }.health)
        } finally { context.close() }
    }

    @Test
    fun scan_rejects_the_minimum_endpoint_but_the_explicit_primary_is_legal_at_both_local_endpoints() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            rangedScenario(true, nearX = 170.0, farX = 210.0))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("near-first", "p1", "a", "b", context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(mapOf<String?, String?>("near" to "b", "far" to null),
                assertNotNull(first.combat).weapons.filter { it.unitId == "a" }.associate { it.weaponName to it.targetUnitId })
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("boundary-primary", "p1", "a", "c", context.world)))
            val second = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(mapOf<String?, String?>("near" to "c", "far" to "c"),
                assertNotNull(second.combat).weapons.filter { it.unitId == "a" }.associate { it.weaponName to it.targetUnitId })
        } finally { context.close() }
    }

    private fun rangedScenario(multi: Boolean, nearX: Double = 190.0, farX: Double = 230.0): Scenario {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val weapons = WeaponCatalog(listOf(
            weapon.copy(name = "near", mount = TurretMount(-12.0, 0.0), maximumRange = 100.0),
            weapon.copy(name = "far", mount = TurretMount(12.0, 0.0), minimumRange = 100.0)))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange,
            combat.copy(namedWeapons = weapons, multiTargeting = multi))
        return base.copy(unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
            if (it.unitId == "b") it.copy(position = Position(nearX, 210.0)) else it
        } + UnitSpawn("c", "p2", "c_tank", Position(farX, 210.0), 270.0),
            combatContract = assertNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L)))
    }

    @Test
    fun an_explicit_limit_on_one_weapon_is_not_silently_ignored_by_the_legacy_path() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single().copy(maximumRange = 100.0)
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange,
            combat.copy(namedWeapons = WeaponCatalog(listOf(weapon))))
        val input = base.copy(unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
            if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
        })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("bounded-one", "p1", "a", "b", context.world)))
            val births = mutableListOf<ProjectileObservation>()
            for (step in 0L..5L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                births += observation.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
                    .map { it.projectile }
            }
            assertTrue(births.isEmpty(), "The one weapon's explicit limit must block an otherwise globally in-range shot")
            assertEquals("b", context.observe().units.single { it.unitId == "a" }.attackTargetId)
        } finally { context.close() }
    }

    @Test
    fun explicit_multi_targeting_attacks_two_existing_ground_targets_with_independent_range_gates() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val weapons = WeaponCatalog(listOf(
            weapon.copy(name = "near", mount = TurretMount(-12.0, 0.0), maximumRange = 100.0),
            weapon.copy(name = "far", mount = TurretMount(12.0, 0.0), minimumRange = 100.0)))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange,
            combat.copy(namedWeapons = weapons, multiTargeting = true))
        val input = base.copy(unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
            if (it.unitId == "b") it.copy(position = Position(190.0, 210.0)) else it
        } + UnitSpawn("c", "p2", "c_tank", Position(230.0, 210.0), 270.0),
            combatContract = assertNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("primary-near", "p1", "a", "b", context.world)))
            val firedTargets = mutableSetOf<String?>()
            var observation = context.observe()
            for (step in 0L..29L) {
                observation = context.advance(Step(step, 1.0, "sim-tick"))
                firedTargets += observation.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
                    .map { it.projectile.targetUnitId }
            }
            val targets = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }
                .associate { it.weaponName to it.targetUnitId }
            assertEquals(mapOf<String?, String?>("near" to "b", "far" to "c"), targets)
            assertEquals(setOf<String?>("b", "c"), firedTargets)
            for (id in listOf("b", "c"))
                assertTrue(assertNotNull(observation.units.single { it.unitId == id }.health) < 210.0,
                    "Both existing targets must receive damage from actual projectiles")
        } finally { context.close() }
    }
}
