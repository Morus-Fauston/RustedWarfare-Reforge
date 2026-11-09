package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class AmphibiousDefinitionValidationTest {
    @Test fun the_native_marker_rejects_modified_weapons_and_incompatible_locomotion_capabilities() {
        val base = AmphibiousJetScene.scenario()
        val jet = base.unitDefinitions.getValue("amphibiousJet")
        val combat = assertNotNull(jet.combat)
        val badWeapons = combat.copy(namedWeapons = WeaponCatalog(combat.namedWeapons!!.ordered.map {
            if (it.name == "body") it.copy(canShoot = true) else it
        }))
        for (variant in 0..4) {
            val changed = UnitDefinition(jet.id, jet.className, jet.radius, jet.displayRadius, jet.totalFrames,
                jet.imagePath, jet.imageScale,
                if (variant == 0) jet.movement.copy(reverseSpeedPercentage = 1.0) else jet.movement,
                jet.retainedUnconsumed, jet.fogOfWarSightRange,
                combat = when (variant) { 1 -> combat.copy(attackRange = 171.0); 2 -> badWeapons; else -> combat },
                build = jet.build, shadowImagePath = jet.shadowImagePath, amphibiousJet = jet.amphibiousJet,
                submarine = if (variant == 3) SubmarineProfile.NATIVE else null,
                fixedUnderwaterTarget = if (variant == 4) FixedUnderwaterTargetProfile() else null)
            val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (jet.id to changed)))
            if (result is InitResult.Accepted) result.context.close()
            assertEquals("unsupported-amphibious-jet", assertIs<InitResult.Rejected>(result,"variant $variant").error.code)
        }
    }
}
