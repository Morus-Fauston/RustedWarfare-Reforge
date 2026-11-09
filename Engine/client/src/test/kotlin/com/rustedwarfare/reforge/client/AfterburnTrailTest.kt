package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class AfterburnTrailTest {
    @Test fun moving_afterburn_emits_two_height_projected_trails_and_cancel_stops_emission_without_replaying_draws() {
        val loaded = AaBeamSandboxScene.load()
        val scene = loaded.scenario.copy(units = listOf(UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("aa" to 8L), 9L))
        val opened = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            repeat(200) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val presentation = AfterburnTrailPresentation()
            fun project() = presentation.project(context.observePlayer(context.world, "p1"), loaded.renderResources)
            assertTrue(project().isEmpty())
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "aa", Position(700.0, 300.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("burn", "p1", "aa", "afterBurn", context.world)))
            repeat(3) { context.advance(Step(200L + it, 1.0, "sim-tick")); assertTrue(project().isEmpty()) }
            context.advance(Step(203, 1.0, "sim-tick"))
            val first = project()
            assertEquals(2, first.size)
            assertEquals(first, project(), "Drawing the same simulation observation must not emit twice")
            val unit = context.observe().units.single()
            assertEquals(unit.position.x - 22.0, first.first().center.x, .000001)
            assertEquals(unit.position.y + 10.0 - unit.height, first.first().center.y, .000001)
            assertEquals(20.0, first.first().center.y - first.last().center.y, .000001)
            assertEquals(.7f, first.first().scale)
            assertEquals(0f, first.first().alpha)
            assertNotNull(first.first().images.afterburnTrailImagePath)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("cancel", "p1", "aa", "cancelBurn", context.world)))
            repeat(6) { context.advance(Step(204L + it, 1.0, "sim-tick")); project() }
            val fading = project()
            assertEquals(2, fading.size)
            assertEquals(.84f, fading.first().scale, .000001f)
            assertEquals(.56f, fading.first().alpha, .000001f)
            assertEquals(first.first().center.x - 1.2, fading.first().center.x, .000001)
            repeat(24) { context.advance(Step(210L + it, 1.0, "sim-tick")); project() }
            assertTrue(project().isEmpty())
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            assertTrue(project().isEmpty(), "A new world cannot retain particles from the old world")
        } finally { context.close() }
    }
}
