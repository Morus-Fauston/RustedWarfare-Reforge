package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.sim.NautilusScene
import kotlin.test.*

class NautilusLegContentTest {
    @Test fun three_formats_include_six_leg_surface_and_land_profiles_with_distinct_feet_and_attachments() {
        for (format in listOf("ini", "json", "hjson")) {
            val definitions = NautilusScene.definitions(format)
            assertNull(definitions.getValue("nautilusSubmarine").mech)
            val surface = assertNotNull(definitions.getValue("nautilusSubmarineSurface").mech)
            val land = assertNotNull(definitions.getValue("nautilusSubmarineLand").mech)
            assertEquals(listOf(-6.0 to 0.0, 6.0 to 0.0, -6.0 to 34.0, 6.0 to 34.0, -4.0 to -25.0, 4.0 to -25.0),
                surface.legs.map { it.x to it.y })
            assertEquals(listOf(-20.0 to 0.0, 20.0 to 0.0, -20.0 to 34.0, 20.0 to 34.0, -14.0 to -25.0, 14.0 to -25.0),
                land.legs.map { it.x to it.y })
            assertEquals(listOf(-6.0 to 6.0, 6.0 to 6.0, -6.0 to 26.0, 6.0 to 26.0, -2.0 to -20.0, 2.0 to -20.0),
                land.legs.map { it.attachX to it.attachY })
            assertTrue(surface.legs.all { it.moveSpeed == .3 && it.heightSpeed == .3 && it.rotateSpeed == 3.0 })
            assertTrue(land.legs.all { it.moveSpeed == 1.5 && it.warmUp == 0.0 && it.maximumMovingLegs == 3 })
        }
    }
}
