package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class HoverProjectionTest {
    @Test fun all_normal_hover_content_formats_keep_the_qualified_native_part_layout() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                fixtures.resolve("hover-production/$format/package"))).value
            val images = pack.units.getValue("hoverTank").imageBindings()
            for ((part, image) in images.images) {
                val png = javax.imageio.ImageIO.read(image.resource.toFile())
                assertEquals(ImageLogicalSize(17, 24), ImageLogicalSize(png.width, png.height),
                    "$format ${part.suffix}: normal content and native resource layer must share one layout")
                assertEquals(1, image.totalFrames)
            }
        }
    }

    @Test fun hover_shadow_survives_the_content_to_visual_and_resource_binding_path() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("hover-production/json/package"))).value
        val parts = pack.units.getValue("hoverTank").imageBindings()
        assertEquals(setOf("body", "dead", "shadow"), parts.images.keys.map { it.suffix }.toSet())
        val visual = parts.visualImages()
        assertEquals(parts.images, visual.bindings().images)
        val resources = PreparedResourcePack.fromBindings(BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER),
            mapOf("hoverTank" to visual), emptyMap())
        assertEquals(setOf("body", "dead", "shadow"), resources.unitBindings.getValue("hoverTank").bindings().images.keys.map { it.suffix }.toSet())
    }

    @Test fun hover_body_and_selection_use_the_same_elevated_center() {
        val unit = UnitObservation("hover", "p1", "hoverTank", Position(200.0, 200.0), 10.0, 1.0, 0.0,
            null, emptyList(), MovementState.MOVING, turretHeading = 90.0, height = 4.0,
            hoverPose = HoverPose(Position(1.0, 0.0), 90.0))
        assertEquals(Position(200.0, 196.0), unit.displayedBodyPosition())
        val projection = MapProjection(600.0, 400.0, MapArea(0.0, 0.0, 600.0, 400.0))
        val frame = assertNotNull(HoverPresentation.project(unit, projection))
        assertEquals(HoverUnitFrame(200f, 204f, -90f, 200f, 200f, true), frame)
        assertFalse(assertNotNull(HoverPresentation.project(unit.copy(alive = false), projection)).drawShadow)
        assertEquals(Position(200.0, 196.0), unit.copy(alive = false).displayedBodyPosition())
        assertEquals(Position(200.0, 200.0), unit.copy(hoverPose = null).displayedBodyPosition())
    }
}
