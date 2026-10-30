package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** The PR mechanism registry excludes this continuous scenario; --kind long uses its CLI contract. */
class ProductionCombatLongScenarioTest {
    @Test fun `long combat consumes the same real product and ends the target without resetting money`() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = Files.createTempDirectory("production-long-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/production-combat-chain.json"),
            root.resolve("contracts/production-combat-chain.json"), output, listOf("production-long")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
    }
}
