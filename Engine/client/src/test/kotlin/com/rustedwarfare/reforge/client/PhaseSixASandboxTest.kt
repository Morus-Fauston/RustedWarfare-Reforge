package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.UnitObservation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PhaseSixASandboxTest {
    private val root get() = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).parent

    @Test fun the_normal_menu_rejects_invalid_maintenance_price_tokens() {
        val seed = root.resolve("scenarios/phase-six-a-json.json")
        val inputs = root.resolve("inputs/phase-six-a-sandbox/json")
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
            val start = TestScenarioChoice.open(root.resolve("scenarios/phase-six-a-$format.json"))
            val spider = TestScenarioChoice.open(root.resolve("scenarios/phase-six-a-spider-$format.json"))
            assertEquals(start.content.content.units.keys, spider.content.content.units.keys)
            assertEquals(start.root, spider.root)
            assertEquals(listOf("naval-ground-40", "construction-strip-5"),
                start.scenario.map.tileSets.map { VisualTileAtlas.from(it).supportedLayout() })
            assertEquals("commandCenter", start.content.unitId)
            assertEquals("modularSpider", spider.content.unitId)
            SandboxSession(start.scenario).use { session ->
                assertEquals(listOf("commandCenter"), session.owned().map { it.typeId })
                assertEquals(30000L, session.observation.balance)
                session.selectUnit(session.owned().single())
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                val builder = session.awaitUnit("builder", 800)
                session.selectUnit(builder)
                assertEquals(setOf("extractorT1", "fabricatorT1", "landFactory", "mechFactory", "airFactory",
                    "seaFactory", "c_turret_t1", "laserDefence", "repairbay", "experimentalLandFactory",
                    "nukeLauncherC", "antiNukeLauncherC"), session.constructionActions.map { it.typeId }.toSet())
            }
            SandboxSession(spider.scenario).use { session ->
                val chassis = session.owned().single { it.typeId == "modularSpider" }
                assertEquals(6, assertNotNull(chassis.attachmentOverview).slots.size)
                assertEquals(5000L, session.observation.balance)
                session.selectUnit(chassis)
                assertIs<ActionResult.Accepted>(session.unitAction("selectSlot1"))
                assertIs<ActionResult.Accepted>(session.unitAction("buildGun"))
                session.awaitUnit("modularSpider_gunturret", 1050)
            }
        }
    }

    @Test fun the_default_technology_chain_uses_ordinary_construction_upgrades_and_production() {
        val start = TestScenarioChoice.open(root.resolve("scenarios/phase-six-a-json.json"))
        SandboxSession(start.scenario).use { session ->
            session.selectUnit(session.owned().single())
            assertIs<ActionResult.Accepted>(session.produce("builder"))
            val builder = session.awaitUnit("builder", 800)
            fun construct(type: String, point: Position): UnitObservation {
                val price = assertNotNull(start.scenario.unitDefinitions.getValue(type).construction).price
                session.awaitCredits(price)
                session.selectUnit(session.owned().single { it.unitId == builder.unitId })
                val submission = session.build(type, point)
                assertIs<ActionResult.Accepted>(submission, "Build $type at $point: $submission / ${session.playerFeedback}")
                return session.awaitUnit(type, 5600)
            }
            fun produce(factory: UnitObservation, type: String, ticks: Int = 4800): UnitObservation {
                val price = assertNotNull(start.scenario.unitDefinitions.getValue(type).build).price
                session.awaitCredits(price)
                session.selectUnit(factory)
                val submission = session.produce(type)
                assertIs<ActionResult.Accepted>(submission, "Produce $type from ${factory.typeId}: $submission")
                return session.awaitUnit(type, ticks)
            }
            fun upgrade(factory: UnitObservation, result: String): UnitObservation {
                val price = assertNotNull(start.scenario.unitDefinitions.getValue(factory.typeId).upgrades).actions.single().price
                session.awaitCredits(price)
                session.selectUnit(factory)
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"), "Upgrade ${factory.typeId}")
                return session.awaitUnit(result, 3100).also { assertEquals(factory.unitId, it.unitId) }
            }

            val land = construct("landFactory", Position(250.0, 150.0))
            val tank = produce(land, "c_tank")
            val tankPickup = Position(280.0, 235.0)
            session.selectUnit(tank)
            assertIs<ActionResult.Accepted>(session.move(tankPickup))
            session.await(600, "tank clears the factory exit") {
                session.owned().single { it.unitId == tank.unitId }.moveTarget == null
            }
            produce(land, "hoverTank")
            val mech = construct("mechFactory", Position(370.0, 150.0))
            produce(mech, "mechGun")
            val bunker = produce(mech, "mechBunker")
            session.selectUnit(bunker)
            assertIs<ActionResult.Accepted>(session.unitAction("deploy"))
            assertEquals(bunker.unitId, session.awaitUnit("mechBunkerDeployed", 500).unitId)
            val mech2 = upgrade(mech, "mechFactoryT2")
            produce(mech2, "mechHeavyMissile")
            produce(mech2, "mechEngineer")
            produce(mech2, "mechFlame")

            val air = construct("airFactory", Position(480.0, 150.0))
            produce(air, "helicopter")
            val air2 = upgrade(air, "airFactoryT2")
            produce(air2, "amphibiousJet")
            val dropship = produce(air2, "dropship")
            session.selectUnit(dropship)
            val pickup = session.owned().single { it.unitId == tank.unitId }.position
            assertEquals(tank.unitId, session.commandTargetAt(pickup))
            val loading = session.command(pickup)
            assertIs<ActionResult.Accepted>(loading, "$loading")
            session.await(1800, "airlift from the land factory") {
                session.owned().single { it.unitId == dropship.unitId }.transport?.passengerIds == listOf(tank.unitId)
            }
            assertTrue(session.owned().none { it.unitId == tank.unitId })
            assertIs<ActionResult.Accepted>(session.move(Position(320.0, 240.0)))
            session.await(1500, "dropship arrives at the landing site") {
                session.owned().single { it.unitId == dropship.unitId }.moveTarget == null
            }
            assertIs<ActionResult.Accepted>(session.unload(true))
            session.await(600, "tank returns to the ground under its original ID") {
                session.owned().any { it.unitId == tank.unitId && it.transportExit?.previousCarrierId == dropship.unitId }
            }
            produce(air2, "aaBeamGunship")
            produce(air2, "bomber")
            session.selectUnit(session.owned().single { it.unitId == builder.unitId })
            assertIs<ActionResult.Accepted>(session.reclaim(air2.unitId))
            var beforeReclaim = session.observation
            session.await(5000, "reclaim the upgraded air factory") {
                val current = session.observation
                if (current.events.any { it.kind == "building-reclaimed" && it.unitId == air2.unitId }) true
                else { beforeReclaim = current; false }
            }
            assertTrue(session.owned().none { it.unitId == air2.unitId })
            val payout = assertNotNull(session.observation.credits) - assertNotNull(beforeReclaim.credits)
            val refund = 2500.0 * .8f.toDouble()
            // Ordinary periodic income may land in the same simulation step.
            assertTrue(payout == refund || payout == refund + 4.5, "Terminal refund: $payout")

            val sea = construct("seaFactory", Position(651.0, 150.0))
            produce(sea, "attackSubmarine")
            produce(sea, "hovercraft")
            val sea2 = upgrade(sea, "seaFactoryT2")
            produce(sea2, "heavyBattleship")
            produce(sea2, "nautilusSubmarine")
            val experimental = construct("experimentalLandFactory", Position(480.0, 300.0))
            produce(experimental, "nautilusSubmarineLand")

            construct("repairbay", Position(250.0, 290.0))
            construct("laserDefence", Position(140.0, 290.0))
            construct("fabricatorT1", Position(350.0, 270.0))
            construct("antiNukeLauncherC", Position(135.0, 400.0))
            construct("nukeLauncherC", Position(510.0, 480.0))
            assertTrue(session.owned().map { it.typeId }.containsAll(setOf("c_tank", "hoverTank",
                "mechBunkerDeployed", "mechHeavyMissile", "mechEngineer", "mechFlame", "amphibiousJet", "dropship",
                "aaBeamGunship", "bomber", "attackSubmarine", "hovercraft", "nautilusSubmarine",
                "nautilusSubmarineLand", "repairbay", "laserDefence", "antiNukeLauncherC", "nukeLauncherC")))
        }
    }

    private fun SandboxSession.owned() = observation.units.filter { it.ownerPlayerId == "p1" && it.alive }

    private fun SandboxSession.selectUnit(unit: UnitObservation) {
        select(Position(unit.position.x, unit.position.y - unit.height))
        assertEquals(unit.unitId, selectedId, unit.typeId)
    }

    private fun SandboxSession.awaitUnit(type: String, maximum: Int): UnitObservation {
        fun match() = owned().firstOrNull { it.typeId == type && it.constructionProgress == 1f }
        await(maximum, "$type birth or completion") { match() != null }
        return assertNotNull(match())
    }

    private fun SandboxSession.awaitCredits(price: Long) =
        await(120000, "ordinary income for $price") { (observation.balance ?: 0L) >= price }

    private fun SandboxSession.await(maximum: Int, label: String, ready: () -> Boolean) {
        repeat(maximum) {
            if (ready()) return
            tick()
        }
        assertTrue(ready(), "$label within $maximum steps, credits=${observation.credits}, feedback=$playerFeedback")
    }
}
