package com.rustedwarfare.reforge.core.navigation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Static package-isolation and anti-whitelist architecture guard for `core.navigation`
 * (SPEC §2.1 / ADR-016 / ADR-012 / ENG-P017-006).
 */
class NavigationModuleArchitectureGuardTest {
    private val blockComments = Regex("/\\*[\\s\\S]*?\\*/")
    private val lineComments = Regex("//.*")

    private fun stripComments(source: String): String =
        source.replace(blockComments, "").replace(lineComments, "")

    private fun resolveCoreModuleRoot(): Path {
        val direct = Path.of("src/main/kotlin").toAbsolutePath().parent?.parent
        if (direct != null && Files.isDirectory(direct.resolve("src/main/kotlin/com/rustedwarfare/reforge/core"))) {
            return direct
        }
        val repoRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("Engine/core/src/main/kotlin")) }
            ?: error("Could not locate Engine/core module root from working directory ${Path.of("").toAbsolutePath()}")
        return repoRoot.resolve("Engine/core")
    }

    @Test
    fun core_navigation_package_has_zero_business_task_or_unit_id_reverse_coupling() {
        val coreRoot = resolveCoreModuleRoot()
        val navDir = coreRoot.resolve("src/main/kotlin/com/rustedwarfare/reforge/core/navigation")
        assertTrue(Files.isDirectory(navDir), "core.navigation package directory must exist")

        val ktFiles = Files.list(navDir).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.sorted().toList()
        }
        assertTrue(ktFiles.size >= 30, "core.navigation must consolidate navigation & pathing files (found ${ktFiles.size})")

        // Old core.path package must be physically removed in BOTH src/main and src/test.
        val legacyMainPathDir = coreRoot.resolve("src/main/kotlin/com/rustedwarfare/reforge/core/path")
        val legacyTestPathDir = coreRoot.resolve("src/test/kotlin/com/rustedwarfare/reforge/core/path")
        assertFalse(Files.exists(legacyMainPathDir), "Legacy src/main/.../core/path directory must be removed")
        assertFalse(Files.exists(legacyTestPathDir), "Legacy src/test/.../core/path directory must be removed")

        val forbiddenBusinessTokens = listOf(
            "UnitCombat",
            "SubmarineCombat",
            "OrdinaryWeapon",
            "NamedWeaponRuntime",
            "GroundWeapon",
            "PointDefense",
            "NuclearOrdnance",
            "ProjectileInterception",
            "ProjectileProjection",
            "BallisticFlight",
            "MissileTargetIndex",
            "CombatTargetQuery",
            "UnitShield",
            "WeaponEnergy",
            "BuildingWork",
            "ConstructionPlacement",
            "ConstructionRetryRules",
            "ProductionLedger",
            "OrdinaryUpgrades",
            "UpgradeQueues",
            "UpgradeRules",
            "MaintenanceRules",
            "RepairBay",
            "CustomUnitActions",
            "AaBeamGunship",
            "MechEngineer",
            "Bunker",
            "Blink",
            "UnitAttachments",
            "FactoryExit",
            "Transport",
            "SimulationContext",
            "BuildingTerrain",
            "CandidateStep",
            "StepState",
            "UnitTaskKind",
            "UnitTaskQueue",
        )
        val businessRegexes = forbiddenBusinessTokens.map { token -> token to Regex("\\b$token\\b") }

        val forbiddenUnitIdLiterals = listOf(
            "\"attackSubmarine\"",
            "\"amphibiousJet\"",
            "\"nautilusSubmarine\"",
            "\"nautilusSubmarineLand\"",
            "\"nautilusSubmarineSurface\"",
            "\"heavyBattleship\"",
            "\"hovercraft\"",
            "\"dropship\"",
            "\"seaFactory\"",
            "\"seaFactoryT2\"",
            "\"mechGun\"",
            "\"tank\"",
            "\"builder\"",
        )

        for (file in ktFiles) {
            val name = file.fileName.toString()
            val codeOnly = stripComments(Files.readString(file))
            assertTrue(
                codeOnly.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ==
                    "package com.rustedwarfare.reforge.core.navigation",
                "$name must declare package com.rustedwarfare.reforge.core.navigation",
            )
            for ((token, regex) in businessRegexes) {
                assertFalse(
                    regex.containsMatchIn(codeOnly),
                    "core.navigation/$name must not depend on upper business/simulation class '$token'",
                )
            }
            assertFalse(".tasks" in codeOnly, "core.navigation/$name must not inspect unit.tasks directly")
            assertFalse(".taskQueue" in codeOnly, "core.navigation/$name must not touch unit.taskQueue directly")
            for (literal in forbiddenUnitIdLiterals) {
                assertFalse(
                    literal in codeOnly,
                    "core.navigation/$name must not match specific unit ID literal $literal",
                )
            }
        }
    }

    @Test
    fun motion_controllers_contain_zero_runtime_unit_id_whitelists() {
        val coreRoot = resolveCoreModuleRoot()
        val simDir = coreRoot.resolve("src/main/kotlin/com/rustedwarfare/reforge/core/sim")
        val pureMotionFiles = listOf("WaterBody.kt", "NautilusMotion.kt", "BodyMotion.kt", "HoverMotion.kt", "FlightMotion.kt")
        val forbiddenUnitIdLiterals = listOf(
            "\"heavyBattleship\"",
            "\"nautilusSubmarineLand\"",
            "\"nautilusSubmarineSurface\"",
            "\"nautilusSubmarine\"",
            "\"hovercraft\"",
            "\"dropship\"",
            "\"attackSubmarine\"",
            "\"amphibiousJet\"",
        )
        for (name in pureMotionFiles) {
            val file = simDir.resolve(name)
            assertTrue(Files.isRegularFile(file), "Expected $name in core/sim")
            val codeOnly = stripComments(Files.readString(file))
            for (literal in forbiddenUnitIdLiterals) {
                assertFalse(literal in codeOnly, "$name must not hardcode unit ID literal $literal")
            }
        }
        // Runtime paths of SubmarineMotion & AmphibiousJetMotion (outside native marker qualification supports(...))
        // must also remain 100% free of unit-ID literals.
        for (name in listOf("SubmarineMotion.kt", "AmphibiousJetMotion.kt")) {
            val file = simDir.resolve(name)
            assertTrue(Files.isRegularFile(file), "Expected $name in core/sim")
            val codeOnly = stripComments(Files.readString(file))
            val runtimeOnly = codeOnly.replace(
                Regex("fun supports\\([\\s\\S]*?(?=\\n    (?:override )?fun |\\n\\})"),
                "",
            )
            for (literal in forbiddenUnitIdLiterals) {
                assertFalse(literal in runtimeOnly, "$name runtime methods must not hardcode unit ID literal $literal")
            }
        }
    }
}
