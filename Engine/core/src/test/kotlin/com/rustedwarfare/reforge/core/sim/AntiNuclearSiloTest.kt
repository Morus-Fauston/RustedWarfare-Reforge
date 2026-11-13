package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.BuildProfile
import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.BuildingProfile
import com.rustedwarfare.reforge.core.content.ConstructionProfile
import com.rustedwarfare.reforge.core.content.CustomBallisticProfile
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.NuclearSiloKind
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun interceptorScenario(map: MapDefinition): Scenario {
    val base = NuclearScene.scenario(map)
    val launcher = base.unitDefinitions.getValue("nukeLauncherC")
    val interceptor = UnitDefinition(
        "antiNukeLauncherC", "CustomUnitMetadata", 35.0, 35.0, 1, launcher.imagePath, 1.0, launcher.movement,
        emptySet(), durability = DurabilityProfile(2800.0, launcher.durability?.deadImagePath),
        building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1), launcher.building?.backImagePath),
        build = BuildProfile(15000L, .001f), construction = ConstructionProfile(15000L, .001f),
        nuclearSilo = checkNotNull(launcher.nuclearSilo).copy(kind = NuclearSiloKind.INTERCEPTOR),
    )
    return base.copy(unitDefinitions = base.unitDefinitions + (interceptor.id to interceptor),
        units = base.units + UnitSpawn("defense", "p1", interceptor.id, Position(280.0, 280.0), 90.0),
        combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "defense" to 2L), 3L))
}

class AntiNuclearSiloTest {
    @Test
    fun current_range_and_nuke_tag_are_independent_of_the_landing_range() = WaterTestScene.withMap { map ->
        val width = 130
        val cells = width * map.heightTiles
        val landGid = map.groundGids.first()
        val land = MapDefinition(width, map.heightTiles, map.tileWidth, map.tileHeight, map.tileSets,
            IntArray(cells) { landGid }, BlockingGrid(width, map.heightTiles, BooleanArray(cells)))
        val base = interceptorScenario(land)
        val scene = base.copy(units = base.units.map {
            if (it.unitId == "silo") it.copy(ownerPlayerId = "p2", position = Position(2485.0, 280.0)) else it
        })
        val round = MutableProjectile(3L, "silo", null, Position(2479.0, 280.0), 270.0,
            100.0, Position(0.0, 0.0), 0f, 2.0, 0.0, height = 51.0,
            nuclearTarget = Position(500.0, 280.0), ballistic = CustomBallisticProfile(80f, 110f),
            appearance = ProjectileAppearance.NUCLEAR, nukeTagged = true)
        val cases = listOf(
            Triple("inside", round, true),
            Triple("boundary", round.copy(position = Position(2480.0, 280.0)), false),
            Triple("outside", round.copy(position = Position(2481.0, 280.0)), false),
            Triple("untagged", round.copy(nukeTagged = false), false),
        )
        val stepsField = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
        for ((name, incoming, shouldLaunch) in cases) {
            val context = NuclearScene.open(scene)
            try {
                fun stock() = context.observePlayer(context.world, "p1").units
                    .single { it.unitId == "defense" }.unitActions?.stockpile
                assertIs<ActionResult.Accepted>(
                    context.submit(UnitAction("stock", "p1", "defense", "buildAntiNuke", context.world)))
                repeat(1429) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertEquals(1, stock(), name)
                // Seed only the incoming pose and tag so neither flight nor the launcher normalizes the cases.
                val state = (stepsField.get(context) as CandidateSteps).current
                assertTrue(state.projectiles.isEmpty(), name)
                assertEquals(incoming.objectId, state.nextObjectId, name)
                state.projectiles.add(incoming)
                state.nextObjectId++
                val result = context.advance(Step(1429, 1.0, "sim-tick"))
                val fired = result.projectileEvents.filter {
                    it.kind == "born" && it.projectile.appearance == ProjectileAppearance.INTERCEPTOR
                }
                assertTrue(result.projectiles.any { it.objectId == incoming.objectId }, name)
                assertEquals(if (shouldLaunch) 1 else 0, fired.size, name)
                assertEquals(if (shouldLaunch) 0 else 1, stock(), name)
            } finally {
                context.close()
            }
        }
    }

