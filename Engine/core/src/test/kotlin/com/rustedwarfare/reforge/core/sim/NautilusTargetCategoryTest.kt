package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class NautilusTargetCategoryTest {
    @Test fun attack_categories_use_current_form_and_targets_physical_water_contact_and_depth() = WaterTestScene.withMap { map ->
        val definitions = NautilusScene.definitions()
        val image = definitions.getValue("hoverTank").imagePath
        val ordinary = UnitDefinition("ordinary", "OwnedTarget", 6.0, 6.0, 1, image, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(1000.0, null))
        val air = UnitDefinition("air", "OwnedAirTarget", 6.0, 6.0, 1, image, 1.0,
            MovementProfile(MovementType.AIR, 0.0, 0.0, 0.0, 0.0, 0.0, true, true, .1,
                flight = FlightProfile(4.0, 0.0, 0.0, 4.0)), emptySet(),
            durability = DurabilityProfile(1000.0, null), rotor = RotorDefinition(image, 0.0))
        data class Case(val actor: String, val target: String, val x: Double, val height: Double, val allowed: Boolean)
        val cases = listOf(
            Case("nautilusSubmarine", "ordinary", 650.0, 2.0, true),
            Case("nautilusSubmarine", "ordinary", 650.0, 2.0001, false),
            Case("nautilusSubmarine", "ordinary", 300.0, 0.0, false),
            Case("nautilusSubmarine", "air", 650.0, 0.0, false),
            Case("nautilusSubmarine", "nautilusSubmarine", 650.0, -8.0, true),
            Case("nautilusSubmarineSurface", "ordinary", 300.0, 0.0, true),
            Case("nautilusSubmarineSurface", "air", 650.0, 0.0, true),
            Case("nautilusSubmarineSurface", "nautilusSubmarine", 650.0, -8.0, false),
            Case("nautilusSubmarineLand", "nautilusSubmarineSurface", 650.0, -1.0001, false),
            Case("nautilusSubmarineLand", "nautilusSubmarineSurface", 650.0, -1.0, true))
        for ((index, case) in cases.withIndex()) {
            val spawns = listOf(UnitSpawn("mother", "p1", case.actor, Position(500.0, 180.0), 90.0, -8.0),
                UnitSpawn("target", "p2", case.target, Position(case.x, 180.0), 270.0, case.height))
            val c = NautilusScene.open(NautilusScene.scenario(map).copy(unitDefinitions = definitions + mapOf("ordinary" to ordinary, "air" to air),
                units = spawns, combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "target" to 9L), 10L)))
            try {
                val result = c.submit(AttackAction("attack", "p1", "mother", "target", c.world))
                if (case.allowed) assertIs<ActionResult.Accepted>(result, "$index: $case")
                else assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(result, "$index: $case").error.code)
            } finally { c.close() }
        }
    }
}
