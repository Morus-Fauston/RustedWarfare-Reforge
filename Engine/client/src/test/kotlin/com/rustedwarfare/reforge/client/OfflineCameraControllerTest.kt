package com.rustedwarfare.reforge.client

import java.nio.file.Path
import com.rustedwarfare.reforge.core.sim.OfflineCameraMotion
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineCameraControllerTest {
    @Test fun the_selected_scene_activates_its_first_cue_at_the_authored_host_step() {
        val publicRoot = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).parent
        val choice = TestScenarioChoice.open(publicRoot.resolve("scenarios/phase-seven-camera.json"))
        assertEquals("public.phase-seven-offline-camera.v1", choice.id)
        val map = MapProjection(1200.0, 800.0)
        map.zoomAt(324.0, 300.0, -30.0)
        val camera = OfflineCameraController(map)
        SandboxSession(choice.scenario, cameraCues = choice.cameraCues).use { session ->
            repeat(180) {
                session.tick()
                session.updateCamera(camera)
            }
            assertEquals(Position(600.0, 400.0), map.camera.center)
            session.tick()
            session.updateCamera(camera)
            assertEquals(Position(400.0, 260.0), map.camera.center)
        }
    }

    @Test fun locate_and_pan_use_the_same_view_and_replacement_begins_at_its_current_center() {
        val (map, camera) = zoomedMap()
        fun advance(step: Long, vararg motions: OfflineCameraMotion) =
            camera.advance(step, motions.toList()) { null }

        advance(0, OfflineCameraMotion.Locate(Position(280.0, 200.0)))
        assertEquals(Position(280.0, 200.0), map.camera.center)
        advance(1, OfflineCameraMotion.Pan(Position(400.0, 200.0), 20))
        advance(11)
        assertEquals(Position(340.0, 200.0), map.camera.center)
        advance(12, OfflineCameraMotion.Pan(Position(240.0, 200.0), 10))
        assertEquals(Position(340.0, 200.0), map.camera.center)
        advance(17)
        assertEquals(Position(290.0, 200.0), map.camera.center)
        advance(22)
        assertEquals(Position(240.0, 200.0), map.camera.center)
        map.centerOn(Position(330.0, 200.0))
        advance(23)
        assertEquals(Position(330.0, 200.0), map.camera.center)
    }

    @Test fun follow_stops_at_the_last_authorized_view_and_manual_takeover_does_not_resume_it() {
        val (map, camera) = zoomedMap()
        var authorized: Position? = Position(280.0, 200.0)
        fun advance(step: Long, vararg motions: OfflineCameraMotion) =
            camera.advance(step, motions.toList()) { if (it == "tracked") authorized else null }

        advance(0, OfflineCameraMotion.Follow("tracked"))
        assertTrue(camera.followsUnit)
        authorized = Position(320.0, 200.0)
        advance(1)
        assertEquals(authorized, map.camera.center)
        map.zoomAt(380.0, 320.0, 1.0, keepCenter = camera.followsUnit)
        assertEquals(3.3333333333333335, map.camera.zoom, 1e-9)
        assertEquals(authorized, map.camera.center)
        authorized = null
        advance(2)
        assertFalse(camera.followsUnit)
        assertEquals(Position(320.0, 200.0), map.camera.center)
        authorized = Position(350.0, 200.0)
        advance(3)
        assertEquals(Position(320.0, 200.0), map.camera.center)

        for (reason in listOf("pan", "minimap", "group")) {
            advance(4, OfflineCameraMotion.Follow("tracked"))
            assertTrue(camera.followsUnit)
            camera.takeOver(reason)
            map.centerOn(Position(250.0, 200.0))
            advance(5)
            assertFalse(camera.followsUnit)
            assertEquals(Position(250.0, 200.0), map.camera.center)
        }
    }

    private fun zoomedMap(): Pair<MapProjection, OfflineCameraController> {
        val map = MapProjection(600.0, 400.0)
        map.zoomAt(324.0, 300.0, -30.0)
        return map to OfflineCameraController(map)
    }
}
