package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object NavalBallisticScene {
    fun scenario(map: MapDefinition, badHeight: Boolean = false): Scenario {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("naval-production/ini/production-package"))).value
        val source = pack.units.getValue("heavyBattleship")
        val ship = ship(source) { it }
        val heli = pack.units.getValue("helicopter")
        fun air(height: Double) = UnitDefinition("air", heli.className, heli.radius, heli.displayRadius, heli.totalFrames,
            heli.imagePath, heli.imageScale, heli.movement.copy(moveSpeed = 0.0, moveAccelerationSpeed = 0.0,
                moveDecelerationSpeed = 0.0, maxTurnSpeed = 0.0, turnAcceleration = 0.0,
                flight = FlightProfile(height, 0.0, 0.0, height)), emptySet(), 0,
            durability = DurabilityProfile(1000.0, null), rotor = heli.rotor)
        val spawns = listOf(UnitSpawn("ship", "p1", "ship", Position(500.0, 200.0), 90.0),
            UnitSpawn("good", "p2", "good", Position(650.0, 200.0), 0.0)) +
            if (badHeight) listOf(UnitSpawn("bad", "p2", "bad", Position(650.0, 200.0), 0.0)) else emptyList()
        return WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = mapOf("ship" to ship, "good" to air(20.0)) +
                if (badHeight) mapOf("bad" to air(Double.MAX_VALUE)) else emptyMap(),
            units = spawns, combatContract = CombatContract(12345, -1,
                spawns.mapIndexed { i, spawn -> spawn.unitId to i + 8L }.toMap(), spawns.size + 8L))
    }

    fun ship(source: UnitDefinition, change: (ProjectileProfile) -> ProjectileProfile): UnitDefinition =
        UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
            source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed, 0,
            combat = source.combat!!.let { combat -> combat.copy(namedWeapons = WeaponCatalog(combat.namedWeapons!!.ordered.map {
                if (it.name == "4") it.copy(projectile = change(it.projectile)) else it.copy(canShoot = false) })) })
}

class BattleshipBallisticFailureTest {
    @Test fun a_real_ballistic_geometry_failure_rolls_back_the_whole_frame_and_same_step_retry_matches_an_unfailed_world() = WaterTestScene.withMap { map ->
        // Finite Double AIR height is a trusted-host fault instrument; it overflows the projectile's Float geometry.
        val input = NavalBallisticScene.scenario(map, badHeight = true)
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        fun host(row: Observation) = listOf(row.units, row.events, row.actionResults, row.projectiles, row.projectileEvents,
            row.combat, row.economy, row.production, row.upgrades, row.construction, row.incomeStates, row.soundTriggers, row.reclaimedUnitIds)
        try {
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("bad-target", "p1", "ship", "bad", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(SelectAction("scheduled", "p1", "ship", context.world, 0)))
            }
            val failed = contexts[0]
            val before = failed.observe()
            val playerBefore = failed.observePlayer(failed.world, "p1")
            assertFailsWith<IllegalArgumentException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(playerBefore, failed.observePlayer(failed.world, "p1"))
            assertTrue(before.projectiles.isEmpty())
            for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(
                AttackAction("correct", "p1", "ship", "good", context.world)))
            for (q in 0L..25L) {
                val rows = contexts.map { it.advance(Step(q, 1.0, "sim-tick")) }
                assertEquals(host(rows[1]), host(rows[0]), "all published host state after retry q$q")
                if (q < 2) {
                    val shot = rows[0].projectiles.single()
                    assertEquals(listOf(0x3e99999a, 0x3f333334)[q.toInt()], shot.height.toFloat().toRawBits())
                    assertEquals(listOf(0x3ecccccd, 0x3f000000)[q.toInt()], assertNotNull(shot.ballistic).speed.toRawBits())
                }
            }
            assertTrue(before.projectiles.isEmpty())
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun invalid_custom_ballistic_profiles_are_rejected_before_world_creation_and_valid_content_still_opens() = WaterTestScene.withMap { map ->
        val input = NavalBallisticScene.scenario(map)
        val stock = input.unitDefinitions.getValue("ship")
        val cases = listOf<Pair<String, (ProjectileProfile) -> ProjectileProfile>>(
            "nan-delay" to { it.copy(ballistic = it.ballistic!!.copy(delayMoveHeight = Float.NaN)) },
            "negative-delay" to { it.copy(ballistic = it.ballistic!!.copy(delayMoveHeight = -.1f)) },
            "infinite-apex" to { it.copy(ballistic = it.ballistic!!.copy(apex = Float.POSITIVE_INFINITY)) },
            "apex-below-delay" to { it.copy(ballistic = it.ballistic!!.copy(delayMoveHeight = 5f)) },
            "unsupported-vertical" to { it.copy(ballistic = it.ballistic!!.copy(verticalSpeed = 0f)) },
            "instant-ballistic" to { it.copy(instant = true) },
            "float-speed-overflow" to { it.copy(speed = Double.MAX_VALUE) },
            "float-life-overflow" to { it.copy(life = Double.MAX_VALUE) },
            "float-acceleration-overflow" to { it.copy(acceleration = LinearProjectileAcceleration(3.0, Double.MAX_VALUE)) },
            "float-squared-speed-overflow" to { it.copy(speed = 1e20) })
        for ((name, change) in cases) {
            val bad = assertIs<InitResult.Rejected>(SimulationContext.initialize(input.copy(
                unitDefinitions = input.unitDefinitions + ("ship" to NavalBallisticScene.ship(stock, change)))), name)
            assertEquals("unsupported-combat", bad.error.code, name)
        }
        assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context.close()
    }
}
