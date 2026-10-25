package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class UpgradeInitializationTest {
    @Test fun real_reader_enabled_definitions_require_both_targets_but_default_old_tower_does_not() {
        val complete = UpgradeTestContent.scenario()
        assertIs<InitResult.Accepted>(SimulationContext.initialize(complete)).context.close()
        val tower = complete.unitDefinitions.getValue("c_turret_t1")
        for (id in listOf("c_turret_t2_gun", "c_turret_t1_artillery")) {
            val missing = complete.copy(unitDefinitions = complete.unitDefinitions - id)
            assertIs<InitResult.Rejected>(SimulationContext.initialize(missing), id)
            assertEquals(2, tower.upgrades!!.actions.size)
        }
        val disabled = UpgradeTestContent.tower(false)
        val old = complete.copy(unitDefinitions = mapOf(disabled.id to disabled))
        assertIs<InitResult.Accepted>(SimulationContext.initialize(old)).context.close()
    }

    @Test fun trusted_malformed_profiles_and_target_metadata_are_rejected_before_publishing_a_world() {
        val scene = UpgradeTestContent.scenario()
        val base = scene.unitDefinitions.getValue("c_turret_t1")
        val actions = base.upgrades!!.actions
        for (bad in listOf(emptyList(), actions.take(1), actions + actions.first(),
            actions.mapIndexed { i, a -> if (i == 0) a.copy(actionKey = "arbitrary") else a },
            actions.mapIndexed { i, a -> if (i == 0) a.copy(targetTypeId = "c_tank") else a },
            actions.mapIndexed { i, a -> if (i == 0) a.copy(price = -1L) else a },
            actions.mapIndexed { i, a -> if (i == 0) a.copy(rate = Float.NaN) else a },
            actions.mapIndexed { i, a -> if (i == 0) a.copy(rate = 0f) else a })) {
            val definition = UpgradeTestContent.variant(base, upgrades = UpgradeProfile(bad))
            assertIs<InitResult.Rejected>(SimulationContext.initialize(scene.copy(
                unitDefinitions = scene.unitDefinitions + (base.id to definition))), bad.toString())
        }
        val gun = scene.unitDefinitions.getValue("c_turret_t2_gun")
        val invalidTargets = listOf(
            UpgradeTestContent.variant(gun, id = "wrong-id"),
            UpgradeTestContent.variant(gun, upgrades = UpgradeProfile(actions)),
            UpgradeTestContent.variant(gun, combat = null),
            UpgradeTestContent.variant(gun, durability = null),
            UpgradeTestContent.variant(gun, building = gun.building!!.copy(footprint = BuildingFootprint(0, 0, 2, 1))),
            UpgradeTestContent.variant(gun, building = gun.building!!.copy(anchor = FootprintAnchor(0f, 0f))),
        )
        for (bad in invalidTargets) assertIs<InitResult.Rejected>(SimulationContext.initialize(scene.copy(
            unitDefinitions = scene.unitDefinitions + (gun.id to bad))))
        assertIs<InitResult.Rejected>(SimulationContext.initialize(scene.copy(economyContract = null)))
    }

    @Test fun rejected_reopen_stays_closed_and_can_then_reopen_the_complete_reader_scene() {
        val scene = UpgradeTestContent.scenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val previous = context.observe()
        context.close()
        assertIs<InitResult.Rejected>(context.reopen(scene.copy(unitDefinitions = scene.unitDefinitions - "c_turret_t2_gun")))
        assertFailsWith<IllegalStateException> { context.world }
        assertEquals("c_turret_t1", previous.units.single().typeId)
        assertIs<InitResult.Accepted>(context.reopen(scene))
        assertEquals(1750L, context.observe().economy!!.balances["p1"])
        context.close()
    }
}
