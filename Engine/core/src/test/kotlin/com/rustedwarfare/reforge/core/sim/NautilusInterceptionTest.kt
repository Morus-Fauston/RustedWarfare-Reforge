package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusInterceptionTest {
    @Test fun naval_targeting_checks_both_strict_ranges_altitude_ownership_and_existing_reservations() = WaterTestScene.withMap { map ->
        val scene = NautilusScene.scenario(map)
        val enemy = NuclearScene.scenario(map).unitDefinitions.getValue("nukeLauncherC")
        val origin = Position(500.0, 180.0)
        val players = listOf(
            scene.players.first().copy(teamId = "naval"),
            scene.players.last().copy(teamId = "other"),
            scene.players.last().copy(playerId = "ally", teamId = "naval"),
        )
        val units = listOf("enemy" to "p2", "allied" to "ally", "owned" to "p1").associate { (id, owner) ->
            id to MutableUnit(id, owner, enemy.id, enemy, Position(300.0, 180.0), 90.0)
        }
        val inRange = MutableProjectile(1, "enemy", null, Position(1599.0, 180.0), 270.0,
            100.0, Position(0.0, 0.0), 0f, 2.0, 0.0, height = 51.0,
            nuclearTarget = Position(999.0, 180.0), appearance = ProjectileAppearance.NUCLEAR, nukeTagged = true)
        val last = inRange.copy(objectId = 2)
        for (definition in scene.unitDefinitions.values.filter { it.id.startsWith("nautilusSubmarine") }) {
            // Exercise exact geometry here and leave paid stock acquisition to the full match.
            val mother = MutableUnit("mother", "p1", definition.id, definition, origin, 90.0,
                ordnance = OrdnanceState(ammo = 1))
            fun target(vararg shots: MutableProjectile) = ProjectileInterception.target(mother, shots.toList(), units, players)
            assertSame(last, target(inRange, last), definition.id)
            val reserved = inRange.copy(objectId = 3, nukeTagged = false,
                interception = InterceptionCourse(last.objectId, last.position, last.position, 51f))
            assertSame(inRange, target(inRange, last, reserved), definition.id)
            assertNull(target(
                inRange.copy(position = Position(1600.0, 180.0)),
                inRange.copy(nuclearTarget = Position(1000.0, 180.0)),
                inRange.copy(height = 50.0),
                inRange.copy(nukeTagged = false),
                inRange.copy(sourceUnitId = "allied"),
                inRange.copy(sourceUnitId = "owned"),
                inRange.copy(remainingLife = 0.0),
            ), definition.id)
        }
    }

    @Test fun a_moving_submerged_nautilus_spends_a_paid_round_and_removes_the_incoming_nuke() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val nuclear = NuclearScene.scenario(map)
        val scene = base.copy(
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = base.unitDefinitions + nuclear.unitDefinitions,
            units = base.units + nuclear.units.single().copy(ownerPlayerId = "p2"),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "mother" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 6000L, "p2" to 11000L)),
        )
        val context = NautilusScene.open(scene)
        try {
            fun command(id: String, player: String, unit: String, name: String, target: Position? = null) =
                assertIs<ActionResult.Accepted>(context.submit(UnitAction(id, player, unit, name, context.world, target = target)))
            fun mother() = context.observePlayer(context.world, "p1").units.single { it.unitId == "mother" }
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            command("naval-ammo", "p1", "mother", "buildAntiNuke")
            command("incoming-ammo", "p2", "silo", "buildNuke")
            repeat(3334) { advance() }
            assertEquals(1, mother().unitActions!!.stockpile)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "mother", Position(760.0, 180.0), context.world)))
            command("incoming", "p2", "silo", "launchNuke", Position(650.0, 180.0))
            val events = mutableListOf<ProjectileEventObservation>()
            repeat(1250) { events += advance().projectileEvents }

            val birth = events.single { it.kind == "born" && it.projectile.appearance == ProjectileAppearance.INTERCEPTOR }
            assertEquals("mother", birth.projectile.sourceUnitId)
            assertEquals("antiNuke", birth.projectile.sourceWeaponName)
            assertTrue(birth.projectile.height < 0.0)
            assertTrue(birth.projectile.position.x > 525.0)
            assertEquals("nautilusSubmarine", mother().typeId)
            assertEquals(0, mother().unitActions!!.stockpile)
            assertEquals(0L, context.observePlayer(context.world, "p1").balance)
            val removed = events.single { it.kind == "intercepted" && it.projectile.appearance == ProjectileAppearance.NUCLEAR }
            assertEquals("mother", removed.interceptorUnitId)
            assertFalse(events.any { it.kind == "impact" && it.projectile.appearance == ProjectileAppearance.NUCLEAR })
        } finally {
            context.close()
        }
    }
}
