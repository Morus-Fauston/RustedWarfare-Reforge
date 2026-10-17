package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class CoreAdapterLifecycleTest {
    @Test fun `close and reopen sample a fresh world with no pending movement`() {
        val fixture = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/fixtures/record-chain/scenario.json")
        val input = ScenarioReader.load(fixture, fixture.resolveSibling("contract.json"))
        CoreAdapter(input).use { core ->
            val initial = core.initial()
            core.submit(input.actions[1])
            core.advance(input.steps.first())
            core.close()
            assertFailsWith<IllegalStateException> { core.observe() }
            val reopened = core.reopen()
            assertNotEquals(initial.worldId, reopened.worldId)
            assertEquals(initial.units, reopened.units)
            assertTrue(reopened.events.isEmpty())
            assertTrue(reopened.actionResults.isEmpty())
            assertEquals(0.0, reopened.simulationTime)
        }
    }
}
