package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class MechFlameCombatTest {
    private val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun openFlameWitness(map: MapDefinition, targetX: Double): SimulationContext {
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("flame-mech-production/ini/production-package"))).value
        val flame = pack.units.getValue("mechFlame")
        val target = UnitDefinition("witness", "CustomUnitMetadata", 5.0, 5.0, 1, flame.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement.copy(moveSpeed = 1.1, moveAccelerationSpeed = 1.1),
            emptySet(), durability = DurabilityProfile(2000.0, null))
        val start = Position(140.0, 145.0)
        val base = WaterTestScene.scenario(map, MovementType.LAND, start)
        val scene = base.copy(unitDefinitions = mapOf(flame.id to flame, target.id to target),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("flame", "p1", flame.id, start, 90.0),
                UnitSpawn("a-target", "p2", target.id, Position(targetX, 145.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("flame" to 8L, "a-target" to 9L), 10L))
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
    }

    @Test fun stop_clears_the_order_while_the_same_flame_finishes_at_its_frozen_point() = WaterTestScene.withMap { map ->
        val context = openFlameWitness(map, 255.0)
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "flame", "a-target", context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            val launch = first.projectileEvents.single { it.kind == "born" }.projectile
            assertEquals("2", launch.sourceWeaponName)
            val frozen = assertNotNull(launch.ground).targetPosition
            val inFlight = first.projectiles.single { it.objectId == launch.objectId }
            assertEquals("a-target", first.units.single { it.unitId == "flame" }.attackTargetId)

            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "flame", context.world)))
            val afterStop = context.observe()
            val actor = afterStop.units.single { it.unitId == "flame" }
            assertNull(actor.attackTargetId)
            assertTrue(actor.tasks.isEmpty())
            assertEquals(inFlight, afterStop.projectiles.single { it.objectId == launch.objectId })

            val frames = (1L..75L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            for (shot in frames.flatMap { it.projectiles }.filter { it.objectId == launch.objectId }) {
                assertEquals(frozen, shot.ground?.targetPosition)
            }
            // Auto-acquisition may launch more shots. Only this already-airborne object is in scope.
            val terminal = frames.flatMap { frame -> frame.projectileEvents }
                .single { it.projectile.objectId == launch.objectId }
            assertEquals("impact", terminal.kind)
            assertEquals(frozen, terminal.projectile.ground?.targetPosition)
            assertTrue(frames.last().projectiles.none { it.objectId == launch.objectId })
        } finally { context.close() }
    }

    @Test fun moving_target_prediction_clips_at_the_muzzle_range_before_axis_spread() = WaterTestScene.withMap { map ->
        val context = openFlameWitness(map, 300.0)
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p2", "a-target", Position(390.0, 145.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "flame", "a-target", context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            val target = first.units.single { it.unitId == "a-target" }
            assertEquals(Position(301.1, 145.0), target.position)
            assertEquals(1.1, target.speed)
            val launch = first.projectileEvents.single { it.kind == "born" }.projectile
            assertEquals("2", launch.sourceWeaponName)
            // The control mount turns +0.17 degrees on this tick. Rotate (30, 15)
            // by that angle about the source at (140, 145) to locate the muzzle.
            assertEquals(169.95536211782036, launch.position.x, .001)
            assertEquals(160.08894563547275, launch.position.y, .001)
            val frozen = assertNotNull(launch.ground).targetPosition

            // Independent three-iteration reference calculation uses 2.5 for lead, a 70 tick cap,
            // and the 170 radius from the rotated muzzle. Then the first shot adds (+19.50, +10.60).
            // The 0.15 allowance covers lookup-table quantization. Wrong x values are about
            // 397.60 (no clip), 339.92 (spread first), and 348.45 (lead at random speed 6.28).
            assertEquals(359.0104249898212, frozen.x, .15)
            assertEquals(158.39745856222433, frozen.y, .15)
        } finally { context.close() }
    }

    @Test fun two_nozzles_fire_independently_at_ground_snapshots_and_earlier_shots_survive_target_death() =
        WaterTestScene.withMap { map ->
            val loaded = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                fixtures.resolve("flame-mech-production/ini/production-package"))).value
            val flame = loaded.units.getValue("mechFlame")
            val witness = UnitDefinition("witness", "OwnedTarget", 5.0, 5.0, 1, flame.imagePath, 1.0,
                WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(5.0, null))
            val position = Position(255.0, 145.0)
            val spawns = listOf(UnitSpawn("flame", "p1", flame.id, Position(140.0, 145.0), 90.0),
                UnitSpawn("target", "p2", witness.id, position, 90.0))
            val scenario = WaterTestScene.scenario(map, MovementType.LAND, spawns.first().position).copy(
                unitDefinitions = mapOf(flame.id to flame, witness.id to witness), units = spawns,
                combatContract = CombatContract(12345, -1, mapOf("flame" to 8L, "target" to 9L), 10L))
            val result = SimulationContext.initialize(scenario)
            val context = assertIs<InitResult.Accepted>(result, result.toString()).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "flame", "target", context.world)))
                val frames = List(120) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val launches = frames.flatMap { frame -> frame.projectileEvents.filter { it.kind == "born" }
                    .map { frame.stepIndex to it.projectile } }
                assertTrue(launches.size >= 4)
                assertEquals(setOf("2", "3"), launches.map { it.second.sourceWeaponName }.toSet())
                val right = launches.first { it.second.sourceWeaponName == "2" }
                val left = launches.first { it.second.sourceWeaponName == "3" }
                assertEquals(1L, left.first - right.first)
                for ((_, shot) in listOf(right, left)) {
                    assertEquals(hypot(15.0, 30.0), hypot(shot.position.x - 140.0, shot.position.y - 145.0), .001)
                }
                assertTrue(right.second.position.y > 145.0 && left.second.position.y < 145.0)
                assertTrue(launches.map { it.second.objectId }.distinct().size == launches.size)
                val distinctPoints = launches.map { (_, shot) ->
                    assertNull(shot.targetUnitId)
                    assertEquals(70.0, shot.remainingLife)
                    assertEquals(0f, assertNotNull(shot.ground).targetHeight)
                    shot.ground.targetPosition.also { point ->
                        assertTrue(point.x - position.x in -25.0..25.0 && point.y - position.y in -25.0..25.0, point.toString())
                    }
                }.toSet()
                assertTrue(distinctPoints.size > 2)
                val death = frames.first { frame -> frame.units.any { it.unitId == "target" && !it.alive } }.stepIndex
                assertTrue(launches.none { it.first > death })
                val impacts = frames.flatMap { frame -> frame.projectileEvents.filter { it.kind == "impact" }
                    .map { frame.stepIndex to it.projectile } }
                assertTrue(impacts.any { it.first > death })
                assertTrue(impacts.groupingBy { it.second.objectId }.eachCount().values.all { it == 1 })
                for ((_, shot) in impacts) {
                    assertEquals(launches.single { it.second.objectId == shot.objectId }.second.ground?.targetPosition,
                        shot.ground?.targetPosition)
                }
                assertTrue(frames.flatMap { it.soundTriggers }.isEmpty())
            } finally { context.close() }
        }

    @Test fun the_fixed_ground_point_controls_area_edges_and_armor_precedes_shield() = WaterTestScene.withMap { map ->
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("flame-mech-production/ini/production-package"))).value
        val flame = pack.units.getValue("mechFlame")
        fun witness(id: String, shield: Float = 0f, armor: ArmorProfile? = null) =
            UnitDefinition(id, "OwnedTarget", 1.0, 1.0, 1, flame.imagePath, 1.0,
                WaterTestScene.unit(MovementType.LAND).movement, emptySet(),
                durability = DurabilityProfile(2000.0, null, armor = armor),
                shield = if (shield > 0f) ShieldProfile(shield, 0f) else null)
        val plain = witness("plain")
        val shielded = witness("shielded", 100f)
        val plated = witness("plated", 100f, ArmorProfile(4.0))
        val depleted = witness("depleted", .5f)
        val heavy = witness("heavily-plated", armor = ArmorProfile(30.0, minimumDamage = 2.0))
        // Independently evaluated from the fixed PC integer sampler for unit 8, seed 12345,
        // first source counter 9 and first frame 0. Salt 2 adds 19.50 and salt 7 adds 10.60.
        val aim = Position(255.0, 145.0)
        val point = Position(274.5, (145f + 10.6f).toDouble())
        fun target(id: String, type: UnitDefinition, dx: Double) =
            UnitSpawn(id, "p2", type.id, Position(point.x + dx, point.y), 90.0)
        val spawns = listOf(UnitSpawn("flame", "p1", flame.id, Position(140.0, 145.0), 90.0),
            UnitSpawn("aim", "p2", plain.id, aim, 90.0), target("center", plain, 0.0),
            target("half", plain, 30.0), target("edge", plain, 60.0), target("outside", plain, 60.125),
            target("shield", shielded, 0.0), target("plate", plated, 0.0),
            target("small-shield", depleted, 60.0), target("minimum", heavy, 0.0),
            target("below-minimum", heavy, 60.0),
            UnitSpawn("friendly", "p1", plain.id, point, 90.0))
        val scenario = WaterTestScene.scenario(map, MovementType.LAND, spawns.first().position).copy(
            unitDefinitions = listOf(flame, plain, shielded, plated, depleted, heavy).associateBy { it.id }, units = spawns,
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, spawn -> spawn.unitId to i + 8L }.toMap(), 8L + spawns.size))
        val initialized = SimulationContext.initialize(scenario)
        val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "flame", "aim", context.world)))
            val frames = List(70) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val born = frames.first().projectileEvents.single { it.kind == "born" }.projectile
            assertEquals(point, assertNotNull(born.ground).targetPosition)
            assertEquals(ProjectileAppearance.MECH_FLAME, born.appearance)
            val firstPosition = frames.first().projectiles.single { it.objectId == born.objectId }.position
            assertEquals(6.28, hypot(firstPosition.x - born.position.x, firstPosition.y - born.position.y), .001)
            val index = frames.indexOfFirst { frame -> frame.projectileEvents.any { it.kind == "impact" && it.projectile.objectId == born.objectId } }
            assertTrue(index > 0)
            val hits = frames[index].projectileEvents.filter { it.kind == "impact" }
            assertEquals(1, hits.size, "This fixed impact frame isolates one shot")
            assertTrue(hypot(hits.single().projectile.position.x - point.x, hits.single().projectile.position.y - point.y) > .13)
            val before = frames[index - 1].units.associateBy { it.unitId }
            val after = frames[index].units.associateBy { it.unitId }
            fun hpLoss(id: String) = checkNotNull(before.getValue(id).health) - checkNotNull(after.getValue(id).health)
            fun shieldLoss(id: String) = checkNotNull(before.getValue(id).shield).current - checkNotNull(after.getValue(id).shield).current
            assertEquals(12.0, hpLoss("center"), 1e-5)
            assertEquals(7.2, hpLoss("half"), 1e-5)
            assertEquals(1.2, hpLoss("edge"), 1e-5)
            assertEquals(0.0, hpLoss("outside"))
            assertEquals(0.0, hpLoss("friendly"))
            assertEquals(12f, shieldLoss("shield"), .00001f)
            assertEquals(0.0, hpLoss("shield"))
            assertEquals(8f, shieldLoss("plate"), .00001f)
            assertEquals(0.0, hpLoss("plate"))
            assertEquals(.5f, shieldLoss("small-shield"), .00001f)
            assertEquals(.7, hpLoss("small-shield"), 1e-5)
            assertEquals(2.0, hpLoss("minimum"))
            assertEquals(1.2, hpLoss("below-minimum"), 1e-5)
        } finally { context.close() }
    }
}
