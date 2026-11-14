package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProjectileExpiryTest {
    @Test fun expiry_birth_uses_the_final_parent_pose_and_new_template_then_advances_next_step() = withContent(10) { pack ->
        val trace = fight(pack, 38)
        val expired = events(trace, "expired").first { it.projectile.remainingLife == 0.0 }.projectile
        val child = events(trace, "born").single { it.projectile.remainingLife == 300.0 }.projectile
        assertNotEquals(expired.objectId, child.objectId)
        assertEquals(10f, expired.age)
        assertEquals(expired.position, child.position)
        assertEquals(expired.height, child.height)
        assertEquals(expired.heading, child.heading)
        assertEquals(expired.sourceUnitId, child.sourceUnitId)
        assertEquals(expired.targetUnitId, child.targetUnitId)
        assertEquals(0f, child.age)
        val birth = trace.indexOfFirst { frame -> frame.projectileEvents.any { it.kind == "born" && it.projectile == child } }
        assertEquals(child, trace[birth].projectiles.single { it.objectId == child.objectId })
        val next = trace[birth + 1].projectiles.single { it.objectId == child.objectId }
        assertEquals(299.0, next.remainingLife)
        assertEquals(2.0, kotlin.math.hypot(next.position.x - child.position.x, next.position.y - child.position.y), 1e-9)
    }

    @Test fun earlier_impact_ends_the_parent_while_impact_at_zero_still_derives_once() {
        var impactAge = -1
        withContent(100) { pack ->
            val trace = fight(pack, 120)
            val impact = events(trace, "impact").single()
            impactAge = impact.projectile.age.toInt()
            assertTrue(impactAge in 11..95)
            assertTrue(impact.projectile.remainingLife > 0.0)
            assertEquals(1, events(trace, "born").size)
        }
        withContent(impactAge) { pack ->
            val trace = fight(pack, impactAge + 55)
            val firstImpact = events(trace, "impact").first()
            assertEquals(0.0, firstImpact.projectile.remainingLife)
            val settlement = trace.single { frame -> firstImpact in frame.projectileEvents }.projectileEvents
            assertEquals(listOf("impact", "born"), settlement.filter { it.projectile.sourceUnitId == "a" }.map { it.kind })
            val child = settlement.single { it.kind == "born" }.projectile
            assertEquals(firstImpact.projectile.position, child.position)
            assertEquals(listOf(25.0, 31.0), events(trace, "impact").map { it.damage })
            assertEquals(2, events(trace, "born").size)
        }
    }

    @Test fun terminal_profile_expiry_never_creates_a_third_stage() = withContent(10, terminalLife = 7) { pack ->
        val trace = fight(pack, 40)
        assertEquals(listOf(10.0, 7.0), events(trace, "born").map { it.projectile.remainingLife })
        assertEquals(listOf(10f, 7f), events(trace, "expired").map { it.projectile.age })
        assertTrue(events(trace, "impact").isEmpty())
    }

    @Test fun player_projection_keeps_visible_derived_birth_private_from_unseen_terrain() =
        withContent(10) { pack ->
            val base = scenario(pack)
            val scene = base.copy(automaticVision = KnowledgeRules(TerrainRetention.MEMORY,
                PathInformationSource.PLAYER_KNOWLEDGE, false))
            val initialized = SimulationContext.initialize(scene)
            val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
            try {
                val attack = context.submit(AttackAction("spot", "p1", "a", "b", context.world))
                assertIs<ActionResult.Accepted>(attack, attack.toString())
                var observedDerivation = false
                for (step in 0L..30L) {
                    val host = context.advance(Step(step, 1.0, "sim-tick"))
                    val own = context.observePlayer(context.world, "p1")
                    val spectator = context.observePlayer(context.world, "p3")
                    assertTrue(spectator.projectileEvents.isEmpty() && spectator.projectiles.isEmpty())
                    if (host.projectileEvents.any { it.kind == "born" && it.projectile.remainingLife == 300.0 }) {
                        observedDerivation = true
                        assertTrue(own.projectileEvents.any { it.kind == "born" &&
                            it.sourceUnitId == "a" && it.targetUnitId == "b" })
                    }
                }
                assertTrue(observedDerivation)
            } finally { context.close() }
        }

    @Test fun bad_references_and_nonterminal_profiles_reject_the_complete_pack() {
        for (bad in listOf("missing", "round", "locked*2", "locked(offsetDir=12)"))
            withContent(10, mode = bad) { assertIs<ContentResult.Failure>(it) }
        withContent(10, terminalNext = "round") { assertIs<ContentResult.Failure>(it) }
    }

    @Test fun structured_and_ini_naval_authored_fields_resolve_the_same_pair_and_reject_invalid_templates() {
        val expected = mutableListOf<ProjectileProfile>()
        for (format in listOf("ini", "json", "hjson")) {
            withContent(30, format = format) { result ->
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
                val shot = pack.units.getValue("spcruiser").combat!!.namedWeapons!!.byName.getValue("port").projectile
                assertEquals(30.0, shot.life)
                assertEquals(2.0, shot.steering?.turnRate)
                assertEquals(300.0, shot.endOfLife?.life)
                assertEquals(6.0, shot.endOfLife?.steering?.turnRate)
                assertNull(shot.endOfLife?.endOfLife)
                expected += shot
            }
            withContent(30, format = format, turn = "invalid") { assertIs<ContentResult.Failure>(it) }
            withContent(30, format = format, instantEnd = true) { assertIs<ContentResult.Failure>(it) }
            withContent(30, mode = "NONE", format = format, instantStart = true) { assertIs<ContentResult.Failure>(it) }
        }
        assertEquals(1, expected.distinct().size)
    }

    @Test fun trusted_land_combat_cannot_enable_naval_expiry() {
        val base = CombatTestContent.scenario()
        val source = base.unitDefinitions.getValue("c_tank")
        val original = checkNotNull(source.combat)
        val definition = PointDefenseTestContent.variant(source,
            combat = original.copy(projectile = original.projectile.copy(endOfLife = ProjectileProfile(2.0, 30.0, 3.0))))
        val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (definition.id to definition)))
        if (result is InitResult.Accepted) result.context.close()
        assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result).error.code)
    }

    @Test fun a_point_defense_at_the_expiry_step_blocks_the_child() = withContent(9) { pack ->
        val base = scenario(pack)
        val defense = PointDefenseTestContent.native()
        val site = Position(370.0, 300.0)
        val scene = PointDefenseTestContent.identities(base.copy(unitDefinitions = base.unitDefinitions + (defense.id to defense),
            units = base.units + UnitSpawn("defender", "p2", defense.id, site, 0.0)))
        val initialized = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "a", "b", context.world)))
            val trace = (0L..32L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            val parent = events(trace, "born").single().projectile
            val deflected = events(trace, "deflected").single()
            assertEquals("defender", deflected.interceptorUnitId)
            assertEquals(8f, deflected.projectile.age)
            val expired = events(trace, "expired").single().projectile
            assertEquals(parent.objectId, expired.objectId)
            assertTrue(expired.intercepted)
            assertEquals(0.0, expired.remainingLife)
            assertTrue(trace.single { deflected in it.projectileEvents }.projectileEvents.any {
                it.kind == "expired" && it.projectile.objectId == parent.objectId
            })
        } finally { context.close() }
    }

    @Test fun nautilus_authorized_land_and_surface_launch_but_submerged_declares_a_hidden_action() =
        WaterTestScene.withMap { map ->
            val base = NautilusScene.scenario(map, "json")
            for ((type, source, target) in listOf(
                Triple("nautilusSubmarineLand", Position(250.0, 180.0), Position(315.0, 180.0)),
                Triple("nautilusSubmarineSurface", Position(500.0, 180.0), Position(565.0, 180.0)),
                Triple("nautilusSubmarine", Position(500.0, 180.0), Position(565.0, 180.0)))) {
                val definition = base.unitDefinitions.getValue(type)
                assertTrue(definition.customActions!!.actions.any { it.name == "launchScoutBot" })
                val scene = base.copy(units = listOf(base.units.single().copy(typeId = type, position = source)))
                val context = NautilusScene.open(scene)
                try {
                    val menu = context.observePlayer(context.world, "p1").units.single().unitActions!!.actions
                    val result = context.submit(UnitAction("launch", "p1", "mother", "launchScoutBot", context.world, target = target))
                    if (type == "nautilusSubmarine") {
                        assertFalse(menu.any { it.name == "launchScoutBot" })
                        assertEquals("unsupported-unit-action", assertIs<ActionResult.Rejected>(result).error.code)
                        assertEquals(5000L, context.observePlayer(context.world, "p1").balance)
                    } else {
                        assertTrue(menu.single { it.name == "launchScoutBot" }.available)
                        assertIs<ActionResult.Accepted>(result, result.toString())
                        assertEquals(4750L, context.observePlayer(context.world, "p1").balance)
                        var spawned: UnitObservation? = null
                        for (step in 0L..355L) {
                            val observation = context.advance(Step(step, 1.0, "sim-tick"))
                            spawned = observation.units.firstOrNull { it.unitId != "mother" }
                            if (spawned != null) {
                                assertTrue(observation.projectileEvents.any { it.kind == "impact" &&
                                    it.projectile.sourceWeaponName == "botTurret" && it.projectile.position == spawned.position })
                                break
                            }
                        }
                        val scout = assertNotNull(spawned, type)
                        assertEquals("p1", scout.ownerPlayerId)
                        assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", scout.unitId, target, context.world)))
                    }
                } finally { context.close() }
            }
        }

    private fun events(trace: List<Observation>, kind: String) = trace.flatMap { it.projectileEvents }
        .filter { it.kind == kind && it.projectile.sourceUnitId == "a" }

    private fun fight(pack: ContentResult<LoadedContentPack>, steps: Int): List<Observation> {
        val initialized = SimulationContext.initialize(scenario(pack))
        val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try {
            val attack = context.submit(AttackAction("attack", "p1", "a", "b", context.world))
            assertIs<ActionResult.Accepted>(attack, attack.toString())
            return (0 until steps).map { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
        } finally { context.close() }
    }

    private fun scenario(pack: ContentResult<LoadedContentPack>): Scenario {
        val units = assertIs<ContentResult.Success<LoadedContentPack>>(pack, pack.toString()).value.units
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixtures.resolve("naval-production/ini/maps/naval-shore.tmx"))).value
        val ship = units.getValue("spcruiser")
        val target = UnitDefinition("target", ship.className, 6.0, 6.0, ship.totalFrames,
            ship.imagePath, ship.imageScale, ship.movement, ship.retainedUnconsumed, ship.fogOfWarSightRange,
            durability = DurabilityProfile(4000.0, null))
        return Scenario("projectile-expiry", map, units + (target.id to target),
            listOf(Player("p1", PlayerControl.LOCAL, "a"), Player("p2", PlayerControl.NONE, "b"),
                Player("p3", PlayerControl.LOCAL, "observer")),
            listOf(UnitSpawn("a", "p1", ship.id, Position(500.0, 300.0), 90.0),
                UnitSpawn("b", "p2", target.id, Position(585.0, 300.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 10L))
    }

    private fun withContent(life: Int, terminalLife: Int = 300, mode: String = "locked", terminalNext: String = "NONE",
                            format: String = "json", turn: String = "2", instantStart: Boolean = false, instantEnd: Boolean = false,
                            action: (ContentResult<LoadedContentPack>) -> Unit) {
        val source = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("author-water/json/package")
        val root = Files.createTempDirectory("rwr-projectile-expiry-").toAbsolutePath().normalize()
        try {
            Files.walk(source).use { paths -> paths.forEach { path ->
                val destination = root.resolve(source.relativize(path)).normalize()
                require(destination.startsWith(root))
                if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
            } }
            val profile = root.resolve("units/base.ini")
            Files.writeString(profile, Files.readString(profile).replace("shootDelay: 75", "shootDelay: 1000"))
            val ship = root.resolve("units/spcruiser.json")
            val turnScalar = turn.toDoubleOrNull()?.let { turn } ?: "\"$turn\""
            val startInstant = if (instantStart) ", \"instant\": true" else ""
            val endInstant = if (instantEnd) ", \"instant\": true" else ""
            val typed = Files.readString(ship)
                .replace("\"delay\": 100,", "\"canShoot\": false,")
                .replace("\"delay\": 70,", "\"delay\": 1000,")
                .replace("\"weapons\": [", """
                    "projectiles": [
                      {"name":"round", "directDamage":25, "life":$life, "speed":1.2,
                        "turnSpeed":$turnScalar, "spawnProjectilesOnEndOfLife":"$mode"$startInstant},
                      {"name":"locked", "directDamage":31, "life":$terminalLife, "speed":2,
                        "turnSpeed":6, "spawnProjectilesOnEndOfLife":"$terminalNext"$endInstant}
                    ],
                    "weapons": [
                """.trimIndent())
            val input = root.resolve("units/spcruiser.$format")
            if (format == "ini") {
                val fixed = source.parent.parent.resolve("ini/package/units/spcruiser.ini")
                val startIni = if (instantStart) "\ninstant: true" else ""
                val endIni = if (instantEnd) "\ninstant: true" else ""
                Files.writeString(input, Files.readString(fixed)
                    .replace("delay: 100", "canShoot: false").replace("delay: 70", "delay: 1000") + "\n" + """
                    [projectile_round]
                    directDamage: 25
                    life: $life
                    speed: 1.2
                    turnSpeed: $turn
                    spawnProjectilesOnEndOfLife: $mode$startIni

                    [projectile_locked]
                    directDamage: 31
                    life: $terminalLife
                    speed: 2
                    turnSpeed: 6
                    spawnProjectilesOnEndOfLife: $terminalNext$endIni
                """.trimIndent())
            } else Files.writeString(input, typed)
            val manifest = root.resolve("pack.json")
            if (format != "json") Files.writeString(manifest, Files.readString(manifest).replace("spcruiser.json", "spcruiser.$format"))
            action(ContentLoader.loadContentPack(root))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                require(path.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(path)
            } }
        }
    }
}
