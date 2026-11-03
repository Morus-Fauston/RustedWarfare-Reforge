package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class MechProjectionTest {
    @Test
    fun real_session_feet_project_height_without_moving_the_world_foot_or_changing_an_old_snapshot() {
        val projection = MapProjection(600.0, 400.0, MapArea(0.0, 0.0, 600.0, 400.0))
        for (format in listOf("ini", "json", "hjson")) {
            SandboxSession(load(format).scenario).use { session ->
                val view = session.observation
                val old = MechPresentation.project(view, projection)
                val initial = old.single { it.unitId == "a" }
                assertEquals(-90f, initial.rotation)
                assertEquals(110f, initial.bodyX)
                assertEquals(190f, initial.bodyY)
                assertEquals(110f, initial.legs.first().anchorX)
                assertEquals(193f, initial.legs.first().anchorY)
                assertEquals(198.1f, initial.legs.first().footY, 1e-4f)
                assertEquals(5.1f, initial.legs.first().length, 1e-4f)
                assertFailsWith<UnsupportedOperationException> { (old as MutableList).clear() }
                assertFailsWith<UnsupportedOperationException> { (initial.legs as MutableList).clear() }
                session.select(Position(110.0, 210.0))
                assertIs<ActionResult.Accepted>(session.command(Position(250.0, 210.0)))
                repeat(10) { session.tick() }
                val next = MechPresentation.project(session.observation, projection).single { it.unitId == "a" }
                assertEquals(112.8f, next.legs.first().footX, 1e-4f)
                assertEquals(200.05f, next.legs.first().footY, 1e-4f)
                assertEquals(202.0, session.observation.units.first { it.unitId == "a" }.mechPose!!.legs.first().foot.y)
                assertEquals(old, MechPresentation.project(view, projection))
            }
        }
    }

    @Test
    fun firing_body_uses_the_authorized_main_body_recoil_pivot_instead_of_the_walkers_position() {
        val projection = MapProjection(600.0, 400.0, MapArea(0.0, 0.0, 600.0, 400.0))
        SandboxSession(load("json").scenario).use { session ->
            session.select(Position(110.0, 210.0))
            assertIs<ActionResult.Accepted>(session.command(Position(350.0, 210.0)))
            var found = false
            repeat(600) {
                if (!found) {
                    session.tick()
                    val view = session.observation
                    val unit = view.units.first { it.unitId == "a" }
                    val weapon = view.weapons.single { it.unitId == "a" && it.weaponName == "mainBody" }
                    if (weapon.pivot != unit.position) {
                        val frame = MechPresentation.project(view, projection).single { it.unitId == "a" }
                        assertEquals(projection.x(weapon.pivot.x), frame.bodyX)
                        assertEquals(projection.y(weapon.pivot.y), frame.bodyY)
                        assertEquals(-weapon.turretHeading.toFloat(), frame.rotation)
                        assertEquals(.5, hypot(weapon.pivot.x - unit.position.x, weapon.pivot.y - unit.position.y), 1e-10)
                        found = true
                    }
                }
            }
            assertTrue(found, "The actual session must fire and expose a nonzero recoil pose")
        }
    }

    private fun load(format: String): LoadedSandbox {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("mech-production/$format")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "mechGun"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("Real mech projection input did not load")
        }
    }
}
