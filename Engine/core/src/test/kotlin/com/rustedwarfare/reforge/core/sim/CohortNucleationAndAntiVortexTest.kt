package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.navigation.*
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Behavioral regression & anti-vortex contract tests for ENG-P017-009:
 * 1. Exact live-playtest step 36307 19x c_tank open-ground convergence to (5667.40, 6175.27);
 * 2. Symmetric 4-way inward converging surround (40 tanks) with zero full-orbit revolution;
 * 3. 50-tank open-ground group convergence (open-50 class) settling compactly & staying stationary >= 600 steps.
 */
class CohortNucleationAndAntiVortexTest {

    private fun publicFixturesDir(): Path {
        System.getProperty("public.fixtures.dir")?.let { return Path.of(it) }
        val repoRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { java.nio.file.Files.isDirectory(it.resolve("SimulationTest/public/inputs")) }
            ?: error("Could not locate SimulationTest/public/inputs")
        return repoRoot.resolve("SimulationTest/public/inputs")
    }

    private fun loadRealCTank(): UnitDefinition {
        val packDir = publicFixturesDir().resolve("phase-five-sandbox/ini/package")
        return assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(packDir)).value.units.getValue("c_tank")
    }

    private fun openMap(w: Int, h: Int): MapDefinition =
        MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, BooleanArray(w * h)))

    private data class ConvergenceMetrics(
        val total: Int,
        val settledCount: Int,
        val firstNucleationStep: Long,
        val allSettledStep: Long,
        val maxCumSweepRad: Double,
        val maxPostSettleDrift: Double,
        val minPairClearance: Double,
        val maxDistFromGoal: Double,
    )

    private fun runConvergenceScenario(
        scenarioId: String,
        mapW: Int,
        mapH: Int,
        tankDef: UnitDefinition,
        spawns: List<UnitSpawn>,
        goal: Position,
        maxSettleSteps: Long,
        holdSteps: Long = 600L,
    ): ConvergenceMetrics {
        val coordModel = CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64")
        val timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta")
        val scene = Scenario(
            scenarioId = scenarioId,
            map = openMap(mapW, mapH),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            unitDefinitions = mapOf(tankDef.id to tankDef),
            units = spawns,
            coordinateModel = coordModel,
            timeModel = timeModel,
            movementContract = MovementContract(16.0, 4.0),
            combatContract = CombatContract(
                12345,
                -1,
                spawns.mapIndexed { idx, s -> s.unitId to (idx + 1L) }.toMap(),
                (spawns.size + 10L),
            ),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 10000L)),
        )
        val init = SimulationContext.initialize(scene)
        val ctx = assertIs<InitResult.Accepted>(init, "Init failed: $init").context
        try {
            val ids = spawns.map { it.unitId }
            val req = checkNotNull(ctx.requestGroupMove("p1", ids, goal))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("m-$id", "p1", id, goal, ctx.world), req))
            }

            val prevAngle = HashMap<String, Double>(ids.size)
            val cumSweep = HashMap<String, Double>(ids.size)
            for (s in spawns) {
                prevAngle[s.unitId] = atan2(s.position.y - goal.y, s.position.x - goal.x)
                cumSweep[s.unitId] = 0.0
            }

            var firstNucleationStep = -1L
            var allSettledStep = -1L
            var minPairClearance = Double.POSITIVE_INFINITY
            val settledPose = HashMap<String, Position>(ids.size)
            var maxPostSettleDrift = 0.0
            var lastObs: Observation? = null
            val radius = tankDef.movementCollision.boundingRadius
            val totalSteps = maxSettleSteps + holdSteps

            for (step in 0L until totalSteps) {
                val obs = ctx.advance(Step(step, 1.0, "sim-tick"))
                lastObs = obs
                var settledNow = 0
                for (u in obs.units) {
                    val d = hypot(u.position.x - goal.x, u.position.y - goal.y)
                    val ang = atan2(u.position.y - goal.y, u.position.x - goal.x)
                    val prev = prevAngle.getValue(u.unitId)
                    var dAng = ang - prev
                    while (dAng > PI) dAng -= 2.0 * PI
                    while (dAng < -PI) dAng += 2.0 * PI
                    // Only accumulate angular sweep once unit is near the convergence basin
                    if (d > 4.0 && d < 220.0 && u.moveTarget != null) {
                        cumSweep[u.unitId] = cumSweep.getValue(u.unitId) + dAng
                    }
                    prevAngle[u.unitId] = ang
                    if (u.moveTarget == null && abs(u.speed) <= 1e-4) {
                        settledNow++
                    }
                }
                if (firstNucleationStep < 0L && settledNow > 0) {
                    firstNucleationStep = step
                }
                if (allSettledStep < 0L && settledNow == ids.size) {
                    allSettledStep = step
                    for (u in obs.units) settledPose[u.unitId] = u.position
                } else if (allSettledStep >= 0L) {
                    for (u in obs.units) {
                        val sp = settledPose[u.unitId] ?: continue
                        val drift = hypot(u.position.x - sp.x, u.position.y - sp.y)
                        if (drift > maxPostSettleDrift) maxPostSettleDrift = drift
                    }
                }
                if (step % 5L == 0L || allSettledStep == step) {
                    for (i in obs.units.indices) {
                        for (j in i + 1 until obs.units.size) {
                            val gap = hypot(
                                obs.units[i].position.x - obs.units[j].position.x,
                                obs.units[i].position.y - obs.units[j].position.y,
                            ) - 2.0 * radius
                            if (gap < minPairClearance) minPairClearance = gap
                        }
                    }
                }
                if (allSettledStep >= 0L && step >= allSettledStep + holdSteps) {
                    break
                }
            }
            val finalUnits = checkNotNull(lastObs).units
            val finalSettled = finalUnits.count { it.moveTarget == null && abs(it.speed) <= 1e-4 }
            val maxDist = finalUnits.maxOf { hypot(it.position.x - goal.x, it.position.y - goal.y) }
            val maxSweep = cumSweep.values.maxOf { abs(it) }
            return ConvergenceMetrics(
                total = ids.size,
                settledCount = finalSettled,
                firstNucleationStep = firstNucleationStep,
                allSettledStep = allSettledStep,
                maxCumSweepRad = maxSweep,
                maxPostSettleDrift = maxPostSettleDrift,
                minPairClearance = minPairClearance,
                maxDistFromGoal = maxDist,
            )
        } finally {
            ctx.close()
        }
    }

    @Test
    fun live_playtest_step_36307_19_c_tank_open_ground_converges_without_vortex() {
        val cTank = loadRealCTank()
        // Exact 19 c_tank coordinates & headings from live playtest run-20261126T141632673Z-bbbe2600 step 36307,
        // translated uniformly by (-5300.0, -5800.0) into an open 60x40 map (1200x800 world units).
        val ox = 5300.0
        val oy = 5800.0
        val goal = Position(5667.400520833336 - ox, 6175.274532624842 - oy)
        val rawUnits = listOf(
            Triple(5904.50862735067, 6034.6451534005755, 16.233477747844518),
            Triple(5944.421166408567, 5999.269052969083, 336.1360945662557),
            Triple(5930.188414331645, 6048.903823547332, 11.932966355529743),
            Triple(6133.565088453003, 6073.829315715916, 214.66963757212056),
            Triple(5931.000389370818, 6093.201888991381, 84.09698914782246),
            Triple(6120.591965442484, 6091.597210339475, 144.06169579896834),
            Triple(5924.349889735192, 6072.231174778542, 16.85130351864916),
            Triple(6130.897183968626, 6033.305779396412, 194.86870958633642),
            Triple(5925.51490311379, 6027.405956137852, 352.3640313958158),
            Triple(6130.696678273717, 6111.139338642152, 222.92111480337223),
            Triple(5907.144668056869, 6008.629014439846, 198.85739306713845),
            Triple(5958.280367505926, 6062.05941149407, 289.1021501306046),
            Triple(6120.1933944992825, 6130.470169714657, 127.9714483451744),
            Triple(5941.750366275263, 6116.906471999449, 274.60996227990483),
            Triple(5902.793864769823, 6090.917341600373, 329.82094548290934),
            Triple(6123.782942912637, 6054.123742173716, 158.959926020245),
            Triple(5885.732163501283, 6074.7002398216, 285.35883148964024),
            Triple(5913.175605477998, 6113.643881268031, 272.7106629146534),
            Triple(5909.395187044328, 6056.095597087369, 358.2729838210346),
        )
        val spawns = rawUnits.mapIndexed { idx, (x, y, h) ->
            UnitSpawn("t-${idx.toString().padStart(2, '0')}", "p1", cTank.id, Position(x - ox, y - oy), h)
        }
        val m = runConvergenceScenario(
            scenarioId = "eng-p017-009-step-36307-19-tanks",
            mapW = 60, mapH = 40,
            tankDef = cTank,
            spawns = spawns,
            goal = goal,
            maxSettleSteps = 1200L,
            holdSteps = 600L,
        )
        assertEquals(19, m.settledCount, "All 19 c_tank units must settle cleanly: $m")
        assertTrue(m.firstNucleationStep in 1L..450L, "First lead tank must nucleate on arrival wave: $m")
        assertTrue(m.allSettledStep in 1L..900L, "All 19 tanks must settle quickly without orbiting: $m")
        assertTrue(m.maxCumSweepRad < PI * 1.2, "Zero full-orbit vortex allowed (maxCumSweepRad=${m.maxCumSweepRad}): $m")
        assertTrue(m.maxPostSettleDrift <= 0.05, "Settled formation must remain stationary for >= 600 steps: $m")
        assertTrue(m.minPairClearance >= -0.05, "Zero hard overlap allowed: $m")
    }

    @Test
    fun live_playtest_step_36600_19_c_tank_active_vortex_basin_nucleates_immediately() {
        val cTank = loadRealCTank()
        // Exact 19 c_tank poses at step 36600 after entering the open-ground basin with tangential headings.
        val ox = 5300.0
        val oy = 5800.0
        val goal = Position(5667.400520833336 - ox, 6175.274532624842 - oy)
        val rawUnits = listOf(
            Triple(5661.127014056137, 6147.780812287705, 233.82859630594533),
            Triple(5696.998928730472, 6117.544978048945, 235.90141205482155),
            Triple(5696.799044942395, 6156.265792584975, 257.6791046671012),
            Triple(5893.870741255903, 6127.836901608448, 257.932228245268),
            Triple(5693.352859579093, 6178.220574740267, 227.37284003223772),
            Triple(5831.857238942934, 6144.763574744529, 260.92885001289403),
            Triple(5715.108393565684, 6185.977733330784, 223.49465995194396),
            Triple(5863.637777420971, 6126.664992443536, 258.27466718329197),
            Triple(5720.695718320497, 6121.797457673759, 233.73488589513386),
            Triple(5862.357145560254, 6153.686840981299, 258.94541198375964),
            Triple(5680.332688079024, 6136.406486705814, 235.89305550296558),
            Triple(5719.741646003008, 6149.750802567313, 213.52908867456176),
            Triple(5833.603138571144, 6169.853441618527, 260.4097467303816),
            Triple(5674.904172440585, 6191.873146897965, 250.31671218043266),
            Triple(5638.36775427664, 6174.134318736681, 259.7140920778478),
            Triple(5833.713650682138, 6120.922875788185, 258.83005928998716),
            Triple(5630.836751697562, 6208.192133503919, 159.16258183259788),
            Triple(5652.939373833924, 6193.117181531248, 263.1038174721533),
            Triple(5661.699067573027, 6172.569778515279, 245.3306402712767),
        )
        val spawns = rawUnits.mapIndexed { idx, (x, y, h) ->
            UnitSpawn("v-${idx.toString().padStart(2, '0')}", "p1", cTank.id, Position(x - ox, y - oy), h)
        }
        val m = runConvergenceScenario(
            scenarioId = "eng-p017-009-step-36600-vortex-basin",
            mapW = 60, mapH = 40,
            tankDef = cTank,
            spawns = spawns,
            goal = goal,
            maxSettleSteps = 900L,
            holdSteps = 600L,
        )
        assertEquals(19, m.settledCount, "All 19 basin tanks must settle cleanly: $m")
        assertTrue(m.firstNucleationStep in 1L..80L, "Lead tank inside basin must nucleate almost immediately: $m")
        assertTrue(m.allSettledStep in 1L..650L, "All 19 basin tanks must settle rapidly: $m")
        assertTrue(m.maxCumSweepRad < PI * 1.2, "Zero full-orbit vortex allowed (maxCumSweepRad=${m.maxCumSweepRad}): $m")
        assertTrue(m.maxPostSettleDrift <= 0.05, "Settled formation must remain stationary for >= 600 steps: $m")
        assertTrue(m.minPairClearance >= -0.05, "Zero hard overlap allowed: $m")
    }

    @Test
    fun two_way_head_on_symmetric_24_tanks_converge_without_right_shift_vortex() {
        val cTank = loadRealCTank()
        val goal = Position(500.0, 500.0)
        val spawns = mutableListOf<UnitSpawn>()
        val spacing = 26.0
        var id = 0
        // Symmetric West 12 (3x4) & East 12 (3x4) rushing head-on into the same center
        for (r in 0 until 4) for (c in 0 until 3) {
            spawns += UnitSpawn("w-${id++}", "p1", cTank.id, Position(goal.x - 160.0 - c * spacing, goal.y + (r - 1.5) * spacing), 90.0)
            spawns += UnitSpawn("e-${id++}", "p1", cTank.id, Position(goal.x + 160.0 + c * spacing, goal.y + (r - 1.5) * spacing), 270.0)
        }
        val m = runConvergenceScenario(
            scenarioId = "eng-p017-009-2way-headon-24",
            mapW = 50, mapH = 50,
            tankDef = cTank,
            spawns = spawns,
            goal = goal,
            maxSettleSteps = 900L,
            holdSteps = 600L,
        )
        assertEquals(24, m.settledCount, "All 24 head-on converging tanks must settle: $m")
        assertTrue(m.firstNucleationStep in 1L..250L, "First nucleation must occur on arrival wave: $m")
        assertTrue(m.allSettledStep in 1L..650L, "All 24 tanks must settle promptly without vortex: $m")
        assertTrue(m.maxCumSweepRad < PI * 1.0, "Zero orbiting sweep in head-on convergence (maxCumSweepRad=${m.maxCumSweepRad}): $m")
        assertTrue(m.maxPostSettleDrift <= 0.05, "Settled formation must remain stationary for >= 600 steps: $m")
        assertTrue(m.minPairClearance >= -0.05, "Zero hard overlap allowed: $m")
    }

    @Test
    fun four_way_symmetric_inward_surround_40_tanks_nucleates_and_settles_with_zero_vortex() {
        val cTank = loadRealCTank()
        val goal = Position(500.0, 500.0)
        val spawns = mutableListOf<UnitSpawn>()
        val spacing = 26.0
        var id = 0
        // North 10 (2x5)
        for (r in 0 until 2) for (c in 0 until 5) {
            spawns += UnitSpawn("n-${id++}", "p1", cTank.id, Position(goal.x + (c - 2) * spacing, goal.y - 220.0 - r * spacing), 180.0)
        }
        // South 10 (2x5)
        for (r in 0 until 2) for (c in 0 until 5) {
            spawns += UnitSpawn("s-${id++}", "p1", cTank.id, Position(goal.x + (c - 2) * spacing, goal.y + 220.0 + r * spacing), 0.0)
        }
        // West 10 (5x2)
        for (r in 0 until 5) for (c in 0 until 2) {
            spawns += UnitSpawn("w-${id++}", "p1", cTank.id, Position(goal.x - 220.0 - c * spacing, goal.y + (r - 2) * spacing), 90.0)
        }
        // East 10 (5x2)
        for (r in 0 until 5) for (c in 0 until 2) {
            spawns += UnitSpawn("e-${id++}", "p1", cTank.id, Position(goal.x + 220.0 + c * spacing, goal.y + (r - 2) * spacing), 270.0)
        }
        val m = runConvergenceScenario(
            scenarioId = "eng-p017-009-4way-surround-40",
            mapW = 50, mapH = 50,
            tankDef = cTank,
            spawns = spawns,
            goal = goal,
            maxSettleSteps = 1200L,
            holdSteps = 600L,
        )
        assertEquals(40, m.settledCount, "All 40 4-way converging tanks must settle: $m")
        assertTrue(m.firstNucleationStep in 1L..350L, "First nucleation must happen on initial wave: $m")
        assertTrue(m.allSettledStep in 1L..950L, "All 40 tanks must settle promptly: $m")
        assertTrue(m.maxCumSweepRad < PI * 1.5, "Zero full-orbit vortex in 4-way convergence (maxCumSweepRad=${m.maxCumSweepRad}): $m")
        assertTrue(m.maxPostSettleDrift <= 0.05, "Settled formation must hold stationary >= 600 steps: $m")
        assertTrue(m.minPairClearance >= -0.05, "Zero hard overlap allowed: $m")
    }

    @Test
    fun open_50_ring_and_grid_convergence_settles_compactly_with_zero_vortex() {
        val cTank = loadRealCTank()
        val goal = Position(500.0, 500.0)
        val spawns = mutableListOf<UnitSpawn>()
        // 50 tanks distributed evenly on two concentric rings around the open goal (maximum angular symmetry stress)
        for (i in 0 until 50) {
            val ring = if (i < 20) 190.0 else 250.0
            val countInRing = if (i < 20) 20 else 30
            val idxInRing = if (i < 20) i else i - 20
            val angle = (2.0 * PI * idxInRing) / countInRing
            val px = goal.x + cos(angle) * ring
            val py = goal.y + sin(angle) * ring
            val heading = ScreenPlane.desired(Position(px, py), goal)
            spawns += UnitSpawn("r-${i.toString().padStart(2, '0')}", "p1", cTank.id, Position(px, py), heading)
        }
        val m = runConvergenceScenario(
            scenarioId = "eng-p017-009-open-50-concentric",
            mapW = 50, mapH = 50,
            tankDef = cTank,
            spawns = spawns,
            goal = goal,
            maxSettleSteps = 1400L,
            holdSteps = 600L,
        )
        assertEquals(50, m.settledCount, "All 50 concentric tanks must settle: $m")
        assertTrue(m.firstNucleationStep in 1L..320L, "First concentric wave must nucleate immediately: $m")
        assertTrue(m.allSettledStep in 1L..1050L, "All 50 tanks must finish settling promptly: $m")
        assertTrue(m.maxCumSweepRad < PI * 1.5, "Zero full-orbit vortex on concentric open-50 (maxCumSweepRad=${m.maxCumSweepRad}): $m")
        assertTrue(m.maxPostSettleDrift <= 0.05, "Settled formation must hold stationary >= 600 steps: $m")
        assertTrue(m.minPairClearance >= -0.05, "Zero hard overlap allowed: $m")
    }

    @Test
    fun tight_tangential_ring_16_tanks_zero_seed_breaks_orbit_and_nucleates_rapidly() {
        val cTank = loadRealCTank()
        val goal = Position(500.0, 500.0)
        val spawns = mutableListOf<UnitSpawn>()
        // 16 tanks on two tight rings with initial tangential CCW headings (pure vortex seed stress!)
        for (i in 0 until 8) {
            val angle = (2.0 * PI * i) / 8.0
            val px = goal.x + cos(angle) * 48.0
            val py = goal.y + sin(angle) * 48.0
            val radialHeading = ScreenPlane.desired(Position(px, py), goal)
            spawns += UnitSpawn("in-$i", "p1", cTank.id, Position(px, py), ScreenPlane.normalize(radialHeading + 75.0))
        }
        for (i in 0 until 8) {
            val angle = (2.0 * PI * i + PI / 8.0) / 8.0
            val px = goal.x + cos(angle) * 82.0
            val py = goal.y + sin(angle) * 82.0
            val radialHeading = ScreenPlane.desired(Position(px, py), goal)
            spawns += UnitSpawn("out-$i", "p1", cTank.id, Position(px, py), ScreenPlane.normalize(radialHeading + 75.0))
        }
        val m = runConvergenceScenario(
            scenarioId = "eng-p017-009-tight-tangential-ring-16",
            mapW = 50, mapH = 50,
            tankDef = cTank,
            spawns = spawns,
            goal = goal,
            maxSettleSteps = 700L,
            holdSteps = 600L,
        )
        assertEquals(16, m.settledCount, "All 16 tanks in tangential ring must break orbit and settle: $m")
        assertTrue(m.firstNucleationStep in 1L..90L, "Lead tank must nucleate promptly without orbiting: $m")
        assertTrue(m.allSettledStep in 1L..450L, "All 16 tanks must settle rapidly: $m")
        assertTrue(m.maxCumSweepRad < PI * 1.0, "Tangential orbit must damp out in < half-turn (maxCumSweepRad=${m.maxCumSweepRad}): $m")
        assertTrue(m.maxPostSettleDrift <= 0.05, "Settled formation must hold stationary >= 600 steps: $m")
        assertTrue(m.minPairClearance >= -0.05, "Zero hard overlap allowed: $m")
    }
}