    @Test
    fun producing_and_cancelling_an_interceptor_round_preserves_the_paid_queue() = WaterTestScene.withMap { map ->
        val context = NuclearScene.open(interceptorScenario(map))
        try {
            fun view() = context.observePlayer(context.world, "p1")
            fun ammo() = checkNotNull(view().units.single { it.unitId == "defense" }.unitActions)
            fun request(id: String, cancel: Boolean = false) = context.submit(
                UnitAction(id, "p1", "defense", "buildAntiNuke", context.world, cancel))
            assertEquals(0, ammo().stockpile)
            assertIs<ActionResult.Accepted>(request("paid"))
            assertIs<ActionResult.Accepted>(request("cancelled"))
            assertEquals(92000L, view().balance)
            assertIs<ActionResult.Accepted>(request("refund", cancel = true))
            assertEquals(96000L, view().balance)
            assertEquals("buildAntiNuke", ammo().queue.single().name)
            repeat(1428) { step -> context.advance(Step(step.toLong(), 1.0, "sim-tick")) }
            assertEquals(0, ammo().stockpile)
            assertTrue(ammo().queue.single().progress in .999f..<1f)
            context.advance(Step(1428, 1.0, "sim-tick"))
            assertEquals(1, ammo().stockpile)
            assertEquals(96000L, view().balance)
            assertTrue(ammo().queue.isEmpty())
        } finally {
            context.close()
        }
    }

