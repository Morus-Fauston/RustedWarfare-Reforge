package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.assertIs

/** All shots in this fixture are born from the ordinary public simulation path. */
internal object PointDefenseTestContent {
    fun native(): UnitDefinition {
        val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-005/20261026-e-defense-input-01/input")
        return assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadPointDefense(root.resolve("units/laserDefence.properties"))).value
    }

    fun variant(source: UnitDefinition, id: String = source.id, combat: CombatProfile? = source.combat,
                defense: PointDefenseProfile? = source.pointDefense,
                durability: DurabilityProfile? = source.durability, radius: Double = source.radius): UnitDefinition = UnitDefinition(id, source.className,
        radius, source.displayRadius, source.totalFrames, source.imagePath, source.imageScale, source.movement,
        source.retainedUnconsumed, source.fogOfWarSightRange, combat, durability, source.building, source.production,
        source.build, source.directHitTargetKind, defense)

    fun scene(speed: Double = 5.0, health: Float = 1f, life: Double = 10000.0,
              profile: PointDefenseProfile = PointDefenseProfile(muzzleOffsetX = 100.0, muzzleOffsetY = 160.0),
              instant: Boolean = false, nonDeflectable: Boolean = false, height: Double = 0.0): Scenario {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val weapon = original.combat!!
        val shooter = variant(original, id = "shooter", combat = weapon.copy(attackRange = 1000.0,
            shootDelay = 10000.0, turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0,
            projectile = weapon.projectile.copy(speed = speed, life = life, instant = instant,
                nonDeflectable = nonDeflectable, deflectionHealth = health)))
        val native = native()
        val target = variant(native, id = "target", defense = null)
        val defense = variant(native, defense = profile)
        return base.copy(unitDefinitions = mapOf("shooter" to shooter, "target" to target, defense.id to defense),
            players = listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.LOCAL, "two"),
                Player("p3", PlayerControl.LOCAL, "three")),
            units = listOf(UnitSpawn("d", "p1", defense.id, Position(50.0, 50.0), 0.0),
                UnitSpawn("s", "p2", "shooter", Position(110.0, 210.0), 90.0, height),
                UnitSpawn("t", "p1", "target", Position(510.0, 210.0), 0.0, height)),
            combatContract = CombatContract(12345, -1, mapOf("d" to 8L, "s" to 9L, "t" to 10L), 11L))
    }

    fun identities(scene: Scenario): Scenario = scene.copy(combatContract = CombatContract(12345, -1,
        scene.units.sortedBy { it.unitId }.mapIndexed { index, unit -> unit.unitId to 8L + index }.toMap(),
        8L + scene.units.size))

    fun trace(scene: Scenario, steps: Int): List<Observation> {
        val result = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(result, result.toString()).context
        return try { aim(context); (0 until steps).map { context.advance(Step(it.toLong(), 1.0, "sim-tick")) } }
        finally { context.close() }
    }

    fun aim(context: SimulationContext) {
        for (source in context.observe().units.filter { it.unitId.startsWith("s") })
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("aim-${source.unitId}", source.ownerPlayerId,
                source.unitId, "t", context.world)))
    }
}
