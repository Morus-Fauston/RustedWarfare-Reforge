package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HeavyMechLegDrawingTest {
    @Test fun four_leg_shafts_crop_the_vertical_source_about_the_foot_and_preserve_world_size_at_both_view_scales() {
        val base = HeavyMechClientScene.scenario()
        val source = base.unitDefinitions.getValue("mechHeavyMissile")
        val mech = assertNotNull(source.mech)
        for ((distance, halfSpan) in listOf(0.0 to 0, 2.9 to 3, 32.0 to 34, 33.0 to 34, 40.0 to 34)) {
            // Explicit shoulder-spacing mechanism variant, retaining the declared finite vertical artwork.
            val unit = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
                source.totalFrames, source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed,
                source.fogOfWarSightRange, combat = source.combat, mech = MechDefinition(mech.body,
                    mech.legs.map { it.copy(attachX = it.x + distance, attachY = it.y) }, mech.kind))
            val scene = base.copy(unitDefinitions = mapOf(unit.id to unit))
            val resources = SandboxRenderResources.from(scene)
            SandboxSession(scene).use { session ->
                session.tick() // Ground the initial .1-height feet before the fixed crop witnesses.
                val view = session.observation
                val foot = view.units.single().mechPose!!.legs.first().foot
                for (scale in listOf(1.0, .5)) {
                    val projection = MapProjection(600.0, 400.0, MapArea(0.0, 0.0, 600.0 * scale, 400.0 * scale))
                    val frame = MechPresentation.project(view, projection, resources).single().legs.first()
                    val shaft = assertNotNull(frame.shaft, "A declared four-leg body needs the vertical crop layout")
                    assertEquals(34 - halfSpan, shaft.sourceY, "distance=$distance")
                    assertEquals(halfSpan * 2, shaft.sourceHeight, "distance=$distance")
                    assertEquals((17.6 * scale).toFloat(), shaft.width, 1e-4f)
                    assertEquals((halfSpan * 2.2 * scale).toFloat(), shaft.height, 1e-4f)
                    assertEquals(projection.x(foot.x), shaft.x)
                    assertEquals(projection.y(foot.y), shaft.y)
                    if (distance > 0.0) assertEquals(-90f, shaft.rotation, 1e-4f)
                }
            }
        }
    }
}
