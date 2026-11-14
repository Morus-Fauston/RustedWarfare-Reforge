package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AuthoredInterceptionTest {
    @TempDir lateinit var temporary: Path

    @Test fun alternate_facility_names_use_the_declared_stock_and_interception_roles() = WaterTestScene.withMap { map ->
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("nuclear-silo/json/package")
        for (image in listOf("arm1.png", "arm2.png", "back.png", "body.png", "dead.png")) {
            Files.copy(fixture.resolve(image), temporary.resolve(image))
        }
        val document = JsonValue.readJSON(Files.readString(fixture.resolve("nukeLauncherC.json"))).asObject()
        fun load(id: String, role: String): UnitDefinition {
            document.set("name", id).set("ordnanceRole", role)
            val file = temporary.resolve("$id.json")
            Files.writeString(file, document.toString())
            return assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadNuclearSiloWithSources(file)).value.definition
        }
        val launcher = load("training_launcher", "launcher")
        document.set("maxHp", 2800).set("price", 15000).set("constructionFootprint", "-1,-1,1,1")
        val interceptor = load("training_interceptor", "interceptor")
        val base = interceptorScenario(map)
        val input = base.copy(unitDefinitions = listOf(launcher, interceptor).associateBy { it.id },
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 11000L)),
            units = base.units.map { if (it.unitId == "silo") it.copy(typeId = launcher.id, ownerPlayerId = "p2")
                else it.copy(typeId = interceptor.id) })
        val context = NuclearScene.open(input)
        try {
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("paid-launcher", "p2", "silo", "buildNuke", context.world)))
            assertEquals(0L, context.observePlayer(context.world, "p2").balance)
            assertIs<ActionResult.Rejected>(context.submit(UnitAction("wrong-role", "p1", "defense", "buildNuke", context.world)))
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            repeat(3334) { advance() }
            assertEquals(1, context.observePlayer(context.world, "p2").units.single { it.unitId == "silo" }.unitActions?.stockpile)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("paid-interception", "p1", "defense", "buildAntiNuke", context.world)))
            assertEquals(0L, context.observePlayer(context.world, "p1").balance)
            repeat(1080) { advance() }
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("launch", "p2", "silo", "launchNuke", context.world,
                target = Position(650.0, 180.0))))
            val events = mutableListOf<ProjectileEventObservation>()
            repeat(950) { events += advance().projectileEvents }
            val stopped = events.single { it.kind == "intercepted" && it.projectile.appearance == ProjectileAppearance.NUCLEAR }
            assertEquals("defense", stopped.interceptorUnitId)
            assertFalse(events.any { it.kind == "impact" && it.projectile.appearance == ProjectileAppearance.NUCLEAR })
            assertEquals(0, context.observePlayer(context.world, "p1").units.single { it.unitId == "defense" }.unitActions?.stockpile)
        } finally { context.close() }
    }


    @Test fun a_water_combat_hull_builds_ammunition_using_its_declared_interception_mount() = WaterTestScene.withMap { map ->
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("author-water/json/package/units")
        for (name in listOf("base.ini", "ship.png", "ship_dead.png", "cannon.png")) {
            Files.copy(fixture.resolve(name), temporary.resolve(name))
        }
        val hullFile = temporary.resolve("escort.json")
        val hull = JsonValue.readJSON(Files.readString(fixture.resolve("spcruiser.json"))).asObject()
        hull.get("core").asObject().set("name", "escort_cutter")
        hull.add("attack", JsonValue.readJSON("""{"maxAttackRange":240}"""))
        hull.get("weapons").asArray().add(JsonValue.readJSON("""{
            "name":"screen","x":7,"y":20,"canShoot":false,"invisible":true,
            "projectile":{"directDamage":600,"life":99999,"speed":0.2,"deflectionPower":-1,
            "targetSpeed":6.5,"targetSpeedAcceleration":0.05,"ballistic":true,
            "ballistic_delaymove_height":80,"ballistic_height":110}
        }"""))
        hull.set("abilities", JsonValue.readJSON("""[{
            "name":"missileScreen","type":"navalInterception","weapon":"screen",
            "landingRange":525,"trackingRange":1150,"minimumHeight":50,
            "ammoPrice":1200,"ammoBuildRate":0.01,"ammoLimit":2
        }]"""))
        Files.writeString(hullFile, hull.toString())
        Files.writeString(temporary.resolve("pack.json"), """{
            "format":"rw-reforge.content-pack.v1","id":"escort-cutter","name":"Escort cutter",
            "units":[{"type":"water-combat","file":"escort.json"}]
        }""")
        val loaded = ContentLoader.loadContentPack(temporary)
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
        val naval = NautilusScene.scenario(map)
        val nuclear = NuclearScene.scenario(map)
        val scene = naval.copy(
            players = naval.players.map { it.copy(control = PlayerControl.LOCAL) },
            unitDefinitions = pack.units + nuclear.unitDefinitions,
            units = listOf(naval.units.single().copy(typeId = "escort_cutter"),
                nuclear.units.single().copy(ownerPlayerId = "p2")),
            combatContract = CombatContract(12345, -1, mapOf("silo" to 1L, "mother" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 1200L, "p2" to 11000L)),
        )
        val noEconomy = NuclearScene.open(scene.copy(unitDefinitions = pack.units, units = scene.units.take(1),
            economyContract = null, combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L)))
        try {
            val error = assertIs<ActionResult.Rejected>(noEconomy.submit(
                UnitAction("no-economy", "p1", "mother", "buildAntiNuke", noEconomy.world))).error
            assertEquals("insufficient-funds", error.code)
        } finally { noEconomy.close() }
        val context = NuclearScene.open(scene)
        try {
            fun command(id: String, player: String, unit: String, name: String, target: Position? = null) =
                context.submit(UnitAction(id, player, unit, name, context.world, target = target))
            fun escort() = context.observePlayer(context.world, "p1").units.single { it.unitId == "mother" }
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Rejected>(command("unauthorized", "p2", "mother", "buildAntiNuke"))
            assertIs<ActionResult.Accepted>(command("escort-ammo", "p1", "mother", "buildAntiNuke"))
            assertEquals(0L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Accepted>(command("incoming-ammo", "p2", "silo", "buildNuke"))
            repeat(3334) { advance() }
            assertEquals(1, escort().unitActions?.stockpile)
            val incoming = command("incoming", "p2", "silo", "launchNuke", Position(650.0, 180.0))
            assertIs<ActionResult.Accepted>(incoming, incoming.toString())
            val events = mutableListOf<ProjectileEventObservation>()
            repeat(1250) { events += advance().projectileEvents }
            val interceptor = events.single { it.kind == "born" && it.projectile.appearance == ProjectileAppearance.INTERCEPTOR }
            assertEquals("screen", interceptor.projectile.sourceWeaponName)
            assertEquals("mother", events.single { it.kind == "intercepted" &&
                it.projectile.appearance == ProjectileAppearance.NUCLEAR }.interceptorUnitId)
            assertFalse(events.any { it.kind == "impact" && it.projectile.appearance == ProjectileAppearance.NUCLEAR })
            assertEquals(0, escort().unitActions?.stockpile)
        } finally { context.close() }
    }

    @Test fun a_declared_mobile_defense_spends_its_own_energy_on_an_ordinary_hostile_round() {
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("hover-production/json/package/units/hoverTank")
        for (image in listOf("tank.png", "tank_dead.png", "shadow.png")) {
            Files.copy(fixture.resolve(image), temporary.resolve(image))
        }
        val source = JsonValue.readJSON(Files.readString(fixture.resolve("hover.json"))).asObject()
        source.get("core").asObject().set("name", "shore_guard")
        source.set("abilities", JsonValue.readJSON("""[
            {"name":"deflector","type":"pointDefense","range":180,"recharge":0,"cost":0.25,
             "initialEnergy":0.75,"muzzleOffsetX":100,"muzzleOffsetY":160}
        ]"""))
        val declaration = temporary.resolve("shore-guard.json")
        Files.writeString(declaration, source.toString())
        val unit = assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadHoverTankWithSources(declaration)).value.definition
        assertEquals(PointDefenseProfile(180.0, 100.0, 160.0, 0f, .25f, .75f, authored = true), unit.pointDefense)

        for (eligible in listOf(true, false)) {
            val base = PointDefenseTestContent.scene(nonDeflectable = !eligible)
            val input = base.copy(unitDefinitions = base.unitDefinitions - "laserDefence" + (unit.id to unit),
                units = base.units.map { if (it.unitId == "d") it.copy(typeId = unit.id) else it })
            val trace = PointDefenseTestContent.trace(input, 12)
            val deflections = trace.flatMap { it.projectileEvents }.filter { it.kind == "deflected" }
            assertEquals(if (eligible) listOf("d") else emptyList(), deflections.map { it.interceptorUnitId })
            assertEquals(if (eligible) .50f else .75f, trace.last().units.single { it.unitId == "d" }.pointDefense?.energy)
        }
    }
}
