package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.UnitObservation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PhaseSixASandboxTest {
    @Test fun the_normal_menu_rejects_invalid_maintenance_price_tokens() {
        val seed = phaseSixFixtureRoot.resolve("scenarios/phase-six-a-json.json")
        val inputs = phaseSixFixtureRoot.resolve("inputs/phase-six-a-sandbox/json")
        val source = Files.readString(seed)
        val path = Files.createTempFile("reforge-phase-six-a-price-", ".json")
        try {
            for (token in listOf("700.5", "\"700\"", "null", "0", "9223372036854775808")) {
                val changed = source.replace("\"landFactory\": 700,", "\"landFactory\": $token,")
                assertNotEquals(source, changed)
                Files.writeString(path, changed)
                assertContains(assertFails { TestScenarioChoice.open(path, inputs) }.message.orEmpty(),
                    "maintenanceContract.basePrices.landFactory", message = token)
            }
            val missing = source.replace("      \"landFactory\": 700,\n", "")
            assertNotEquals(source, missing)
            Files.writeString(path, missing)
            assertContains(assertFails { TestScenarioChoice.open(path, inputs) }.message.orEmpty(),
                "maintenanceContract.basePrices.landFactory")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test fun both_normal_starts_share_the_catalogue_in_all_three_formats() {
        for (format in listOf("ini", "json", "hjson")) {
            val start = TestScenarioChoice.open(phaseSixFixtureRoot.resolve("scenarios/phase-six-a-$format.json"))
            val spider = TestScenarioChoice.open(phaseSixFixtureRoot.resolve("scenarios/phase-six-a-spider-$format.json"))
            assertEquals(start.content.content.units.keys, spider.content.content.units.keys)
            assertEquals(start.root, spider.root)
            assertEquals(listOf("naval-ground-40", "construction-strip-5"),
                start.scenario.map.tileSets.map { VisualTileAtlas.from(it).supportedLayout() })
            assertEquals("commandCenter", start.content.unitId)
            assertEquals("modularSpider", spider.content.unitId)
            SandboxSession(start.scenario).use { session ->
                assertEquals(listOf("commandCenter"), session.phaseSixOwned().map { it.typeId })
                assertEquals(30000L, session.observation.balance)
                session.phaseSixSelectUnit(session.phaseSixOwned().single())
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                val builder = session.phaseSixAwaitUnit("builder", 800)
                session.phaseSixSelectUnit(builder)
                assertEquals(setOf("extractorT1", "fabricatorT1", "landFactory", "mechFactory", "airFactory",
                    "seaFactory", "c_turret_t1", "laserDefence", "repairbay", "experimentalLandFactory",
                    "nukeLauncherC", "antiNukeLauncherC"), session.constructionActions.map { it.typeId }.toSet())
            }
            SandboxSession(spider.scenario).use { session ->
                val chassis = session.phaseSixOwned().single { it.typeId == "modularSpider" }
                assertEquals(6, assertNotNull(chassis.attachmentOverview).slots.size)
                assertEquals(5000L, session.observation.balance)
                session.phaseSixSelectUnit(chassis)
                assertIs<ActionResult.Accepted>(session.unitAction("selectSlot1"))
                assertIs<ActionResult.Accepted>(session.unitAction("buildGun"))
                session.phaseSixAwaitUnit("modularSpider_gunturret", 1050)
            }
        }
    }

}

internal val phaseSixFixtureRoot get() = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).parent

internal fun SandboxSession.phaseSixOwned() = observation.units.filter { it.ownerPlayerId == "p1" && it.alive }

internal fun SandboxSession.phaseSixSelectUnit(unit: UnitObservation) {
    select(Position(unit.position.x, unit.position.y - unit.height))
    assertEquals(unit.unitId, selectedId, unit.typeId)
}

internal fun SandboxSession.phaseSixAwaitUnit(type: String, maximum: Int): UnitObservation {
    fun match() = phaseSixOwned().firstOrNull { it.typeId == type && it.constructionProgress == 1f }
    phaseSixAwait(maximum, "$type birth or completion") { match() != null }
    return assertNotNull(match())
}

internal fun SandboxSession.phaseSixAwait(maximum: Int, label: String, ready: () -> Boolean) {
    repeat(maximum) {
        if (ready()) return
        tick()
    }
    assertTrue(ready(), "$label within $maximum steps, credits=${observation.credits}, feedback=$playerFeedback")
}
