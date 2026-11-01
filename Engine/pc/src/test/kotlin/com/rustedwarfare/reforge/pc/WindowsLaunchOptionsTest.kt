package com.rustedwarfare.reforge.pc

import java.nio.file.Files
import com.rustedwarfare.reforge.client.ControlledScenario
import com.rustedwarfare.reforge.client.SandboxSession
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertSame

class WindowsLaunchOptionsTest {
    @Test fun packaged_default_can_produce_its_first_worker_build_and_upgrade_from_the_finite_start() {
        val root = requireNotNull(WindowsLaunchOptions.parse(emptyArray(),
            java.nio.file.Path.of("").toAbsolutePath()).inputs)
        SandboxSession(ControlledScenario.load(root)).use { session ->
            var steps = 0
            fun until(label: String, condition: () -> Boolean) {
                while (!condition() && steps < 20000) { session.tick(); steps++ }
                assertTrue(condition(), "$label did not finish by step $steps")
            }
            assertEquals(1750.0, session.observation.credits)
            assertTrue(session.observation.units.none { it.typeId == "builder" })
            session.select(Position(110.0, 210.0))
            assertIs<ActionResult.Accepted>(session.produce("builder"))
            until("first worker") { session.observation.units.any { it.typeId == "builder" && it.alive } }
            val worker = session.observation.units.single { it.typeId == "builder" && it.alive }
            session.select(worker.position)
            assertIs<ActionResult.Accepted>(session.build("extractorT1", Position(190.0, 290.0)))
            until("completed extractor") { session.observation.units.any {
                it.typeId == "extractorT1" && it.alive && it.constructionProgress == 1f
            } }
            val extractor = session.observation.units.single { it.typeId == "extractorT1" && it.alive }
            session.select(extractor.position)
            val upgrade = session.selectedUpgrades.single { it.targetTypeId == "extractorT2" }
            until("earned upgrade price") { session.observation.credits!! >= upgrade.price }
            assertIs<ActionResult.Accepted>(session.upgrade(upgrade.actionKey))
            until("completed paid upgrade") { session.observation.units.any {
                it.unitId == extractor.unitId && it.typeId == "extractorT2" && it.alive
            } }
            assertEquals(1, session.observation.units.count { it.typeId == "builder" && it.alive })
            println("packaged-default-first-economic-chain-steps=$steps")
        }
    }

