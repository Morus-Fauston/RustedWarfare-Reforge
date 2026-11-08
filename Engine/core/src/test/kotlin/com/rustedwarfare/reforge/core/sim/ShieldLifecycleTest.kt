package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class ShieldLifecycleTest {
    @Test fun a_second_shot_id_overflow_rolls_back_spent_shield_regeneration_and_player_history() {
        val extend: (Scenario) -> Scenario = { base ->
            val source = base.unitDefinitions.getValue("mechHeavyMissile")
            val passive = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
                source.totalFrames, source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed,
                source.fogOfWarSightRange, durability = DurabilityProfile(2500.0, null), mech = source.mech,
                shield = source.shield, shieldImagePath = source.shieldImagePath)
            val original = base.unitDefinitions.getValue("c_tank")
            val probe = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
                original.fogOfWarSightRange, combat = original.combat!!.copy(shootDelay = 100.0))
            base.copy(unitDefinitions = mapOf(passive.id to passive, probe.id to probe),
                combatContract = base.combatContract!!.copy(nextObjectId = Long.MAX_VALUE - 1))
        }
        withShieldDuel(40.0, extend) { context ->
            val hit = firstShieldHit(context)
            assertEquals(460f, hit.units.single { it.unitId == "heavy" }.shield?.current)
            var failedStep: Long? = null
            for (step in hit.stepIndex + 1..180L) {
                val before = context.observe()
                val player = context.observePlayer(context.world, "p1")
                try { context.advance(Step(step, 1.0, "sim-tick")) }
                catch (error: ArithmeticException) {
                    failedStep = step
                    assertSame(before, context.observe())
                    assertSame(player, context.observePlayer(context.world, "p1"))
                    val shield = before.units.single { it.unitId == "heavy" }.shield!!
                    assertTrue(shield.current in 460f..499f)
                    assertFailsWith<ArithmeticException> { context.advance(Step(step, 1.0, "sim-tick")) }
                    assertSame(before, context.observe())
                    assertSame(player, context.observePlayer(context.world, "p1"))
                    break
                }
            }
            assertNotNull(failedStep, "The actual second normal projectile must overflow the object identity counter")
        }
    }

    @Test fun paid_construction_initializes_shield_and_a_failed_payment_publishes_no_partial_site() {
        val base = AuthorConstructionScene.load()
        val workshop = base.unitDefinitions.getValue("workshop").withShield(ShieldProfile(50f, 0f))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + ("workshop" to workshop))
        val nonce = "e".repeat(32)
        val id = "constructed:p1:$nonce"
        val context = HeavyMechTestScene.open(scene)
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "a", "workshop", Position(190.0, 210.0), nonce, context.world)))
            var frame = context.observe()
            for (step in 0L..200L) {
                frame = context.advance(Step(step, 1.0, "sim-tick"))
                if (frame.units.any { it.unitId == id }) break
            }
            val site = frame.units.single { it.unitId == id }
            assertTrue(site.constructionProgress < 1f)
            assertEquals(ShieldObservation(50f, 50f), site.shield)
            assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
        val scarce = HeavyMechTestScene.open(scene.copy(economyContract = EconomyContract(mapOf("p1" to 699L, "p2" to 0L))))
        try {
            assertIs<ActionResult.Accepted>(scarce.submit(BuildAction("poor", "p1", "a", "workshop", Position(190.0, 210.0), nonce, scarce.world)))
            repeat(200) { scarce.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(scarce.observe().units.none { it.unitId == id })
            assertEquals(699L, scarce.observePlayer(scarce.world, "p1").balance)
        } finally { scarce.close() }
    }
}
