package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

/**
 * Phase VI-B parameterized movement archetype contracts (ENG-P017-006 / SPEC §2.1):
 * Zero new engine unit-ID branches — all four cross-tier prototypes are constructed purely
 * from data-driven [UnitDefinition] / [MovementProfile] parameters.
 */
class PhaseSixBMovementContractTest {
    private val dummyImage = Path.of("units/placeholder.png")

    private fun baseScenario(): Scenario = TestContentFactory.openScenario()

    private fun buildMap(
        width: Int,
        height: Int,
        groundAt: (Int, Int) -> Int,
    ): MapDefinition {
        val gids = IntArray(width * height) { idx -> groundAt(idx % width, idx / width) }
        // local tile IDs (gid - 1): 0 = land, 1 = water (auto-blocked for LAND & OVER_CLIFF by GroundRules),
        // 2 = cliff (blocked for LAND/HOVER/WATER, passable for OVER_CLIFF & OVER_CLIFF_WATER)
        val groundSet = TileSetDefinition(
            firstGid = 1,
            name = "terrain",
            tileWidth = 20,
            tileHeight = 20,
            imagePath = dummyImage,
            blockedTileIds = setOf(2),
            tileCount = 4,
            columns = 4,
            waterTileIds = setOf(1),
            hoverBlockedTileIds = setOf(2),
            overCliffBlockedTileIds = setOf(3),
        )
        val blocked = BooleanArray(width * height) { idx -> gids[idx] != 1 }
        return MapDefinition(
            widthTiles = width,
            heightTiles = height,
            tileWidth = 20,
            tileHeight = 20,
            tileSets = listOf(groundSet),
            groundGids = gids,
            blocking = BlockingGrid(width, height, blocked),
        )
    }

    private fun sixLeggedMech(legRadius: Double = 28.0): MechDefinition {
        val offsets = listOf(
            -legRadius to -legRadius * 0.6,
            legRadius to -legRadius * 0.6,
            -legRadius * 1.1 to 0.0,
            legRadius * 1.1 to 0.0,
            -legRadius to legRadius * 0.6,
            legRadius to legRadius * 0.6,
        )
        return MechDefinition(
            body = MechBodyDefinition(0.0, 1.0, 0.0, 180.0, 0.0, 0.0, 0.0, 0),
            legs = offsets.map { (lx, ly) ->
                MechLegDefinition(
                    x = lx,
                    y = ly,
                    attachX = lx * 0.35,
                    attachY = ly * 0.35,
                    moveSpeed = 1.8,
                    warmUp = 0.0,
                    rotateSpeed = 3.0,
                    heightSpeed = 0.4,
                    holdMinimum = 10.0,
                    holdWithMovingNeighbour = 20.0,
                    predictionMultiplier = 1.0,
                    legImage = dummyImage,
                    footImage = dummyImage,
                    resetAngle = 20.0,
                    maximumMovingLegs = 3,
                )
            },
            kind = MechKind.SIX_LEGGED,
        )
    }