    @Test fun we01_default_sandbox_packages_the_single_center_phase_four_chain() {
        val repository = Files.createTempDirectory("reforge-windows-launch-test-")
        try {
            val options = WindowsLaunchOptions.parse(emptyArray(), repository)
            assertEquals("sandbox", options.mode)
            val root = requireNotNull(options.inputs)
            assertTrue(Files.isRegularFile(root.resolve("construction.properties")),
                "Default startup must include the owned phase-four construction inputs")
            val scene = ControlledScenario.load(root)
            SandboxSession(scene).use { session ->
                assertEquals(1750.0, session.observation.credits)
                assertEquals(listOf("commandCenter"), session.observation.units
                    .filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            }
            assertEquals(listOf("extractorT1", "landFactory", "fabricatorT1", "c_turret_t1", "laserDefence"),
                scene.unitDefinitions.getValue("builder").builder!!.buildingTypeIds)
            assertEquals(listOf("builder", "c_tank"), scene.unitDefinitions.getValue("landFactory").production!!.unitTypeIds)
            assertTrue(setOf("extractorT3", "fabricatorT3", "c_turret_t2_gun", "c_turret_t1_artillery")
                .all { it in scene.unitDefinitions })
            assertEquals(repository.resolve("Engine/pc/build/sandbox-runs"), options.evidence.parent)
            assertEquals("RustedWarfare-Reforge | Phase IV sandbox", options.title)
            assertEquals(options.runId, options.evidence.fileName.toString())
            assertTrue(Regex("run-\\d{8}T\\d{9}Z-[a-f0-9]{8}").matches(options.runId))
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test fun we01_explicit_p001_and_evidence_paths_are_preserved() {
        val repository = Files.createTempDirectory("reforge-windows-launch-test-")
        try {
            val explicitInputs = repository.resolve("P001 inputs")
            val explicitEvidence = repository.resolve("review/run")
            val options = WindowsLaunchOptions.parse(arrayOf("--inputs", explicitInputs.toString(),
                "--evidence", explicitEvidence.toString()), repository)
            assertEquals(explicitInputs, options.inputs)
            assertEquals("RustedWarfare-Reforge | P002 Windows sandbox", options.title)
            assertEquals(explicitEvidence, options.evidence)
            val relative = WindowsLaunchOptions.parse(arrayOf("--inputs", "old/../P001 inputs"), repository)
            assertEquals(explicitInputs, relative.inputs)
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test fun we01_demo_and_invalid_argument_rules_remain_explicit() {
        val repository = Files.createTempDirectory("reforge-windows-launch-test-")
        try {
            val demo = WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), repository)
            assertEquals("demo", demo.mode)
            assertNull(demo.inputs)
            assertEquals("RustedWarfare-Reforge | Field Demo", demo.title)
            assertEquals(repository.resolve("Engine/pc/build/demo-runs"), demo.evidence.parent)
            assertFailsWith<IllegalArgumentException> {
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo", "--inputs", "anything"), repository)
            }
            assertFailsWith<IllegalArgumentException> { WindowsLaunchOptions.parse(arrayOf("--inputs"), repository) }
            assertFailsWith<IllegalArgumentException> { WindowsLaunchOptions.parse(arrayOf("--other", "x"), repository) }
            assertFailsWith<IllegalArgumentException> { WindowsLaunchOptions.parse(arrayOf("--mode", "other"), repository) }
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test fun default_input_files_survive_match_close_and_the_next_match_starts_with_a_fresh_wallet() {
        val repository = Files.createTempDirectory("reforge-default-reopen-")
        try {
            val root = requireNotNull(WindowsLaunchOptions.parse(emptyArray(), repository).inputs)
            SandboxSession(ControlledScenario.load(root)).use { session ->
                session.select(Position(110.0, 210.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                assertEquals(1250.0, session.observation.credits)
            }
            assertTrue(Files.isRegularFile(root.resolve("construction.properties")))
            val reopened = requireNotNull(WindowsLaunchOptions.parse(emptyArray(), repository).inputs)
            assertEquals(root, reopened)
            SandboxSession(ControlledScenario.load(reopened)).use { session ->
                assertEquals(1750.0, session.observation.credits)
                assertEquals(listOf("commandCenter"), session.observation.units
                    .filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            }
        } finally {
            Files.delete(repository)
        }
    }

    @Test fun a_missing_resource_removes_only_its_own_new_directory() {
        val parent = Files.createTempDirectory("reforge-default-missing-").toRealPath()
        val unrelated = parent.resolve("preserved.txt")
        Files.writeString(unrelated, "preserve")
        try {
            val failure = assertFailsWith<IllegalStateException> { PhaseFourInputs.extract({ null }, parent) }
            assertTrue(failure.message.orEmpty().startsWith("Missing packaged construction resource: phase4/"))
            assertEquals("preserve", Files.readString(unrelated))
            Files.list(parent).use { assertEquals(listOf(unrelated), it.toList()) }
        } finally {
            Files.delete(unrelated)
            Files.delete(parent)
        }
    }

    @Test fun a_partial_resource_read_preserves_the_first_failure_and_removes_the_partial_file() {
        val parent = Files.createTempDirectory("reforge-default-read-").toRealPath()
        val first = IOException("controlled resource read failure")
        try {
            val actual = assertFailsWith<IOException> {
                PhaseFourInputs.extract({ object : InputStream() {
                    override fun read(): Int = throw first
                } }, parent)
            }
            assertSame(first, actual)
            Files.list(parent).use { assertEquals(0L, it.count()) }
        } finally {
            Files.delete(parent)
        }
    }
}
