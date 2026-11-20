package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.math.hypot
import kotlin.test.*

class DiagnosticSceneProjectorTest {
    @Test fun projects_circle_and_rectangle_hulls_buffers_velocity_and_guidance_vectors() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val circleUnit = UnitDefinition(
            id = "circleTank", className = tank.className, radius = 10.0, displayRadius = 10.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(moveSpeed = 1.2), retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(10.0), mass = 1000.0
        )
        val rectShip = UnitDefinition(
            id = "rectShip", className = tank.className, radius = 20.0, displayRadius = 20.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(type = MovementType.WATER, moveSpeed = 0.8), retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Rectangle(length = 102.0, width = 20.0), mass = 5000.0
        )
        val airDrone = UnitDefinition(
            id = "airDrone", className = tank.className, radius = 8.0, displayRadius = 8.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(type = MovementType.AIR, moveSpeed = 2.0,
                flight = FlightProfile(targetHeight = 20.0, heightDrift = 0.0, heightChangeRate = 1.0, startingHeightOffset = 20.0)),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(8.0)
        )
        val building = UnitDefinition(
            id = "factory", className = "AuthoredBuilding", radius = 20.0, displayRadius = 20.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(type = MovementType.NONE, moveSpeed = 0.0), retainedUnconsumed = emptySet(),
            building = BuildingProfile(footprint = BuildingFootprint(0, 0, 0, 0)),
            movementCollision = MovementCollisionShape.Rectangle(40.0, 40.0)
        )
        val definitions = mapOf(
            circleUnit.id to circleUnit,
            rectShip.id to rectShip,
            airDrone.id to airDrone,
            building.id to building,
        )
        val obs = Observation(
            sampleId = "s0", worldId = "w0", scenarioId = "sc0", stepIndex = 0L, simulationTime = 0.0,
            units = listOf(
                UnitObservation(
                    unitId = "c1", ownerPlayerId = "p1", typeId = "circleTank",
                    position = Position(100.0, 200.0), heading = 90.0, speed = 1.0, angularVelocity = 0.0,
                    moveTarget = Position(300.0, 200.0), pathPoints = listOf(Position(180.0, 200.0), Position(300.0, 200.0)),
                    movementState = MovementState.MOVING
                ),
                UnitObservation(
                    unitId = "r1", ownerPlayerId = "p1", typeId = "rectShip",
                    position = Position(500.0, 300.0), heading = 90.0, speed = 0.5, angularVelocity = 0.0,
                    moveTarget = Position(650.0, 300.0), pathPoints = listOf(Position(650.0, 300.0)),
                    movementState = MovementState.MOVING,
                    waterPose = WaterPose(reversing = true, sinkSpeed = 0f, settled = true)
                ),
                UnitObservation(
                    unitId = "a1", ownerPlayerId = "p1", typeId = "airDrone",
                    position = Position(250.0, 250.0), heading = 0.0, speed = 2.0, angularVelocity = 0.0,
                    moveTarget = Position(250.0, 100.0), pathPoints = emptyList(),
                    movementState = MovementState.MOVING, height = 20.0,
                    flightPose = FlightPose(velocity = Position(0.0, -2.0), height = 20.0, heightPhase = 0.0, rotorPhase = 0.0, rotorHeading = 0.0, rotorHeightOffset = 0.0)
                ),
                UnitObservation(
                    unitId = "b1", ownerPlayerId = "p1", typeId = "factory",
                    position = Position(50.0, 50.0), heading = 0.0, speed = 0.0, angularVelocity = 0.0,
                    moveTarget = null, pathPoints = emptyList(), movementState = MovementState.IDLE
                )
            ),
            events = emptyList(), actionResults = emptyList()
        )

        val scene = DiagnosticSceneProjector.projectFrame(obs, definitions)
        assertEquals(3, scene.units.size, "Buildings must be excluded from mobile diagnostic projection")

