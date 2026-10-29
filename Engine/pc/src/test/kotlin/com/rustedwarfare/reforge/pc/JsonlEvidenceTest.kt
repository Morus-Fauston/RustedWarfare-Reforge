package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.client.ControlledScenario
import com.rustedwarfare.reforge.client.SandboxSession
import com.rustedwarfare.reforge.client.SandboxInput
import com.rustedwarfare.reforge.client.MapProjection
import com.rustedwarfare.reforge.client.SelectionMode
import com.badlogic.gdx.Input

class JsonlEvidenceTest {
    @Test fun siege_construction_evidence_keeps_007_identity_and_binds_the_actual_siege_input() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val source = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")
        val temporary = Files.createTempDirectory("p006-siege-evidence-")
        val inputs = temporary.resolve("input")
        val directory = temporary.resolve("run")
        try {
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = inputs.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            Files.writeString(inputs.resolve("construction.properties"),
                "format=rw-reforge.construction-sandbox.v1\nvariant=defense-construction-siege\n")
            JsonlEvidence(directory, "siege-run", inputs).use { sink ->
                val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
                assertEquals("ENG-P006-007", manifest.getString("ticket"))
                assertEquals("P006", manifest.getString("phase"))
                assertEquals("defense-construction-windows-client", manifest.getString("topic"))
                sink.record("load-ready", mapOf("generation" to 1L))
            }
            val rows = records(directory)
            assertTrue(rows.all { it.getString("runId") == "siege-run" })
            val identity = rows.single { it.getString("kind") == "input-identities" }
            assertEquals("p006-construction", identity.getString("scenarioId"))
            assertEquals(inputs.resolve("construction.properties").toString(), identity.get("sandboxManifest").getString("path"))
            val files = identity.get("files").toList()
            val expected = Files.walk(inputs).use { paths -> paths.filter {
                Files.isRegularFile(it) && it.fileName.toString() != "manifest.json" }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) {
                val file = Path.of(row.getString("path"))
                assertEquals(Files.size(file), row.getLong("bytes"))
                assertEquals(evidenceSha256(file), row.getString("sha256"))
            }
        } finally {
            val resolved = temporary.toRealPath()
            require(resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()) &&
                resolved.fileName.toString().startsWith("p006-siege-evidence-"))
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun defense_construction_evidence_keeps_007_identity_and_records_the_real_loaded_input() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")
        val temporary = Files.createTempDirectory("p006-defense-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "defense-run", root).use { sink ->
                val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
                assertEquals("ENG-P006-007", manifest.getString("ticket"))
                assertEquals("P006", manifest.getString("phase"))
                assertEquals("defense-construction-windows-client", manifest.getString("topic"))
                assertEquals("defense-run", manifest.getString("runId"))
                sink.record("load-request", mapOf("generation" to 7L, "root" to root.toString()))
                sink.record("load-ready", mapOf("generation" to 7L))
            }
            val rows = records(directory)
            assertTrue(rows.all { it.getString("runId") == "defense-run" })
            val identity = rows.single { it.getString("kind") == "input-identities" }
            assertEquals("p006-construction", identity.getString("scenarioId"))
            assertEquals(7L, identity.getLong("generation"))
            val files = identity.get("files").toList()
            for (name in listOf("construction.properties", "units/builder.properties", "units/turret_t1.ini", "units/laserDefence.properties")) {
                val file = root.resolve(name)
                val row = files.single { Path.of(it.getString("path")) == file }
                assertEquals(evidenceSha256(file), row.getString("sha256"))
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun queue_evidence_uses_its_own_ticket_and_binds_every_loaded_input_and_runtime_classpath_entry() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val source = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")
        val temporary = Files.createTempDirectory("p006-queue-evidence-")
        val inputs = temporary.resolve("input")
        try {
            Files.walk(source).use { paths -> paths.forEach { path ->
                val dest = inputs.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(dest) else Files.copy(path, dest)
            } }
            Files.writeString(inputs.resolve("construction.properties"), "format=rw-reforge.construction-sandbox.v1\nvariant=construction-queue\n")
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "queue-run", inputs).use { sink ->
                val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
                assertEquals("ENG-P006-006", manifest.getString("ticket"))
                assertEquals("P006", manifest.getString("phase"))
                assertEquals("construction-queue-windows-client", manifest.getString("topic"))
                assertEquals("queue-run", manifest.getString("runId"))
                assertEquals(System.getProperty("java.version"), manifest.getString("javaVersion"))
                assertEquals(System.getProperty("os.name"), manifest.getString("osName"))
                assertEquals(Path.of("").toAbsolutePath().toString(), manifest.getString("workingDirectory"))
                val runtime = manifest.get("runtimeJars").toList()
                assertEquals(System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toAbsolutePath().normalize().toString() },
                    runtime.map { it.getString("path") })
                for (entry in runtime) {
                    val path = Path.of(entry.getString("path"))
                    if (Files.isRegularFile(path)) {
                        assertEquals(Files.size(path), entry.getLong("bytes"))
                        assertEquals(evidenceSha256(path), entry.getString("sha256"))
                    } else assertFalse(entry.getBoolean("exists"))
                }
                sink.record("load-request", mapOf("generation" to 7L, "root" to inputs.toString()))
                sink.record("load-ready", mapOf("generation" to 7L))
            }
            val identity = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals(7L, identity.getLong("generation"))
            val files = identity.get("files").toList()
            val unused = setOf("manifest.json", "p005-corridor.tmx", "p005-sealed.tmx")
            val expected = Files.walk(inputs).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() !in unused }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (entry in files) assertEquals(evidenceSha256(Path.of(entry.getString("path"))), entry.getString("sha256"))
            assertTrue(files.any { Path.of(it.getString("path")) == inputs.resolve("construction.properties") })
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun real_press_and_batch_records_keep_selection_identity_across_stale_release_and_selection_clear() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val scene = base.copy(map = com.rustedwarfare.reforge.core.content.MapDefinition(40, 25, 20, 20,
            base.map.tileSets, IntArray(1000) { ground }, com.rustedwarfare.reforge.core.content.BlockingGrid(40, 25, BooleanArray(1000))),
            units = listOf(
            UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L), 3L))
        val temporary = Files.createTempDirectory("p006-press-identity-")
        val revisions = mutableListOf<Long>()
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "press-identity", root).use { sink ->
                SandboxSession(scene, sink).use { session ->
                    fun selectBoth() {
                        session.select(Position(120.0, 170.0))
                        session.select(Position(120.0, 210.0), SelectionMode.ADD)
                    }
                    selectBoth()
                    val map = MapProjection(600.0, 400.0)
                    var shift = true
                    val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                        { true }, { false }, { key -> shift && key == Input.Keys.SHIFT_LEFT }, evidence = sink)
                    fun down(x: Double) = input.touchDown(map.x(x).toInt(), map.y(170.0).toInt(), 0, Input.Buttons.LEFT)
                    fun up(x: Double) = input.touchUp(map.x(x).toInt(), map.y(170.0).toInt(), 0, Input.Buttons.LEFT)
                    assertTrue(input.beginConstruction("landFactory"))
                    revisions += session.selectionRevision
                    assertTrue(down(190.0))
                    selectBoth()
                    assertTrue(up(190.0))
                    assertTrue(session.observation.units.all { it.tasks.isEmpty() }, "The stale release must dispatch no build")
                    assertTrue(input.beginConstruction("landFactory"))
                    revisions += session.selectionRevision
                    assertTrue(down(190.0))
                    shift = false
                    assertTrue(up(190.0))
                    assertEquals(setOf("a", "b"), session.selectedIds)
                    assertEquals(2, session.lastCommandBatch!!.accepted)
                    revisions += session.selectionRevision
                    assertTrue(down(330.0))
                    shift = true
                    assertTrue(up(330.0))
                    assertTrue(session.selectedIds.isEmpty())
                    assertEquals(2, session.lastCommandBatch!!.accepted)
                    session.select(Position(120.0, 170.0))
                    assertTrue(input.beginConstruction("landFactory"))
                    revisions += session.selectionRevision
                    shift = false
                    assertTrue(down(450.0)); assertTrue(up(450.0))
                    assertTrue(session.selectedIds.isEmpty())
                    assertEquals(1, session.lastCommandBatch!!.accepted)
                }
            }
            val rows = records(directory)
            val actions = rows.filter { it.getString("kind") == "action" && it.getString("type") == "BuildAction" }
            assertEquals(5, actions.size, "Only fresh presses dispatch, including both workers")
            assertEquals(listOf(true, true, false, false, false), actions.map { it.getBoolean("append") })
            val pointers = rows.filter { it.getString("kind") == "pointer" }
            assertEquals(revisions, pointers.map { it.getLong("selectionRevision") })
            assertEquals(listOf(listOf("a", "b"), listOf("a", "b"), listOf("a", "b"), listOf("a")),
                pointers.map { row -> row.get("selectionAtPress").map { it.asString() } })
            assertEquals(listOf(true, true, false, false), pointers.map { it.getBoolean("append") })
            assertTrue(pointers.all { it.getString("mode") == "construction" && it.get("targetUnitId").isNull })
            val points = rows.filter { it.getString("kind") == "construction-point" }
            assertEquals(revisions.drop(1), points.map { it.getLong("selectionRevision") })
            assertEquals(listOf(true, false, false), points.map { it.getBoolean("append") })
            assertEquals(listOf(2, 2, 1), points.map { it.getInt("accepted") })
            assertTrue(points.all { it.getString("typeId") == "landFactory" })
            val batches = rows.filter { it.getString("kind") == "selection-command" }
            assertEquals(revisions.drop(1), batches.map { it.getLong("selectionRevision") })
            assertEquals(listOf(listOf("a", "b"), listOf("a", "b"), listOf("a")),
                batches.map { row -> row.get("selectionAtPress").map { it.asString() } })
            assertEquals(listOf(2, 2, 1), batches.map { it.getInt("accepted") })
            val starts = rows.filter { it.getString("kind") == "construction-mode" && it.getBoolean("active") }
            assertEquals(listOf(revisions[0], revisions[1], revisions[3]), starts.map { it.getLong("selectionRevision") })
            assertEquals(3, rows.count { it.getString("kind") == "construction-mode" && !it.getBoolean("active") })
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun real_queue_actions_and_trusted_navigation_encode_without_leaking_navigation_into_player_samples() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")
        val base = ControlledScenario.load(root)
        val tileset = base.map.tileSets.first { 2 in it.blockedTileIds }
        val scene = base.copy(map = com.rustedwarfare.reforge.core.content.MapDefinition(40, 25, 20, 20, base.map.tileSets,
            IntArray(1000) { tileset.firstGid }, com.rustedwarfare.reforge.core.content.BlockingGrid(40, 25, BooleanArray(1000))),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val temporary = Files.createTempDirectory("p006-queue-jsonl-")
        fun navigation(): Map<String, Any?> = context.observe().construction!!.workers.single().let { worker -> mapOf(
            "hostOnly" to true, "singleNodePathCount" to worker.singleNodePathCount,
            "logicalRequestX" to worker.logicalRequestX, "logicalRequestY" to worker.logicalRequestY,
            "pendingPathNodeCounts" to worker.pendingPathNodeCounts, "consumedPathNodeCounts" to worker.consumedPathNodeCounts,
            "qBeforeDispatch" to worker.qBeforeDispatch, "movementRequested" to worker.movementRequested) }
        try {
            repeat(128) { index -> assertIs<ActionResult.Accepted>(context.submit(MoveAction("same-$index", "p1", "worker", Position(650.0, 170.0), context.world))) }
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals((-128).toByte(), context.observe().construction!!.workers.single().singleNodePathCount)
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "queue-codec", root).use { sink ->
                sink.record("host-navigation", navigation())
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "worker", context.world)))
                sink.record("host-navigation", navigation())
                SandboxSession(scene, sink).use { session ->
                    session.select(Position(120.0, 170.0))
                    assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 170.0), true))
                    session.tick()
                    val target = session.observation.units.single { it.typeId == "landFactory" }
                    assertIs<ActionResult.Accepted>(session.reclaim(target.unitId, true))
                    assertIs<ActionResult.Accepted>(session.command(target.position, true))
                }
                SandboxSession(scene.copy(maintenanceContract = null), sink).use { session ->
                    session.select(Position(120.0, 170.0))
                    assertIs<ActionResult.Accepted>(session.build("landFactory", Position(190.0, 170.0), true))
                    session.tick()
                    val target = session.observation.units.single { it.typeId == "landFactory" }
                    assertIs<ActionResult.Accepted>(session.command(target.position, true))
                }
            }
            val rows = records(directory)
            val host = rows.filter { it.getString("kind") == "host-navigation" }
            assertEquals(2, host.size)
            assertEquals(-128, host[0].getInt("singleNodePathCount"))
            assertEquals(128, host[0].get("consumedPathNodeCounts").size)
            assertEquals(650.0, host[0].getDouble("logicalRequestX"))
            assertTrue(host.all { it.getBoolean("hostOnly") })
            assertEquals(0, host[1].getInt("singleNodePathCount"))
            assertTrue(host[1].get("logicalRequestX").isNull && host[1].get("logicalRequestY").isNull)
            val actionTypes = setOf("BuildAction", "RepairBuildingAction", "ReclaimBuildingAction", "ResumeConstructionAction")
            val actions = rows.filter { it.getString("kind") == "action" && it.getString("type") in actionTypes }
            assertEquals(actionTypes, actions.map { it.getString("type") }.toSet())
            assertTrue(actions.all { it.getBoolean("append") })
            val samples = rows.filter { it.getString("kind") == "observation" }.map { it.get("sample") }
            assertTrue(samples.isNotEmpty())
            for (sample in samples) {
                assertTrue(sample.has("construction") && sample.get("construction").isNull)
                assertFalse(sample.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json).contains("singleNodePathCount"))
                assertFalse(sample.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json).contains("logicalRequestX"))
            }
        } finally { context.close(); temporary.toFile().deleteRecursively() }
    }

    @Test fun maintenance_evidence_binds_its_real_input_set_and_own_ticket() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-005/20261029-implementation-01/input")
        val temporary = Files.createTempDirectory("p006-maintenance-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "maintenance-run", root).use { it.record("load-ready", mapOf("generation" to 1L)) }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("ENG-P006-005", manifest.getString("ticket"))
            assertEquals("building-maintenance-windows-client", manifest.getString("topic"))
            val files = records(directory).single { it.getString("kind") == "input-identities" }.get("files").toList()
            val unused = setOf("manifest.json", "p005-corridor.tmx", "p005-sealed.tmx")
            val expected = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() !in unused }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) assertEquals(evidenceSha256(Path.of(row.getString("path"))), row.getString("sha256"))
        } finally { temporary.toFile().deleteRecursively() }
    }
    @Test fun economic_upgrades_identify_the_new_ticket_and_every_loaded_input() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-004/20261028-implementation-01/input")
        val temporary = Files.createTempDirectory("p006-upgrade-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "upgrades-run", root).use { it.record("load-ready", mapOf("generation" to 1L)) }
            assertEquals("ENG-P006-004", JsonReader().parse(Files.readString(directory.resolve("manifest.json"))).getString("ticket"))
            val files = records(directory).single { it.getString("kind") == "input-identities" }.get("files").toList()
            val expected = Files.walk(root).use { paths -> paths.filter {
                Files.isRegularFile(it) && it.fileName.toString() != "manifest.json" }.toList().toSet() }
            assertEquals(34, expected.size)
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (entry in files) assertEquals(evidenceSha256(Path.of(entry.getString("path"))), entry.getString("sha256"))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun economic_construction_records_every_loaded_template_and_image_under_its_own_ticket() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-003/20261027-contract-01/candidate-input-01")
        val temporary = Files.createTempDirectory("p006-economic-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "economic-run", root).use { it.record("load-ready", mapOf("generation" to 1L)) }
            assertEquals("ENG-P006-003", JsonReader().parse(Files.readString(directory.resolve("manifest.json"))).getString("ticket"))
            val files = records(directory).single { it.getString("kind") == "input-identities" }.get("files").toList()
            val expected = Files.walk(root).use { paths -> paths.filter {
                Files.isRegularFile(it) && it.fileName.toString() != "manifest.json" }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (entry in files) assertEquals(evidenceSha256(Path.of(entry.getString("path"))), entry.getString("sha256"))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun factory_construction_records_the_new_ticket_and_the_factory_metadata_hash() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-002/20261027-contract-01/candidate-input-01")
        val temporary = Files.createTempDirectory("p006-factory-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "factory-run", root).use {
                it.record("load-ready", mapOf("generation" to 1L))
            }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("ENG-P006-002", manifest.getString("ticket"))
            val files = records(directory).single { it.getString("kind") == "input-identities" }.get("files").toList()
            val factory = root.resolve("units/landFactory.properties")
            assertEquals(evidenceSha256(factory), files.single { it.getString("path") == factory.toString() }.getString("sha256"))
            val unused = setOf("manifest.json", "p005-corridor.tmx", "p005-sealed.tmx")
            val expected = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() !in unused }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun construction_evidence_binds_only_loaded_inputs_and_uses_its_own_ticket() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-001/20261027-intake-01/scene-input-01")
        val temporary = Files.createTempDirectory("p006-construction-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "construction-run", root).use {
                it.record("load-request", mapOf("generation" to 1L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 1L))
            }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("ENG-P006-001", manifest.getString("ticket"))
            assertEquals("P006", manifest.getString("phase"))
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p006-construction", identities.getString("scenarioId"))
            assertEquals(root.resolve("construction.properties").toString(), identities.get("sandboxManifest").getString("path"))
            val files = identities.get("files").toList()
            val unused = setOf("manifest.json", "p005-corridor.tmx", "p005-sealed.tmx")
            val expected = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() !in unused }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) {
                val file = Path.of(row.getString("path"))
                assertEquals(Files.size(file), row.getLong("bytes"))
                assertEquals(evidenceSha256(file), row.getString("sha256"))
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun fixed_defense_evidence_labels_005_and_identifies_every_one_of_the_eighteen_real_inputs() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-005/20261026-e-defense-input-01/input")
        val temporary = Files.createTempDirectory("p005-defense-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "defense-run", root).use {
                it.record("load-request", mapOf("generation" to 1L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 1L))
            }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("ENG-P005-005", manifest.getString("ticket"))
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p005-point-defense", identities.getString("scenarioId"))
            assertEquals(root.resolve("point-defense.properties").toString(), identities.get("sandboxManifest").getString("path"))
            val files = identities.get("files").toList()
            assertEquals(18, files.size)
            val expected = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) {
                val file = Path.of(row.getString("path"))
                assertEquals(Files.size(file), row.getLong("bytes"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                    .joinToString("") { "%02x".format(it) }, row.getString("sha256"))
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun fixed_turret_evidence_labels_003_and_identifies_the_twenty_fixed_input_files() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261026-e-turret-contract-01/input")
        val temporary = Files.createTempDirectory("p005-turret-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "turret-run", root).use {
                it.record("load-request", mapOf("generation" to 1L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 1L))
            }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("P005", manifest.getString("phase"))
            assertEquals("ENG-P005-003", manifest.getString("ticket"))
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p005-turret", identities.getString("scenarioId"))
            assertEquals(root.resolve("turret.properties").toString(), identities.get("sandboxManifest").getString("path"))
            val files = identities.get("files").toList()
            assertEquals(20, files.size)
            val expected = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) {
                val file = Path.of(row.getString("path"))
                assertEquals(Files.size(file), row.getLong("bytes"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                    .joinToString("") { "%02x".format(it) }, row.getString("sha256"))
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    private val properties = """
        format=rw-reforge.sandbox.v1
        p1.team=blue
        p1.x=110
        p1.y=110
        p1.heading=90
        p2.team=blue
        p2.x=510
        p2.y=310
        p2.heading=0
        p3.team=red
        p3.x=550
        p3.y=310
        p3.heading=0
    """.trimIndent()

    private fun fixture(root: Path, withProperties: Boolean = true): Path {
        Files.createDirectories(root.resolve("maps"))
        Files.createDirectories(root.resolve("tilesets"))
        Files.createDirectories(root.resolve("units"))
        DataOutputStream(Files.newOutputStream(root.resolve("atlas.png"))).use {
            it.writeLong(0x89504E470D0A1A0AuL.toLong())
            it.writeInt(13); it.writeInt(0x49484452); it.writeInt(20); it.writeInt(20)
        }
        val tileset = """
            <tileset name="own_test" tilewidth="20" tileheight="20" tilecount="1" columns="1">
              <image source="../atlas.png" width="20" height="20"/>
            </tileset>
        """.trimIndent()
        Files.writeString(root.resolve("tilesets/p001-ground.tsx"), tileset)
        Files.writeString(root.resolve("tilesets/p001-rock.tsx"), tileset)
        Files.writeString(root.resolve("maps/p001-controlled.tmx"), """
            <map orientation="orthogonal" renderorder="right-down" width="30" height="20" tilewidth="20" tileheight="20">
              <tileset firstgid="1" source="../tilesets/p001-ground.tsx"/>
              <tileset firstgid="2" source="../tilesets/p001-rock.tsx"/>
              <layer name="Ground" width="30" height="20"><data encoding="csv">${List(600) { "1" }.joinToString(",")}</data></layer>
            </map>
        """.trimIndent())
        Files.writeString(root.resolve("units/weaponless_tank.ini"), """
            [core]
            name: own_test_tank
            class: CustomUnitMetadata
            radius: 11
            displayRadius: 11
            fogOfWarSightRange: 3
            [graphics]
            total_frames: 1
            image: ../atlas.png
            [movement]
            movementType: LAND
            moveSpeed: 1.1
            moveAccelerationSpeed: 0.07
            moveDecelerationSpeed: 0.17
            maxTurnSpeed: 4.1
            turnAcceleration: 0.25
            moveSlidingMode: false
            moveIgnoringBody: false
        """.trimIndent())
        if (withProperties) Files.writeString(root.resolve("sandbox.properties"), properties)
        return root
    }

    private fun records(directory: Path): List<JsonValue> =
        Files.readAllLines(directory.resolve("reforge/client-diagnostic.jsonl")).map { JsonReader().parse(it) }

    @Test fun we02_new_windows_manifest_labels_the_current_phase_and_preserves_the_diagnostic_schema() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "test-run", temporary.resolve("inputs")).use { }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("p001.windows-diagnostic.v1", manifest.getString("format"))
            assertEquals("P002", manifest.getString("phase"))
            assertEquals("ENG-P002-009", manifest.getString("ticket"))
            assertEquals("03-windows-client", manifest.getString("topic"))
            assertEquals("test-run", manifest.getString("runId"))
            assertFalse(manifest.getBoolean("originalGameExecuted"))
            assertEquals("not-evaluated", manifest.getString("compatibilityVerdict"))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we03_loaded_p002_records_the_configuration_bytes_after_the_legacy_file_list() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val root = fixture(temporary.resolve("p002"))
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "p002-run", temporary.resolve("unused-initial-inputs")).use {
                it.record("load-request", mapOf("generation" to 7L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 7L))
            }
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p002-knowledge", identities.getString("scenarioId", null))
            assertEquals(root.toString(), identities.getString("root"))
            assertEquals(7L, identities.getLong("generation"))
            val files = identities.get("files").toList()
            assertEquals(root.resolve("maps/p001-controlled.tmx").toString(), files.first().getString("path"))
            val manifest = assertNotNull(identities.get("sandboxManifest"))
            assertEquals(root.resolve("sandbox.properties").toString(), manifest.getString("path"))
            assertEquals(Files.size(root.resolve("sandbox.properties")), manifest.getLong("bytes"))
            val digest = MessageDigest.getInstance("SHA-256").digest(properties.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(digest, manifest.getString("sha256"))
            assertEquals(manifest.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json),
                files.last().toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
            assertEquals(1, files.count { it.getString("path") == root.resolve("sandbox.properties").toString() })
            assertTrue(files.all { Files.isRegularFile(Path.of(it.getString("path"))) && it.has("sha256") })
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we04_absent_p001_configuration_is_explicit_without_adding_a_missing_file_to_the_legacy_list() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val root = fixture(temporary.resolve("p001"), withProperties = false)
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "p001-run", root).use {
                it.record("load-request", mapOf("generation" to 3L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 3L))
            }
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p001-windows", identities.getString("scenarioId"))
            val manifest = assertNotNull(identities.get("sandboxManifest"))
            assertFalse(manifest.isNull, "P001 must explicitly identify the absent optional configuration")
            assertEquals(root.resolve("sandbox.properties").toString(), manifest.getString("path"))
            assertFalse(manifest.getBoolean("exists"))
            val files = identities.get("files").toList()
            assertEquals(root.resolve("maps/p001-controlled.tmx").toString(), files.first().getString("path"))
            assertEquals(5, files.size)
            assertTrue(files.none { it.getString("path").endsWith("sandbox.properties") })
            assertTrue(files.all { Files.isRegularFile(Path.of(it.getString("path"))) && it.has("sha256") })
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we05_each_generation_uses_its_requested_root_and_keeps_a_single_event_sequence() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val p002 = fixture(temporary.resolve("p002"))
            val p001 = fixture(temporary.resolve("p001"), withProperties = false)
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "generations", temporary.resolve("unused")).use {
                for ((generation, root) in listOf(7L to p002, 9L to p001)) {
                    it.record("load-request", mapOf("generation" to generation, "root" to root.toString()))
                    it.record("load-ready", mapOf("generation" to generation))
                }
            }
            val rows = records(directory)
            assertEquals((0L..5L).toList(), rows.map { it.getLong("sequence") })
            val identities = rows.filter { it.getString("kind") == "input-identities" }
            assertEquals(listOf(7L, 9L), identities.map { it.getLong("generation") })
            assertEquals(listOf(p002.toString(), p001.toString()), identities.map { it.getString("root") })
            assertEquals(listOf("p002-knowledge", "p001-windows"), identities.map { it.getString("scenarioId") })
            assertTrue(identities[0].get("sandboxManifest").has("sha256"))
            assertFalse(identities[1].get("sandboxManifest").getBoolean("exists"))
            for ((index, root) in listOf(p002, p001).withIndex()) {
                assertTrue(identities[index].get("files").all { Path.of(it.getString("path")).startsWith(root) })
            }
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we06_invalid_configuration_cannot_emit_successful_identities_and_a_later_load_recovers() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val invalid = fixture(temporary.resolve("invalid"))
            Files.writeString(invalid.resolve("sandbox.properties"), properties + "\nunknown=1")
            val valid = fixture(temporary.resolve("valid"))
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "recovery", invalid).use {
                it.record("load-request", mapOf("generation" to 1L, "root" to invalid.toString()))
                assertFailsWith<IllegalArgumentException> { it.record("load-ready", mapOf("generation" to 1L)) }
                it.record("load-request", mapOf("generation" to 2L, "root" to valid.toString()))
                it.record("load-ready", mapOf("generation" to 2L))
            }
            val identities = records(directory).filter { it.getString("kind") == "input-identities" }
            assertEquals(1, identities.size)
            assertEquals(2L, identities.single().getLong("generation"))
            assertEquals(valid.toString(), identities.single().getString("root"))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we02_reusing_an_existing_run_directory_is_rejected_without_overwriting_the_manifest() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "original-run", temporary.resolve("inputs")).use { }
            val original = Files.readAllBytes(directory.resolve("manifest.json"))
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                JsonlEvidence(directory, "replacement", temporary.resolve("different-inputs"))
            }
            assertTrue(original.contentEquals(Files.readAllBytes(directory.resolve("manifest.json"))))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }
}
