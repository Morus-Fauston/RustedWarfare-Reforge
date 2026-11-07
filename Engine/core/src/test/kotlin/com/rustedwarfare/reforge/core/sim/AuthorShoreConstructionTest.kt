package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class AuthorShoreConstructionTest {
    private fun scene(format: String): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("author-shore/$format")
        val units = assertIs<ContentResult.Success<LoadedContentSet>>(
            ContentLoader.loadContentPacks(listOf(root.resolve("package")))).value.units
        val map = assertIs<ContentResult.Success<MapDefinition>>(
            ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))).value
        return TestContentFactory.openScenario().copy(scenarioId = "author-shore-foundations", map = map,
            unitDefinitions = units, units = listOf(UnitSpawn("a", "p1", "spbuilder", Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
    }

    @Test fun authoritative_placement_rejects_dry_and_cross_shore_foundations_without_registering_or_charging() {
        for (format in listOf("ini", "json", "hjson")) {
            val input = scene(format)
            assertEquals(MovementType.WATER, input.unitDefinitions.getValue("spshipyard").foundationMovementType)
            for (position in listOf(Position(90.0, 150.0), Position(190.0, 150.0), Position(250.0, 590.0))) {
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
                try {
                    assertIs<ActionResult.Accepted>(context.submit(BuildAction("bad-ground", "p1", "a", "spshipyard",
                        position, "a".repeat(32), context.world)))
                    var rejected = false
                    for (step in 0L until 1000L) {
                        val view = context.advance(Step(step, 1.0, "sim-tick"))
                        assertEquals(3000L, view.economy!!.balances.getValue("p1"))
                        assertTrue(view.units.none { it.typeId == "spshipyard" })
                        if (view.events.any { it.kind == "construction-unavailable" && it.unitId == "a" }) {
                            rejected = true
                            break
                        }
                    }
                    assertTrue(rejected, "$format $position must be rejected by the authority")
                    val before = context.observe()
                    assertIs<ActionResult.Rejected>(context.submit(BuildAction("outside", "p1", "a", "spshipyard",
                        Position(250.0, 650.0), "b".repeat(32), context.world)))
                    val after = context.observe()
                    assertEquals(before.units, after.units)
                    assertEquals(before.stepIndex, after.stepIndex)
                    assertEquals(before.economy!!.balances, after.economy!!.balances)
                    assertEquals(before.economy.queuedProduction, after.economy.queuedProduction)
                    assertEquals(before.construction!!.nonces, after.construction!!.nonces)
                } finally { context.close() }
            }
        }
    }

    @Test fun an_overlapping_five_row_placement_is_rejected_without_a_second_site_or_charge() {
        for (format in listOf("ini", "json", "hjson")) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(format))).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("legal", "p1", "a", "spshipyard",
                    Position(250.0, 150.0), "a".repeat(32), context.world)))
                var step = 0L
                while (context.observe().units.none { it.typeId == "spshipyard" && it.constructionProgress == 1f } && step < 2500)
                    context.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(1f, context.observe().units.single { it.typeId == "spshipyard" }.constructionProgress)
                assertEquals(2300L, context.observe().economy!!.balances.getValue("p1"))
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("overlap", "p1", "a", "spshipyard",
                    Position(250.0, 230.0), "b".repeat(32), context.world)))
                var rejected = false
                repeat(500) {
                    val view = context.advance(Step(step++, 1.0, "sim-tick"))
                    rejected = rejected || view.events.any { it.kind == "construction-unavailable" && it.unitId == "a" }
                    assertEquals(2300L, view.economy!!.balances.getValue("p1"))
                    assertEquals(1, view.units.count { it.typeId == "spshipyard" })
                }
                assertTrue(rejected, "$format occupied placement must report its refusal")
            } finally { context.close() }
        }
    }
}
