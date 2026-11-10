package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechEngineerAdmissionTest {
    @Test fun direct_world_admission_rechecks_the_engineer_construction_menu_and_accepts_qualified_subsets() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-engineer-production/json/production-package"))).value
        val normal = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("repairbay-production/json/normal-package"))).value
        val definitions = pack.units + mapOf("landFactory" to normal.units.getValue("landFactory"),
            "c_tank" to normal.units.getValue("c_tank"))
        val source = pack.units.getValue("mechEngineer")
        val profile = checkNotNull(source.builder)
        for (targets in listOf(profile.buildingTypeIds, listOf("repairbay"), listOf("mechGun"),
                listOf("landFactory"), profile.buildingTypeIds + "landFactory")) {
            val forged = UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
                source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed, source.fogOfWarSightRange,
                combat = source.combat, build = source.build,
                builder = BuilderProfile(targets, profile.range, profile.preparation, profile.decay,
                    profile.repairRate, profile.buildSpeedMultiplier, profile.mainNanoTurret),
                income = source.income, mech = source.mech, transportSlots = source.transportSlots,
                maintenance = source.maintenance, selfRegenRate = source.selfRegenRate, techLevel = source.techLevel)
            val input = CombatTestContent.scenario().copy(unitDefinitions = definitions + (source.id to forged),
                units = listOf(UnitSpawn("engineer", "p1", source.id, Position(110.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
            val result = SimulationContext.initialize(input)
            if ("landFactory" in targets) {
                if (result is InitResult.Accepted) {
                    result.context.close()
                    fail("Direct world admission accepted the forbidden engineer land factory menu: $targets")
                }
                assertTrue(assertIs<InitResult.Rejected>(result).error.code.startsWith("unsupported-"))
            } else {
                val context = assertIs<InitResult.Accepted>(result, "Qualified menu subset rejected: $targets").context
                try {
                    assertIs<ActionResult.Accepted>(context.submit(BuildAction("allowed", "p1", "engineer",
                        targets.first(), Position(210.0, 210.0), "c".repeat(32), context.world)))
                } finally { context.close() }
            }
        }
    }

    @Test fun unsupported_work_parameters_and_tech_levels_are_rejected_before_any_world_is_published() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-engineer-production/ini/production-package"))).value
        val source = pack.units.getValue("builder")
        val targets = checkNotNull(source.builder).buildingTypeIds
        val changes = listOf(
            BuilderProfile(targets, range = Float.NaN) to 1,
            BuilderProfile(targets, preparation = -1f) to 1,
            BuilderProfile(targets, repairRate = Float.POSITIVE_INFINITY) to 1,
            BuilderProfile(targets, buildSpeedMultiplier = -1f) to 1,
            BuilderProfile(targets, mainNanoTurret = "missing") to 1,
            BuilderProfile(targets) to 0,
            BuilderProfile(targets) to 4)
        for ((builder, tier) in changes) {
            val forged = UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
                source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed, source.fogOfWarSightRange,
                combat = source.combat, durability = source.durability, build = source.build,
                builder = builder, techLevel = tier)
            val input = CombatTestContent.scenario().copy(unitDefinitions = pack.units + (source.id to forged),
                units = listOf(UnitSpawn("worker", "p1", source.id, Position(110.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))
            when (val result = SimulationContext.initialize(input)) {
                is InitResult.Accepted -> { result.context.close(); fail("Invalid work/tier accepted: $builder / $tier") }
                is InitResult.Rejected -> assertEquals(if (tier !in 1..3) "unsupported-tech-level" else "unsupported-construction", result.error.code)
            }
        }
    }

    @Test fun the_finite_engineer_marker_does_not_admit_changed_weapons_or_feet() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-engineer-production/ini/package"))).value
        val source = pack.units.getValue("mechEngineer")
        val combat = checkNotNull(source.combat)
        val catalog = checkNotNull(combat.namedWeapons)
        val body = catalog.ordered[0]
        val nano = catalog.ordered[1]
        val changes = listOf(
            combat.copy(attackRange = 400.0),
            combat.copy(namedWeapons = WeaponCatalog(listOf(body.copy(canAttackLandUnits = true), nano))),
            combat.copy(namedWeapons = WeaponCatalog(listOf(body.copy(warmup = 0.0), nano))),
            combat.copy(namedWeapons = WeaponCatalog(listOf(body.copy(projectile = body.projectile.copy(directDamage = 999.0)), nano))),
            combat.copy(namedWeapons = WeaponCatalog(listOf(body, nano.copy(warmup = 30.0)))),
        ).map { it to checkNotNull(source.mech) } +
            listOf(combat to MechDefinition(checkNotNull(source.mech).body,
                source.mech.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(maximumMovingLegs = 4) else leg }, MechKind.FOUR_LEGGED))
        for ((weapon, feet) in changes) {
            val forged = UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
                source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed, source.fogOfWarSightRange,
                combat = weapon, build = source.build, builder = source.builder, income = source.income, mech = feet,
                transportSlots = source.transportSlots, maintenance = source.maintenance,
                selfRegenRate = source.selfRegenRate, techLevel = source.techLevel)
            val base = CombatTestContent.scenario()
            val input = base.copy(unitDefinitions = pack.units + (source.id to forged),
                units = listOf(UnitSpawn("engineer", "p1", source.id, Position(110.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))
            when (val result = SimulationContext.initialize(input)) {
                is InitResult.Accepted -> { result.context.close(); fail("The finite work marker accepted changed weapon/feet capabilities") }
                is InitResult.Rejected -> assertTrue(result.error.code.startsWith("unsupported-"))
            }
        }
    }
}
