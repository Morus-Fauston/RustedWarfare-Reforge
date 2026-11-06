package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.*

class UnitRegistrationTest {
    @Test fun initial_registration_keeps_explicit_identity_without_consuming_birth_sequence() {
        val input = BuildingTestContent.scenario()
        val state = StepState(nextObjectId = 11L)
        val spawn = input.units.single { it.unitId == "a" }.copy(heading = -270.0, height = 7.5)
        val unit = UnitRegistration.initial(state, input, spawn, ScreenPlane)
        assertSame(unit, state.units.getValue("a"))
        assertEquals("a", unit.unitId)
        assertEquals("p1", unit.ownerPlayerId)
        assertEquals("c_tank", unit.typeId)
        assertEquals(8L, unit.objectId)
        assertEquals("a", unit.updateKey)
        assertEquals(90.0, unit.heading)
        assertEquals(90.0, unit.combat?.turretHeading)
        assertEquals(7.5, unit.height)
        assertEquals(210.0, unit.health)
        assertEquals(1f, unit.constructionProgress)
        assertNull(unit.producerId)
        assertEquals(0f, unit.birthRemaining)
        assertEquals(11L, state.nextObjectId)
        assertEquals(0L, state.lastBirthSerial)
        assertTrue(state.pendingEvents.isEmpty())
    }

    @Test fun completed_factory_work_registers_a_real_unit_with_shared_identity_and_its_own_exit_state() {
        val input = BuildingTestContent.scenario()
        val state = StepState(nextObjectId = 11L)
        val factory = UnitRegistration.initial(state, input, input.units.single { it.unitId == "f" }, ScreenPlane)
        val nonce = "0".repeat(32)
        val unit = UnitRegistration.produced(state, input, factory,
            ProductionCompletion("f", "p1", "c_tank", nonce))
        val id = "produced:p1:$nonce"
        assertSame(unit, state.units.getValue(id))
        assertEquals(listOf("f", id), state.units.keys.toList())
        assertEquals("p1", unit.ownerPlayerId)
        assertEquals("c_tank", unit.typeId)
        assertEquals(11L, unit.objectId)
        assertEquals("~produced/00000000000000000001", unit.updateKey)
        assertEquals("f", unit.producerId)
        assertEquals(30f, unit.birthRemaining)
        assertEquals(Position(310.0, 215.0), unit.position)
        assertEquals(Position(309.0, 300.0), unit.moveTarget)
        assertEquals(listOf(Position(309.0, 300.0)), unit.pathPoints)
        assertEquals(180.0, unit.heading)
        assertEquals(180.0, unit.combat?.turretHeading)
        assertEquals(0.0, unit.height)
        assertEquals(0.0, unit.speed)
        assertEquals(210.0, unit.health)
        assertEquals(1f, unit.constructionProgress)
        assertEquals(12L, state.nextObjectId)
        assertEquals(1L, state.lastBirthSerial)
        assertEquals(listOf(Event("unit-produced", id)), state.pendingEvents)
        assertNull(state.productionLedger, "Arrival registration must not create or advance the caller's paid ledger")
    }


    @Test fun duplicate_initial_or_completed_identity_is_rejected_without_a_second_birth() {
        val input = BuildingTestContent.scenario()
        val state = StepState(nextObjectId = 11L)
        val spawn = input.units.single { it.unitId == "f" }
        val factory = UnitRegistration.initial(state, input, spawn, ScreenPlane)
        assertFailsWith<IllegalStateException> { UnitRegistration.initial(state, input, spawn, ScreenPlane) }
        assertSame(factory, state.units.getValue("f"))
        val completed = ProductionCompletion("f", "p1", "c_tank", "0".repeat(32))
        val born = UnitRegistration.produced(state, input, factory, completed)
        assertFailsWith<IllegalStateException> { UnitRegistration.produced(state, input, factory, completed) }
        assertEquals(2, state.units.size)
        assertSame(born, state.units.getValue(born.unitId))
        assertEquals(12L, state.nextObjectId)
        assertEquals(1L, state.lastBirthSerial)
        assertEquals(listOf(Event("unit-produced", born.unitId)), state.pendingEvents)
    }

    @Test fun legacy_movement_world_keeps_null_initial_object_identity_and_no_combat_state() {
        val base = BuildingTestContent.scenario()
        val old = base.unitDefinitions.getValue("c_tank")
        val movementOnly = UnitDefinition(old.id, old.className, old.radius, old.displayRadius,
            old.totalFrames, old.imagePath, old.imageScale, old.movement, old.retainedUnconsumed, old.fogOfWarSightRange)
        val input = base.copy(unitDefinitions = mapOf(old.id to movementOnly),
            units = listOf(base.units.single { it.unitId == "a" }), combatContract = null, economyContract = null)
        val state = StepState()
        val born = UnitRegistration.initial(state, input, input.units.single(), ScreenPlane)
        assertNull(born.objectId)
        assertNull(born.combat)
        assertNull(born.namedWeapons)
        assertEquals(1L, state.nextObjectId)
        assertEquals(0L, state.lastBirthSerial)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertNull(context.observe().combat)
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", "a", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "a", Position(170.0, 210.0), context.world)))
            val view = context.advance(Step(0L, 1.0, "sim-tick"))
            assertEquals("a", view.units.single().unitId)
            assertEquals(Position(170.0, 210.0), view.units.single().moveTarget)
        } finally { context.close() }
    }

}