    @Test
    fun giant_radius38_over_cliff_water_six_legged_group_crosses_cliff_and_water_and_pushes_cleanly() {
        // 40x24 map: left land (x<12), cliff band (x 12..15), deep water band (x 16..23), right land (x>=24)
        val map = buildMap(40, 24) { x, _ ->
            when {
                x in 12..15 -> 3 // cliff (local tile 2)
                x in 16..23 -> 2 // water (local tile 1)
                else -> 1        // land (local tile 0)
            }
        }
        val giantDef = UnitDefinition(
            id = "customColossusSpider",
            className = "CustomUnitMetadata",
            radius = 38.0,
            displayRadius = 38.0,
            totalFrames = 1,
            imagePath = dummyImage,
            imageScale = 1.0,
            movement = MovementProfile(
                type = MovementType.OVER_CLIFF_WATER,
                moveSpeed = 0.9,
                moveAccelerationSpeed = 0.08,
                moveDecelerationSpeed = 0.12,
                maxTurnSpeed = 1.6,
                turnAcceleration = 0.12,
                slidingMode = false,
                ignoringBody = false,
                reverseSpeedPercentage = 0.4,
            ),
            retainedUnconsumed = emptySet(),
            mech = sixLeggedMech(),
            durability = DurabilityProfile(maxHp = 6000.0, deadImagePath = null),
            mass = 12.0,
        )
        val base = baseScenario()
        val scenario = base.copy(
            scenarioId = "p6b-giant-over-cliff-water",
            map = map,
            unitDefinitions = mapOf(giantDef.id to giantDef),
            units = listOf(
                UnitSpawn("g1", "p1", giantDef.id, Position(120.0, 200.0), 90.0),
                UnitSpawn("g2", "p1", giantDef.id, Position(120.0, 290.0), 90.0),
                UnitSpawn("g3", "p1", giantDef.id, Position(610.0, 240.0), 270.0),
            ),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val g3Start = Position(610.0, 240.0)
            val target = Position(610.0, 240.0)
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("gm-colossus-1", "p1", "g1", target, ctx.world)))
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("gm-colossus-2", "p1", "g2", target, ctx.world)))
            var crossedCliff = false
            var crossedWater = false
            for (tick in 0L until 1400L) {
                val obs = ctx.advance(Step(tick, 1.0, "sim-tick"))
                val g1 = obs.units.single { it.unitId == "g1" }
                val g2 = obs.units.single { it.unitId == "g2" }
                val g3 = obs.units.single { it.unitId == "g3" }
                if (g1.position.x in 240.0..320.0) crossedCliff = true
                if (g1.position.x in 320.0..480.0) crossedWater = true
                val pose = assertNotNull(g1.mechPose, "Six-legged giant must emit finite mechPose")
                assertEquals(6, pose.legs.size)
                // Hard-contact zero-intrusion invariant between 38px-radius giants
                val pairs = listOf(g1 to g2, g1 to g3, g2 to g3)
                for ((a, b) in pairs) {
                    val dist = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                    assertTrue(dist >= 75.5, "Radius-38 giants ${a.unitId}/${b.unitId} must not penetrate (dist=$dist at tick $tick)")
                }
                if (g1.moveTarget == null && g2.moveTarget == null && tick > 200L) break
            }
            val finalObs = ctx.observe()
            val g1 = finalObs.units.single { it.unitId == "g1" }
            val g2 = finalObs.units.single { it.unitId == "g2" }
            val g3 = finalObs.units.single { it.unitId == "g3" }
            assertTrue(crossedCliff, "OVER_CLIFF_WATER giant must traverse cliff band")
            assertTrue(crossedWater, "OVER_CLIFF_WATER giant must traverse deep water band")
            assertTrue(g1.position.x > 520.0 && g2.position.x > 520.0, "Both giants must reach east shore (g1=${g1.position}, g2=${g2.position})")
            assertTrue(
                hypot(g3.position.x - g3Start.x, g3.position.y - g3Start.y) > 5.0 || g1.moveTarget == null,
                "Arriving radius-38 giants must displace idle giant g3 or settle cleanly around it",
            )
        } finally {
            ctx.close()
        }
    }

    @Test
    fun parameterized_custom_submarine_dives_surfaces_switches_layer_without_native_id() {
        val map = buildMap(30, 20) { _, _ -> 2 } // Open water
        val subProfile = SubmersibleMotionProfile(
            surfaceHeight = 1.5,
            submergedHeight = -9.0,
            fastVerticalRate = 0.15,
            fineVerticalRate = 0.04,
            verticalAcceleration = 0.01,
            submergedSpeed = 0.55,
            submergedTurnSpeed = 1.1,
            divingChangesRoute = true,
            diveRequiresOverWater = true,
        )
        val customSub = UnitDefinition(
            id = "customHeavySub",
            className = "CustomUnitMetadata",
            radius = 24.0,
            displayRadius = 24.0,
            totalFrames = 1,
            imagePath = dummyImage,
            imageScale = 1.0,
            movement = MovementProfile(
                type = MovementType.WATER,
                moveSpeed = 1.1,
                moveAccelerationSpeed = 0.04,
                moveDecelerationSpeed = 0.08,
                maxTurnSpeed = 1.6,
                turnAcceleration = 0.08,
                slidingMode = false,
                ignoringBody = false,
                reverseSpeedPercentage = 0.5,
                submersible = subProfile,
            ),
            retainedUnconsumed = emptySet(),
            durability = DurabilityProfile(maxHp = 1200.0, deadImagePath = null),
        )
        val surfaceBlocker = UnitDefinition(
            id = "customPatrolBoat",
            className = "CustomUnitMetadata",
            radius = 20.0,
            displayRadius = 20.0,
            totalFrames = 1,
            imagePath = dummyImage,
            imageScale = 1.0,
            movement = MovementProfile(
                type = MovementType.WATER,
                moveSpeed = 1.0,
                moveAccelerationSpeed = 0.05,
                moveDecelerationSpeed = 0.08,
                maxTurnSpeed = 1.5,
                turnAcceleration = 0.08,
                slidingMode = false,
                ignoringBody = false,
            ),
            retainedUnconsumed = emptySet(),
            durability = DurabilityProfile(maxHp = 500.0, deadImagePath = null),
        )
        val base = baseScenario()
        val scenario = base.copy(
            scenarioId = "p6b-custom-sub",
            map = map,
            unitDefinitions = mapOf(customSub.id to customSub, surfaceBlocker.id to surfaceBlocker),
            units = listOf(
                UnitSpawn("sub1", "p1", customSub.id, Position(120.0, 200.0), 90.0, height = 1.5),
                UnitSpawn("boat1", "p1", surfaceBlocker.id, Position(260.0, 200.0), 90.0, height = 0.0),
            ),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            // 1. Starts with default dive intent at height=1.5 -> dives below SUBMERGED_DEPTH (-1.0)
            var crossedDown = false
            for (tick in 0L until 120L) {
                val obs = ctx.advance(Step(tick, 1.0, "sim-tick"))
                if (obs.events.any { it.kind == "submarine-threshold-crossed" && it.unitId == "sub1" }) crossedDown = true
            }
            assertTrue(crossedDown, "Parameterized custom submarine must emit submarine-threshold-crossed on dive")
            val submergedObs = ctx.observe().units.single { it.unitId == "sub1" }
            assertTrue(submergedObs.height < UnitLocomotion.SUBMERGED_DEPTH, "Custom submarine must submerge below -1.0 (h=${submergedObs.height})")
            assertEquals(true, submergedObs.submarine?.underwater)
            assertEquals(0.55, submergedObs.submarine?.maximumSpeed)

            // 2. While submerged, pass directly underneath surface boat without surface contact blocking
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-under", "p1", "sub1", Position(420.0, 200.0), ctx.world)))
            for (tick in 120L until 800L) {
                val obs = ctx.advance(Step(tick, 1.0, "sim-tick"))
                if (obs.units.single { it.unitId == "sub1" }.moveTarget == null) break
            }
            val passedSub = ctx.observe().units.single { it.unitId == "sub1" }
            assertTrue(passedSub.position.x > 390.0, "Submerged custom submarine must travel past surface boat (pos=${passedSub.position})")

            // 3. Surface back above -1.0 and restore surface speed 1.1
            assertIs<ActionResult.Accepted>(ctx.submit(SubmarineDepthAction("surf", "p1", "sub1", surface = true, ctx.world)))
            var crossedUp = false
            for (tick in 800L until 950L) {
                val obs = ctx.advance(Step(tick, 1.0, "sim-tick"))
                if (obs.events.any { it.kind == "submarine-threshold-crossed" && it.unitId == "sub1" }) crossedUp = true
            }
            assertTrue(crossedUp, "Parameterized custom submarine must emit submarine-threshold-crossed on surface")
            val surfacedObs = ctx.observe().units.single { it.unitId == "sub1" }
            assertTrue(surfacedObs.height > UnitLocomotion.SUBMERGED_DEPTH)
            assertEquals(false, surfacedObs.submarine?.underwater)
            assertEquals(1.1, surfacedObs.submarine?.maximumSpeed)

            // 4. Explicit dive command again works end-to-end
            assertIs<ActionResult.Accepted>(ctx.submit(SubmarineDepthAction("dive-again", "p1", "sub1", surface = false, ctx.world)))
            var crossedDownAgain = false
            for (tick in 950L until 1070L) {
                val obs = ctx.advance(Step(tick, 1.0, "sim-tick"))
                if (obs.events.any { it.kind == "submarine-threshold-crossed" && it.unitId == "sub1" }) crossedDownAgain = true
            }
            assertTrue(crossedDownAgain, "Explicit SubmarineDepthAction(surface=false) must dive back below threshold")
            assertEquals(true, ctx.observe().units.single { it.unitId == "sub1" }.submarine?.underwater)

            // 5. Cross-medium route switching: HOVER-surface / WATER-submerged custom submersible switches MovementType on dive/surface
            val hoverSub = UnitDefinition(
                id = "customHoverSub",
                className = "CustomUnitMetadata",
                radius = 18.0,
                displayRadius = 18.0,
                totalFrames = 1,
                imagePath = dummyImage,
                imageScale = 1.0,
                movement = MovementProfile(
                    type = MovementType.HOVER,
                    moveSpeed = 1.2,
                    moveAccelerationSpeed = 0.05,
                    moveDecelerationSpeed = 0.08,
                    maxTurnSpeed = 1.6,
                    turnAcceleration = 0.08,
                    slidingMode = false,
                    ignoringBody = false,
                    submersible = subProfile,
                ),
                retainedUnconsumed = emptySet(),
                durability = DurabilityProfile(maxHp = 800.0, deadImagePath = null),
            )
            assertEquals(setOf(MovementType.HOVER, MovementType.WATER), UnitLocomotion.routeTypes(hoverSub))
            val hoverUnit = MutableUnit("hs1", "p1", hoverSub.id, hoverSub, Position(120.0, 200.0), 90.0, height = 1.5)
            hoverUnit.submarineSurfaceIntent = true
            assertEquals(MovementType.HOVER, UnitLocomotion.movement(hoverUnit).type)
            hoverUnit.submarineSurfaceIntent = false
            assertEquals(MovementType.WATER, UnitLocomotion.movement(hoverUnit).type)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun parameterized_dual_speed_hovercraft_switches_speed_between_water_and_land_on_landing() {
        // West half water (x < 18), east half land (x >= 18)
        val map = buildMap(36, 16) { x, _ -> if (x < 18) 2 else 1 }
        val customHover = UnitDefinition(
            id = "customAssaultHover",
            className = "CustomUnitMetadata",
            radius = 22.0,
            displayRadius = 22.0,
            totalFrames = 1,
            imagePath = dummyImage,
            imageScale = 1.0,
            movement = MovementProfile(
                type = MovementType.HOVER,
                moveSpeed = 0.85,
                moveAccelerationSpeed = 0.08,
                moveDecelerationSpeed = 0.1,
                maxTurnSpeed = 1.5,
                turnAcceleration = 0.15,
                slidingMode = true,
                ignoringBody = true,
                reverseSpeedPercentage = 0.25,
                hover = HoverProfile(targetHeight = 4.0, heightDrift = 1.0, phaseSpeed = 3.0, heightChangeRate = 0.15),
                waterLandSpeed = WaterLandSpeedProfile(
                    waterMoveSpeed = 1.6,
                    landMoveSpeed = 0.85,
                    waterTurnSpeed = 2.4,
                    landTurnSpeed = 1.2,
                ),
            ),
            retainedUnconsumed = emptySet(),
            durability = DurabilityProfile(maxHp = 900.0, deadImagePath = null),
        )
        val base = baseScenario()
        val scenario = base.copy(
            scenarioId = "p6b-dual-speed-hover",
            map = map,
            unitDefinitions = mapOf(customHover.id to customHover),
            units = listOf(UnitSpawn("hv1", "p1", customHover.id, Position(100.0, 160.0), 90.0)),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("land-run", "p1", "hv1", Position(600.0, 160.0), ctx.world)))
            var peakWaterSpeed = 0.0
            var steadyLandSpeed = Double.POSITIVE_INFINITY
            var prevPos = Position(100.0, 160.0)
            for (tick in 0L until 600L) {
                val obs = ctx.advance(Step(tick, 1.0, "sim-tick"))
                val u = obs.units.single()
                val stepSpeed = hypot(u.position.x - prevPos.x, u.position.y - prevPos.y)
                prevPos = u.position
                if (u.position.x in 180.0..320.0) peakWaterSpeed = maxOf(peakWaterSpeed, stepSpeed)
                if (u.position.x in 440.0..540.0) steadyLandSpeed = minOf(steadyLandSpeed, stepSpeed)
                if (u.moveTarget == null && tick > 100L) break
            }
            val finalUnit = ctx.observe().units.single()
            assertTrue(peakWaterSpeed > 1.35, "Hovercraft must cruise near waterMoveSpeed=1.6 over water (peak=$peakWaterSpeed)")
            assertTrue(steadyLandSpeed < 0.95, "Hovercraft must slow to landMoveSpeed=0.85 after landing (steady=$steadyLandSpeed)")
            assertNull(finalUnit.moveTarget, "Hovercraft must settle at east land target")
            assertTrue(finalUnit.position.x > 580.0, "Hovercraft must reach landing destination (pos=${finalUnit.position})")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun large_custom_rectangular_dreadnought_routes_wide_channel_and_sinks_in_water_without_unit_id_whitelist() {
        // 40x32 water map with vertical rock island wall at x in 18..20:
        // - North narrow gap at y in 4..5 (2 tiles = 40px, TOO NARROW for width=44px ship!)
        // - South wide channel at y in 20..25 (6 tiles = 120px, SAFE for 72x44 dreadnought!)
        val map = buildMap(40, 32) { x, y ->
            val wall = x in 18..20 && y !in 4..5 && y !in 20..25
            if (wall) 1 else 2 // 1=land (blocks WATER), 2=water
        }
        val dreadnought = UnitDefinition(
            id = "customDreadnought",
            className = "CustomUnitMetadata",
            radius = 34.0,
            displayRadius = 36.0,
            totalFrames = 1,
            imagePath = dummyImage,
            imageScale = 1.0,
            movement = MovementProfile(
                type = MovementType.WATER,
                moveSpeed = 0.75,
                moveAccelerationSpeed = 0.025,
                moveDecelerationSpeed = 0.05,
                maxTurnSpeed = 0.9,
                turnAcceleration = 0.05,
                slidingMode = false,
                ignoringBody = false,
                reverseSpeedPercentage = 0.6,
            ),
            retainedUnconsumed = emptySet(),
            durability = DurabilityProfile(maxHp = 4500.0, deadImagePath = null),
            movementCollision = MovementCollisionShape.Rectangle(length = 72.0, width = 44.0),
        )
        val base = baseScenario()
        val scenario = base.copy(
            scenarioId = "p6b-custom-dreadnought",
            map = map,
            unitDefinitions = mapOf(dreadnought.id to dreadnought),
            units = listOf(UnitSpawn("dn1", "p1", dreadnought.id, Position(200.0, 120.0), 90.0)),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("m-wide", "p1", "dn1", Position(620.0, 120.0), ctx.world)))
            var usedWideChannel = false
            for (tick in 0L until 2600L) {
                val obs = ctx.advance(Step(tick, 1.0, "sim-tick"))
                val u = obs.units.single()
                if (u.position.x in 340.0..440.0) {
                    assertTrue(u.position.y > 360.0, "Large 72x44 dreadnought must not pass through 2-tile narrow gap (pos=${u.position})")
                    usedWideChannel = true
                }
                if (u.moveTarget == null && tick > 200L) break
            }
            val arrived = ctx.observe().units.single()
            assertTrue(usedWideChannel, "Large rectangular dreadnought must detour through wide south channel")
            assertNull(arrived.moveTarget, "Dreadnought must arrive and stop cleanly")
            assertTrue(
                hypot(arrived.position.x - 620.0, arrived.position.y - 120.0) < 35.0,
                "Dreadnought must reach east destination (pos=${arrived.position})",
            )

            // Also verify general parameterized water corpse sinking (zero unit-ID whitelist!)
            val stepsField = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
            val liveState = (stepsField.get(ctx) as CandidateSteps).current
            val liveUnit = liveState.units.getValue("dn1")
            liveUnit.health = 0.0
            liveState.spatialIndex.remove(liveUnit.unitId)
            for (tick in 2600L until 2800L) {
                ctx.advance(Step(tick, 1.0, "sim-tick"))
            }
            val sunk = ctx.observe().units.single()
            assertFalse(sunk.alive)
            assertTrue(sunk.height <= -9.5, "Custom WATER ship corpse must sink without heavyBattleship ID whitelist (h=${sunk.height})")
            assertEquals(true, sunk.waterPose?.settled, "Sunk custom WATER ship must settle at bottom")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun movement_profile_validation_rejects_float_or_square_overflow_and_conflicting_vertical_controllers_and_handles_compositions() {
        val base = baseScenario()
        val map = buildMap(20, 16) { x, _ -> if (x < 10) 2 else 1 } // west water, east land
        fun makeDef(id: String, movement: MovementProfile): UnitDefinition = UnitDefinition(
            id = id,
            className = "CustomUnitMetadata",
            radius = 18.0,
            displayRadius = 18.0,
            totalFrames = 1,
            imagePath = dummyImage,
            imageScale = 1.0,
            movement = movement,
            retainedUnconsumed = emptySet(),
            durability = DurabilityProfile(maxHp = 600.0, deadImagePath = null),
        )
        fun initError(def: UnitDefinition, pos: Position = Position(80.0, 120.0)): String {
            val res = SimulationContext.initialize(base.copy(
                scenarioId = "p6b-invalid-${def.id}",
                map = map,
                unitDefinitions = mapOf(def.id to def),
                units = listOf(UnitSpawn("u1", "p1", def.id, pos, 90.0)),
            ))
            if (res is InitResult.Accepted) res.context.close()
            return assertIs<InitResult.Rejected>(res, "Expected ${def.id} to be rejected").error.code
        }
        val baseWater = MovementProfile(MovementType.WATER, 1.0, 0.05, 0.08, 1.5, 0.08, false, false)

        // 1. Float overflow & underflow rejected for SubmersibleMotionProfile and CustomVerticalPoseProfile
        assertEquals("unsupported-submarine", initError(makeDef("subFloatOv", baseWater.copy(
            submersible = SubmersibleMotionProfile(fastVerticalRate = 1e308),
        ))))
        assertEquals("unsupported-submarine", initError(makeDef("subFloatUnder", baseWater.copy(
            submersible = SubmersibleMotionProfile(verticalAcceleration = 1e-50),
        ))))
        assertEquals("unsupported-unit", initError(makeDef("poseFloatOv", baseWater.copy(
            verticalPose = CustomVerticalPoseProfile(targetHeight = 1e308),
        ))))
        // 2. Square overflow rejected for shortReverseDistance
        assertEquals("unsupported-unit", initError(makeDef("shortRevSqOv", baseWater.copy(
            shortReverseDistance = 1e200,
        ))))
        // 3. Conflicting vertical pose controllers rejected
        assertEquals("unsupported-submarine", initError(makeDef("subPlusHover", baseWater.copy(
            type = MovementType.HOVER,
            hover = HoverProfile(),
            submersible = SubmersibleMotionProfile(),
        ))))
        assertEquals("unsupported-submarine", initError(makeDef("subPlusPose", baseWater.copy(
            verticalPose = CustomVerticalPoseProfile(targetHeight = 1.0),
            submersible = SubmersibleMotionProfile(),
        ))))
        assertEquals("unsupported-submarine", initError(makeDef("subOnNone", baseWater.copy(
            type = MovementType.NONE, moveSpeed = 0.0, moveAccelerationSpeed = 0.0,
            moveDecelerationSpeed = 0.0, maxTurnSpeed = 0.0, turnAcceleration = 0.0,
            submersible = SubmersibleMotionProfile(),
        ))))
        assertEquals("unsupported-unit", initError(makeDef("posePlusHover", baseWater.copy(
            type = MovementType.HOVER,
            hover = HoverProfile(),
            verticalPose = CustomVerticalPoseProfile(targetHeight = 1.0),
        ))))

        // 4. Combined waterLandSpeed + submersible: uses dual speeds on surface AND switches to WATER + submergedSpeed when submerged
        val hybridDef = makeDef("hybridHoverSub", MovementProfile(
            type = MovementType.HOVER,
            moveSpeed = 1.0,
            moveAccelerationSpeed = 0.05,
            moveDecelerationSpeed = 0.08,
            maxTurnSpeed = 1.5,
            turnAcceleration = 0.08,
            slidingMode = false,
            ignoringBody = false,
            waterLandSpeed = WaterLandSpeedProfile(waterMoveSpeed = 1.7, landMoveSpeed = 0.9, waterTurnSpeed = 2.2, landTurnSpeed = 1.1),
            submersible = SubmersibleMotionProfile(submergedSpeed = 0.5, submergedTurnSpeed = 0.8, divingChangesRoute = true, diveRequiresOverWater = true),
        ))
        val hybridUnit = MutableUnit("hyb", "p1", hybridDef.id, hybridDef, Position(80.0, 120.0), 90.0, height = 1.0)
        hybridUnit.submarineSurfaceIntent = true
        hybridUnit.overLiquid = true
        assertEquals(MovementType.HOVER, UnitLocomotion.movement(hybridUnit).type)
        assertEquals(1.7, UnitLocomotion.movement(hybridUnit).moveSpeed)
        hybridUnit.overLiquid = false
        assertEquals(0.9, UnitLocomotion.movement(hybridUnit).moveSpeed)
        hybridUnit.submarineSurfaceIntent = false
        hybridUnit.height = -4.0
        assertEquals(MovementType.WATER, UnitLocomotion.movement(hybridUnit).type)
        assertEquals(0.5, UnitLocomotion.movement(hybridUnit).moveSpeed)
        assertEquals(0.8, UnitLocomotion.movement(hybridUnit).maxTurnSpeed)

        // 5. WaterBody respects explicit sinksInWaterOnDeath=false, prevents land burial for HOVER+submersible,
        //    and sinks non-WATER OVER_CLIFF_WATER unit with explicit sinksInWaterOnDeath=true in water.
        val noSinkSub = makeDef("noSinkSub", baseWater.copy(submersible = SubmersibleMotionProfile(), sinksInWaterOnDeath = false))
        val cliffWaterSink = makeDef("cliffSink", baseWater.copy(type = MovementType.OVER_CLIFF_WATER, sinksInWaterOnDeath = true))
        val corpseCtx = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            scenarioId = "p6b-water-corpse-rules",
            map = map,
            unitDefinitions = mapOf(noSinkSub.id to noSinkSub, hybridDef.id to hybridDef, cliffWaterSink.id to cliffWaterSink),
            units = listOf(
                UnitSpawn("ns1", "p1", noSinkSub.id, Position(80.0, 120.0), 90.0, height = 1.0),
                UnitSpawn("hybLand", "p1", hybridDef.id, Position(300.0, 120.0), 90.0, height = 1.0),
                UnitSpawn("cwWater", "p1", cliffWaterSink.id, Position(120.0, 180.0), 90.0, height = 0.0),
            ),
        ))).context
        try {
            val stepsField = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
            val state = (stepsField.get(corpseCtx) as CandidateSteps).current
            for (id in listOf("ns1", "hybLand", "cwWater")) {
                val u = state.units.getValue(id)
                u.health = 0.0
                state.spatialIndex.remove(u.unitId)
            }
            for (tick in 0L until 180L) corpseCtx.advance(Step(tick, 1.0, "sim-tick"))
            val obs = corpseCtx.observe()
            val ns = obs.units.single { it.unitId == "ns1" }
            val hyb = obs.units.single { it.unitId == "hybLand" }
            val cw = obs.units.single { it.unitId == "cwWater" }
            assertEquals(1.0, ns.height, "sinksInWaterOnDeath=false must prevent submarine corpse sinking")
            assertEquals(0.0, hyb.height, "HOVER+submersible dying on land must settle at ground level 0.0, not sink underground")
            assertTrue(cw.height <= -9.5, "OVER_CLIFF_WATER with explicit sinksInWaterOnDeath=true must sink in water (h=${cw.height})")
        } finally {
            corpseCtx.close()
        }
    }
}