        val c1 = scene.units.single { it.unitId == "c1" }
        assertEquals(Position(100.0, 200.0), c1.center)
        assertTrue(c1.hullCorners.isEmpty())
        assertEquals(10.0, c1.boundingRadius, 1e-6)
        assertEquals(10.0 + UNIT_CONTACT_BUFFER_WIDTH, assertNotNull(c1.bufferRadius), 1e-6)
        assertEquals(Position(110.0, 200.0).x, c1.headingTip.x, 1e-6)
        assertEquals(Position(110.0, 200.0).y, c1.headingTip.y, 1e-6)
        assertEquals(1.0, c1.velocityVector.x, 1e-6)
        assertEquals(0.0, c1.velocityVector.y, 1e-6)
        assertEquals(1.0, assertNotNull(c1.guidanceDirection).x, 1e-6)
        assertEquals(0.0, assertNotNull(c1.guidanceDirection).y, 1e-6)
        assertFalse(c1.stalledHighlight)

        val r1 = scene.units.single { it.unitId == "r1" }
        assertEquals(4, r1.hullCorners.size)
        assertEquals(102.0, r1.hullCorners.maxOf { it.x } - r1.hullCorners.minOf { it.x }, 1e-6)
        assertEquals(20.0, r1.hullCorners.maxOf { it.y } - r1.hullCorners.minOf { it.y }, 1e-6)
        assertEquals(hypot(51.0, 10.0), r1.boundingRadius, 1e-6)
        assertEquals(102.0 + 2 * UNIT_CONTACT_BUFFER_WIDTH, r1.bufferCorners.maxOf { it.x } - r1.bufferCorners.minOf { it.x }, 1e-6)
        // Reversing ship moves opposite to heading 90 -> (-0.5, 0.0)
        assertEquals(-0.5, r1.velocityVector.x, 1e-6)
        assertEquals(0.0, r1.velocityVector.y, 1e-6)

