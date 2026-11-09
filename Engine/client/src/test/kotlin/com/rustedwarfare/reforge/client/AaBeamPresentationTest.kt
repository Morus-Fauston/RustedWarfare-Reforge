package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class AaBeamPresentationTest {
    @Test fun visible_beams_use_height_projected_endpoints_all_three_images_and_evidence() {
        val loaded = AaBeamSandboxScene.load()
        val scene = loaded.scenario.copy(units = listOf(
            UnitSpawn("a", "p1", "aaBeamGunship", Position(350.0, 300.0), 90.0),
            UnitSpawn("b", "p2", "aaBeamGunship", Position(580.0, 300.0), 270.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var tick = 0L
            while (context.observe().projectiles.isEmpty() && tick < 200L) context.advance(Step(tick++, 1.0, "sim-tick"))
            val view = context.observePlayer(context.world, "p1")
            val drawings = BeamPresentation.project(view, loaded.renderResources)
            assertEquals(view.projectiles.size, drawings.size)
            assertTrue(drawings.isNotEmpty())
            for ((shot, drawing) in view.projectiles.zip(drawings)) {
                val end = shot.beam!!
                assertEquals(Position(shot.position.x, shot.position.y - shot.height), drawing.start)
                assertEquals(Position(end.end.x, end.end.y - end.endHeight), drawing.end)
                assertNotNull(drawing.images.beamImagePath)
                assertNotNull(drawing.images.beamStartImagePath)
                assertNotNull(drawing.images.beamEndImagePath)
            }
            val recorded = view.fields()["projectiles"] as List<*>
            assertNotNull((recorded.first() as Map<*, *>)["beam"])
            val beam = (recorded.first() as Map<*, *>)["beam"] as Map<*, *>
            assertEquals(2f, beam["textureOffset"], "The first displayed tick scrolls by the qualified rate 2")
            val aa = view.units.single { it.unitId == "a" }
            val fall = aa.flightPose!!.fields()["fall"]
            assertEquals(mapOf("speed" to 0f, "landed" to false, "landedInWater" to false, "settled" to false), fall)
            val weapon = view.weapons.single { it.unitId == "a" }
            val turret = loaded.renderResources.units.getValue("aaBeamGunship").turretDrawings(aa, listOf(weapon)).single()
            assertEquals(weapon.pivot.y - kotlin.math.cos(Math.toRadians(weapon.turretHeading)) * 5.0 - aa.height,
                turret.pivot.y, .000001)
        } finally { context.close() }
    }
}
