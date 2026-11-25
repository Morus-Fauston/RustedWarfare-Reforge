package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PhaseSevenPackAndDebugDenoiseTest {

    private fun repoRoot(): Path =
        System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }

    @Test
    fun phaseSevenSandboxPackLoadsWithRectangularShipsAndSeaFactoryProduction() {
        val packDir = repoRoot().resolve("SimulationTest/public/inputs/phase-seven-sandbox/json/package")
        assertTrue(Files.isRegularFile(packDir.resolve("pack.json")), "Missing phase-seven-sandbox pack.json")

        val loaded = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(packDir)).value
        assertEquals("phase-seven.json", loaded.id)

        // Complete unit roster preserved + gunboat added
        for (expectedId in listOf("commandCenter", "builder", "extractorT1", "landFactory", "seaFactory", "seaFactoryT2",
            "heavyBattleship", "gunboat", "attackSubmarine", "hovercraft", "modularSpider", "bomber", "nautilusSubmarine")) {
            assertTrue(expectedId in loaded.units, "Expected $expectedId in phase-seven-sandbox")
        }

        // Rectangular naval hulls strictly match phase-seven confirmed specs
        val battleship = loaded.units.getValue("heavyBattleship")
        val bsShape = assertIs<MovementCollisionShape.Rectangle>(battleship.movementCollision)
        assertEquals(102.0, bsShape.length, 1e-6)
        assertEquals(20.0, bsShape.width, 1e-6)

        val gunboat = loaded.units.getValue("gunboat")
        val gbShape = assertIs<MovementCollisionShape.Rectangle>(gunboat.movementCollision)
        assertEquals(24.0, gbShape.length, 1e-6)
        assertEquals(13.0, gbShape.width, 1e-6)

        // Sea factories produce gunboat & heavyBattleship
        assertTrue("gunboat" in loaded.units.getValue("seaFactory").production!!.unitTypeIds)
        assertTrue("gunboat" in loaded.units.getValue("seaFactoryT2").production!!.unitTypeIds)
        assertTrue("heavyBattleship" in loaded.units.getValue("seaFactoryT2").production!!.unitTypeIds)

        // Original map alias resolves "gunboat" and "battleship" cleanly
        assertEquals("gunboat", TmxSkirmishScenarioBuilder.resolveUnitTypeId("gunboat", loaded.units))
        assertEquals("heavyBattleship", TmxSkirmishScenarioBuilder.resolveUnitTypeId("battleship", loaded.units))

        // Build full skirmish scenario on built-in map and verify gunboat & battleship images bind cleanly
        val demo = TilesetAtlasResolver.builtInSkirmishMaps().first()
        val built = TmxSkirmishScenarioBuilder.build(demo.path, loaded.units)
        val resources = SandboxRenderResources.from(built.scenario)
        assertTrue(Files.isRegularFile(resources.units.getValue("gunboat").imagePath))
        assertTrue(Files.isRegularFile(resources.units.getValue("heavyBattleship").imagePath))
    }

    @Test
    fun debugLayerStateSupportsF3DDetailsDefaultOffToggleAndReset() {
        val initial = HudDebugLayerState()
        assertFalse(initial.enabled)
        assertTrue(initial.showBoxes)
        assertTrue(initial.showRoutes)
        assertFalse(initial.showDetails, "F3+D details must default OFF")
        assertFalse(initial.showGrid)

        // Toggling details while F3 disabled is a no-op
        assertEquals(initial, initial.toggleDetails())

        val on = initial.toggleEnabled()
        assertEquals("layers [B] boxes ON · [R] routes ON · [D] details off · [G] grid off",
            HudDebugProjection.buildLayersLine(on))

        val detailsOn = on.toggleDetails()
        assertTrue(detailsOn.showDetails)
        assertEquals("layers [B] boxes ON · [R] routes ON · [D] details ON · [G] grid off",
            HudDebugProjection.buildLayersLine(detailsOn))

        val reset = detailsOn.reset()
        assertEquals(HudDebugLayerState(), reset)
        assertFalse(reset.showDetails)
    }

    @Test
    fun collisionBoxPlanDenoisesUnselectedUnitsAndPlacesExteriorFrontArrowOutsideHull() {
        val rectCorners = listOf(
            Position(151.0, 210.0), // front-top (length=102 along +x, width=20 along y)
            Position(151.0, 190.0), // front-bottom
            Position(49.0, 190.0),  // rear-bottom
            Position(49.0, 210.0),  // rear-top
        )
        val ship = CollisionBoxDrawing(
            unitId = "ship-1",
            center = Position(100.0, 200.0),
            corners = rectCorners,
            bound = 52.0,
            bufferCorners = rectCorners,
            bufferRadius = 56.0,
            headingTip = Position(160.0, 200.0),
            velocityTip = Position(130.0, 200.0),
            guidanceTip = Position(165.0, 200.0),
            route = listOf(Position(250.0, 200.0)),
            target = Position(250.0, 200.0),
            stalledHighlight = false,
        )
        val stalledTank = CollisionBoxDrawing(
            unitId = "tank-2",
            center = Position(300.0, 200.0),
            corners = emptyList(),
            bound = 14.0,
            bufferCorners = emptyList(),
            bufferRadius = 18.0,
            headingTip = Position(300.0, 220.0),
            velocityTip = null,
            guidanceTip = null,
            route = emptyList(),
            target = null,
            stalledHighlight = true,
        )

        // Default F3 (showDetails=false), only ship-1 selected:
        val plansDefault = CollisionBoxView.planUnits(
            drawings = listOf(ship, stalledTank),
            selectedIds = setOf("ship-1"),
            showBoxes = true,
            showRoutes = true,
            showDetails = false,
        )
        assertTrue(plansDefault[0].drawHardHullAndArrow)
        assertTrue(plansDefault[0].drawSecondaryDetails, "Selected unit draws secondary details by default")
        assertTrue(plansDefault[0].drawRoute)
        assertEquals(CollisionBoxView.SELECTED_ROUTE_ALPHA, plansDefault[0].routeAlpha)

        assertTrue(plansDefault[1].drawHardHullAndArrow)
        assertTrue(plansDefault[1].stalledHighlight)
        assertFalse(plansDefault[1].drawSecondaryDetails, "Unselected unit hides secondary details when F3+D is OFF")
        assertFalse(plansDefault[1].drawRoute, "Unit with empty route/target does not draw route")

        // When ship-1 is unselected, its active route is dimmed to 0.35 alpha and secondary details hidden
        val plansNoneSelected = CollisionBoxView.planUnits(
            drawings = listOf(ship, stalledTank),
            selectedIds = emptySet(),
            showBoxes = true,
            showRoutes = true,
            showDetails = false,
        )
        assertFalse(plansNoneSelected[0].drawSecondaryDetails)
        assertTrue(plansNoneSelected[0].drawRoute)
        assertEquals(CollisionBoxView.UNSELECTED_ROUTE_ALPHA, plansNoneSelected[0].routeAlpha)

        // When F3+D is ON, ALL units expand secondary details even when unselected
        val plansDetailsOn = CollisionBoxView.planUnits(
            drawings = listOf(ship, stalledTank),
            selectedIds = emptySet(),
            showBoxes = true,
            showRoutes = true,
            showDetails = true,
        )
        assertTrue(plansDetailsOn[0].drawSecondaryDetails)
        assertTrue(plansDetailsOn[1].drawSecondaryDetails)

        // Exterior front arrow sits cleanly outside the front edge (x > 151.0), never crossing center (100.0)
        val (left, tip, right) = CollisionBoxView.exteriorHeadingArrow(ship)
        assertTrue(left.x > 151.0 && right.x > 151.0, "Arrow base must be outside front edge of hull")
        assertTrue(tip.x > left.x && tip.x > right.x, "Arrow tip must point forward along +x heading")
        assertEquals(200.0, tip.y, 1e-6)
                assertEquals(200.0, (left.y + right.y) * 0.5, 1e-6)

        // Multi-angle & circular hull exterior arrow checks (0/45/270/315 deg + circular)
        val circleUnit = stalledTank.copy(center = Position(100.0, 100.0), bound = 14.0, headingTip = Position(100.0, 130.0))
        val (cLeft, cTip, cRight) = CollisionBoxView.exteriorHeadingArrow(circleUnit)
        assertTrue(cLeft.y > 114.0 && cRight.y > 114.0, "Circular hull arrow must sit outside radius 14")
        assertTrue(cTip.y > cLeft.y && cTip.y > cRight.y)
        assertEquals(100.0, cTip.x, 1e-6)

        val diagShip = ship.copy(
            center = Position(0.0, 0.0),
            corners = listOf(Position(10.0, 10.0), Position(10.0, -10.0), Position(-10.0, -10.0), Position(-10.0, 10.0)),
            headingTip = Position(20.0, 20.0),
        )
        val (dLeft, dTip, dRight) = CollisionBoxView.exteriorHeadingArrow(diagShip)
        assertTrue(dTip.x > 10.0 && dTip.y > 10.0, "Diagonal exterior arrow must project beyond front corner")
        assertTrue(dTip.x > (dLeft.x + dRight.x) * 0.5 && dTip.y > (dLeft.y + dRight.y) * 0.5)

        // showBoxes=false disables secondary details even if showDetails=true
        val plansBoxesOff = CollisionBoxView.planUnits(listOf(ship), setOf("ship-1"), showBoxes = false, showRoutes = true, showDetails = true)
        assertFalse(plansBoxesOff[0].drawHardHullAndArrow)
        assertFalse(plansBoxesOff[0].drawSecondaryDetails)
        assertTrue(plansBoxesOff[0].drawRoute)
    }
}
