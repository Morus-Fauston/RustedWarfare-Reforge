package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionQueuePrivacyTest {
    @Test fun foreign_full_queue_changes_do_not_change_the_complete_player_payload_before_contact_while_visible_or_after_losing_sight() {
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val spawns = listOf(UnitSpawn("observer", "p1", "builder", Position(50.0, 70.0), 90.0),
                UnitSpawn("worker", "p2", "builder", Position(650.0, 350.0), 90.0),
                UnitSpawn("factory", "p2", "landFactory", Position(710.0, 350.0), 0.0))
            val input = scene(spawns).copy(automaticVision = SandboxOptions(mode).knowledgeRules())
            val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
            val rows = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
            val sinks = rows.map { output -> ClientEvidence { kind, fields -> output += kind to fields } }
            var step = 0L
            fun sample(label: String, retain: Boolean = true) {
                val views = contexts.mapIndexed { index, c -> c.observePlayer(c.world, "p1").also {
                    if (retain) sinks[index].record("player", it.fields())
                    assertNull(it.fields()["construction"])
                    assertTrue(it.events.none { event -> event.kind == "construction-queue-full" })
                    assertTrue(it.actionResults.none { result -> result.actionId.startsWith("private-") })
                    assertTrue(it.units.filter { unit -> unit.ownerPlayerId == "p2" }.all { unit ->
                        unit.tasks.isEmpty() && unit.constructionTargetId == null
                    })
                    noHostFields(it.fields())
                } }
                assertEquals(payload(views[0]), payload(views[1]), "$mode $label")
                assertEquals(rows[0], rows[1], "$mode $label complete ClientEvidence values")
            }
            fun common(action: (SimulationContext) -> Action) = contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(action(it))) }
            fun tick() {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                step++
                sample("movement step $step", false)
            }
            fun capacity(label: String, visible: Boolean) {
                contexts.forEach { c -> assertEquals(visible, c.observePlayer(c.world, "p1").units.any { it.unitId == "factory" }, "$mode $label visibility") }
                common { PatrolAction("common-head-$label", "p2", "worker", Position(650.0, 350.0), false, it.world) }
                sample("$label head")
                val changed = contexts[1]
                repeat(28) { index -> assertIs<ActionResult.Accepted>(changed.submit(BuildAction("private-$label-$index", "p2", "worker",
                    "landFactory", Position(710.0, 350.0), index.toString(16).padStart(32, '0'), changed.world, append = true))) }
                val full = changed.observePlayer(changed.world, "p2").units.single { it.unitId == "worker" }.tasks
                assertEquals(29, full.size)
                val overflow = assertIs<ActionResult.Accepted>(changed.submit(BuildAction("private-$label-overflow", "p2", "worker",
                    "landFactory", Position(710.0, 350.0), "f".repeat(32), changed.world, append = true)))
                assertEquals(listOf(Event("construction-queue-full", "worker")), overflow.events)
                assertEquals(full, changed.observePlayer(changed.world, "p2").units.single { it.unitId == "worker" }.tasks)
                assertEquals(1, contexts[0].observePlayer(contexts[0].world, "p2").units.single { it.unitId == "worker" }.tasks.size)
                sample("$label capacity changed")
                common { StopAction("common-stop-$label", "p2", "worker", it.world) }
                sample("$label normalized")
                assertTrue(contexts.all { c -> c.observePlayer(c.world, "p2").units.single { it.unitId == "worker" }.tasks.isEmpty() })
            }
            try {
                sample("initial")
                capacity("never-contact", false)
                common { MoveAction("discover", "p1", "observer", Position(500.0, 250.0), it.world) }
                val discoverDeadline = step + 1000
                while (contexts[0].observe().units.single { it.unitId == "observer" }.moveTarget != null && step < discoverDeadline) tick()
                assertNull(contexts[0].observe().units.single { it.unitId == "observer" }.moveTarget)
                assertTrue(contexts[0].observe().units.single { it.unitId == "observer" }.position.x > 480.0)
                capacity("visible", true)
                assertEquals(false, contexts[0].observePlayer(contexts[0].world, "p1").knowledge!!.cell(35, 17).lastKnownBlocked)
                common { MoveAction("return", "p1", "observer", Position(50.0, 70.0), it.world) }
                val returnDeadline = step + 1000
                while (contexts[0].observe().units.single { it.unitId == "observer" }.moveTarget != null && step < returnDeadline) tick()
                assertNull(contexts[0].observe().units.single { it.unitId == "observer" }.moveTarget)
                capacity("lost-sight", false)
                assertEquals(if (mode == SandboxMode.MEMORY) false else null,
                    contexts[0].observePlayer(contexts[0].world, "p1").knowledge!!.cell(35, 17).lastKnownBlocked)
                for (target in listOf("factory", "missing")) {
                    val results = contexts.map { c -> c.submit(RepairBuildingAction("probe-$target", "p1", "observer", target, c.world, append = true)) }
                    assertEquals(results[0], results[1])
                    assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(results[1]).error.code)
                    sample("lost-sight probe $target")
                }
            } finally { contexts.forEach { it.close() } }
        }
    }

    @Test fun hidden_present_and_absent_members_produce_equal_batch_records_feedback_and_player_samples() {
        val own = UnitSpawn("own", "p1", "builder", Position(50.0, 70.0), 90.0)
        val hidden = UnitSpawn("hidden", "p2", "builder", Position(650.0, 350.0), 90.0)
        val rows = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
        val sessions = listOf(listOf(own), listOf(own, hidden)).mapIndexed { index, spawns ->
            SandboxSession(scene(spawns), ClientEvidence { kind, fields -> rows[index] += kind to fields }, SandboxOptions(SandboxMode.MEMORY))
        }
        fun equal() {
            assertEquals(payload(sessions[0].observation), payload(sessions[1].observation))
            assertEquals(sessions[0].playerFeedback, sessions[1].playerFeedback)
            assertEquals(rows[0], rows[1])
        }
        try {
            sessions.forEach { it.select(Position(50.0, 70.0)) }
            equal()
            sessions.forEach { session ->
                assertIs<ActionResult.Accepted>(session.patrol(Position(130.0, 70.0), true, setOf("own", "hidden", "missing")))
                val batch = assertNotNull(session.lastCommandBatch)
                assertEquals(1, batch.accepted); assertEquals(0, batch.rejected); assertEquals(2, batch.skipped)
                assertEquals(listOf("own"), batch.members.map { it.unitId })
                assertEquals("Accepted 1, rejected 0, skipped 2", session.playerFeedback)
            }
            equal()
            for (members in listOf(setOf("hidden", "missing"), emptySet())) {
                sessions.forEach { session ->
                    assertNull(session.patrol(Position(130.0, 70.0), true, members))
                    val batch = assertNotNull(session.lastCommandBatch)
                    assertEquals(0, batch.accepted); assertEquals(0, batch.rejected); assertEquals(members.size, batch.skipped)
                    assertTrue(batch.members.isEmpty())
                    assertEquals(if (members.isEmpty()) "No selection" else "Accepted 0, rejected 0, skipped 2", session.playerFeedback)
                }
                equal()
            }
            assertTrue(rows.all { output -> output.filter { it.first == "action" }.all { it.second["unitId"] == "own" } })
            assertEquals(3, rows[0].count { it.first == "selection-command" })
            rows[0].filter { it.first == "observation" }.forEach { (_, fields) -> noHostFields(fields) }
            SandboxSession(scene(listOf(own, hidden)), ClientEvidence.NONE, SandboxOptions(SandboxMode.MEMORY)).use { muted ->
                muted.select(Position(50.0, 70.0))
                assertIs<ActionResult.Accepted>(muted.patrol(Position(130.0, 70.0), true, setOf("own", "hidden", "missing")))
                assertEquals(1, muted.lastCommandBatch!!.accepted)
                assertEquals(2, muted.lastCommandBatch!!.skipped)
                assertEquals(1, muted.observation.units.single { it.unitId == "own" }.tasks.size)
            }
        } finally { sessions.forEach { it.close() } }
    }

    private fun payload(v: PlayerObservation): List<Any?> = listOf(v.fields(),
        v.vision?.let { listOf(it.worldId, it.playerId, it.epoch, it.basicCells, it.effectiveCells) },
        v.knowledge?.let { listOf(it.worldId, it.playerId, it.width, it.height, it.revision, it.cells) })

    private fun noHostFields(value: Any?) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                assertTrue(key !in setOf("singleNodePathCount", "pendingPathNodeCounts", "qBeforeDispatch", "consumedPathNodeCounts",
                    "retryTime", "preparation", "elapsed", "requestX", "requestY", "requestSize", "unitNonce", "buildingNonce",
                    "constructionNonces", "queuedProduction", "balances", "objectId", "updateKey"))
                noHostFields(child)
            }
            is Iterable<*> -> value.forEach(::noHostFields)
        }
    }

    private fun scene(spawns: List<UnitSpawn>): Scenario {
        val repo = System.getProperty("repo.root")?.let { Path.of(it) } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val base = ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")))
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground }, BlockingGrid(40, 25, BooleanArray(1000))),
            units = spawns, players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), spawns.size + 1L))
    }
}
