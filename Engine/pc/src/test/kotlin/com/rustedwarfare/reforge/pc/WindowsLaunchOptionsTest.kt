package com.rustedwarfare.reforge.pc

import java.nio.file.Files
import com.rustedwarfare.reforge.client.SandboxSession
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import com.rustedwarfare.reforge.client.VisualAssetSource
import kotlin.test.assertIs

class WindowsLaunchOptionsTest {
    @Test fun a_default_launch_uses_the_paired_phase_six_scene_and_a_broken_default_disables_start() {
        val root = java.nio.file.Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).parent.parent.parent
        val options = WindowsLaunchOptions.parse(emptyArray(), root)
        assertEquals("RustedWarfare-Reforge | Phase VI A sandbox", options.title)
        val seed = root.resolve("SimulationTest/public/scenarios/phase-six-a-json.json")
        assertEquals(seed, options.testScenarioSeed)
        val menu = com.rustedwarfare.reforge.client.TestScenarioMenu()
        menu.choose(seed)
        assertTrue(menu.canStart)
        val choice = requireNotNull(menu.choice)
        assertEquals(choice.root, options.inputs)
        assertEquals("commandCenter", choice.content.unitId)
        assertTrue(setOf("modularSpider", "mechFlame", "bomber", "nautilusSubmarine", "antiNukeLauncherC")
            .all(choice.content.content.units::containsKey))
        menu.choose(root.resolve("SimulationTest/public/scenarios/missing-phase-five.json"))
        assertFalse(menu.canStart)
        assertNull(menu.choice)
        assertTrue(menu.selected)
    }
    @Test fun visual_flags_are_explicit_temporary_overrides_with_resolved_paths_and_no_ambiguous_pairs() {
        val repository = Files.createTempDirectory("visual-cli-")
        try {
            val defaults = WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), repository)
            assertNull(defaults.visualSource)
            assertNull(defaults.originalAssets)
            assertFalse(defaults.hasVisualOverride)
            val original = WindowsLaunchOptions.parse(arrayOf("--mode", "demo", "--original-assets", "原版 assets"), repository)
            assertEquals(VisualAssetSource.ORIGINAL, original.visualSource)
            assertEquals(repository.resolve("原版 assets"), original.originalAssets)
            assertTrue(original.hasVisualOverride)
            assertEquals(VisualAssetSource.PLACEHOLDER, WindowsLaunchOptions.parse(arrayOf("--mode", "demo",
                "--visuals", "placeholder"), repository).visualSource)
            for (flags in listOf(arrayOf("--visuals", "unknown"),
                arrayOf("--visuals", "placeholder", "--original-assets", "images"),
                arrayOf("--visuals", "original", "--visuals", "original"),
                arrayOf("--original-assets", "a", "--original-assets", "b"))) {
                assertFailsWith<IllegalArgumentException> { WindowsLaunchOptions.parse(arrayOf("--mode", "demo", *flags), repository) }
            }
        } finally { repository.toFile().deleteRecursively() }
    }
    @Test fun a_paired_default_can_reopen_with_a_fresh_wallet_and_keeps_its_files() {
        val repository = java.nio.file.Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).parent.parent.parent
        val options = WindowsLaunchOptions.parse(emptyArray(), repository)
        val menu = com.rustedwarfare.reforge.client.TestScenarioMenu()
        menu.choose(requireNotNull(options.testScenarioSeed))
        val choice = requireNotNull(menu.choice)
        SandboxSession(choice.scenario).use { session ->
            session.select(Position(110.0, 150.0))
            assertIs<ActionResult.Accepted>(session.produce("builder"))
            assertEquals(29500.0, session.observation.credits)
        }
        choice.verifyStable()
        menu.choose(requireNotNull(options.testScenarioSeed))
        SandboxSession(requireNotNull(menu.choice).scenario).use { session ->
            assertEquals(30000.0, session.observation.credits)
            assertEquals(listOf("commandCenter"), session.observation.units
                .filter { it.ownerPlayerId == "p1" }.map { it.typeId })
        }
        assertEquals(repository.resolve("Engine/pc/build/sandbox-runs"), options.evidence.parent)
        assertEquals(options.runId, options.evidence.fileName.toString())
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

}
