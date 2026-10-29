package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseConstructionTest {
    @Test fun unfinished_defenses_cannot_fire_intercept_or_advance_queued_upgrades() {
        val base = ControlledScenario.load(inputRoot())
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val scene = base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))), units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("enemy", "p2", "c_tank", Position(300.0, 160.0), 270.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "enemy" to 2L), 3L))
        val violations = mutableListOf<String>()
        for (type in listOf("c_turret_t1", "laserDefence")) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", type,
                    Position(190.0, 170.0), "1".repeat(32), context.world)))
                val created = context.advance(Step(0, 1.0, "sim-tick")).units.single { it.typeId == type }
                assertEquals(0f, created.constructionProgress)
                val weapon = context.observe().combat!!.weapons.singleOrNull { it.unitId == created.unitId }
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "worker", context.world)))
                if (type == "c_turret_t1") assertIs<ActionResult.Accepted>(context.submit(
                    UpgradeAction("queue-gun", "p1", created.unitId, "upgrade_gun", context.world)))
                var enemyShots = 0
                var ownShots = 0
                var deflections = 0
                for (step in 1L..80L) {
                    val frame = context.advance(Step(step, 1.0, "sim-tick"))
                    enemyShots += frame.events.count { it.kind == "projectile-fired" && it.unitId == "enemy" }
                    ownShots += frame.events.count { it.kind == "projectile-fired" && it.unitId == created.unitId }
                    deflections += frame.projectileEvents.count { it.kind == "deflected" && it.interceptorUnitId == created.unitId }
                }
                assertTrue(enemyShots > 0, "The ordinary enemy weapon must create a real stimulus for $type")
                val final = context.observe()
                assertEquals(0f, final.units.single { it.unitId == created.unitId }.constructionProgress)
                if (ownShots != 0) violations += "$type fired $ownShots shots"
                if (deflections != 0) violations += "$type intercepted $deflections shots"
                if (weapon != final.combat!!.weapons.singleOrNull { it.unitId == created.unitId }) violations += "$type advanced weapon state"
                if (type == "c_turret_t1") {
                    val queue = context.observePlayer(context.world, "p1").upgradeQueues.single { it.unitId == created.unitId }
                    if (queue.items.single().progress != 0f) violations += "unfinished turret advanced upgrade: ${queue.items}"
                } else if (final.units.single { it.unitId == created.unitId }.pointDefense!!.energy != 1f) {
                    violations += "unfinished shield consumed energy"
                }
            } finally { context.close() }
        }
        assertEquals(emptyList(), violations)
    }

    @Test fun defense_preview_and_real_construction_use_the_same_twenty_unit_anchor_while_factory_keeps_ten() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        for ((type, expected, price) in listOf(
            Triple("c_turret_t1", Position(180.0, 160.0), 500.0),
            Triple("laserDefence", Position(180.0, 160.0), 1200.0),
            Triple("landFactory", Position(190.0, 170.0), 700.0))) {
            val preview = SandboxSession(scene).use { session ->
                session.select(Position(120.0, 170.0))
                assertNotNull(session.previewConstruction(type, Position(190.0, 170.0)))
            }
            assertEquals(PlacementState.CLEAR, preview.state)
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", type,
                    Position(190.0, 170.0), "1".repeat(32), context.world)))
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                val created = frame.units.single { it.typeId == type }
                assertEquals(listOf(expected, expected), listOf(preview.position, created.position), type)
                if (type != "landFactory") assertEquals(setOf(8 to 7, 9 to 7, 8 to 8, 9 to 8), preview.cells.toSet())
                assertEquals(0f, created.constructionProgress)
                assertEquals(1750.0 - price, context.observePlayer(context.world, "p1").credits)
            } finally { context.close() }
        }
    }

    @Test fun invalid_defense_metadata_cannot_impersonate_an_old_economic_shape() {
        val base = ControlledScenario.load(inputRoot())
        for (id in listOf("c_turret_t1", "laserDefence")) {
            val d = base.unitDefinitions.getValue(id)
            val construction = checkNotNull(d.construction)
            val building = checkNotNull(d.building)
            val bad = listOf(
                changed(d, construction = construction.copy(price = construction.price + 1L), upgrades = null),
                changed(d, construction = construction.copy(rate = Float.NaN), upgrades = null),
                changed(d, building = building.copy(anchor = FootprintAnchor(10f, 10f)), upgrades = null),
                changed(d, building = building.copy(footprint = BuildingFootprint(-1, -1, 1, 1),
                    placementFootprint = BuildingFootprint(-1, -1, 1, 1), anchor = FootprintAnchor(10f, 10f)), upgrades = null))
            for (replacement in bad) {
                val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (id to replacement)))
                if (result is InitResult.Accepted) result.context.close()
                assertEquals("unsupported-construction", assertIs<InitResult.Rejected>(result,
                    "$id: ${replacement.construction}, ${replacement.building}").error.code)
            }
        }
    }

    @Test fun the_defense_variant_registers_the_complete_finite_chain_with_one_center_and_1750_credits() {
        val scene = ControlledScenario.load(inputRoot())
        assertEquals(listOf("extractorT1", "landFactory", "fabricatorT1", "c_turret_t1", "laserDefence"),
            scene.unitDefinitions.getValue("builder").builder!!.buildingTypeIds)
        assertEquals(setOf("commandCenter", "builder", "landFactory", "c_tank", "extractorT1", "extractorT2",
            "extractorT3", "fabricatorT1", "fabricatorT2", "fabricatorT3", "c_turret_t1", "c_turret_t2_gun",
            "c_turret_t1_artillery", "laserDefence"), scene.unitDefinitions.keys)
        assertEquals(listOf("builder", "c_tank"), scene.unitDefinitions.getValue("landFactory").production!!.unitTypeIds)
        assertEquals(ConstructionProfile(500L, .0008f), scene.unitDefinitions.getValue("c_turret_t1").construction)
        assertEquals(ConstructionProfile(1200L, .001f), scene.unitDefinitions.getValue("laserDefence").construction)
        assertEquals(listOf("upgrade_gun", "upgrade_artillery"), scene.unitDefinitions.getValue("c_turret_t1").upgrades!!.actions.map { it.actionKey })
        for (id in listOf("c_turret_t2_gun", "c_turret_t1_artillery")) assertNull(scene.unitDefinitions.getValue(id).construction)
        assertNull(scene.unitDefinitions.getValue("laserDefence").upgrades)
        assertEquals(setOf("commandCenter", "landFactory", "extractorT1", "extractorT2", "extractorT3",
            "fabricatorT1", "fabricatorT2", "fabricatorT3", "c_turret_t1", "c_turret_t2_gun",
            "c_turret_t1_artillery", "laserDefence"), scene.maintenanceContract!!.basePrices.keys)
        assertEquals(listOf("commandCenter"), scene.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
        assertEquals(1750L, scene.economyContract!!.initialBalances.getValue("p1"))
        val result = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(result, result.toString()).context
        try {
            val owner = context.observePlayer(context.world, "p1")
            assertEquals(1750.0, owner.credits)
            assertEquals(listOf("commandCenter"), owner.units.filter { u -> u.ownerPlayerId == "p1" }.map { u -> u.typeId })
        } finally { context.close() }
    }

    private fun inputRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")

    private fun changed(d: UnitDefinition, construction: ConstructionProfile? = d.construction,
                        building: BuildingProfile? = d.building, upgrades: UpgradeProfile? = d.upgrades): UnitDefinition =
        UnitDefinition(d.id, d.className, d.radius, d.displayRadius, d.totalFrames, d.imagePath, d.imageScale,
            d.movement, d.retainedUnconsumed, d.fogOfWarSightRange, d.combat, d.durability, building,
            d.production, d.build, d.directHitTargetKind, d.pointDefense, upgrades, d.income, d.builder,
            construction, d.deathExplosion)
}
