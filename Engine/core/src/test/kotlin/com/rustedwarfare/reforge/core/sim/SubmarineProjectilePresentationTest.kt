package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class SubmarineProjectilePresentationTest {
    @Test fun launch_appearance_is_captured_and_projected_to_both_visible_audiences() {
        for ((height, appearance) in listOf(-8.0 to ProjectileAppearance.SUBMARINE_TORPEDO,
                -1.0 to ProjectileAppearance.SUBMARINE_MISSILE)) withSubmarineCombat(height, 150.0) { context ->
            val first = context.advance(Step(0L, 1.0, "sim-tick"))
            assertEquals(appearance, first.projectileEvents.single { it.kind == "born" }.projectile.appearance)
            assertEquals(appearance, first.projectiles.single().appearance)
            for (player in listOf("p1", "p2")) {
                val public = context.observePlayer(context.world, player)
                assertEquals(appearance, public.projectiles.single().appearance)
                assertEquals(appearance, public.projectileEvents.single { it.kind == "born" }.appearance)
            }
            val second = context.advance(Step(1L, 1.0, "sim-tick"))
            assertEquals(appearance, second.projectiles.single().appearance)
        }
    }
}
