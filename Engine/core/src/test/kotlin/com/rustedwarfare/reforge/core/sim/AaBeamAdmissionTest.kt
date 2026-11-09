package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AaBeamAdmissionTest {
    @TempDir lateinit var temporary: Path

    @Test fun unqualified_named_energy_or_beam_and_modified_aa_weapons_are_rejected_at_initialization() = WaterTestScene.withMap { map ->
        val aa = AaBeamScene.definition(temporary)
        val combat = aa.combat!!
        val named = combat.namedWeapons!!.ordered.single()
        fun variant(base: UnitDefinition, weapon: CombatProfile, energy: EnergyProfile? = base.energy) =
            UnitDefinition(base.id, base.className, base.radius, base.displayRadius, base.totalFrames,
                base.imagePath, base.imageScale, base.movement, base.retainedUnconsumed,
                combat = weapon, build = base.build, rotor = base.rotor, energy = energy)
        val helicopter = HelicopterCombatScene.unit()
        val hc = helicopter.combat!!
        val hNamed = NamedWeaponDefinition("1", "1", hc.projectile, hc.mount, hc.shootDelay,
            hc.turretSize, hc.turretTurnSpeed, hc.turretTurnAcceleration, hc.turretImagePath, hc.turretImageScale)
        val inputs = listOf(
            variant(helicopter, hc.copy(namedWeapons = WeaponCatalog(listOf(hNamed.copy(energyUsage = .5f))))),
            variant(helicopter, hc.copy(namedWeapons = WeaponCatalog(listOf(hNamed.copy(projectile =
                hc.projectile.copy(beam = combat.projectile.beam)))))),
            variant(aa, combat.copy(namedWeapons = WeaponCatalog(listOf(named.copy(shootDelay = 0.0))))),
            variant(aa, combat.copy(namedWeapons = WeaponCatalog(listOf(named.copy(energyUsage = Float.NaN))))),
            variant(aa, combat.copy(namedWeapons = WeaponCatalog(listOf(named.copy(mount = TurretMount(999.0, 2.0)))))),
            variant(aa, combat.copy(projectile = combat.projectile.copy(retargetOnDeath = true), namedWeapons =
                WeaponCatalog(listOf(named.copy(projectile = combat.projectile.copy(retargetOnDeath = true))))))
        )
        for ((index, definition) in inputs.withIndex()) {
            val base = AaBeamScene.scenario(map, aa, emptyList())
            val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (definition.id to definition)))
            if (result is InitResult.Accepted) result.context.close()
            assertIs<InitResult.Rejected>(result, "Unqualified variant $index must be rejected")
        }
    }
}
