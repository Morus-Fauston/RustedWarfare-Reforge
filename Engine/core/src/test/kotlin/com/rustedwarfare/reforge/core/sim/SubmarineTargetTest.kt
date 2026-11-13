package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineTargetTest {
    @Test fun attack_uses_actual_strict_depth_and_static_water_relation_instead_of_intent_or_movement_type() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val sub = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("attackSubmarine")
            val surface = UnitDefinition("surface", "OwnedTarget", 6.0, 6.0, 1, sub.imagePath, 1.0,
                MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                durability = DurabilityProfile(1000.0, null))
            val air = UnitDefinition("air", "OwnedAirTarget", 6.0, 6.0, 1, sub.imagePath, 1.0,
                MovementProfile(MovementType.AIR, 0.0, 0.0, 0.0, 0.0, 0.0, true, true, .1,
                    flight = FlightProfile(4.0, 0.0, 0.0, 4.0)), emptySet(),
                durability = DurabilityProfile(1000.0, null), rotor = RotorDefinition(sub.imagePath, 0.0))
            data class Case(val actor: Double, val type: UnitDefinition, val target: Double, val x: Double, val allowed: Boolean)
            val cases = listOf(
                Case(0.0, sub, -8.0, 650.0, false),
                Case(-1.0, sub, -8.0, 650.0, false),
                Case(-1.0001, sub, -8.0, 650.0, true),
                Case(-8.0, sub, -1.0, 650.0, true),
                Case(-8.0, surface, 0.0, 650.0, true),
                Case(-8.0, surface, 0.0, 300.0, false),
                Case(-8.0, surface, -8.0, 300.0, false),
                Case(-8.0, surface, 2.0, 650.0, true),
                Case(-8.0, surface, 2.0001, 650.0, false),
                Case(-8.0, air, 4.0, 650.0, false),
                Case(0.0, air, 4.0, 650.0, true),
                Case(0.0, surface, 0.0, 300.0, true))
            for ((index, case) in cases.withIndex()) {
                val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                    unitDefinitions = mapOf("actor" to sub, "target" to case.type),
                    units = listOf(UnitSpawn("actor", "p1", "actor", Position(500.0, 200.0), 90.0, case.actor),
                        UnitSpawn("target", "p2", "target", Position(case.x, 200.0), 0.0,
                            if (case.type.movement.type == MovementType.AIR) 0.0 else case.target)),
                    combatContract = CombatContract(12345, -1, mapOf("actor" to 8L, "target" to 9L), 10L))
                val initialized = SimulationContext.initialize(input)
                val context = assertIs<InitResult.Accepted>(initialized, "$index: $case: $initialized").context
                try {
                    val result = context.submit(AttackAction("attack-$index", "p1", "actor", "target", context.world))
                    if (case.allowed) assertIs<ActionResult.Accepted>(result, "$index: $case")
                    else assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(result, "$index: $case").error.code)
                } finally { context.close() }
            }
        } }
}
