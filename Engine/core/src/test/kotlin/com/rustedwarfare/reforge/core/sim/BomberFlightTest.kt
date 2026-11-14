package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BomberFlightTest {
    private fun bomber(authored: Boolean = false): UnitDefinition {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        if (authored) return authoredWeaponsRepresentative(fixtures.resolve("bomber-production/json/package"),
            "bomber.json", "survey_bomber")
        val loaded = ContentLoader.loadContentPack(fixtures.resolve("bomber-production/ini/package"))
        return assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units.getValue("bomber")
    }

    private fun open(map: MapDefinition, bomber: UnitDefinition, spawns: List<UnitSpawn>,
                     additional: Map<String, UnitDefinition> = emptyMap()): SimulationContext {
        val base = WaterTestScene.scenario(map, MovementType.AIR, spawns.first().position)
        val objectIds = spawns.mapIndexed { index, unit -> unit.unitId to (index + 1L) }.toMap()
        val scenario = base.copy(unitDefinitions = additional + (bomber.id to bomber), units = spawns,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(12345, -1, objectIds, spawns.size + 1L))
        val result = SimulationContext.initialize(scenario)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }

    @Test fun only_idle_on_dry_terrain_lands_and_a_move_request_takes_off_again() = WaterTestScene.withMap { map ->
        val bomber = bomber()
        val spawns = listOf(UnitSpawn("dry", "p1", bomber.id, Position(120.0, 180.0), 90.0),
            UnitSpawn("water", "p1", bomber.id, Position(520.0, 180.0), 90.0))
        val context = open(map, bomber, spawns)
        try {
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            repeat(500) { advance() }
            val units = context.observe().units.associateBy { it.unitId }
            assertEquals(2.0, units.getValue("dry").height)
            assertTrue(units.getValue("water").height in 33.4..36.6)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("takeoff", "p1", "dry", Position(300.0, 180.0), context.world)))
            repeat(60) { advance() }
            val moving = context.observe().units.single { it.unitId == "dry" }
            assertTrue(moving.height > 4 && moving.position.x > 130)
        } finally { context.close() }
    }

    @Test fun an_airborne_drop_retains_its_ground_point_and_impacts_after_stop() = WaterTestScene.withMap { map ->
        val bomber = bomber(authored = true)
        assertEquals("survey_bomber", bomber.id)
        val target = UnitDefinition("ground-witness", "OwnedTarget", 6.0, 6.0, 1, bomber.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(2000.0, null))
        val spawns = listOf(UnitSpawn("bomber", "p1", bomber.id, Position(120.0, 180.0), 90.0),
            UnitSpawn("target", "p2", target.id, Position(270.0, 180.0), 90.0),
            UnitSpawn("nearby", "p2", target.id, Position(300.0, 180.0), 90.0),
            UnitSpawn("outside", "p2", target.id, Position(350.0, 180.0), 90.0))
        val context = open(map, bomber, spawns, mapOf(target.id to target))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "bomber", "target", context.world)))
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            var launch: ProjectileObservation? = null
            while (launch == null && step < 350) {
                launch = advance().projectileEvents.firstOrNull { it.kind == "born" }?.projectile
            }
            val bomb = assertNotNull(launch)
            assertEquals("1", bomb.sourceWeaponName)
            assertEquals(ProjectileAppearance.BOMBER_BOMB, bomb.appearance)
            assertTrue(bomb.height > 25.0)
            assertNull(bomb.targetUnitId)
            val point = assertNotNull(bomb.ground).targetPosition
            assertEquals(Position(270.0, 180.0), point)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "bomber", context.world)))
            assertTrue(context.observe().units.single { it.unitId == "bomber" }.height > 25.0)
            val samples = List(200) { advance() }
            val events = samples.flatMap { it.projectileEvents }
            val impact = events.single { it.kind == "impact" && it.projectile.objectId == bomb.objectId }.projectile
            assertEquals(point, impact.ground?.targetPosition)
            assertTrue(impact.height < 3.0)
            val impactFrame = samples.single { sample -> sample.projectileEvents.any { it.kind == "impact" && it.projectile.objectId == bomb.objectId } }
            val hitSound = impactFrame.soundTriggers.single { it.position == impact.position }
            assertEquals("reforge:events/bomber-impact", hitSound.eventId)
            assertEquals(impact.position, hitSound.position)
            assertEquals(.5f, hitSound.volume)
            val health = impactFrame.units.associate { it.unitId to it.health }
            assertEquals(1630.0, health.getValue("target"))
            assertTrue(checkNotNull(health.getValue("nearby")) in 1631.0..1999.0)
            assertEquals(2000.0, health.getValue("outside"))
        } finally { context.close() }
    }

    @Test fun an_airborne_bomb_still_impacts_after_another_bomb_kills_the_original_target() = WaterTestScene.withMap { map ->
        val bomber = bomber()
        val target = UnitDefinition("ground-witness", "OwnedTarget", 6.0, 6.0, 1, bomber.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(80.0, null))
        val spawns = listOf(
            UnitSpawn("first", "p1", bomber.id, Position(120.0, 170.0), 90.0),
            UnitSpawn("second", "p1", bomber.id, Position(115.0, 190.0), 90.0),
            UnitSpawn("target", "p2", target.id, Position(270.0, 180.0), 90.0))
        val context = open(map, bomber, spawns, mapOf(target.id to target))
        try {
            for (id in listOf("first", "second"))
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack-$id", "p1", id, "target", context.world)))
            val samples = List(380) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val death = samples.first { sample -> sample.units.any { it.unitId == "target" && !it.alive } }.stepIndex
            val second = samples.asSequence().filter { it.stepIndex < death }.flatMap { it.projectileEvents.asSequence() }
                .first { it.kind == "born" && it.projectile.sourceUnitId == "second" }.projectile
            val afterDeath = samples.filter { it.stepIndex > death }.flatMap { it.projectileEvents }
                .single { it.kind == "impact" && it.projectile.objectId == second.objectId }
            assertEquals(Position(270.0, 180.0), afterDeath.projectile.ground?.targetPosition)
        } finally { context.close() }
    }

    @Test fun a_moving_ground_target_is_led_at_release_and_never_updates_the_captured_point() = WaterTestScene.withMap { map ->
        val bomber = bomber()
        val target = UnitDefinition("moving-witness", "OwnedTarget", 6.0, 6.0, 1, bomber.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(2000.0, null))
        val spawns = listOf(UnitSpawn("bomber", "p1", bomber.id, Position(120.0, 180.0), 90.0),
            UnitSpawn("target", "p2", target.id, Position(270.0, 140.0), 180.0))
        val context = open(map, bomber, spawns, mapOf(target.id to target))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "bomber", "target", context.world)))
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            repeat(90) { advance() }
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-target", "p2", "target", Position(270.0, 370.0), context.world)))
            var born: ProjectileObservation? = null
            while (born == null && step < 500) {
                born = advance().projectileEvents.firstOrNull { it.kind == "born" }?.projectile
            }
            val shot = assertNotNull(born)
            val point = assertNotNull(shot.ground).targetPosition
            val moving = context.observe().units.single { it.unitId == "target" }
            assertTrue(moving.speed > .5 && point.y > moving.position.y + 2.0, "shot=$point target=$moving")
            assertTrue(kotlin.math.hypot(point.x - shot.position.x, point.y - shot.position.y) <= 45.01)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-bomber", "p1", "bomber", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-target", "p2", "target", context.world)))
            val terminal = List(250) { advance() }.flatMap { it.projectileEvents }
                .single { it.kind == "impact" && it.projectile.objectId == shot.objectId }
            assertEquals(point, terminal.projectile.ground?.targetPosition)
        } finally { context.close() }
    }

    @Test fun an_exhausted_bomber_leaves_its_approach_then_returns_to_the_same_order() = WaterTestScene.withMap { map ->
        val bomber = bomber()
        val target = UnitDefinition("ground-witness", "OwnedTarget", 6.0, 6.0, 1, bomber.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(5000.0, null))
        val spawns = listOf(UnitSpawn("bomber", "p1", bomber.id, Position(120.0, 180.0), 90.0),
            UnitSpawn("target", "p2", target.id, Position(270.0, 180.0), 90.0))
        val context = open(map, bomber, spawns, mapOf(target.id to target))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "bomber", "target", context.world)))
            var brokeAway = false
            var turnedBack = false
            var firedAgain = false
            var finalState = ""
            for (step in 0L..2600L) {
                val report = context.advance(Step(step, 1.0, "sim-tick"))
                val actor = context.observe().units.single { it.unitId == "bomber" }
                val energy = assertNotNull(actor.energy)
                finalState = "step $step, position ${actor.position}, move ${actor.moveTarget}, energy $energy, tasks ${actor.tasks}"
                if (energy.recharging && actor.moveTarget?.x == 16.0 && actor.position.x < 210.0) brokeAway = true
                if (brokeAway && !energy.recharging && actor.moveTarget == Position(270.0, 180.0)) turnedBack = true
                if (turnedBack && report.projectileEvents.any { it.kind == "born" && it.projectile.sourceWeaponName == "1" }) {
                    assertEquals("target", actor.tasks.single().targetUnitId)
                    firedAgain = true
                    break
                }
            }
            assertTrue(brokeAway, "Exhaustion never sent the bomber back along its approach")
            assertTrue(turnedBack, "The bomber did not resume its original approach after recharge: $finalState")
            assertTrue(firedAgain, "The resumed order did not fire another bomb: $finalState")
        } finally { context.close() }
    }

    @Test fun a_depth_charge_tracks_a_submerged_body_and_deals_direct_damage() = WaterTestScene.withMap { map ->
        val bomber = bomber()
        val target = UnitDefinition("underwater-witness", "OwnedTarget", 6.0, 6.0, 1, bomber.imagePath, 1.0,
            MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false),
            emptySet(), durability = DurabilityProfile(1000.0, null),
            fixedUnderwaterTarget = FixedUnderwaterTargetProfile())
        val spawns = listOf(UnitSpawn("bomber", "p1", bomber.id, Position(460.0, 180.0), 90.0),
            UnitSpawn("target", "p2", target.id, Position(570.0, 180.0), 90.0, -8.0))
        val context = open(map, bomber, spawns, mapOf(target.id to target))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "bomber", "target", context.world)))
            val samples = List(301) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val events = samples.flatMap { it.projectileEvents }
            val born = events.first { it.kind == "born" }.projectile
            assertEquals("2", born.sourceWeaponName)
            assertEquals(ProjectileAppearance.BOMBER_DEPTH_CHARGE, born.appearance)
            assertEquals("target", born.targetUnitId)
            assertNull(born.ground)
            assertTrue(born.height > 25.0)
            val hit = events.single { it.projectile.objectId == born.objectId && it.kind == "impact" }
            assertEquals(70.0, hit.damage)
            assertTrue(hit.projectile.height in -11.0..-5.0)
            assertTrue(samples.flatMap { it.soundTriggers }.none { it.eventId == "reforge:events/bomber-impact" },
                "Ordinary depth-charge contact does not use the ground bomb's large-impact sound")
        } finally { context.close() }
    }

    @Test fun a_ground_bomb_still_aloft_at_lifetime_end_only_expires() = WaterTestScene.withMap { map ->
        val bomber = bomber()
        val target = UnitDefinition("ground-witness", "OwnedTarget", 6.0, 6.0, 1, bomber.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(2000.0, null))
        val spawns = listOf(UnitSpawn("bomber", "p1", bomber.id, Position(120.0, 180.0), 90.0),
            UnitSpawn("target", "p2", target.id, Position(270.0, 180.0), 90.0))
        val context = open(map, bomber, spawns, mapOf(target.id to target))
        try {
            val projectile = checkNotNull(bomber.combat?.namedWeapons).byName.getValue("1").projectile
            val acceleration = checkNotNull(projectile.acceleration)
            val ground = checkNotNull(projectile.ground)
            val steps = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
            val initial = (steps.get(context) as CandidateSteps).current
            val id = initial.nextObjectId++
            // Seed an unarrived bomb at the terminal boundary before the first simulation tick.
            // The original finite speed, acceleration, impact gates and area profile remain in use.
            initial.projectiles += MutableProjectile(id, "bomber", null, Position(230.0, 180.0), 90.0,
                1.0, Position(0.0, 0.0), 0f, acceleration.targetSpeed, projectile.directDamage, height = 35.0,
                age = 249f, deflectionHealth = projectile.deflectionHealth, ground = GroundShot(270f, 180f, 0f, ground),
                sourceWeaponName = "1", acceleration = acceleration, appearance = ProjectileAppearance.BOMBER_BOMB)
            val result = context.advance(Step(0, 1.0, "sim-tick"))
            val event = result.projectileEvents.single()
            assertEquals(id, event.projectile.objectId)
            assertEquals("expired", event.kind)
            assertEquals(0.0, event.projectile.remainingLife)
            assertTrue(event.projectile.height > 3.0)
            assertTrue(result.projectiles.isEmpty())
            assertTrue(result.soundTriggers.isEmpty())
            assertEquals(2000.0, result.units.single { it.unitId == "target" }.health)
        } finally { context.close() }
    }
}
