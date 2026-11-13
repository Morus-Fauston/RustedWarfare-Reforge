package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class SubmarineProjectionTest {
    @Test fun owner_sees_control_state_but_visible_enemy_does_not_see_intent_or_its_action_event() {
        withSubmarineCombat(-8.0, 150.0) { context ->
            val enemyBefore = context.observePlayer(context.world, "p2")
            val before = assertNotNull(context.observe().units.single { it.unitId == "sub" }.submarine)
            assertEquals(false, before.surfaceIntent)
            assertEquals(0f, before.verticalSpeed)
            assertTrue(before.underwater)
            assertEquals(.45, before.maximumSpeed)
            assertEquals(180.0, before.attackRange)
            assertIs<ActionResult.Accepted>(context.submit(SubmarineDepthAction("surface", "p1", "sub", true, context.world)))
            val owner = context.observePlayer(context.world, "p1")
            val state = assertNotNull(owner.units.single { it.unitId == "sub" }.submarine)
            assertEquals(true, state.surfaceIntent)
            assertEquals(0f, state.verticalSpeed)
            assertTrue(owner.events.any { it.kind == "submarine-surface-requested" })
            val enemy = context.observePlayer(context.world, "p2")
            val public = assertNotNull(enemy.units.single { it.unitId == "sub" }.submarine)
            assertNull(public.surfaceIntent)
            assertNull(public.verticalSpeed)
            assertTrue(public.underwater)
            assertEquals(enemyBefore.sampleId, enemy.sampleId, "A hidden intent does not advance the enemy's public identity")
            assertTrue(enemy.events.none { it.kind.endsWith("requested") })
            assertTrue(enemy.actionResults.isEmpty())
            context.advance(Step(0L, 1.0, "sim-tick"))
            val after = assertNotNull(context.observePlayer(context.world, "p1").units.single { it.unitId == "sub" }.submarine)
            assertEquals(.003f, after.verticalSpeed)
            assertEquals(0f, state.verticalSpeed, "Published owner DTO stays immutable")
        }
    }
}
