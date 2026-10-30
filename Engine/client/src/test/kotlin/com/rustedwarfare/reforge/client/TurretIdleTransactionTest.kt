package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

/** Real later-shooter overflow after four independently reached idle states; no private state injection. */
class TurretIdleTransactionTest {
    @Test fun waiting_timer_retries_without_publishing_a_partial_idle_step() = retry(98, false)
    @Test fun endpoint_switch_and_random_wait_retry_without_advancing_twice() = retry(209, false)
    @Test fun a_real_public_turret_deflection_retries_and_preserves_its_future_endpoints() = retry(220, false)
    @Test fun real_aim_hold_retries_without_losing_one_tick_of_hold() = retry(1, true)

    private fun retry(warmSteps: Int, hold: Boolean) {
        for (retention in listOf(TerrainRetention.MEMORY, TerrainRetention.HARDCORE_FORGET)) {
            val scene = input(hold, retention)
            val worlds = List(2) {
                val initialized = SimulationContext.initialize(scene)
                assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
            }
            val records = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
            val sinks = records.map { rows -> ClientEvidence { kind, values -> rows += kind to values } }
            var comparedFrames = 0
            fun capture(index: Int) {
                val context = worlds[index]
                for (player in listOf("p1", "p2")) sinks[index].record("observation", mapOf(
                    "sample" to context.observePlayer(context.world, player).fields()))
            }
            fun equalFrames() {
                assertEquals(host(worlds[1].observe()), host(worlds[0].observe()))
                for (player in listOf("p1", "p2")) assertEquals(
                    player(worlds[1].observePlayer(worlds[1].world, player)),
                    player(worlds[0].observePlayer(worlds[0].world, player)))
                // Compare both complete player payloads as a stream, rather than retaining
                // thousands of duplicate 600-cell knowledge maps in the test worker heap.
                records.forEach { it.clear() }
                capture(0); capture(1)
                assertEquals(records[1], records[0])
                comparedFrames++
            }
            try {
                worlds.forEach { context ->
                    context.enablePathDiagnostics(context.world)
                    for (shooter in listOf("b", "c")) assertIs<ActionResult.Accepted>(context.submit(
                        AttackAction("prepare-$shooter", "p1", shooter, "z", context.world)))
                }
                equalFrames()
                for (step in 0 until warmSteps) {
                    worlds.forEach { it.advance(Step(step.toLong(), 1.0, "sim-tick")) }
                    equalFrames()
                }
                if (hold) assertTrue(worlds[0].observe().combat!!.nextObjectId == Long.MAX_VALUE - 1,
                    "The real tower's prior shot, not an injected timer, establishes aim hold")
                val beforeHeading = worlds[0].observe().units.single { it.unitId == "aa" }.turretHeading
                if (warmSteps == 220) assertTrue(beforeHeading in 90.1..110.0, "Real idle deflection precedes the failure")
                worlds.forEach { context ->
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("enter", "p2", "z", Position(100.0, 270.0), context.world)))
                    if (hold) assertIs<ActionResult.Accepted>(context.submit(
                        MoveAction("release-hold", "p2", "az", Position(500.0, 80.0), context.world)))
                    context.advance(Step(warmSteps.toLong(), 1.0, "sim-tick"))
                    if (hold) {
                        assertTrue(context.observe().units.single { it.unitId == "az" }.position.x > 245.0,
                            "The real hold target has publicly left the tower's strict range")
                        assertIs<ActionResult.Accepted>(context.submit(
                            StopAction("park-hold-target", "p2", "az", context.world)))
                    }
                    context.drainPathDiagnostics(context.world)
                    assertIs<ActionResult.Accepted>(context.submit(SelectAction("queued-select", "p1", "aa", context.world, warmSteps + 1L)))
                }
                equalFrames()
                val oldHost = worlds[0].observe()
                val oldPlayers = listOf("p1", "p2").map { worlds[0].observePlayer(worlds[0].world, it) }
                val oldFields = oldPlayers.map { it.fields() }
                val oldRecordCount = records[0].size
                val failureStep = warmSteps + 1L
                assertFailsWith<ArithmeticException> { worlds[0].advance(Step(failureStep, 1.0, "sim-tick")) }
                assertSame(oldHost, worlds[0].observe())
                oldPlayers.forEach { assertSame(it, worlds[0].observePlayer(worlds[0].world, it.playerId)) }
                assertEquals(oldRecordCount, records[0].size)
                assertTrue(worlds[0].drainPathDiagnostics(worlds[0].world).isEmpty())
                worlds.forEach { context ->
                    // An available, strictly in-range right-angle target closes the real aim gate.
                    assertIs<ActionResult.Accepted>(context.submit(AttackAction("correct-c", "p1", "c", "side", context.world)))
                    context.advance(Step(failureStep, 1.0, "sim-tick"))
                }
                equalFrames()
                val retried = worlds[0].observe()
                assertEquals(listOf("b"), retried.projectileEvents.filter { it.kind == "born" }.map { it.projectile.sourceUnitId })
                assertEquals(Long.MAX_VALUE, retried.combat!!.nextObjectId)
                assertTrue(retried.actionResults.any { it.actionId == "queued-select" })
                if (warmSteps == 209) assertEquals(90.0, retried.units.single { it.unitId == "aa" }.turretHeading,
                    "This failing candidate changed the endpoint at timer 211, without a same-tick turn")
                worlds.forEach { context ->
                    assertIs<ActionResult.Accepted>(context.submit(AttackAction("correct-b", "p1", "b", "side", context.world)))
                }
                val headings = mutableListOf<Double>()
                for (step in failureStep + 1..failureStep + 650) {
                    worlds.forEach { context ->
                        try { context.advance(Step(step, 1.0, "sim-tick")) }
                        catch (failure: ArithmeticException) {
                            throw AssertionError("Unexpected continuation shot at step=$step; prior units=${context.observe().units}; weapons=${context.observe().combat!!.weapons}", failure)
                        }
                    }
                    equalFrames()
                    headings += worlds[0].observe().units.single { it.unitId == "aa" }.turretHeading
                    val diagnostics = worlds.map { context -> context.drainPathDiagnostics(context.world).map {
                        it.copy(inputPreparationNanos = 0L, searchNanos = 0L)
                    } }
                    assertEquals(diagnostics[1], diagnostics[0])
                }
                assertTrue(headings.distinct().size > 30, "Future public motion checks hidden idle scalars after rollback")
                assertTrue(comparedFrames >= 650 + warmSteps + 3)
                assertEquals(records[1], records[0], "Compare every nested field of the real ClientEvidence serializer")
                assertEquals(oldFields, oldPlayers.map { it.fields() }, "Old public samples remain immutable")
                val context = worlds[0]
                val oldWorld = context.world
                context.close()
                assertIs<InitResult.Accepted>(context.reopen(scene))
                assertEquals(90.0, context.observe().units.single { it.unitId == "aa" }.turretHeading)
                assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(
                    SelectAction("stale", "p1", "aa", oldWorld))).error.code)
            } finally { worlds.forEach { it.close() } }
        }
    }

    private fun host(view: Observation): List<Any?> = listOf(view.sampleId, view.worldId, view.scenarioId,
        view.stepIndex, view.simulationTime, view.units, view.events, view.actionResults, view.projectiles,
        view.combat, view.projectileEvents, view.economy?.balances, view.economy?.queuedProduction, view.production?.units)
    private fun player(view: PlayerObservation): List<Any?> = listOf(view.fields(), view.vision?.worldId,
        view.vision?.playerId, view.vision?.basicCells, view.vision?.effectiveCells, view.knowledge?.worldId,
        view.knowledge?.playerId, view.knowledge?.width, view.knowledge?.height, view.knowledge?.revision,
        view.knowledge?.cells)

    private fun input(hold: Boolean, retention: TerrainRetention): Scenario {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/turret") }
        ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261026-e-turret-contract-01/input"))
        val base = ControlledScenario.load(root)
        val tower = base.unitDefinitions.getValue("c_turret_t1")
        val tank = base.unitDefinitions.getValue("c_tank")
        fun variant(id: String, armed: Boolean, speed: Double = 40.0) = UnitDefinition(id, tank.className,
            1.0, 1.0, tank.totalFrames, tank.imagePath, tank.imageScale,
            if (armed) tank.movement.copy(moveSpeed = 0.0, moveAccelerationSpeed = 0.0, moveDecelerationSpeed = 0.0,
                maxTurnSpeed = 0.0, turnAcceleration = 0.0) else
                tank.movement.copy(moveSpeed = speed, moveAccelerationSpeed = 1.0, moveDecelerationSpeed = 1.0,
                    maxTurnSpeed = 360.0, turnAcceleration = 0.0, slidingMode = false, ignoringBody = false),
            tank.retainedUnconsumed, 15,
            if (armed) tank.combat!!.copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0) else null,
            if (armed) tank.durability else DurabilityProfile(100000.0, tank.combat!!.deadImagePath))
        val shooter = variant("shooter", true)
        val target = variant("target", false)
        val escaping = variant("escaping", false, 200.0)
        val units = listOf(UnitSpawn("aa", "p1", tower.id, Position(80.0, 80.0), 90.0),
            UnitSpawn("b", "p1", shooter.id, Position(300.0, 270.0), 90.0),
            UnitSpawn("c", "p1", shooter.id, Position(300.0, 270.0), 90.0),
            UnitSpawn("side", "p2", target.id, Position(300.0, 360.0), 0.0),
            UnitSpawn("z", "p2", target.id, Position(450.0, 270.0), 270.0)) +
            if (hold) listOf(UnitSpawn("az", "p2", escaping.id, Position(180.0, 80.0), 90.0)) else emptyList()
        return base.copy(unitDefinitions = mapOf(tower.id to tower, shooter.id to shooter, target.id to target, escaping.id to escaping),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) }, units = units,
            combatContract = CombatContract(12345, -1, units.mapIndexed { i, unit -> unit.unitId to i + 8L }.toMap(),
                if (hold) Long.MAX_VALUE - 2 else Long.MAX_VALUE - 1),
            automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
    }
}
