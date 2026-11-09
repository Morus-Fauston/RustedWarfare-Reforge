package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class AmphibiousJetLightningVisibilityTest {
    @Test fun a_visible_lightning_origin_cannot_publish_the_hidden_endpoint() = withJetTarget(transform = { base ->
        val jet = base.unitDefinitions.getValue("amphibiousJet")
        val witness = UnitDefinition("witness", "OwnedWitness", 1.0, 1.0, 1, jet.imagePath, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), 1,
            durability = DurabilityProfile(1000.0, null))
        base.copy(unitDefinitions = base.unitDefinitions + ("witness" to witness),
            players = base.players.map { if (it.playerId == "p1") it.copy(teamId = "friends") else it } +
                Player("p3", PlayerControl.NONE, "friends"),
            units = base.units + UnitSpawn("witness", "p3", "witness", Position(500.0, 200.0), 0.0),
            combatContract = CombatContract(12345, -1, mapOf("jet" to 8L, "target" to 9L, "witness" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L, "p3" to 0L)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
    }) { context ->
        var fired = false
        for (q in 0L..60L) {
            val host = context.advance(Step(q, 1.0, "sim-tick"))
            val view = context.observePlayer(context.world, "p3")
            assertTrue(view.units.any { it.unitId == "jet" })
            assertTrue(view.units.none { it.unitId == "target" })
            if (host.projectileEvents.any { it.kind == "born" }) {
                fired = true
                assertTrue(view.projectiles.isEmpty(), "Visible origin must not disclose a hidden target position")
                assertTrue(view.projectileEvents.isEmpty())
                break
            }
        }
        assertTrue(fired)
    }
}
