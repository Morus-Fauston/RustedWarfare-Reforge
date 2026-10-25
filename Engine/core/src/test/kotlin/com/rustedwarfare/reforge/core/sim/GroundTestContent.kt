package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*

internal object GroundTestContent {
    /** Trusted finite geometry/profile overrides isolate gates on real publicly fired projectiles. */
    fun scene(distance: Float = 100f, life: Float = 240f, height: Float = 0f,
              vertical: Float = 1.2f, gravity: Float = .18f, trueGravity: Float = 0f): Scenario {
        val base = UpgradeTestContent.scenario()
        val artillery = base.unitDefinitions.getValue("c_turret_t1_artillery")
        val combat = artillery.combat!!
        val weapon = UpgradeTestContent.variant(artillery, combat = combat.copy(
            turretSize = 353.0 - 236.0 - distance,
            projectile = combat.projectile.copy(life = life.toDouble(), ground = combat.projectile.ground!!.copy(
                spread = 0f, initialVerticalSpeed = vertical, gravity = gravity, trueGravity = trueGravity))))
        val tank = base.unitDefinitions.getValue("c_tank")
        val dummy = UpgradeTestContent.variant(tank, id = "dummy", combat = null,
            durability = DurabilityProfile(10000.0, tank.combat!!.deadImagePath))
        return base.copy(unitDefinitions = base.unitDefinitions + (weapon.id to weapon) + (dummy.id to dummy),
            units = listOf(UnitSpawn("t", "p1", weapon.id, Position(230.0, 150.0), 90.0, height.toDouble()),
                UnitSpawn("z", "p2", dummy.id, Position(353.0, 150.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L), 15L))
    }
}
