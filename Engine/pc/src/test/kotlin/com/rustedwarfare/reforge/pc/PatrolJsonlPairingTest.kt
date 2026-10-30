package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter
import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PatrolJsonlPairingTest {
    @Test fun real_session_loss_and_block_discovery_pair_the_complete_committed_log_and_next_return() {
        val base = ControlledScenario.load(root())
        val tank = base.unitDefinitions.getValue("c_tank")
        val dummy = UnitDefinition("dummy", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 3,
            durability = DurabilityProfile(210.0, checkNotNull(tank.combat).deadImagePath))
        val locked = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 3,
            checkNotNull(tank.combat).copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
        val blocked = BooleanArray(600).also { it[10 * 30 + 12] = true; it[9 * 30 + 12] = true }
        val scene = base.copy(map = MapDefinition(30, 20, 20, 20, base.map.tileSets, base.map.groundGids, BlockingGrid(30, 20, blocked)),
            unitDefinitions = mapOf(locked.id to locked, dummy.id to dummy),
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 0.0),
                UnitSpawn("b", "p2", dummy.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("scout", "p1", dummy.id, Position(350.0, 210.0), 90.0),
                UnitSpawn("reveal", "p1", dummy.id, Position(250.0, 290.0), 0.0),
                UnitSpawn("witness", "p1", tank.id, Position(110.0, 190.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "scout" to 10L,
                "reveal" to 11L, "witness" to 12L), 13L))
        val out = System.getProperty("p005.patrol.evidence.dir")?.let { Path.of(it).resolve("loss-knowledge").also(Files::createDirectories) }
            ?: Files.createTempDirectory("p005-patrol-knowledge-jsonl-")
        try {
            for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
                val directory = out.resolve(mode.name)
                JsonlEvidence(directory, "knowledge-run", root()).use { sink ->
                    SandboxSession(scene, sink, SandboxOptions(mode)).use { s ->
                        val home = Position(30.0, 210.0)
                        s.select(Position(110.0, 210.0)); assertIs<ActionResult.Accepted>(s.patrol(home, false))
                        s.select(Position(110.0, 190.0)); assertIs<ActionResult.Accepted>(s.command(Position(310.0, 210.0)))
                        s.select(Position(350.0, 210.0)); assertIs<ActionResult.Accepted>(s.move(Position(550.0, 210.0)))
                        s.select(Position(250.0, 290.0)); assertIs<ActionResult.Accepted>(s.move(Position(250.0, 250.0)))
                        var previous = s.observation
                        var at: Long? = null
                        repeat(90) {
                            s.tick()
                            val view = s.observation
                            if (at == null && previous.units.any { it.unitId == "b" } && view.units.none { it.unitId == "b" } &&
                                previous.knowledge!!.cell(12, 10).lastKnownBlocked != true && view.knowledge!!.cell(12, 10).lastKnownBlocked == true) {
                                at = view.stepIndex
                                assertEquals(24L, at)
                                assertNull(view.units.single { it.unitId == "a" }.attackTargetId)
                                assertNull(view.units.single { it.unitId == "a" }.moveTarget)
                                recordFrame(s, sink, "knowledge-loss-boundary")
                            } else if (at != null && view.stepIndex == at!! + 1) {
                                val mine = view.units.single { it.unitId == "a" }
                                assertNull(mine.attackTargetId); assertEquals(home, mine.moveTarget)
                                assertEquals(home, mine.tasks.single().target)
                                recordFrame(s, sink, "knowledge-return-boundary")
                            }
                            previous = view
                        }
                        assertEquals(24L, at)
                    }
                }
                val rows = Files.readAllLines(directory.resolve("reforge/client-diagnostic.jsonl")).map { JsonReader().parse(it) }
                for (kind in listOf("knowledge-loss-boundary", "knowledge-return-boundary")) {
                    val marker = rows.indexOfFirst { it.getString("kind") == kind }
                    assertTrue(marker > 0)
                    val observation = rows[marker - 1]
                    assertEquals("observation", observation.getString("kind"))
                    assertEquals("step", observation.getString("phase"))
                    assertEquals(rows[marker].getLong("step"), observation.get("sample").getLong("stepIndex"))
                    assertEquals(600, rows[marker].get("terrain").size)
                    assertNotNull(rows[marker].get("vision").get("basicCells"))
                    assertEquals(true, observation.get("sample").get("knowledge").get(10 * 30 + 12).getBoolean("blocked"))
                }
                assertEquals(rows.indices.map { it.toLong() }, rows.map { it.getLong("sequence") })
                println("P005-007 loss-knowledge Session JSONL mode=$mode records=${rows.size}")
            }
        } finally { if (System.getProperty("p005.patrol.evidence.dir") == null) out.toFile().deleteRecursively() }
    }

    @Test fun genuine_session_birth_with_a_target_loses_vision_at_birth_end_and_emits_the_paired_authorized_frames() {
        val base=ControlledScenario.load(root())
        val tank=base.unitDefinitions.getValue("c_tank")
        val dummy=UnitDefinition("dummy",tank.className,tank.radius,tank.displayRadius,tank.totalFrames,
            tank.imagePath,tank.imageScale,tank.movement,tank.retainedUnconsumed,3,
            durability=DurabilityProfile(210.0,checkNotNull(tank.combat).deadImagePath))
        val definitions=base.unitDefinitions.mapValues { (_,d) -> UnitDefinition(d.id,d.className,d.radius,d.displayRadius,
            d.totalFrames,d.imagePath,d.imageScale,d.movement,d.retainedUnconsumed,3,
            d.combat?.copy(turretTurnSpeed=0.0,turretTurnAcceleration=0.0),d.durability,
            building=d.building,production=d.production,build=d.build,directHitTargetKind=d.directHitTargetKind,
            pointDefense=d.pointDefense,upgrades=d.upgrades) } + (dummy.id to dummy)
        val scene=base.copy(unitDefinitions=definitions,units=listOf(base.units.single { it.unitId=="f" },
            UnitSpawn("b","p2",dummy.id,Position(510.0,215.0),0.0),
            UnitSpawn("scout","p1",dummy.id,Position(550.0,215.0),90.0)),
            combatContract=CombatContract(12345,-1,mapOf("f" to 10L,"b" to 9L,"scout" to 8L),11L))
        val out=System.getProperty("p005.patrol.evidence.dir")?.let { Path.of(it).resolve("birth-loss").also(Files::createDirectories) }
            ?: Files.createTempDirectory("p005-patrol-birth-jsonl-")
        try {
            for (mode in listOf(SandboxMode.MEMORY,SandboxMode.HARDCORE)) {
                val directory=out.resolve(mode.name)
                var loss:Long?=null
                JsonlEvidence(directory,"birth-run",root()).use { sink ->
                    SandboxSession(scene,sink,SandboxOptions(mode)).use { s ->
                        s.select(Position(310.0,210.0));assertIs<ActionResult.Accepted>(s.produce())
                        repeat(501) { s.tick() }
                        val newborn=s.observation.units.single { it.unitId.startsWith("produced:") }
                        val point=Position(310.0,315.0)
                        s.select(newborn.position);assertIs<ActionResult.Accepted>(s.patrol(point,false))
                        for(step in 501L..540L) {
                            if(step==505L) { s.select(Position(550.0,215.0));assertIs<ActionResult.Accepted>(s.move(Position(585.0,215.0))) }
                            s.tick()
                            val unit=s.observation.units.single { it.unitId==newborn.unitId }
                            if(step==504L) assertEquals("b",unit.attackTargetId,"A genuine newborn has a visible T before loss")
                            if(loss==null && s.observation.units.none { it.unitId=="b" }) {
                                loss=step;assertEquals(529L,loss)
                                assertNull(unit.attackTargetId);assertNull(unit.moveTarget)
                                assertEquals(point,unit.tasks.single().target)
                                recordFrame(s,sink,"birth-loss-boundary")
                            } else if(loss!=null && step==loss!!+1) {
                                assertNull(unit.attackTargetId);assertEquals(point,unit.moveTarget)
                                recordFrame(s,sink,"birth-return-boundary")
                            }
                        }
                        assertEquals(529L,loss)
                    }
                }
                val rows=Files.readAllLines(directory.resolve("reforge/client-diagnostic.jsonl")).map { JsonReader().parse(it) }
                for(kind in listOf("birth-loss-boundary","birth-return-boundary")) {
                    val marker=rows.indexOfFirst { it.getString("kind")==kind }
                    val actual=rows[marker-1]
                    assertEquals("observation",actual.getString("kind"));assertEquals("step",actual.getString("phase"))
                    assertEquals(rows[marker].getLong("step"),actual.get("sample").getLong("stepIndex"))
                    assertNotNull(rows[marker].get("vision"));assertEquals(600,rows[marker].get("terrain").size)
                }
                println("P005-007 birth Session JSONL mode=$mode loss=$loss records=${rows.size}")
            }
        } finally { if(System.getProperty("p005.patrol.evidence.dir")==null) out.toFile().deleteRecursively() }
    }

    private fun recordFrame(s:SandboxSession,sink:ClientEvidence,kind:String) {
        val view=s.observation
        sink.record(kind,mapOf("step" to view.stepIndex,"vision" to view.vision!!.let {
            mapOf("epoch" to it.epoch,"basicCells" to it.basicCells.map { c->mapOf("column" to c.column,"row" to c.row) },
                "effectiveCells" to it.effectiveCells.map { c->mapOf("column" to c.column,"row" to c.row) }) },
            "terrain" to s.frame.terrain.map { c->mapOf("column" to c.column,"row" to c.row,"gid" to c.gid,"visible" to c.visible) }))
    }
    private fun root(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/patrol") } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-006/20261026-e-selection-contract-01/input"))

    @Test fun real_session_loss_projects_and_returns_without_hidden_coordinate_changes_in_complete_jsonl_vision_and_terrain() {
        val base = ControlledScenario.load(root())
        val tank = base.unitDefinitions.getValue("c_tank")
        val locked = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement.copy(maxTurnSpeed = 0.0, turnAcceleration = 0.0), tank.retainedUnconsumed, 3,
            checkNotNull(tank.combat).copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
        val dummy = UnitDefinition("dummy", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 3,
            durability = DurabilityProfile(210.0, checkNotNull(tank.combat).deadImagePath))
        val temporary = System.getProperty("p005.patrol.evidence.dir")?.let { Path.of(it).resolve("loss-pair").also(Files::createDirectories) }
            ?: Files.createTempDirectory("p005-patrol-jsonl-")
        try {
            for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
                val paired = mutableListOf<List<String>>()
                for ((branch, enemyY) in listOf(210.0, 215.0).withIndex()) {
                    val scene = base.copy(unitDefinitions = mapOf(locked.id to locked, dummy.id to dummy),
                        units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 0.0),
                            UnitSpawn("b", "p2", dummy.id, Position(310.0, enemyY), 0.0),
                            UnitSpawn("scout", "p1", dummy.id, Position(350.0, 210.0), 90.0)),
                        combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "scout" to 10L), 11L))
                    val directory = temporary.resolve("$mode-$branch")
                    var lossStep: Long? = null
                    JsonlEvidence(directory, "paired-run", root()).use { sink ->
                        SandboxSession(scene, sink, SandboxOptions(mode)).use { s ->
                            val home = Position(30.0, 210.0)
                            s.select(Position(110.0, 210.0)); assertIs<ActionResult.Accepted>(s.patrol(home, false))
                            s.select(Position(350.0, 210.0)); assertIs<ActionResult.Accepted>(s.move(Position(550.0, 210.0)))
                            var seen = false
                            var chased = false
                            repeat(90) {
                                s.tick()
                                val view = s.observation
                                val unit = view.units.single { it.unitId == "a" }
                                if (unit.attackTargetId == "b") seen = true
                                if (unit.attackTargetId == "b" && unit.moveTarget == Position(310.0, enemyY) && unit.pathPoints.isNotEmpty()) chased = true
                                if (lossStep == null && seen && view.units.none { it.unitId == "b" }) {
                                    lossStep = view.stepIndex
                                    assertNull(unit.attackTargetId)
                                    assertTrue(unit.pathPoints.none { point -> point.x == 310.0 })
                                    assertEquals(home, unit.tasks.single().target)
                                    sink.record("loss-boundary", mapOf("step" to view.stepIndex))
                                }
                                if (lossStep != null) {
                                    assertNull(unit.attackTargetId)
                                    assertTrue(view.units.none { it.unitId == "b" })
                                    sink.record("authorized-frame", mapOf("step" to view.stepIndex,
                                        "vision" to view.vision!!.let { mapOf("epoch" to it.epoch,
                                            "basicCells" to it.basicCells.map { cell -> mapOf("column" to cell.column, "row" to cell.row) },
                                            "effectiveCells" to it.effectiveCells.map { cell -> mapOf("column" to cell.column, "row" to cell.row) }) },
                                        "terrain" to s.frame.terrain.map { cell -> mapOf("column" to cell.column, "row" to cell.row,
                                            "gid" to cell.gid, "visible" to cell.visible) }))
                                }
                            }
                            assertNotNull(lossStep, "The public scout must really lose vision: $mode")
                            assertTrue(chased, "Both branches must hold an actual old enemy route before loss")
                            val returned = s.observation.units.single { it.unitId == "a" }
                            assertEquals(home, returned.moveTarget)
                            assertEquals(Position(110.0, 210.0), returned.position,
                                "The zero-turn fixture isolates old chase routes without letting visible movement diverge")
                            assertEquals(home, returned.tasks.single().target)
                            println("P005-007 Session JSONL loss mode=$mode branch=$branch step=$lossStep returned=${returned.position}")
                        }
                    }
                    val rows = Files.readAllLines(directory.resolve("reforge/client-diagnostic.jsonl")).map { JsonReader().parse(it) }
                    val cut = rows.indexOfFirst { it.getString("kind") == "loss-boundary" }
                    assertTrue(cut > 0)
                    // Include the actual loss observation before the marker, then every subsequent complete record.
                    paired += rows.drop(cut - 1).map { row -> row.remove("wallTime"); row.toJson(JsonWriter.OutputType.json) }
                }
                assertEquals(paired[0], paired[1], "All Session/JSONL keys, sequence/sample identity, vision and terrain after loss: $mode")
            }
        } finally { if (System.getProperty("p005.patrol.evidence.dir") == null) temporary.toFile().deleteRecursively() }
    }
}
