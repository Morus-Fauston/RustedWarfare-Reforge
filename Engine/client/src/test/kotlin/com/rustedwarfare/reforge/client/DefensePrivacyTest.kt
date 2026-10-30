package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefensePrivacyTest {
    @Test fun actual_hidden_shield_combat_leaves_all_session_frames_and_nested_client_evidence_equal_to_an_absent_world() {
        val base = scene()
        val spawns = listOf(base.units.first(),
            UnitSpawn("shield", "p2", "laserDefence", Position(650.0, 350.0), 0.0),
            UnitSpawn("tank", "p3", "c_tank", Position(760.0, 350.0), 270.0))
        val full = base.copy(units = spawns, combatContract = CombatContract(1, -1,
            spawns.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 4L))
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val witness = assertIs<InitResult.Accepted>(SimulationContext.initialize(full.copy(
                automaticVision = SandboxOptions(mode).knowledgeRules()))).context
            try {
                var interceptions = 0
                var minimumEnergy = 1f
                repeat(400) { step ->
                    val host = witness.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    interceptions += host.projectileEvents.count { it.kind == "deflected" && it.interceptorUnitId == "shield" }
                    minimumEnergy = minOf(minimumEnergy, witness.observePlayer(witness.world, "p2")
                        .units.single { it.unitId == "shield" }.pointDefense!!.energy)
                }
                assertTrue(interceptions > 0)
                assertTrue(minimumEnergy < 1f)
            } finally { witness.close() }
            val absent = full.copy(units = spawns.take(1), combatContract = full.combatContract!!.copy(unitObjectIds = mapOf("observer" to 1L)))
            val rows = List(2) { mutableListOf<Pair<String, Map<String, Any?>>>() }
            val sessions = listOf(full, absent).mapIndexed { i, input -> SandboxSession(input,
                ClientEvidence { kind, values -> rows[i] += kind to values }, SandboxOptions(mode)) }
            try {
                repeat(401) { step ->
                    assertEquals(payload(sessions[0].observation), payload(sessions[1].observation), "$mode/$step")
                    assertEquals(sessions[0].frame.terrain, sessions[1].frame.terrain)
                    assertEquals(rows[0], rows[1], "Full nested ClientEvidence $mode/$step")
                    assertEquals(listOf("observer"), sessions[0].observation.units.map { it.unitId })
                    rows.forEach { it.clear() }; sessions.forEach { it.tick() }
                }
            } finally { sessions.forEach { it.close() } }
        }
    }

    @Test fun hidden_real_construction_upgrade_interception_and_reclaim_do_not_change_the_other_players_complete_observation() {
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            val scene = scene().copy(automaticVision = SandboxOptions(mode).knowledgeRules())
            val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
            val changed = contexts[1]
            var step = 0L
            fun compare() {
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(payload(views[0]), payload(views[1]), "$mode/$step")
                assertEquals(listOf("observer"), views[1].units.map { it.unitId })
                assertTrue(views[1].projectiles.isEmpty())
                assertTrue(views[1].projectileEvents.isEmpty())
                assertTrue(views[1].upgradeQueues.isEmpty())
            }
            fun tick(): Observation {
                val frames = contexts.map { it.advance(Step(step, 1.0, "sim-tick")) }
                step++; compare(); return frames[1]
            }
            fun waitUntil(label: String, limit: Int = 5000, ready: () -> Boolean) {
                var ticks = 0
                while (!ready() && ticks++ < limit) tick()
                assertTrue(ready(), "$mode/$label at $step")
            }
            try {
                compare()
                assertIs<ActionResult.Accepted>(changed.submit(BuildAction("hidden-turret", "p2", "worker", "c_turret_t1",
                    Position(650.0, 210.0), "1".repeat(32), changed.world)))
                compare()
                waitUntil("turret completes") { changed.observe().units.any { it.typeId == "c_turret_t1" && it.constructionProgress == 1f } }
                val turret = changed.observe().units.single { it.typeId == "c_turret_t1" }.unitId
                assertIs<ActionResult.Accepted>(changed.submit(UpgradeAction("hidden-upgrade", "p2", turret, "upgrade_gun", changed.world)))
                assertEquals("upgrade_gun", changed.observePlayer(changed.world, "p2").upgradeQueues.single { it.unitId == turret }.items.single().actionKey)
                compare()
                assertIs<ActionResult.Accepted>(changed.submit(BuildAction("hidden-shield", "p2", "worker", "laserDefence",
                    Position(650.0, 350.0), "2".repeat(32), changed.world)))
                waitUntil("shield and upgrade complete") {
                    changed.observe().units.any { it.typeId == "laserDefence" && it.constructionProgress == 1f } &&
                        changed.observe().units.any { it.unitId == turret && it.typeId == "c_turret_t2_gun" }
                }
                val shield = changed.observe().units.single { it.typeId == "laserDefence" }.unitId
                assertEquals(1f, changed.observePlayer(changed.world, "p2").units.single { it.unitId == shield }.pointDefense!!.energy)
                val balance = changed.observePlayer(changed.world, "p2").credits!!
                fun reclaim(target: String, index: Int) {
                    assertIs<ActionResult.Accepted>(changed.submit(ReclaimBuildingAction("hidden-reclaim-$index", "p2", "worker", target, changed.world)))
                    compare()
                    var terminalEvents = 0
                    var ticks = 0
                    while (changed.observe().units.any { it.unitId == target } && ticks++ < 5000) {
                        terminalEvents += tick().events.count { it.kind == "building-reclaimed" && it.unitId == target }
                    }
                    assertTrue(changed.observe().units.none { it.unitId == target })
                    assertEquals(1, terminalEvents)
                    assertEquals(listOf(target), changed.observePlayer(changed.world, "p2").events
                        .filter { it.kind == "building-reclaimed" }.map { it.unitId })
                    compare()
                }
                // Reclaim the real upgraded gun before the tank crosses its range toward the shield.
                reclaim(turret, 0)
                assertIs<ActionResult.Accepted>(changed.submit(MoveAction("real-attacker", "p3", "tank", Position(760.0, 350.0), changed.world)))
                var intercepted = false
                var ticks = 0
                var tankShots = 0
                var turretShots = 0
                var tankDeaths = 0
                while (!intercepted && ticks++ < 1500) {
                    val frame = tick()
                    tankShots += frame.events.count { it.kind == "projectile-fired" && it.unitId == "tank" }
                    turretShots += frame.events.count { it.kind == "projectile-fired" && it.unitId == turret }
                    tankDeaths += frame.events.count { it.kind == "unit-died" && it.unitId == "tank" }
                    intercepted = frame.projectileEvents.any { it.kind == "deflected" && it.interceptorUnitId == shield }
                }
                assertTrue(intercepted, "A real hidden shot must stimulate the constructed shield; tankShots=$tankShots, " +
                    "turretShots=$turretShots, tankDeaths=$tankDeaths, tank=${changed.observe().units.single { it.unitId == "tank" }}")
                assertTrue(changed.observePlayer(changed.world, "p2").units.single { it.unitId == shield }.pointDefense!!.energy < 1f)
                assertIs<ActionResult.Accepted>(changed.submit(MoveAction("retreat", "p3", "tank", Position(760.0, 40.0), changed.world)))
                compare()
                reclaim(shield, 1)
                assertTrue(changed.observePlayer(changed.world, "p2").credits!! > balance)
                assertEquals(8000.0, contexts[0].observePlayer(contexts[0].world, "p2").credits)
                repeat(30) { tick() }
            } finally { contexts.forEach { it.close() } }
        }
    }

    private fun payload(v: PlayerObservation): List<Any?> = listOf(v.fields(), v.units, v.events, v.actionResults,
        v.projectiles, v.projectileEvents, v.balance, v.credits, v.factoryQueues, v.upgradeQueues,
        v.vision?.let { listOf(it.worldId, it.playerId, it.epoch, it.basicCells, it.effectiveCells) },
        v.knowledge?.let { listOf(it.worldId, it.playerId, it.width, it.height, it.revision, it.cells) })

    private fun scene(): Scenario {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input"))
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val units = listOf(UnitSpawn("observer", "p1", "builder", Position(50.0, 70.0), 90.0),
            UnitSpawn("worker", "p2", "builder", Position(590.0, 350.0), 90.0),
            UnitSpawn("tank", "p3", "c_tank", Position(760.0, 40.0), 180.0))
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))), units = units,
            players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.LOCAL, "red"),
                Player("p3", PlayerControl.LOCAL, "green")),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 8000L, "p3" to 0L)),
            combatContract = CombatContract(1, -1, units.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 4L))
    }
}
