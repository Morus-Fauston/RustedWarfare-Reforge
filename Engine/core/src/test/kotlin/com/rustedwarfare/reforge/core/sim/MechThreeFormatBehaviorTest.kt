package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechThreeFormatBehaviorTest {
    @Test
    fun ini_json_and_commented_hjson_feed_identical_real_movement_gait_and_armed_observations() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        var expected: List<Triple<List<UnitObservation>, List<VisibleUnitWeapon>, List<VisibleProjectileEvent>>>? = null
        for (format in listOf("json", "hjson", "ini")) {
            val loaded = ContentLoader.loadContentPack(fixtures.resolve("mech-production/$format/package"))
            val mech = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, "$format: $loaded").value.units.getValue("mechGun")
            val scene = MechTestScene.scenario().copy(scenarioId = "p008-mech-formats",
                unitDefinitions = mapOf("mech" to mech),
                units = listOf(UnitSpawn("u1", "p1", "mech", Position(100.0, 100.0), 90.0),
                    UnitSpawn("u2", "p2", "mech", Position(200.0, 100.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("u1" to 8L, "u2" to 9L), 10L),
                economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene), format).context
            try {
                var born = false
                var footMoved = false
                val samples = (0L..119L).map { step ->
                    when (step) {
                        0L -> assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1", Position(120.0, 100.0), context.world)))
                        40L -> assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "u1", context.world)))
                        60L -> assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "u1", "u2", context.world)))
                    }
                    context.advance(Step(step, 1.0, "sim-tick"))
                    val view = context.observePlayer(context.world, "p1")
                    born = born || view.projectileEvents.any { it.kind == "born" }
                    footMoved = footMoved || assertNotNull(view.units.first { it.unitId == "u1" }.mechPose).legs.any {
                        it.height > .1 && it.phase != MechLegPhase.PLANTED }
                    Triple(view.units, view.weapons, view.projectileEvents)
                }
                assertTrue(born, format)
                assertTrue(footMoved, format)
                expected?.let { assertEquals(it, samples, "Full authorized physical samples: $format") } ?: run { expected = samples }
            } finally { context.close() }
        }
    }
}
