package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NamedWeaponEvidenceTest {
    @Test
    fun `last authorized weapon disappearing is an explicit empty list in both evidence sinks`() {
        val armed = input()
        val original = armed.unitDefinitions.getValue("c_tank")
        val unarmed = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange)
        val context = (SimulationContext.initialize(armed) as InitResult.Accepted).context
        try {
            val records = mutableListOf<Map<String, Any?>>()
            val sink = ClientEvidence { _, fields -> records += fields }
            val before = context.observePlayer(context.world, "p1")
            assertTrue(before.weapons.isNotEmpty())
            sink.recordObservation("before", before)
            context.close()
            assertTrue(context.reopen(armed.copy(unitDefinitions = mapOf("c_tank" to unarmed))) is InitResult.Accepted)
            val after = context.observePlayer(context.world, "p1")
            assertTrue(after.weapons.isEmpty())
            sink.recordObservation("after", after)
            val sample = records.last()["sample"] as Map<*, *>
            assertEquals(emptyList<Any>(), assertNotNull(sample["weapons"]))
            val fixedSample = FixedDiagnosticRecord.observation("after", after).values["sample"] as Map<*, *>
            assertEquals(emptyList<Any>(), assertNotNull(fixedSample["weapons"]))
        } finally { context.close() }
    }

    @Test
    fun `last visible enemy weapon leaving sight clears both sinks without exposing enemy clocks`() {
        val base = input()
        val original = base.unitDefinitions.getValue("c_tank")
        val watcher = UnitDefinition("watcher", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, 2)
        val combat = assertNotNull(original.combat)
        val enemy = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, 2, combat.copy(namedWeapons = WeaponCatalog(
                assertNotNull(combat.namedWeapons).ordered.map { it.copy(canShoot = false) })))
        val scenario = base.copy(unitDefinitions = mapOf("watcher" to watcher, "c_tank" to enemy),
            units = listOf(UnitSpawn("a", "p1", "watcher", Position(110.0, 210.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(130.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val context = (SimulationContext.initialize(scenario) as InitResult.Accepted).context
        try {
            val world = context.world
            val before = context.observePlayer(world, "p1")
            assertEquals(listOf("near", "far"), before.weapons.map { it.weaponName })
            assertTrue(before.weapons.all { it.cooldown == null && it.warmup == null && it.targetUnitId == null })
            assertTrue(context.submit(MoveAction("leave-sight", "p1", "a", Position(110.0, 330.0), world)) is ActionResult.Accepted)
            repeat(200) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val after = context.observePlayer(world, "p1")
            assertEquals(before.worldId, after.worldId)
            assertTrue(after.units.none { it.unitId == "b" })
            assertTrue(after.weapons.isEmpty())
            val records = mutableListOf<Map<String, Any?>>()
            ClientEvidence { _, fields -> records += fields }.recordObservation("after", after)
            assertEquals(emptyList<Any>(), (records.single()["sample"] as Map<*, *>)["weapons"])
            assertEquals(emptyList<Any>(), (FixedDiagnosticRecord.observation("after", after).values["sample"] as Map<*, *>)["weapons"])
        } finally { context.close() }
    }

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