    @Test
    fun a_paid_interceptor_removes_an_incoming_round_without_the_nuclear_impact() = WaterTestScene.withMap { map ->
        val base = interceptorScenario(map)
        val witness = UnitDefinition("witness", "OwnedGroundWitness", 6.0, 6.0, 1,
            base.unitDefinitions.getValue("nukeLauncherC").imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(150.0, null))
        val context = NuclearScene.open(base.copy(
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = base.unitDefinitions + (witness.id to witness),
            units = base.units.map { if (it.unitId == "silo") it.copy(ownerPlayerId = "p2") else it } +
                UnitSpawn("witness", "p2", witness.id, Position(299.3, 180.0), 90.0),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "defense" to 2L, "witness" to 3L), 4L),
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 11000L)),
        ))
        try {
            fun order(id: String, player: String, unit: String, action: String, target: Position? = null) =
                assertIs<ActionResult.Accepted>(context.submit(UnitAction(id, player, unit, action, context.world, target = target)))
            order("attack-ammo", "p2", "silo", "buildNuke")
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            repeat(3334) { advance() }
            // The defense comes online after 139 ballistic updates, when the original launch point
            // for the interceptor is x=299.3, clear of the attacking silo's occupied cells.
            order("defense-ammo", "p1", "defense", "buildAntiNuke")
            repeat(1080) { advance() }
            order("attack", "p2", "silo", "launchNuke", Position(650.0, 180.0))
            val events = mutableListOf<VisibleProjectileEvent>()
            repeat(950) {
                advance()
                events += context.observePlayer(context.world, "p1").projectileEvents
            }
            val view = context.observePlayer(context.world, "p1")
            assertEquals(0, view.units.single { it.unitId == "defense" }.unitActions?.stockpile)
            assertEquals(1, events.count { it.kind == "born" && it.sourceUnitId == "defense" })
            val intercepted = events.single { it.kind == "intercepted" && it.appearance == ProjectileAppearance.NUCLEAR }
            assertEquals("defense", intercepted.interceptorUnitId)
            assertFalse(events.any { it.kind == "impact" && it.appearance == ProjectileAppearance.NUCLEAR })
            assertTrue(view.projectiles.isEmpty())
            assertEquals(50.0, context.observe().units.single { it.unitId == "witness" }.health)
        } finally {
            context.close()
        }
    }

    @Test
    fun overlapping_defenders_reserve_once_and_do_not_spend_on_allied_or_out_of_range_rounds() = WaterTestScene.withMap { map ->
        for (case in listOf("overlap", "allied", "outside", "empty")) {
            val base = interceptorScenario(map)
            val point = if (case == "outside") Position(180.0, 1280.0) else Position(440.0, 180.0)
            val context = NuclearScene.open(base.copy(
                players = base.players.map { it.copy(control = PlayerControl.LOCAL,
                    teamId = if (case == "allied") "shared" else null) },
                units = base.units.map { if (it.unitId == "silo") it.copy(ownerPlayerId = "p2") else it } +
                    UnitSpawn("reserve", "p1", "antiNukeLauncherC", Position(280.0, 80.0), 90.0),
                combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "defense" to 2L, "reserve" to 3L), 4L),
                economyContract = EconomyContract(mapOf("p1" to 8000L, "p2" to 11000L)),
            ))
            try {
                if (case != "empty") for (id in listOf("defense", "reserve"))
                    assertIs<ActionResult.Accepted>(context.submit(UnitAction("stock-$id", "p1", id, "buildAntiNuke", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("stock-attack", "p2", "silo", "buildNuke", context.world)))
                var step = 0L
                fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
                repeat(3334) { advance() }
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("attack", "p2", "silo", "launchNuke", context.world,
                    target = point)))
                val events = mutableListOf<ProjectileEventObservation>()
                repeat(1250) { events += advance().projectileEvents }
                val births = events.filter { it.kind == "born" && it.projectile.appearance == ProjectileAppearance.INTERCEPTOR }
                val hits = events.count { it.kind == "intercepted" }
                val explosions = events.count { it.kind == "impact" && it.projectile.appearance == ProjectileAppearance.NUCLEAR }
                if (case == "overlap") {
                    assertEquals(1, births.size, case)
                    assertEquals(1, hits, case)
                    assertEquals(0, explosions, case)
                    assertEquals(listOf(0, 1), context.observePlayer(context.world, "p1").units
                        .mapNotNull { it.unitActions?.stockpile }.sorted())
                } else {
                    assertTrue(births.isEmpty(), case)
                    assertEquals(0, hits, case)
                    assertEquals(1, explosions, case)
                }
            } finally {
                context.close()
            }
        }
    }

    @Test
    fun stock_and_pending_rounds_share_the_twelve_round_admission_threshold() = WaterTestScene.withMap { map ->
        val context = NuclearScene.open(interceptorScenario(map).copy(nukesEnabled = false))
        try {
            fun request(id: String, cancel: Boolean = false) = context.submit(
                UnitAction(id, "p1", "defense", "buildAntiNuke", context.world, cancel))
            repeat(12) { assertIs<ActionResult.Accepted>(request("round-$it")) }
            assertEquals("ammo-queue-limit", assertIs<ActionResult.Rejected>(request("overflow")).error.code)
            repeat(1429) { step -> context.advance(Step(step.toLong(), 1.0, "sim-tick")) }
            val player = context.observePlayer(context.world, "p1")
            val stock = checkNotNull(player.units.single { it.unitId == "defense" }.unitActions)
            assertEquals(1, stock.stockpile)
            assertEquals(11, stock.queue.size)
            assertFalse(stock.actions.single().available)
            assertEquals("ammo-queue-limit", assertIs<ActionResult.Rejected>(request("still-full")).error.code)
            assertIs<ActionResult.Accepted>(request("cancel", cancel = true))
            assertIs<ActionResult.Accepted>(request("replacement"))
            assertEquals(52000L, context.observePlayer(context.world, "p1").balance)
        } finally {
            context.close()
        }
    }

    @Test
    fun an_interceptor_launched_just_before_nuclear_impact_keeps_flying_without_a_refund() = WaterTestScene.withMap { map ->
        val base = interceptorScenario(map)
        val context = NuclearScene.open(base.copy(
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units.map { if (it.unitId == "silo") it.copy(ownerPlayerId = "p2")
                else it.copy(position = Position(60.0, 350.0)) },
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 11000L)),
        ))
        try {
            fun command(id: String, player: String, unit: String, action: String, target: Position? = null) =
                assertIs<ActionResult.Accepted>(context.submit(UnitAction(id, player, unit, action, context.world, target = target)))
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            // The finite scenario starts with 11000 for the attacking player and 4000 for the defense.
            command("attack-round", "p2", "silo", "buildNuke")
            repeat(3334) { advance() }
            command("defense-round", "p1", "defense", "buildAntiNuke")
            repeat(1015) { advance() }
            command("launch", "p2", "silo", "launchNuke", Position(440.0, 180.0))
            val events = mutableListOf<ProjectileEventObservation>()
            repeat(1200) { events += advance().projectileEvents }
            val birth = events.indexOfFirst { it.kind == "born" && it.projectile.appearance == ProjectileAppearance.INTERCEPTOR }
            val detonation = events.indexOfFirst { it.kind == "impact" && it.projectile.appearance == ProjectileAppearance.NUCLEAR }
            val terminal = events.indexOfFirst { it.kind == "impact" && it.projectile.appearance == ProjectileAppearance.INTERCEPTOR }
            assertTrue(birth >= 0 && detonation > birth && terminal > detonation, events.joinToString { "${it.kind}/${it.projectile.appearance}" })
            assertFalse(events.any { it.kind == "intercepted" })
            assertEquals(0L, context.observePlayer(context.world, "p1").balance)
            assertEquals(0, context.observePlayer(context.world, "p1").units.single { it.unitId == "defense" }.unitActions?.stockpile)
        } finally {
            context.close()
        }
    }
}
