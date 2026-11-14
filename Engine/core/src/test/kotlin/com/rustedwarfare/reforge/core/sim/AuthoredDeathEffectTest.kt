package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AuthoredDeathEffectTest {
    @TempDir lateinit var temporary: Path

    @Test fun a_destroyed_authored_unit_runs_one_declared_area_effect_with_normal_host_rules() =
        WaterTestScene.withMap { map ->
            val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
                .resolve("hover-production/json/package/units/hoverTank")
            for (name in listOf("tank.png", "tank_dead.png", "shadow.png")) {
                Files.copy(fixture.resolve(name), temporary.resolve(name))
            }
            val base = JsonValue.readJSON(Files.readString(fixture.resolve("hover.json"))).asObject()
            base.get("core").asObject().set("name", "flare_guard")
            base.get("weapons").asArray().single().asObject().set("canShoot", false)
            val path = temporary.resolve("flare.json")
            for (kind in listOf("ordinary", "nuclear", "none")) {
                if (kind == "none") base.remove("abilities")
                else base.set("abilities", JsonValue.readJSON("""[{"name":"lastFlare","type":"deathExplosion",
                    "effect":"$kind","damage":500,"radius":70,"expandTicks":10}]"""))
                Files.writeString(path, base.toString())
                val definition = ContentLoader.loadHoverTankWithSources(path)
                val flare = assertIs<ContentResult.Success<LoadedUnitContent>>(definition, definition.toString()).value.definition
                val gunner = combatant(flare, "gunner", hit = 500.0, range = 110.0)
                val witness = combatant(flare, "witness", hit = 0.0, range = 0.0)
                val units = listOf(UnitSpawn("flare", "p2", flare.id, Position(210.0, 180.0), 270.0),
                    UnitSpawn("gunner", "p1", gunner.id, Position(160.0, 180.0), 90.0),
                    UnitSpawn("same-owner", "p2", witness.id, Position(210.0, 230.0), 0.0),
                    UnitSpawn("ally", "ally", witness.id, Position(210.0, 125.0), 0.0),
                    UnitSpawn("remote", "remote", witness.id, Position(370.0, 370.0), 0.0))
                for (enabled in listOf(true, false)) {
                    val fixtureScenario = WaterTestScene.scenario(map, MovementType.LAND, Position(160.0, 180.0))
                    val scenario = fixtureScenario.copy(unitDefinitions = listOf(flare, gunner, witness).associateBy { it.id },
                        players = listOf(Player("p1", PlayerControl.LOCAL, "blue"), Player("p2", PlayerControl.NONE, "red"),
                            Player("ally", PlayerControl.NONE, "red"), Player("remote", PlayerControl.NONE, "other")),
                        units = units, nukesEnabled = enabled,
                        automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
                        combatContract = CombatContract(12345, -1, units.mapIndexed { index, unit -> unit.unitId to (8L + index) }.toMap(), 13L))
                    val context = NuclearScene.open(scenario)
                    try {
                        val attack = context.submit(AttackAction("attack", "p1", "gunner", "flare", context.world))
                        assertIs<ActionResult.Accepted>(attack, attack.toString())
                        val remoteEvents = mutableListOf<VisibleProjectileEvent>()
                        val events = (0L..39L).flatMap {
                            val frame = context.advance(Step(it, 1.0, "sim-tick"))
                            remoteEvents += context.observePlayer(context.world, "remote").projectileEvents
                            frame.projectileEvents
                        }
                        val traces = events.filter { it.projectile.sourceUnitId == "flare" && it.projectile.areaExpansion != null }
                        val shouldExplode = kind == "ordinary" || kind == "nuclear" && enabled
                        assertTrue(remoteEvents.none { it.sourceUnitId == "flare" || it.targetUnitId == "flare" })
                        assertEquals(kind == "nuclear" && enabled,
                            remoteEvents.any { it.appearance == ProjectileAppearance.NUCLEAR && it.kind == "born" })
                        assertTrue(remoteEvents.none { it.appearance == ProjectileAppearance.STANDARD && it.areaRadius != null })
                        if (shouldExplode) assertTrue(observedHealth(context, "gunner") < 2000.0)
                        else assertEquals(2000.0, observedHealth(context, "gunner"))
                        assertEquals(if (shouldExplode) 1 else 0, traces.count { it.kind == "born" })
                        assertEquals(if (shouldExplode) 1 else 0, traces.count { it.kind == if (kind == "nuclear") "area-ended" else "impact" })
                        val observed = context.observe().units.associateBy { it.unitId }
                        assertFalse(observed.getValue("flare").alive)
                        assertEquals(shouldExplode, observed.getValue("same-owner").health!! < 2000.0)
                        assertEquals(kind == "nuclear" && enabled, observed.getValue("ally").health!! < 2000.0)
                    } finally { context.close() }
                }
            }
        }


    @Test fun inherited_death_parameters_and_locked_ground_forms_are_located_at_the_declaration() {
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("hover-production/json/package/units/hoverTank")
        for (name in listOf("tank.png", "tank_dead.png", "shadow.png"))
            Files.copy(fixture.resolve(name), temporary.resolve(name))
        val parent = temporary.resolve("parent.json")
        val child = temporary.resolve("child.json")
        val base = JsonValue.readJSON(Files.readString(fixture.resolve("hover.json"))).asObject()
        val ability = JsonValue.readJSON("""{"name":"onDeath","type":"deathExplosion",
            "effect":"ordinary","damage":100,"radius":20,"expandTicks":10}""").asObject()
        base.set("abilities", JsonValue.readJSON("[]").asArray().add(ability))
        val form = JsonValue.readJSON("""{"format":"rw-reforge.unit.v2","copyFrom":["parent.json"],
            "core":{"name":"quiet_guard"}}""").asObject()
        Files.writeString(child, form.toString())
        for ((field, invalid) in listOf("radius" to JsonValue.valueOf(0), "effect" to JsonValue.valueOf("unknown"),
                "expandTicks" to JsonValue.valueOf(36001))) {
            val original = ability.get(field)
            ability.set(field, invalid)
            Files.writeString(parent, base.toString())
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadHoverTankWithSources(child)).error
            assertEquals(parent.toString(), error.file)
            assertEquals("ability_onDeath", error.section)
            assertEquals(field, error.field)
            assertTrue("inheritance=" in error.reason)
            ability.set(field, original)
        }

        form.set("movement", JsonValue.readJSON("""{"movementType":"NONE","moveSpeed":0,"moveAccelerationSpeed":0,
            "moveDecelerationSpeed":0,"maxTurnSpeed":0,"turnAcceleration":0}"""))
        form.set("abilities", JsonValue.readJSON("""[{"name":"jump","type":"blink","range":700,"chargeTicks":210,
            "cooldownTicks":1500,"locked":true,"queueSpeed":3}]"""))
        Files.writeString(child, form.toString())
        Files.writeString(parent, base.toString())
        val locked = assertIs<ContentResult.Failure>(ContentLoader.loadHoverTankWithSources(child)).error
        assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, locked.code)
        assertEquals(parent.toString(), locked.file)
        assertEquals("ability_onDeath", locked.section)
        assertEquals("type", locked.field)
    }

    @Test fun a_lethally_hit_unfinished_fabricator_has_no_death_explosion() = WaterTestScene.withMap { map ->
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("build-economy/upgrades/units/fabricatorT1.ini")
        val result = ContentLoader.loadEconomyUnit(fixture)
        val fabricator = assertIs<ContentResult.Success<UnitDefinition>>(result, result.toString()).value
        assertNotNull(fabricator.deathExplosion)
        val worker = UnitDefinition("worker", "CustomUnitMetadata", 6.0, 6.0, 1, fabricator.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), builder = BuilderProfile(listOf(fabricator.id)))
        val gunner = combatant(fabricator, "gunner", 700.0, 110.0)
        val units = listOf(UnitSpawn("worker", "p2", worker.id, Position(200.0, 180.0), 90.0),
            UnitSpawn("gunner", "p1", gunner.id, Position(165.0, 180.0), 90.0))
        val scene = WaterTestScene.scenario(map, MovementType.LAND, units.last().position).copy(
            unitDefinitions = listOf(fabricator, gunner, worker).associateBy { it.id }, units = units,
            economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 10000L)),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            combatContract = CombatContract(12345, -1, mapOf("gunner" to 8L, "worker" to 9L), 10L))
        val context = NuclearScene.open(scene)
        try {
            val nonce = "a".repeat(32)
            val site = "constructed:p2:$nonce"
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p2", "worker", fabricator.id,
                Position(260.0, 180.0), nonce, context.world)))
            val birth = context.advance(Step(0L, 1.0, "sim-tick"))
            assertEquals(0f, birth.units.single { it.unitId == site }.constructionProgress)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("destroy", "p1", "gunner", site, context.world)))
            val frames = (1L..12L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            assertFalse(frames.last().units.single { it.unitId == site }.alive)
            assertTrue(frames.flatMap { it.projectileEvents }.none { it.projectile.sourceUnitId == site })
            assertEquals(2000.0, observedHealth(context, "gunner"))
        } finally { context.close() }
    }

    private fun observedHealth(context: SimulationContext, id: String) = assertNotNull(context.observe().units.single { it.unitId == id }.health)

    private fun combatant(base: UnitDefinition, id: String, hit: Double, range: Double): UnitDefinition {
        val movement = WaterTestScene.unit(MovementType.LAND).movement
        return UnitDefinition(id, "CustomUnitMetadata", 6.0, 6.0, 1, base.imagePath, 1.0, movement, emptySet(),
            fogOfWarSightRange = if (hit == 0.0) 4 else 15,
            durability = DurabilityProfile(2000.0, null), combat = if (hit == 0.0) null else CombatProfile(2000.0, range,
                80.0, 0.0, 8.0, 2.0, ProjectileProfile(hit, 80.0, 15.0, instant = true), null, null, 1.0))
    }
}
