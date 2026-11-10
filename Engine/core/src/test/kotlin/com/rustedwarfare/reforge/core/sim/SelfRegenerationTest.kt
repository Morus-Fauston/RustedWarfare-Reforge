package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SelfRegenerationTest {
    @Test fun nonfinite_negative_or_healthless_regeneration_is_rejected_at_world_admission() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        for ((rate, durable) in listOf(Float.NaN to true, Float.POSITIVE_INFINITY to true,
                -.08f to true, .08f to false)) {
            val invalid = UnitDefinition("invalid", "CustomUnitMetadata", original.radius,
                original.displayRadius, original.totalFrames, original.imagePath, original.imageScale,
                original.movement, emptySet(), durability = if (durable) DurabilityProfile(210.0, null) else null,
                selfRegenRate = rate)
            val input = base.copy(unitDefinitions = base.unitDefinitions + (invalid.id to invalid))
            when (val result = SimulationContext.initialize(input)) {
                is InitResult.Accepted -> { result.context.close(); fail("Invalid self regeneration was admitted: $rate/$durable") }
                is InitResult.Rejected -> assertEquals("unsupported-self-regeneration", result.error.code)
            }
        }
    }

    @Test fun a_really_hit_active_unit_regenerates_without_a_recent_hit_delay_and_clamps_at_maximum() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        // Trusted host test content: the .08 capability is not a claim about native tank regeneration.
        val healing = UnitDefinition("healing-fixture", "CustomUnitMetadata", original.radius,
            original.displayRadius, original.totalFrames, original.imagePath, original.imageScale,
            original.movement, emptySet(), durability = DurabilityProfile(210.0, null), selfRegenRate = .08f)
        val input = base.copy(unitDefinitions = base.unitDefinitions + (healing.id to healing),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a", "p1", healing.id, Position(110.0, 210.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(210.0, 210.0), 270.0)))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            fun health() = checkNotNull(c.observe().units.single { it.unitId == "a" }.health)
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("actual-hit", "p2", "b", "a", c.world)))
            while (health() == 210.0 && step < 150L) tick()
            assertTrue(health() in 1.0..<210.0, "The public weapon path must really injure the target")
            assertIs<ActionResult.Accepted>(c.submit(StopAction("cease", "p2", "b", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("retreat", "p2", "b", Position(510.0, 210.0), c.world)))
            val injured = health()
            tick()
            assertEquals((injured.toFloat() + .08f).toDouble(), health())
            while (health() < 210.0 && step < 1000L) tick()
            assertEquals(210.0, health())
            repeat(3) { tick(); assertEquals(210.0, health()) }
        } finally { c.close() }
    }
}
