package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.UnitObservation
import kotlin.test.*

class PhaseSixATechnologyChainTest {
    @Test fun the_default_technology_chain_uses_ordinary_construction_upgrades_and_production() {
        val start = TestScenarioChoice.open(phaseSixFixtureRoot.resolve("scenarios/phase-six-a-json.json"))
        SandboxSession(start.scenario).use { session ->
            session.phaseSixSelectUnit(session.phaseSixOwned().single())
            assertIs<ActionResult.Accepted>(session.produce("builder"))
            val builder = session.phaseSixAwaitUnit("builder", 800)
            fun construct(type: String, point: Position): UnitObservation {
                val price = assertNotNull(start.scenario.unitDefinitions.getValue(type).construction).price
                session.awaitCredits(price)
                session.phaseSixSelectUnit(session.phaseSixOwned().single { it.unitId == builder.unitId })
                val submission = session.build(type, point)
                assertIs<ActionResult.Accepted>(submission, "Build $type at $point: $submission / ${session.playerFeedback}")
                return session.phaseSixAwaitUnit(type, 5600)
            }
            fun produce(factory: UnitObservation, type: String, ticks: Int = 4800): UnitObservation {
                val price = assertNotNull(start.scenario.unitDefinitions.getValue(type).build).price
                session.awaitCredits(price)
                session.phaseSixSelectUnit(factory)
                val submission = session.produce(type)
                assertIs<ActionResult.Accepted>(submission, "Produce $type from ${factory.typeId}: $submission")
                return session.phaseSixAwaitUnit(type, ticks)
            }
            fun upgrade(factory: UnitObservation, result: String): UnitObservation {
                val price = assertNotNull(start.scenario.unitDefinitions.getValue(factory.typeId).upgrades).actions.single().price
                session.awaitCredits(price)
                session.phaseSixSelectUnit(factory)
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"), "Upgrade ${factory.typeId}")
                return session.phaseSixAwaitUnit(result, 3100).also { assertEquals(factory.unitId, it.unitId) }
            }

            val land = construct("landFactory", Position(250.0, 150.0))
            val tank = produce(land, "c_tank")
            val tankPickup = Position(280.0, 235.0)
            session.phaseSixSelectUnit(tank)
            assertIs<ActionResult.Accepted>(session.move(tankPickup))
            session.phaseSixAwait(600, "tank clears the factory exit") {
                session.phaseSixOwned().single { it.unitId == tank.unitId }.moveTarget == null
            }
            produce(land, "hoverTank")
            val mech = construct("mechFactory", Position(370.0, 150.0))
            produce(mech, "mechGun")
            val bunker = produce(mech, "mechBunker")
            session.phaseSixSelectUnit(bunker)
            assertIs<ActionResult.Accepted>(session.unitAction("deploy"))
            assertEquals(bunker.unitId, session.phaseSixAwaitUnit("mechBunkerDeployed", 500).unitId)
            val mech2 = upgrade(mech, "mechFactoryT2")
            produce(mech2, "mechHeavyMissile")
            produce(mech2, "mechEngineer")
            produce(mech2, "mechFlame")

            val air = construct("airFactory", Position(480.0, 150.0))
            produce(air, "helicopter")
            val air2 = upgrade(air, "airFactoryT2")
            produce(air2, "amphibiousJet")
            val dropship = produce(air2, "dropship")
            session.phaseSixSelectUnit(dropship)
            val pickup = session.phaseSixOwned().single { it.unitId == tank.unitId }.position
            assertEquals(tank.unitId, session.commandTargetAt(pickup))
            val loading = session.command(pickup)
            assertIs<ActionResult.Accepted>(loading, "$loading")
            session.phaseSixAwait(1800, "airlift from the land factory") {
                session.phaseSixOwned().single { it.unitId == dropship.unitId }.transport?.passengerIds == listOf(tank.unitId)
            }
            assertTrue(session.phaseSixOwned().none { it.unitId == tank.unitId })
            assertIs<ActionResult.Accepted>(session.move(Position(320.0, 240.0)))
            session.phaseSixAwait(1500, "dropship arrives at the landing site") {
                session.phaseSixOwned().single { it.unitId == dropship.unitId }.moveTarget == null
            }
            assertIs<ActionResult.Accepted>(session.unload(true))
            session.phaseSixAwait(600, "tank returns to the ground under its original ID") {
                session.phaseSixOwned().any { it.unitId == tank.unitId && it.transportExit?.previousCarrierId == dropship.unitId }
            }
            produce(air2, "aaBeamGunship")
            produce(air2, "bomber")
            session.phaseSixSelectUnit(session.phaseSixOwned().single { it.unitId == builder.unitId })
            assertIs<ActionResult.Accepted>(session.reclaim(air2.unitId))
            var beforeReclaim = session.observation
            session.phaseSixAwait(5000, "reclaim the upgraded air factory") {
                val current = session.observation
                if (current.events.any { it.kind == "building-reclaimed" && it.unitId == air2.unitId }) true
                else { beforeReclaim = current; false }
            }
            assertTrue(session.phaseSixOwned().none { it.unitId == air2.unitId })
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
            assertTrue(session.phaseSixOwned().map { it.typeId }.containsAll(setOf("c_tank", "hoverTank",
                "mechBunkerDeployed", "mechHeavyMissile", "mechEngineer", "mechFlame", "amphibiousJet", "dropship",
                "aaBeamGunship", "bomber", "attackSubmarine", "hovercraft", "nautilusSubmarine",
                "nautilusSubmarineLand", "repairbay", "laserDefence", "antiNukeLauncherC", "nukeLauncherC")))
        }
    }

    private fun SandboxSession.awaitCredits(price: Long) =
        phaseSixAwait(120000, "ordinary income for $price") { (observation.balance ?: 0L) >= price }
}