        val a1 = scene.units.single { it.unitId == "a1" }
        assertNull(a1.bufferRadius, "Airborne unit above contact layer has no contact buffer")
        assertTrue(a1.bufferCorners.isEmpty())
        assertEquals(0.0, a1.velocityVector.x, 1e-6)
        assertEquals(-2.0, a1.velocityVector.y, 1e-6)
    }

    @Test fun highlights_unarrived_units_stalled_for_two_seconds_and_clears_when_moving_or_arrived() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val unitDef = UnitDefinition(
            id = "c_tank", className = tank.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(moveSpeed = 1.1), retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0
        )
        val defs = mapOf("c_tank" to unitDef)
        val goal = Position(500.0, 200.0)

        // Build 130 observations (0..129):
        // - "stalled": has moveTarget = goal, creeps only 0.01 per step (1.2 units in 120 steps < threshold) -> highlighted at step 120!
        // - "flowing": has moveTarget = goal, moves 0.8 per step (96 units in 120 steps) -> never highlighted!
        // - "arrived": reaches goal at step 50 (emits movement-arrived, moveTarget = null) -> never highlighted at step 120+!
        val observations = (0L..129L).map { step ->
            val arrivedDone = step >= 50L
            val events = buildList {
                if (step == 0L) {
                    add(Event("movement-started", "stalled"))
                    add(Event("movement-started", "flowing"))
                    add(Event("movement-started", "arrived"))
                }
                if (step == 50L) add(Event("movement-arrived", "arrived"))
            }
            Observation(
                sampleId = "s$step", worldId = "w0", scenarioId = "sc0",
                stepIndex = step, simulationTime = step / 60.0,
                units = listOf(
                    UnitObservation(
                        unitId = "stalled", ownerPlayerId = "p1", typeId = "c_tank",
                        position = Position(100.0 + step * 0.01, 200.0), heading = 90.0, speed = 0.01, angularVelocity = 0.0,
                        moveTarget = goal, pathPoints = listOf(goal), movementState = MovementState.MOVING
                    ),
                    UnitObservation(
                        unitId = "flowing", ownerPlayerId = "p1", typeId = "c_tank",
                        position = Position(100.0 + step * 0.8, 240.0), heading = 90.0, speed = 0.8, angularVelocity = 0.0,
                        moveTarget = goal, pathPoints = listOf(goal), movementState = MovementState.MOVING
                    ),
                    UnitObservation(
                        unitId = "arrived", ownerPlayerId = "p1", typeId = "c_tank",
                        position = if (arrivedDone) Position(140.0, 280.0) else Position(100.0 + step * 0.8, 280.0),
                        heading = 90.0, speed = if (arrivedDone) 0.0 else 0.8, angularVelocity = 0.0,
                        moveTarget = if (arrivedDone) null else Position(140.0, 280.0),
                        pathPoints = emptyList(),
                        movementState = if (arrivedDone) MovementState.IDLE else MovementState.MOVING
                    )
                ),
                events = events, actionResults = emptyList()
            )
        }

        val sequence = DiagnosticSceneProjector.projectSequence(observations, defs)
        assertEquals(130, sequence.frames.size)

        // Before 120 steps elapsed on active order, stalled is not yet highlighted
        assertFalse(sequence.frames[119].units.single { it.unitId == "stalled" }.stalledHighlight)

        // At step 120 (exact 2.0s window from step 0), stalled IS highlighted!
        val frame120 = sequence.frames[120]
        val stalled120 = frame120.units.single { it.unitId == "stalled" }
        assertTrue(stalled120.stalledHighlight, "Unit with < threshold net displacement over 120 steps must be highlighted red")
        assertEquals(1, frame120.stalledCount)
        assertEquals(1, frame120.arrivedCount)
        assertFalse(frame120.units.single { it.unitId == "flowing" }.stalledHighlight)
        assertFalse(frame120.units.single { it.unitId == "arrived" }.stalledHighlight)
    }

    @Test fun selects_two_second_interval_and_event_keyframes_and_counts_contacts() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val unitDef = UnitDefinition(
            id = "c_tank", className = tank.className, radius = 10.0, displayRadius = 10.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(moveSpeed = 1.0), retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(10.0), mass = 3000.0
        )
        val defs = mapOf("c_tank" to unitDef)
        val goal = Position(400.0, 200.0)

        // Two units touch hard hulls at step 35 (distance = 20.0 <= 10 + 10), stall around step 150, last arrives at step 195
        val observations = (0L..250L).map { step ->
            val u1Arrived = step >= 180L
            val u2Arrived = step >= 195L
            val dist = if (step in 35L..45L) 20.0 else 40.0
            val events = buildList {
                if (step == 5L) {
                    add(Event("movement-started", "u1"))
                    add(Event("movement-started", "u2"))
                }
                if (step == 180L) add(Event("movement-arrived", "u1"))
                if (step == 195L) add(Event("movement-arrived", "u2"))
            }
            val u1Pos = when {
                step < 5L -> Position(100.0, 200.0)
                step in 5L..160L -> Position(102.0, 200.0) // stalls for > 120 steps (5..160)
                else -> Position(400.0, 200.0)
            }
            val u2Pos = when {
                step < 5L -> Position(100.0, 240.0)
                step in 35L..45L -> Position(u1Pos.x, u1Pos.y + dist)
                step < 195L -> Position(100.0 + step * 1.5, 240.0)
                else -> Position(400.0, 240.0)
            }
            Observation(
                sampleId = "s$step", worldId = "w0", scenarioId = "sc0",
                stepIndex = step, simulationTime = step / 60.0,
                units = listOf(
                    UnitObservation("u1", "p1", "c_tank", u1Pos, 90.0, if (step < 5L || u1Arrived) 0.0 else 0.1, 0.0,
                        if (step < 5L || u1Arrived) null else goal, emptyList(), if (step < 5L || u1Arrived) MovementState.IDLE else MovementState.MOVING),
                    UnitObservation("u2", "p1", "c_tank", u2Pos, 90.0, if (step < 5L || u2Arrived) 0.0 else 1.5, 0.0,
                        if (step < 5L || u2Arrived) null else Position(400.0, 240.0), emptyList(), if (step < 5L || u2Arrived) MovementState.IDLE else MovementState.MOVING)
                ),
                events = events, actionResults = emptyList()
            )
        }

        val sequence = DiagnosticSceneProjector.projectSequence(observations, defs)
        assertEquals(1, sequence.frames[35].hardContactCount, "Exact touching hulls at step 35 must count as 1 hard contact pair")
        assertEquals(0, sequence.frames[10].hardContactCount)

        val keySteps = sequence.keyframes.map { it.frame.stepIndex }.toSet()
        // Fixed 120-step intervals: 0, 120, 240
        assertTrue(0L in keySteps)
        assertTrue(120L in keySteps)
        assertTrue(240L in keySteps)
        // Event frames: first move (5), first contact (35), peak congestion (in 125..160 where stalledCount=1), last arrival (195)
        assertTrue(5L in keySteps, "First autonomous movement step must be included in keyframes: $keySteps")
        assertTrue(35L in keySteps, "First hard contact step must be included in keyframes: $keySteps")
        assertTrue(195L in keySteps, "Last arrival step must be included in keyframes: $keySteps")
        assertNotNull(sequence.peakCongestionFocus, "Peak congestion focus region must be computed when stalls/contacts occur")
    }

    @Test fun internal_slot_reassignment_preserves_stall_window_while_new_player_order_resets_it_and_idle_units_never_highlight() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val unitDef = UnitDefinition(
            id = "c_tank", className = tank.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(moveSpeed = 1.1), retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0
        )
        val defs = mapOf("c_tank" to unitDef)

        // Build initial (:initial:) + steps 0..140:
        // - "slotSwap": keeps actionId="cmd-1", internal target changes at step 60 from (500,200) to (520,218) -> MUST still highlight at step 120!
        // - "reordered": gets new player command actionId="cmd-2" at step 80 -> resets window at step 80, NOT highlighted at step 120!
        // - "idle": never receives an order (moveTarget = null, tasks = empty) -> NEVER highlighted at step 120+!
        val initialObs = Observation(
            sampleId = "sc0:1:initial:0", worldId = "w0", scenarioId = "sc0", stepIndex = 0L, simulationTime = 0.0,
            units = listOf(
                UnitObservation("slotSwap", "p1", "c_tank", Position(100.0, 200.0), 90.0, 0.0, 0.0, Position(500.0, 200.0), emptyList(), MovementState.IDLE,
                    tasks = listOf(UnitTask(UnitTaskKind.MOVE, "cmd-1", Position(500.0, 200.0)))),
                UnitObservation("reordered", "p1", "c_tank", Position(100.0, 240.0), 90.0, 0.0, 0.0, Position(500.0, 240.0), emptyList(), MovementState.IDLE,
                    tasks = listOf(UnitTask(UnitTaskKind.MOVE, "cmd-1", Position(500.0, 240.0)))),
                UnitObservation("idle", "p1", "c_tank", Position(100.0, 280.0), 90.0, 0.0, 0.0, null, emptyList(), MovementState.IDLE),
            ),
            events = emptyList(), actionResults = emptyList()
        )

        val stepObservations = (0L..140L).map { step ->
            val swapGoal = if (step < 60L) Position(500.0, 200.0) else Position(520.0, 218.0)
            val reorderId = if (step < 80L) "cmd-1" else "cmd-2"
            val reorderGoal = if (step < 80L) Position(500.0, 240.0) else Position(100.0, 400.0)
            Observation(
                sampleId = "sc0:1:step$step:${step + 1}", worldId = "w0", scenarioId = "sc0",
                stepIndex = step, simulationTime = (step + 1).toDouble(),
                units = listOf(
                    UnitObservation("slotSwap", "p1", "c_tank", Position(100.0 + step * 0.01, 200.0), 90.0, 0.01, 0.0,
                        swapGoal, listOf(swapGoal), MovementState.MOVING,
                        tasks = listOf(UnitTask(UnitTaskKind.MOVE, "cmd-1", swapGoal))),
                    UnitObservation("reordered", "p1", "c_tank", Position(100.0 + step * 0.01, 240.0), 90.0, 0.01, 0.0,
                        reorderGoal, listOf(reorderGoal), MovementState.MOVING,
                        tasks = listOf(UnitTask(UnitTaskKind.MOVE, reorderId, reorderGoal))),
                    UnitObservation("idle", "p1", "c_tank", Position(100.0, 280.0), 90.0, 0.0, 0.0,
                        null, emptyList(), MovementState.IDLE),
                ),
                events = emptyList(), actionResults = emptyList()
            )
        }

        val sequence = DiagnosticSceneProjector.projectSequence(listOf(initialObs) + stepObservations, defs)
        // Deduplicates :initial: vs step0 in keyframes
        val zeroStepKeyframes = sequence.keyframes.filter { it.frame.stepIndex == 0L }
        assertEquals(1, zeroStepKeyframes.size, "Must not emit duplicate step=0 keyframes when :initial: and step0 coexist")
        assertFalse(DiagnosticSceneProjector.isInitialSample(zeroStepKeyframes.single().frame.sampleId))
        assertEquals(0.0, sequence.frames[0].elapsedSeconds, 1e-6)
        assertEquals(1.0 / 60.0, sequence.frames[1].elapsedSeconds, 1e-6)

        // Step 119 corresponds to frames[120] (since frames[0] is :initial:, frames[1] is step0, ..., frames[120] is step119: span from -1 to 119 = 120!)
        val atStep120 = sequence.frames.first { !DiagnosticSceneProjector.isInitialSample(it.sampleId) && it.stepIndex == 120L }
        assertTrue(atStep120.units.single { it.unitId == "slotSwap" }.stalledHighlight,
            "Same actionId with internal slot target reassignment must remain stalled-highlighted at 2s")
        assertFalse(atStep120.units.single { it.unitId == "reordered" }.stalledHighlight,
            "New player command actionId at step 80 must reset the 120-step stall window")
        assertFalse(atStep120.units.single { it.unitId == "idle" }.stalledHighlight,
            "Uncommanded stationary unit must never be highlighted as stalled")
    }

    @Test fun player_observation_tracker_clears_arrived_state_for_enemies_and_fog_reappearance() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val unitDef = UnitDefinition(
            id = "c_tank", className = tank.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tank.totalFrames, imagePath = tank.imagePath, imageScale = tank.imageScale,
            movement = tank.movement.copy(moveSpeed = 1.1), retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0
        )
        val defs = mapOf("c_tank" to unitDef)
        val tracker = DiagnosticSceneProjector.LiveTracker(defs)

        val pObs0 = PlayerObservation(
            "w:p1:step0:0:0", "w", "sc", "p1", 0L, 0.0, null, null,
            units = listOf(
                UnitObservation("own", "p1", "c_tank", Position(100.0, 100.0), 90.0, 0.0, 0.0, null, emptyList(), MovementState.IDLE),
                UnitObservation("enemy", "p2", "c_tank", Position(200.0, 100.0), 270.0, 0.0, 0.0, null, emptyList(), MovementState.IDLE),
            ),
            events = listOf(Event("movement-arrived", "own"), Event("movement-arrived", "enemy")),
            actionResults = emptyList()
        )
        val scene0 = tracker.updateAndProjectPlayer(pObs0)
        assertTrue(scene0.units.single { it.unitId == "own" }.arrived)
        assertFalse(scene0.units.single { it.unitId == "enemy" }.arrived, "Enemy without visible command intent must not retain sticky arrived flag")

        // Own unit disappears from visible set then reappears moving -> arrived cleared
        val pObs1 = PlayerObservation("w:p1:step1:1:1", "w", "sc", "p1", 1L, 1.0, null, null, units = emptyList(), events = emptyList(), actionResults = emptyList())
        tracker.ingestPlayer(pObs1)
        val pObs2 = PlayerObservation(
            "w:p1:step2:2:2", "w", "sc", "p1", 2L, 2.0, null, null,
            units = listOf(UnitObservation("own", "p1", "c_tank", Position(102.0, 100.0), 90.0, 0.5, 0.0, null, emptyList(), MovementState.MOVING)),
            events = emptyList(), actionResults = emptyList()
        )
        val scene2 = tracker.updateAndProjectPlayer(pObs2)
        assertFalse(scene2.units.single { it.unitId == "own" }.arrived)
    }
}
