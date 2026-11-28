package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.*

/**
 * Generates and validates same-origin 2-second red-highlight contact sheets & peak zooms for ENG-P017-008:
 * 1. Continuous no-rally 25-tank factory fan-out;
 * 2. Continuous with-rally 20-tank smooth handoff (no braking trough at y+90);
 * 3. Collinear multi-body pushing buckling an idle column into a 2D sheet under ADR-009.
 *
 * Writes into a cleaned temp directory during ordinary test runs, or into
 * `-Devidence.dir=<path>` when capturing review artifacts.
 */
class FactoryFanOutVisualEvidenceTest {

    private fun fixturesDir(): Path {
        System.getProperty("public.fixtures.dir")?.let { return Path.of(it) }
        val repoRoot = Path.of(checkNotNull(System.getProperty("repo.root")))
        return repoRoot.resolve("SimulationTest/public/inputs")
    }

    private fun hexNonce(i: Int): String = i.toString(16).padStart(32, '0')

    private val coordModel = CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64")
    private val timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta")
    private val moveContract = MovementContract(16.0, 4.0)

    private fun openMap(w: Int, h: Int, blocked: BooleanArray = BooleanArray(w * h)): MapDefinition {
        return MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
    }

    private fun buildNoRallyScenario(factoryPos: Position): Scenario {
        val prodRoot = fixturesDir().resolve("production-combat/production/units")
        val rawTank = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadCombatUnit(prodRoot.resolve("tank.ini")),
        ).value
        val rawFactory = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadFactory(prodRoot.resolve("landFactory.properties")),
        ).value
        val tank = UnitDefinition(
            id = "c_tank",
            className = rawTank.className,
            radius = 6.0,
            displayRadius = 6.0,
            totalFrames = rawTank.totalFrames,
            imagePath = rawTank.imagePath,
            imageScale = rawTank.imageScale,
            movement = rawTank.movement.copy(moveSpeed = 1.15, moveAccelerationSpeed = 0.08, moveDecelerationSpeed = 0.08, maxTurnSpeed = 6.0),
            retainedUnconsumed = emptySet(),
            fogOfWarSightRange = 15,
            combat = rawTank.combat,
            durability = rawTank.durability,
            build = BuildProfile(100L, 0.025f),
            movementCollision = MovementCollisionShape.Circle(6.0),
            mass = 3000.0,
        )
        val factory = UnitDefinition(
            id = rawFactory.id,
            className = rawFactory.className,
            radius = rawFactory.radius,
            displayRadius = rawFactory.displayRadius,
            totalFrames = rawFactory.totalFrames,
            imagePath = rawFactory.imagePath,
            imageScale = rawFactory.imageScale,
            movement = rawFactory.movement,
            retainedUnconsumed = rawFactory.retainedUnconsumed,
            fogOfWarSightRange = rawFactory.fogOfWarSightRange,
            combat = rawFactory.combat,
            durability = rawFactory.durability,
            building = rawFactory.building,
            production = checkNotNull(rawFactory.production).let { p ->
                ProductionProfile(listOf("c_tank"), p.exitProfile, p.speedMultiplier, rallyEnabled = false)
            },
            build = rawFactory.build,
            construction = rawFactory.construction,
            directHitTargetKind = rawFactory.directHitTargetKind,
        )
        return Scenario(
            scenarioId = "eng-p017-008-no-rally-fan-25",
            map = openMap(50, 34),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            unitDefinitions = mapOf(tank.id to tank, factory.id to factory),
            units = listOf(UnitSpawn("f1", "p1", factory.id, factoryPos, 0.0)),
            coordinateModel = coordModel,
            timeModel = timeModel,
            movementContract = moveContract,
            combatContract = CombatContract(12345, -1, mapOf("f1" to 10L), 100L),
            economyContract = EconomyContract(mapOf("p1" to 200000L, "p2" to 0L)),
        )
    }

    private fun buildWithRallyScenario(tempPack: Path, factoryPos: Position): Scenario {
        val source = fixturesDir().resolve("author-production/json/package")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = tempPack.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
        val factoryJson = tempPack.resolve("units/spfactory.json")
        val text = Files.readString(factoryJson)
        val patched = text.replaceFirst("{", "{\n  \"rally\": true,")
        Files.writeString(factoryJson, patched)
        val rawDefs = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(tempPack)).value.units
        val rawTank = rawDefs.getValue("c_tank")
        val fastTank = UnitDefinition(
            id = rawTank.id,
            className = rawTank.className,
            radius = 6.0,
            displayRadius = 6.0,
            totalFrames = rawTank.totalFrames,
            imagePath = rawTank.imagePath,
            imageScale = rawTank.imageScale,
            movement = rawTank.movement.copy(moveSpeed = 1.15, moveAccelerationSpeed = 0.08, moveDecelerationSpeed = 0.08, maxTurnSpeed = 6.0),
            retainedUnconsumed = emptySet(),
            fogOfWarSightRange = rawTank.fogOfWarSightRange,
            combat = rawTank.combat,
            durability = rawTank.durability,
            build = BuildProfile(100L, 0.025f),
            movementCollision = MovementCollisionShape.Circle(6.0),
            mass = 3000.0,
        )
        return Scenario(
            scenarioId = "eng-p017-008-with-rally-handoff-20",
            map = openMap(50, 34),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            unitDefinitions = rawDefs + (fastTank.id to fastTank),
            units = listOf(UnitSpawn("f1", "p1", "spfactory", factoryPos, 0.0)),
            coordinateModel = coordModel,
            timeModel = timeModel,
            movementContract = moveContract,
            combatContract = CombatContract(12345, -1, mapOf("f1" to 10L), 100L),
            economyContract = EconomyContract(mapOf("p1" to 200000L, "p2" to 0L)),
        )
    }

    private fun buildSheetPushScenario(tempPack: Path): Scenario {
        // Real gameplay scenario exercising UnitContact pairwise-normal + collinear lateral buckling
        // through the 100% public API: 6 strictly collinear idle tanks stand directly in front of a
        // rally-enabled factory doorway (x = 510.0, initial spanX = 0.0). Newly produced tanks exit
        // south into the collinear idle column (birth-exit push drives directly into UnitContact),
        // buckling the idle column laterally into a wide 2D sheet while all 10 rallied tanks stream
        // cleanly to the south rally point with zero hard overlap and zero 2-second red stalls.
        val factoryPos = Position(510.0, 170.0)
        val base = buildWithRallyScenario(tempPack, factoryPos)
        val rawTank = base.unitDefinitions.getValue("c_tank")
        val pacedTank = UnitDefinition(
            id = rawTank.id, className = rawTank.className, radius = rawTank.radius, displayRadius = rawTank.displayRadius,
            totalFrames = rawTank.totalFrames, imagePath = rawTank.imagePath, imageScale = rawTank.imageScale,
            movement = rawTank.movement.copy(moveSpeed = 1.75, moveAccelerationSpeed = 0.25, moveDecelerationSpeed = 0.25, maxTurnSpeed = 8.0),
            retainedUnconsumed = rawTank.retainedUnconsumed,
            fogOfWarSightRange = rawTank.fogOfWarSightRange, combat = rawTank.combat, durability = rawTank.durability,
            build = BuildProfile(100L, 0.015f), movementCollision = rawTank.movementCollision, mass = rawTank.mass,
        )
        // Place idle-0 at y=219 so exiting tanks contact it at y=207 (clear of factory south edge y=200)
        // while still inside their birth-exit drive window, directly triggering UnitContact lateral buckling!
        val idleSpawns = (0 until 6).map { idx ->
            UnitSpawn("idle-$idx", "p1", "c_tank", Position(510.0, 219.0 + idx * 12.2), 180.0)
        }
        val ids = mapOf("f1" to 10L) + idleSpawns.mapIndexed { i, s -> s.unitId to (11L + i) }.toMap()
        return base.copy(
            scenarioId = "eng-p017-008-sheet-push-collinear",
            unitDefinitions = base.unitDefinitions + (pacedTank.id to pacedTank),
            units = listOf(UnitSpawn("f1", "p1", "spfactory", factoryPos, 0.0)) + idleSpawns,
            combatContract = CombatContract(12345, -1, ids, 100L),
        )
    }

    @Test
    fun renders_eng_p017_008_factory_fan_rally_and_sheet_push_contact_sheets() {
        val explicitDir = System.getProperty("evidence.dir")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val outDir = explicitDir ?: Files.createTempDirectory("eng-p017-008-visual-")
        val tempPackRoot = Files.createTempDirectory("eng-p017-008-pack-")
        try {
            Files.createDirectories(outDir)

            // 1. No-rally 25-tank fan-out
            run {
                val factoryPos = Position(510.0, 190.0)
                val scene = buildNoRallyScenario(factoryPos)
                val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                val obs = mutableListOf<Observation>()
                try {
                    for (i in 1..25) {
                        assertIs<ActionResult.Accepted>(
                            ctx.submit(ProduceAction("prod-$i", "p1", "f1", "c_tank", hexNonce(i), ctx.world)),
                        )
                    }
                    var step = 0L
                    obs += ctx.observe()
                    while (step < 1800L) {
                        val frame = ctx.advance(Step(step++, 1.0, "sim-tick"))
                        obs += frame
                        val tanks = frame.units.filter { it.typeId == "c_tank" }
                        if (tanks.size == 25 && tanks.all { it.moveTarget == null }) break
                    }
                } finally {
                    ctx.close()
                }
                val seq = DiagnosticSceneProjector.projectSequence(obs, scene.unitDefinitions)
                val maxStalled = seq.frames.maxOfOrNull { it.stalledCount } ?: 0
                assertEquals(0, maxStalled, "No-rally 25-tank fan must have zero 2s red-highlighted stalls")
                val art = DiagnosticContactSheetRenderer.writeArtifacts(
                    sequence = seq,
                    map = scene.map,
                    title = "ENG-P017-008: No-Rally 25-Tank Continuous Fan-Out",
                    contactSheetPath = outDir.resolve("no-rally-fan-25-contact-sheet.png"),
                    peakZoomPath = outDir.resolve("no-rally-fan-25-peak.png"),
                )
                assertTrue(Files.size(art.contactSheetPath) > 4096L)
            }

            // 2. With-rally 20-tank smooth handoff
            run {
                val factoryPos = Position(250.0, 190.0)
                val rallyPos = Position(760.0, 460.0)
                val scene = buildWithRallyScenario(tempPackRoot.resolve("rally"), factoryPos)
                val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                val obs = mutableListOf<Observation>()
                var minHandoffSpeed = Double.POSITIVE_INFINITY
                try {
                    assertIs<ActionResult.Accepted>(
                        ctx.submit(UnitAction("set-rally", "p1", "f1", "setRally", ctx.world, target = rallyPos)),
                    )
                    for (i in 1..20) {
                        assertIs<ActionResult.Accepted>(
                            ctx.submit(ProduceAction("prod-$i", "p1", "f1", "c_tank", hexNonce(100 + i), ctx.world)),
                        )
                    }
                    var step = 0L
                    obs += ctx.observe()
                    while (step < 2400L) {
                        val frame = ctx.advance(Step(step++, 1.0, "sim-tick"))
                        obs += frame
                        val tanks = frame.units.filter { it.typeId == "c_tank" }
                        for (t in tanks) {
                            if (t.position.y in (factoryPos.y + 55.0)..(factoryPos.y + 115.0) &&
                                hypot(t.position.x - rallyPos.x, t.position.y - rallyPos.y) > 120.0
                            ) {
                                minHandoffSpeed = min(minHandoffSpeed, t.speed)
                            }
                        }
                        if (tanks.size == 20 && tanks.all { it.moveTarget == null }) break
                    }
                } finally {
                    ctx.close()
                }
                assertTrue(minHandoffSpeed >= 0.35, "Rally handoff speed must never brake near zero: $minHandoffSpeed")
                val seq = DiagnosticSceneProjector.projectSequence(obs, scene.unitDefinitions)
                val art = DiagnosticContactSheetRenderer.writeArtifacts(
                    sequence = seq,
                    map = scene.map,
                    title = "ENG-P017-008: With-Rally 20-Tank Smooth Handoff (minHandoffSpd=%.2f)".format(minHandoffSpeed),
                    contactSheetPath = outDir.resolve("with-rally-handoff-20-contact-sheet.png"),
                    peakZoomPath = outDir.resolve("with-rally-handoff-20-peak.png"),
                )
                assertTrue(Files.size(art.contactSheetPath) > 4096L)
            }

            // 3. Collinear 2D sheet buckling push: 10 factory-exiting tanks buckle a strictly collinear
            // 6-tank idle doorway column (initial spanX = 0.0) into a 2D sheet under ADR-009.
            run {
                val scene = buildSheetPushScenario(tempPackRoot.resolve("sheet-push"))
                val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                val obs = mutableListOf<Observation>()
                val idleIds = (0 until 6).map { "idle-$it" }.toSet()
                val rallyPos = Position(510.0, 480.0)
                val producedTotal = 10
                try {
                    assertIs<ActionResult.Accepted>(
                        ctx.submit(UnitAction("set-rally-push", "p1", "f1", "setRally", ctx.world, target = rallyPos)),
                    )
                    for (i in 1..producedTotal) {
                        assertIs<ActionResult.Accepted>(
                            ctx.submit(ProduceAction("push-prod-$i", "p1", "f1", "c_tank", hexNonce(500 + i), ctx.world)),
                        )
                    }
                    obs += ctx.observe()
                    for (step in 0L until 1400L) {
                        val frame = ctx.advance(Step(step, 1.0, "sim-tick"))
                        obs += frame
                        // Verify zero hard overlap on every tick among tanks clear of the factory birth pad (ADR-009)
                        val tanks = frame.units.filter { it.typeId == "c_tank" && it.position.y > 202.0 }
                        for (i in tanks.indices) for (j in i + 1 until tanks.size) {
                            val d = hypot(tanks[i].position.x - tanks[j].position.x, tanks[i].position.y - tanks[j].position.y)
                            assertTrue(d >= 12.0 - 1e-4, "Zero hard overlap violated at step $step: $d")
                        }
                        val produced = frame.units.filter { it.typeId == "c_tank" && it.unitId !in idleIds }
                        if (produced.size == producedTotal && produced.all { it.moveTarget == null }) break
                    }
                } finally {
                    ctx.close()
                }
                val finalUnits = obs.last().units
                val idleEnd = finalUnits.filter { it.unitId in idleIds }
                val producedEnd = finalUnits.filter { it.typeId == "c_tank" && it.unitId !in idleIds }
                val spanX = idleEnd.maxOf { it.position.x } - idleEnd.minOf { it.position.x }
                val leftMoved = idleEnd.count { it.position.x < 506.0 }
                val rightMoved = idleEnd.count { it.position.x > 514.0 }
                val pushArrived = producedEnd.count { it.moveTarget == null }
                val seq = DiagnosticSceneProjector.projectSequence(obs, scene.unitDefinitions)
                val maxStalled = seq.frames.maxOfOrNull { it.stalledCount } ?: 0
                val art = DiagnosticContactSheetRenderer.writeArtifacts(
                    sequence = seq,
                    map = scene.map,
                    title = "ENG-P017-008: Collinear Doorway 2D Sheet Buckling Push (idleSpanX=0.0 -> %.1f)".format(spanX),
                    contactSheetPath = outDir.resolve("sheet-push-collinear-contact-sheet.png"),
                    peakZoomPath = outDir.resolve("sheet-push-collinear-peak.png"),
                )
                assertTrue(Files.size(art.contactSheetPath) > 4096L)
                assertEquals(0, maxStalled, "Collinear doorway push must not cause 2s red stalls: maxStalled=$maxStalled")
                assertEquals(producedTotal, pushArrived, "All produced pushers must pass & arrive cleanly: $pushArrived/$producedTotal")
                assertTrue(
                    spanX >= 24.0 && maxOf(leftMoved, rightMoved) >= 3,
                    "Collinear idle column must buckle laterally into a 2D sheet: spanX=$spanX left=$leftMoved right=$rightMoved " +
                        "idle=${idleEnd.map { "(%.1f,%.1f)".format(it.position.x, it.position.y) }}",
                )
            }
        } finally {
            fun deleteTree(dir: Path) {
                runCatching {
                    if (Files.exists(dir)) {
                        Files.walk(dir).use { stream ->
                            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                        }
                    }
                }
            }
            deleteTree(tempPackRoot)
            if (explicitDir == null) deleteTree(outDir)
        }
    }
}
