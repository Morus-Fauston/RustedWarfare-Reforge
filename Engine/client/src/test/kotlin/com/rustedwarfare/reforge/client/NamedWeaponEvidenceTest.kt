package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NamedWeaponEvidenceTest {
    @Test
    fun actual_session_records_all_authorized_weapon_states_and_the_stable_firing_weapon_without_host_state() {
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        val evidence = ClientEvidence { kind, fields -> rows += kind to fields }
        SandboxSession(input(), evidence, SandboxOptions(SandboxMode.MEMORY)).use { session ->
            repeat(3) { session.tick() }
        }
        val samples = rows.filter { it.first == "observation" && it.second["phase"] == "step" }
            .map { assertNotNull(it.second["sample"] as? Map<*, *>) }
        assertEquals(3, samples.size)
        val first = assertNotNull(samples.first()["weapons"] as? List<*>).map { it as Map<*, *> }
        val own = first.filter { it["unitId"] == "a" }
        assertEquals(listOf("near", "far"), own.map { it["weaponName"] })
        assertEquals(listOf("b", "c"), own.map { it["targetUnitId"] })
        assertEquals(listOf(1.0, 1.0), own.map { it["warmup"] })
        val enemies = first.filter { it["unitId"] != "a" }
        assertTrue(enemies.isNotEmpty())
        assertTrue(enemies.all { it["targetUnitId"] == null && it["warmup"] == null && it["cooldown"] == null })
        assertTrue(own.all { it["cooldown"] is Double })
        assertTrue(first.all { row -> row.keys.none { it in setOf("objectId", "randomCounter", "offset", "angularVelocity") } })
        val births = samples.flatMap { assertNotNull(it["projectileEvents"] as? List<*>).map { value -> value as Map<*, *> } }
            .filter { it["kind"] == "born" && it["sourceUnitId"] == "a" }
        assertEquals(mapOf<Any?, Any?>("near" to "b", "far" to "c"), births.associate { it["sourceWeaponName"] to it["targetUnitId"] })
    }

    private fun input(): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val base = ControlledScenario.load(fixtures.resolve("production-combat/combat"))
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(multiTargeting = true,
                namedWeapons = WeaponCatalog(listOf(weapon.copy(name = "near", maximumRange = 100.0, warmup = 2.0),
                    weapon.copy(name = "far", minimumRange = 100.0, warmup = 2.0, warmupNoReset = true)))))
        return base.copy(unitDefinitions = mapOf("c_tank" to tank), units = listOf(
            UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
            UnitSpawn("b", "p2", "c_tank", Position(190.0, 210.0), 270.0),
            UnitSpawn("c", "p2", "c_tank", Position(230.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L))
    }
}
