package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class AmphibiousJetLightningTest {
    @Test fun lightning_hits_on_the_launch_update_once_and_keeps_its_visible_lifetime() {
        withJetTarget { context ->
            var hit: Observation? = null
            for (q in 0L..80L) {
                val row = context.advance(Step(q, 1.0, "sim-tick"))
                if (row.projectileEvents.any { it.kind == "born" }) { hit = row; break }
            }
            val first = assertNotNull(hit)
            val born = first.projectileEvents.single { it.kind == "born" }.projectile
            assertEquals("left", born.sourceWeaponName)
            assertEquals(955.0, first.units.single { it.unitId == "target" }.health,
                "Instant lightning must hit on its launch update, before ordinary flight could reach this target")
            assertEquals(45.0, first.projectileEvents.single { it.kind == "impact" }.damage)
            val retained = first.projectiles.single { it.objectId == born.objectId }
            assertEquals(LightningSegment(Position(580.0, 200.0), 0.0), retained.lightning)
            assertEquals(247, retained.appearance.red)
            assertEquals(212, retained.appearance.green)
            assertEquals(129, retained.appearance.blue)
            val publicShot = context.observePlayer(context.world, "p1").projectiles.single()
            assertEquals(retained.lightning, publicShot.lightning)
            assertEquals(retained.lightning, context.observePlayer(context.world, "p1").projectileEvents.single { it.kind == "impact" }.lightning)
            assertEquals(born.position, retained.position)
            assertEquals(9.0, retained.remainingLife)
            assertEquals(.2f, first.soundTriggers.single().volume)
            for (q in first.stepIndex + 1..first.stepIndex + 9) {
                val row = context.advance(Step(q, 1.0, "sim-tick"))
                assertTrue(row.projectileEvents.none { it.kind == "impact" && it.projectile.objectId == born.objectId })
                assertEquals(955.0, row.units.single { it.unitId == "target" }.health)
            }
            assertTrue(context.observe().projectiles.none { it.objectId == born.objectId })
        }
    }
}

internal fun withJetTarget(targetDefinition: UnitDefinition? = null, sourcePosition: Position = Position(500.0, 200.0),
                          targetPosition: Position? = null,
                          transform: (Scenario) -> Scenario = { it },
                          check: (SimulationContext) -> Unit) = WaterTestScene.withMap { map ->
    val base = AmphibiousJetScene.scenario()
    val jet = base.unitDefinitions.getValue("amphibiousJet")
    val target = targetDefinition ?: UnitDefinition("target", "OwnedTarget", 6.0, 6.0, 1, jet.imagePath, 1.0,
        MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
        durability = DurabilityProfile(1000.0, null))
    val context = AmphibiousJetScene.open(transform(base.copy(map = map,
        unitDefinitions = base.unitDefinitions + (target.id to target),
        units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", sourcePosition, 90.0),
            UnitSpawn("target", "p2", target.id, targetPosition ?: Position(sourcePosition.x + 80.0, sourcePosition.y), 0.0)),
        combatContract = CombatContract(12345, -1, mapOf("jet" to 8L, "target" to 9L), 10L))))
    try { check(context) } finally { context.close() }
}
