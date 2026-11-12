package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.*

class NautilusFormsContentTest {
    @Test fun scout_bot_launch_keeps_one_action_identity_across_the_three_forms() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val forms = mapOf("nautilusSubmarine" to false, "nautilusSubmarineSurface" to true,
            "nautilusSubmarineLand" to true)
        for (syntax in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPack(fixtures.resolve("nautilus-production/$syntax/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
            for ((id, visible) in forms) {
                val actions = checkNotNull(pack.units.getValue(id).customActions).actions
                val launch = assertNotNull(actions.singleOrNull { it.name == "launchScoutBot" }, "$syntax $id")
                assertEquals("claunchScoutBot", launch.actionId)
                assertEquals(visible, launch.visible)
                assertEquals(1f / 210f, launch.rate)
                assertEquals(29999, launch.cooldown)
                assertTrue(launch.cannotMove)
                assertTrue(launch.canPlayerCancel)
                assertFalse(launch.allowMultipleInQueue)
            }
        }
    }

    @Test fun inherited_conversion_actions_retain_their_original_ids_rates_visibility_and_cancellation_defaults() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (syntax in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPack(fixtures.resolve("nautilus-production/$syntax/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
            val subActions = checkNotNull(pack.units.getValue("nautilusSubmarine").customActions).actions
            val surfaceActions = checkNotNull(pack.units.getValue("nautilusSubmarineSurface").customActions).actions
            val landActions = checkNotNull(pack.units.getValue("nautilusSubmarineLand").customActions).actions
            assertEquals("nautilusSubmarineSurface_5", subActions.single { it.name == "surface" }.actionId)
            val dive = surfaceActions.single { it.name == "dive" }
            assertEquals("nautilusSubmarine_7", dive.actionId)
            assertEquals(1f / 270f, dive.rate)
            assertTrue(dive.canPlayerCancel)
            assertFalse(dive.allowMultipleInQueue)
            assertTrue(dive.cannotMove)
            assertTrue(dive.visible)
            assertFalse(surfaceActions.single { it.name == "surface" }.visible)
            assertEquals(listOf("trigger_onWater", "trigger_onLand"), landActions.filter { it.automatic != null }.map { it.name })
            assertEquals("nautilusSubmarineLand_12", landActions.last().actionId)
        }
    }

    @Test fun three_syntaxes_load_all_three_qualified_movement_forms_and_their_shared_resources() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (syntax in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPack(fixtures.resolve("nautilus-production/$syntax/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
            val sub = pack.units.getValue("nautilusSubmarine")
            val surface = pack.units.getValue("nautilusSubmarineSurface")
            val land = pack.units.getValue("nautilusSubmarineLand")
            assertEquals(MovementProfile(MovementType.WATER, .45, .015, .05, .8, .05, false, false, .6), sub.movement)
            assertEquals(MovementProfile(MovementType.HOVER, .6, .015, .05, .8, .05, false, false, .6), surface.movement)
            assertEquals(MovementProfile(MovementType.OVER_CLIFF_WATER, .35, .05, .05, .5, .2, false, false, .6, 45), land.movement)
            for (d in listOf(sub, surface, land)) {
                assertEquals(4000.0, d.maximumHealth)
                assertEquals(BuildProfile(18000L, .0003f), d.build)
                assertEquals(EnergyProfile(6f, .01f, .01f, true), d.energy)
                assertEquals(listOf("hoverTank"), d.production!!.unitTypeIds)
                assertEquals(2f, d.production.speedMultiplier)
            }
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
