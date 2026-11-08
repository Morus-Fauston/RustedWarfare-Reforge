package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class HoverSinkingTest {
    @Test fun actual_damage_leaves_a_hover_corpse_that_lands_or_sinks_without_bobbing_or_moving() {
        for (terrain in listOf("land", "water", "bridge")) withHoverPackage { root ->
            val hover = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("hoverTank")
            val victim = UnitDefinition(hover.id, hover.className, hover.radius, hover.displayRadius,
                hover.totalFrames, hover.imagePath, hover.imageScale, hover.movement, emptySet(),
                durability = DurabilityProfile(150.0, hover.combat!!.deadImagePath))
            val tiles = TileSetDefinition(1, "floor", 20, 20, Path.of("unused.png"), emptySet(), 1, 1,
                waterTileIds = if (terrain == "water") setOf(0) else emptySet(),
                waterBridgeTileIds = if (terrain == "bridge") setOf(0) else emptySet())
            val base = TestContentFactory.openScenario()
            val input = base.copy(map = MapDefinition(40, 30, 20, 20, listOf(tiles), IntArray(1200) { 1 },
                BlockingGrid(40, 30, BooleanArray(1200))),
                unitDefinitions = mapOf("attacker" to hover, "victim" to victim),
                units = listOf(UnitSpawn("a", "p1", "attacker", Position(300.0, 200.0), 90.0),
                    UnitSpawn("v", "p2", "victim", Position(400.0, 200.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "v" to 9L), 10L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                var step = 0L
                fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
                while (context.observe().units.single { it.unitId == "v" }.alive && step < 1200) tick()
                val death = context.observe().units.single { it.unitId == "v" }
                assertFalse(death.alive)
                assertEquals(0.0, death.health)
                assertTrue(death.height > 0)
                val first = tick().units.single { it.unitId == "v" }
                assertEquals(death.height - .402, first.height, 1e-6)
                repeat(150) { tick() }
                val settled = context.observePlayer(context.world, "p2").units.single { it.unitId == "v" }
                assertEquals(if (terrain == "water") -10.0 else 0.0, settled.height)
                assertEquals(death.position, settled.position)
                assertEquals(death.hoverPose!!.heightPhase, settled.hoverPose!!.heightPhase)
                assertEquals(0.0, settled.speed)
                assertEquals(settled.height, tick().units.single { it.unitId == "v" }.height)
                assertTrue(death.height > 0, "Published death frame remains immutable")
            } finally { context.close() }
        }
    }
}
