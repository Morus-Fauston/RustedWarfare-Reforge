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

class AuthoredWeaponTest {
    @TempDir lateinit var temporary: Path

    @Test fun linked_weapons_on_an_authored_unit_obey_the_source_condition_and_use_the_declared_arc() =
        WaterTestScene.withMap { map ->
            val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
                .resolve("air-transport-production/json/production-package/units")
            Files.walk(fixtures, 1).use { files -> files.filter(Files::isRegularFile).forEach {
                Files.copy(it, temporary.resolve(it.fileName))
            } }
            val original = JsonValue.readJSON(Files.readString(temporary.resolve("helicopter.json"))).asObject()
            original.get("core").asObject().set("name", "field_flyer")
            val weapons = original.get("weapons").asArray()
            val leading = weapons.first().asObject()
            original.get("attack").asObject().set("canAttackLandUnits", "if not self.flying")
            val leader = leading.getString("name", "")
            val it = JsonValue.readJSON(leading.toString()).asObject()
            it.set("name", "auxiliary").set("linkDelayWithTurret", leader).remove("delay")
            it.set("projectile", JsonValue.readJSON("""{
                "directDamage":31,"life":180,"speed":5,"ballistic":true,
                "ballistic_delaymove_height":8,"ballistic_height":16,"autoTargetingOnDeadTarget":true
            }"""))
            weapons.add(it)
            val file = temporary.resolve("author.json")
            val base = WaterTestScene.scenario(map, MovementType.LAND, Position(160.0, 180.0))
            for (flying in listOf(false, true)) {
                original.get("movement").asObject().set("startingHeightOffset", if (flying) 20 else 0)
                Files.writeString(file, original.toString())
                val loaded = ContentLoader.loadHelicopterUnit(file)
                val source = assertIs<ContentResult.Success<UnitDefinition>>(loaded, loaded.toString()).value
                val arc = source.combat!!.namedWeapons!!.byName.getValue("auxiliary")
                assertEquals(CustomBallisticProfile(8f, 16f), arc.projectile.ballistic)
                assertTrue(arc.projectile.retargetOnDeath)
                assertEquals(leader, arc.linkDelayWithTurret)
                val target = UnitDefinition("target", "CustomUnitMetadata", 5.0, 5.0, 1, source.imagePath, 1.0,
                    WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(2000.0, null))
                val spawns = listOf(UnitSpawn("actor", "p1", source.id, Position(160.0, 180.0), 90.0),
                    UnitSpawn("target", "p2", target.id, Position(205.0, 180.0), 270.0))
                val scene = base.copy(unitDefinitions = mapOf(source.id to source, target.id to target), units = spawns,
                    combatContract = CombatContract(12345, -1, mapOf("actor" to 8L, "target" to 9L), 10L))
                val context = NuclearScene.open(scene)
                try {
                    val result = context.submit(AttackAction("order", "p1", "actor", "target", context.world))
                    if (flying) {
                        assertIs<ActionResult.Rejected>(result, result.toString())
                        repeat(20) { step -> assertTrue(context.advance(Step(step.toLong(), 1.0, "sim-tick")).projectileEvents.isEmpty()) }
                    } else {
                        assertIs<ActionResult.Accepted>(result, result.toString())
                        val sample = context.advance(Step(0, 1.0, "sim-tick"))
                        val names = sample.projectileEvents.filter { e -> e.kind == "born" }.map { e -> e.projectile.sourceWeaponName }
                        assertEquals(listOf(leader, "auxiliary"), names)
                    }
                } finally { context.close() }
            }
            it.set("linkDelayWithTurret", "absent")
            Files.writeString(file, original.toString())
            val missing = assertIs<ContentResult.Failure>(ContentLoader.loadHelicopterUnit(file))
            assertEquals(ContentErrorCode.BAD_REFERENCE, missing.error.code)
        }


    @Test fun landed_ground_fire_uses_the_current_height_without_blocking_air_fire() =
        WaterTestScene.withMap { map ->
            val path = writeAirCombat("field_carrier", land = true)
            val body = JsonValue.readJSON(Files.readString(path)).asObject()
            body.get("attack").asObject().set("canAttackLandUnits", "if not self.flying")
            Files.writeString(path, body.toString())
            val loaded = loadPack("air-combat", path)
            val source = loaded.units.getValue("field_carrier")
            val airTarget = UnitDefinition("air_target", "CustomUnitMetadata", 5.0, 5.0, 1, source.imagePath, 1.0,
                source.movement.copy(flight = source.movement.flight!!.copy(landWhenStationary = false)),
                emptySet(), durability = DurabilityProfile(2000.0, null),
                rotor = RotorDefinition(source.imagePath, 1.0))
            val ground = passive(source, "ground", 2000.0)
            val spawns = listOf(UnitSpawn("actor", "p1", source.id, Position(160.0, 180.0), 90.0),
                UnitSpawn("sky", "p2", airTarget.id, Position(230.0, 180.0), 270.0),
                UnitSpawn("ground", "p2", ground.id, Position(232.0, 210.0), 270.0))
            val context = NuclearScene.open(weaponScene(map, listOf(source, airTarget, ground), spawns))
            try {
                var frame = 0L
                fun advance() = context.advance(Step(frame++, 1.0, "sim-tick"))
                fun order(id: String, target: String) = context.submit(AttackAction(id, "p1", "actor", target, context.world))
                fun height() = context.observe().units.single { it.unitId == "actor" }.height
                assertTrue(height() >= 4)
                assertIs<ActionResult.Rejected>(order("ground-airborne", "ground"))
                assertIs<ActionResult.Accepted>(order("sky-airborne", "sky"))
                val shots = (0..2).flatMap { advance().projectileEvents }.filter { it.kind == "born" }
                assertTrue(shots.any { it.projectile.targetUnitId == "sky" && it.projectile.height >= 4 })
                assertIs<ActionResult.Accepted>(context.submit(StopAction("land", "p1", "actor", context.world)))
                repeat(110) { if (height() >= 4) advance() }
                assertTrue(height() < 4)
                assertIs<ActionResult.Accepted>(order("ground-landed", "ground"))
                val grounded = (0..30).flatMap { advance().projectileEvents }.filter { it.kind == "born" }
                assertTrue(grounded.any { it.projectile.targetUnitId == "ground" && it.projectile.height < 4 })
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("takeoff", "p1", "actor", Position(155.0, 260.0), context.world)))
                repeat(110) { if (height() < 4) advance() }
                assertTrue(height() >= 4)
                assertIs<ActionResult.Rejected>(order("ground-after-takeoff", "ground"))
            } finally { context.close() }

            body.get("attack").asObject().set("canAttackLandUnits", "true")
            Files.writeString(path, body.toString())
            val bad = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(temporary)).error
            assertEquals("canAttackLandUnits", bad.field)
            assertEquals(path.toString(), bad.file)
        }

    @Test fun air_and_stationary_flames_use_their_own_spread_and_actual_area_damage() =
        WaterTestScene.withMap { map ->
            val path = writeAirCombat("air_flame", land = false)
            val body = JsonValue.readJSON(Files.readString(path)).asObject()
            for (stationary in listOf(false, true)) {
                val id = if (stationary) "fixed_flame" else "air_flame"
                val damage = if (stationary) 4.0 else 45.0
                val radius = if (stationary) 65f else 40f
                val spread = if (stationary) 25f else 20f
                val speedSpread = if (stationary) 1f else 0f
                body.get("core").asObject().set("name", id)
                body.get("graphics").asObject().set("image_turret", "NONE")
                body.get("attack").asObject().set("canAttackFlyingUnits", false)
                val mount = body.get("weapons").asArray().single().asObject()
                mount.set("y", 0).set("barrelY", 0).set("delay", 250)
                val projectile = body.get("projectiles").asArray().single().asObject()
                projectile.set("directDamage", 0).set("life", if (stationary) 70 else 240)
                    .set("speed", if (stationary) 7.0 else 2.6).set("flameWeapon", true).set("targetGround", true)
                    .set("areaDamage", damage).set("areaRadius", radius.toDouble())
                    .set("targetGroundSpread", spread.toDouble()).set("speedSpread", speedSpread.toDouble())
                if (stationary) {
                    projectile.set("targetSpeed", 2.5).set("targetSpeedAcceleration", 0.3).set("deflectionPower", -1)
                    body.remove("movement")
                    body.set("movement", JsonValue.readJSON("""{"movementType":"NONE","moveSpeed":0,"moveAccelerationSpeed":0,
                        "moveDecelerationSpeed":0,"maxTurnSpeed":0,"turnAcceleration":0,"moveSlidingMode":false,"moveIgnoringBody":false}"""))
                    mount.set("recoilOutTime", 1).set("recoilReturnTime", 1)
                }
                Files.writeString(path, body.toString())
                val kind = if (stationary) "static-combat" else "air-combat"
                val unit = loadPack(kind, path).units.getValue(id)
                assertEquals(stationary, unit.building != null)
                val ground = unit.combat!!.projectile.ground
                assertEquals(GroundProjectileProfile(damage.toFloat(), radius, spread, 0f, 0f,
                    speedSpread = speedSpread, resolveAtSnapshot = true), ground)
                val target = passive(unit, "target", 2000.0)
                val spawns = listOf(UnitSpawn("actor", "p1", id, Position(170.0, 170.0), 90.0),
                    UnitSpawn("target", "p2", target.id, Position(245.0, 170.0), 270.0),
                    UnitSpawn("outer", "p2", target.id, Position(245.0, 222.0), 270.0),
                    UnitSpawn("ally", "p1", target.id, Position(245.0, 165.0), 270.0))
                val context = NuclearScene.open(weaponScene(map, listOf(unit, target), spawns))
                try {
                    assertIs<ActionResult.Accepted>(context.submit(AttackAction("fire", "p1", "actor", "target", context.world)))
                    val frames = (0L..90L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                    val events = frames.flatMap { it.projectileEvents }
                    val shot = events.single { it.kind == "born" }.projectile
                    assertEquals(ProjectileAppearance.MECH_FLAME, shot.appearance)
                    val point = assertNotNull(shot.ground).targetPosition
                    val sourceTarget = spawns[1].position
                    assertTrue(kotlin.math.abs(point.x - sourceTarget.x) <= spread)
                    assertTrue(kotlin.math.abs(point.y - sourceTarget.y) <= spread)
                    assertTrue(events.any { it.kind == "impact" && it.projectile.objectId == shot.objectId })
                    val results = frames.last().units.associateBy { it.unitId }
                    fun afterBlast(p: Position): Double {
                        val distance = GroundMath.sqrt(GroundMath.square(point.x.toFloat(), point.y.toFloat(),
                            p.x.toFloat(), p.y.toFloat()))
                        if (distance > radius) return 2000.0
                        return 2000.0 - damage.toFloat() * minOf(1f, (1f - distance / radius + .1f))
                    }
                    assertEquals(afterBlast(sourceTarget), results.getValue("target").health!!, .002)
                    assertEquals(afterBlast(spawns[2].position), results.getValue("outer").health!!, .002)
                    assertEquals(2000.0, results.getValue("ally").health)
                } finally { context.close() }
                projectile.set("targetGround", false)
                Files.writeString(path, body.toString())
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(temporary))
            }
        }

    private fun writeAirCombat(id: String, land: Boolean): Path {
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("air-transport-production/json/production-package/units")
        for (name in listOf("body.png", "dead.png")) Files.copy(fixture.resolve(name), temporary.resolve(name))
        val body = JsonValue.readJSON(Files.readString(fixture.resolve("helicopter.json"))).asObject()
        body.set("format", "rw-reforge.unit.v2")
        body.remove("arm_1")
        body.get("core").asObject().set("name", id)
        body.get("movement").asObject().set("landOnGround", land).set("startingHeightOffset", 20)
        return temporary.resolve("author.json").also { Files.writeString(it, body.toString()) }
    }

    private fun loadPack(kind: String, file: Path): LoadedContentPack {
        Files.writeString(temporary.resolve("pack.json"), """{
            "format":"rw-reforge.content-pack.v1","id":"authored-specials","name":"Authored specials",
            "units":[{"type":"$kind","file":"${file.fileName}"}]
        }""")
        val loaded = ContentLoader.loadContentPack(temporary)
        return assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
    }

    private fun passive(source: UnitDefinition, id: String, hp: Double) =
        UnitDefinition(id, "CustomUnitMetadata", 5.0, 5.0, 1, source.imagePath, 1.0,
            WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(hp, null))

    private fun weaponScene(map: MapDefinition, definitions: List<UnitDefinition>, spawns: List<UnitSpawn>) =
        WaterTestScene.scenario(map, MovementType.LAND, spawns.first().position).copy(
            unitDefinitions = definitions.associateBy { it.id }, units = spawns,
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { index, unit ->
                unit.unitId to (8L + index)
            }.toMap(), 8L + spawns.size))




    @Test fun linked_flank_checks_its_range_each_time_the_leader_shoots() = WaterTestScene.withMap { map ->
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("hover-production/json/package/units/hoverTank")
        for (name in listOf("tank.png", "tank_dead.png", "shadow.png"))
            Files.copy(fixture.resolve(name), temporary.resolve(name))
        val body = JsonValue.readJSON(Files.readString(fixture.resolve("hover.json"))).asObject()
        body.get("core").asObject().set("name", "crossfire")
        val mounts = body.get("weapons").asArray()
        val main = mounts.single().asObject().set("delay", 16)
        mounts.add(JsonValue.readJSON(main.toString()).asObject().set("name", "auxiliary")
            .set("linkDelayWithTurret", "1").set("limitingRange", 72).remove("delay"))
        val path = temporary.resolve("author.json")
        Files.writeString(path, body.toString())
        val actor = loadPack("hover-tank", path).units.getValue("crossfire")
        val target = passive(actor, "target", 12000.0)
        for (targetX in listOf(210.0, 250.0)) {
            val spawns = listOf(UnitSpawn("actor", "p1", actor.id, Position(160.0, 180.0), 90.0),
                UnitSpawn("target", "p2", target.id, Position(targetX, 180.0), 270.0))
            val context = NuclearScene.open(weaponScene(map, listOf(actor, target), spawns))
            try {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("fire", "p1", "actor", "target", context.world)))
                val volleys = (0L..49L).map { frame ->
                    context.advance(Step(frame, 1.0, "sim-tick")).projectileEvents
                        .filter { it.kind == "born" }.map { it.projectile.sourceWeaponName }
                }.filter { it.isNotEmpty() }
                assertEquals(4, volleys.size)
                assertEquals(List(4) { if (targetX == 210.0) listOf("1", "auxiliary") else listOf("1") }, volleys)
            } finally { context.close() }
        }
    }

    @Test fun hover_weapon_follows_its_current_water_contact() = WaterTestScene.withMap { map ->
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("hover-production/json/package/units/hoverTank")
        for (name in listOf("tank.png", "tank_dead.png", "shadow.png"))
            Files.copy(fixture.resolve(name), temporary.resolve(name))
        val body = JsonValue.readJSON(Files.readString(fixture.resolve("hover.json"))).asObject()
        body.get("core").asObject().set("name", "crossing_guard")
        body.get("weapons").asArray().single().asObject().set("canAttackCondition", "if self.overwater")
        val file = temporary.resolve("author.json")
        Files.writeString(file, body.toString())
        val guard = loadPack("hover-tank", file).units.getValue("crossing_guard")
        val target = passive(guard, "target", 2000.0)
        val shore = Position(350.0, 180.0)
        val water = Position(425.0, 180.0)
        fun overWater(p: Position) = map.isOverWater((p.x / map.tileWidth).toInt(), (p.y / map.tileHeight).toInt())
        assertFalse(overWater(shore))
        assertTrue(overWater(water))
        val spawns = listOf(UnitSpawn("actor", "p1", guard.id, shore, 90.0),
            UnitSpawn("target", "p2", target.id, Position(370.0, 210.0), 90.0))
        val context = NuclearScene.open(weaponScene(map, listOf(guard, target), spawns))
        try {
            var frame = 0L
            fun advance() = context.advance(Step(frame++, 1.0, "sim-tick"))
            fun position() = context.observe().units.single { it.unitId == "actor" }.position
            fun attack(id: String) = context.submit(AttackAction(id, "p1", "actor", "target", context.world))
            assertIs<ActionResult.Rejected>(attack("land"))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("cross", "p1", "actor", water, context.world)))
            val events = mutableListOf<ProjectileEventObservation>()
            repeat(160) {
                if (!overWater(position())) events += advance().projectileEvents
            }
            assertTrue(overWater(position()))
            assertIs<ActionResult.Accepted>(attack("water"))
            repeat(90) { events += advance().projectileEvents }
            assertTrue(events.any { it.kind == "born" && it.projectile.targetUnitId == "target" })
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("return", "p1", "actor", shore, context.world)))
            repeat(200) { if (overWater(position())) advance() }
            assertFalse(overWater(position()))
            assertIs<ActionResult.Rejected>(attack("land-again"))
        } finally { context.close() }
    }

    @Test fun inherited_beam_reference_errors_identify_the_declaring_weapon_field() {
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("hover-production/json/package/units/hoverTank")
        for (name in listOf("tank.png", "tank_dead.png", "shadow.png"))
            Files.copy(fixture.resolve(name), temporary.resolve(name))
        Files.writeString(temporary.resolve("wrong.png"), "wrong png")
        val parent = temporary.resolve("parent.json")
        val child = temporary.resolve("child.json")
        val body = JsonValue.readJSON(Files.readString(fixture.resolve("hover.json"))).asObject()
        val mount = body.get("weapons").asArray().single().asObject()
        Files.writeString(child, """{"format":"rw-reforge.unit.v2","copyFrom":["parent.json"],
            "core":{"name":"beam_guard"}}""")
        val outside = Files.createTempFile("reforge-beam-outside-", ".png")
        try {
            Files.write(outside, Files.readAllBytes(temporary.resolve("tank.png")))
            for (image in listOf("missing.png", "wrong.png", outside.toString())) {
                mount.set("projectile", JsonValue.readJSON("""{"directDamage":12,"life":40,"speed":3,
                    "instant":true,"beamStartImage":"tank.png","beamEndImage":"tank.png"}""").asObject()
                    .set("beamImage", image))
                body.remove("projectiles")
                Files.writeString(parent, body.toString())
                Files.writeString(temporary.resolve("pack.json"), """{
                    "format":"rw-reforge.content-pack.v1","id":"beam-check","name":"Beam check",
                    "units":[{"type":"hover-tank","file":"child.json"}]
                }""")
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(temporary)).error
                assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
                assertEquals(parent.toString(), error.file)
                assertEquals("turret_1", error.section)
                assertEquals("projectile.beamImage", error.field)
                assertTrue("inheritance=" in error.reason)
            }
        } finally { Files.deleteIfExists(outside) }
        mount.set("canAttackLandUnits", "true")
        Files.writeString(parent, body.toString())
        val invalid = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(temporary)).error
        assertEquals("turret_1", invalid.section)
        assertEquals("canAttackLandUnits", invalid.field)
    }

    @Test fun continuous_beam_rearms_while_lightning_spends_shield_and_hp_at_its_own_shot() =
        WaterTestScene.withMap { map ->
            val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
                .resolve("hover-production/json/package/units/hoverTank")
            for (name in listOf("tank.png", "tank_dead.png", "shadow.png")) {
                Files.copy(fixture.resolve(name), temporary.resolve(name))
            }
            for (kind in listOf("beam", "lightning", "lightning-locked")) {
                val body = JsonValue.readJSON(Files.readString(fixture.resolve("hover.json"))).asObject()
                val type = "field_$kind"
                body.get("core").asObject().set("name", type)
                val mount = body.get("weapons").asArray().single().asObject()
                mount.set("delay", 5)
                if (kind.startsWith("lightning")) {
                    mount.set("canAttackCondition", "if not self.hasFlag(id=0)")
                    body.get("core").asObject().set("initialWeaponFlagZero", kind == "lightning-locked")
                }
                val payload = JsonValue.readJSON("""{
                    "directDamage":20,"life":20,"speed":5,"instant":true,
                    "shieldDamage":0.75,"shieldDefection":0.25,"buildingDamage":1
                }""").asObject()
                if (kind == "beam") {
                    payload.set("beamImage", "tank.png").set("beamStartImage", "tank.png").set("beamEndImage", "tank.png")
                } else payload.set("lightingEffect", true)
                mount.set("projectile", payload)
                body.remove("projectiles")
                val file = temporary.resolve("$type.json")
                Files.writeString(file, body.toString())
                val loaded = ContentLoader.loadHoverTankWithSources(file)
                val unit = assertIs<ContentResult.Success<LoadedUnitContent>>(loaded, loaded.toString()).value.definition
                val target = UnitDefinition("shielded", "CustomUnitMetadata", 6.0, 6.0, 1, unit.imagePath, 1.0,
                    WaterTestScene.unit(MovementType.LAND).movement, emptySet(), durability = DurabilityProfile(1000.0, null),
                    shield = ShieldProfile(100f, 0f))
                val base = WaterTestScene.scenario(map, MovementType.LAND, Position(160.0, 180.0))
                val scene = base.copy(unitDefinitions = listOf(unit, target).associateBy { it.id },
                    units = listOf(UnitSpawn("actor", "p1", type, Position(160.0, 180.0), 90.0),
                        UnitSpawn("target", "p2", target.id, Position(240.0, 180.0), 270.0)),
                    combatContract = CombatContract(12345, -1, mapOf("actor" to 8L, "target" to 9L), 10L))
                val context = NuclearScene.open(scene)
                try {
                    val order = context.submit(AttackAction("attack", "p1", "actor", "target", context.world))
                    if (kind == "lightning-locked") {
                        assertIs<ActionResult.Rejected>(order)
                        assertTrue((0L..8L).all { context.advance(Step(it, 1.0, "sim-tick")).projectileEvents.isEmpty() })
                        continue
                    }
                    assertIs<ActionResult.Accepted>(order)
                    val frames = (0L..8L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                    val first = frames.first()
                    val firstTarget = first.units.single { it.unitId == "target" }
                    assertEquals(985.0, firstTarget.health)
                    assertEquals(85f, firstTarget.shield?.current)
                    assertEquals(985.0, frames[1].units.single { it.unitId == "target" }.health)
                    val events = frames.flatMap { it.projectileEvents }
                    val born = events.first { it.kind == "born" }.projectile
                    if (kind == "beam") {
                        assertEquals(type, born.beam?.resourceUnitType)
                        val rearmed = events.single { it.kind == "rearmed" }
                        assertEquals(born.objectId, rearmed.projectile.objectId)
                    } else {
                        assertNotNull(born.lightning)
                        assertEquals(2, events.filter { it.kind == "born" }.map { it.projectile.objectId }.distinct().size)
                        assertTrue(events.none { it.kind == "rearmed" })
                    }
                    assertEquals(970.0, frames.last().units.single { it.unitId == "target" }.health)
                } finally { context.close() }
                payload.set("instant", false)
                Files.writeString(file, body.toString())
                assertIs<ContentResult.Failure>(ContentLoader.loadHoverTankWithSources(file))
            }
        }


    @Test fun an_authored_water_weapon_changes_to_a_live_enemy_after_the_first_target_dies() =
        WaterTestScene.withMap { map ->
            val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("author-water/json/package/units")
            for (file in listOf("base.ini", "ship.png", "ship_dead.png", "cannon.png")) {
                Files.copy(fixture.resolve(file), temporary.resolve(file))
            }
            val doc = JsonValue.readJSON(Files.readString(fixture.resolve("spcruiser.json"))).asObject()
            doc.get("core").asObject().set("name", "arc_cutter")
            val mounts = doc.get("weapons").asArray()
            mounts.get(0).asObject().set("projectile", JsonValue.readJSON("""{"directDamage":200,"life":80,"speed":10}"""))
            mounts.get(1).asObject().set("projectile", JsonValue.readJSON("""{
                "directDamage":35,"life":250,"speed":3,"ballistic":true,
                "ballistic_delaymove_height":10,"ballistic_height":20,"autoTargetingOnDeadTarget":true
            }"""))
            val file = temporary.resolve("arc_cutter.json")
            Files.writeString(file, doc.toString())
            Files.writeString(temporary.resolve("pack.json"), """{
                "format":"rw-reforge.content-pack.v1","id":"arc-cutter","name":"Arc cutter",
                "units":[{"type":"water-combat","file":"arc_cutter.json"}]
            }""")
            val loaded = ContentLoader.loadContentPack(temporary)
            val unit = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units.getValue("arc_cutter")
            assertTrue(unit.combat!!.namedWeapons!!.byName.getValue("starboard").projectile.retargetOnDeath)
            fun passive(name: String, health: Double) = UnitDefinition(name, "CustomUnitMetadata", 5.0, 5.0, 1,
                unit.imagePath, 1.0, WaterTestScene.unit(MovementType.HOVER).movement, emptySet(),
                durability = DurabilityProfile(health, null))
            val fragile = passive("fragile", 150.0)
            val hull = passive("hull", 2000.0)
            for (eligible in listOf(false, true)) {
                val units = listOf(UnitSpawn("cutter", "p1", unit.id, Position(500.0, 180.0), 90.0),
                    UnitSpawn("first", "p2", fragile.id, Position(590.0, 180.0), 90.0),
                    UnitSpawn("replacement", if (eligible) "p2" else "p1", hull.id, Position(610.0, 180.0), 90.0))
                val scene = WaterTestScene.scenario(map, MovementType.WATER, units.first().position).copy(
                    unitDefinitions = listOf(unit, fragile, hull).associateBy { it.id }, units = units,
                    combatContract = CombatContract(12345, -1, mapOf("cutter" to 1L, "first" to 2L, "replacement" to 3L), 4L),
                    players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE),
                        Player("observer", PlayerControl.NONE)),
                    automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
                val context = NuclearScene.open(scene)
                try {
                    assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "cutter", "first", context.world)))
                    val visible = mutableListOf<VisibleProjectileEvent>()
                    val frames = (0L..65L).map {
                        val frame = context.advance(Step(it, 1.0, "sim-tick"))
                        visible += context.observePlayer(context.world, "p1").projectileEvents
                        assertTrue(context.observePlayer(context.world, "observer").projectileEvents.isEmpty())
                        frame
                    }
                    val launch = frames.flatMap { it.projectileEvents }.first {
                        it.kind == "born" && it.projectile.sourceWeaponName == "starboard"
                    }.projectile
                    assertEquals("first", launch.targetUnitId)
                    assertTrue(frames.any { !it.units.single { u -> u.unitId == "first" }.alive })
                    val redirected = frames.flatMap { it.projectiles }.any {
                        it.objectId == launch.objectId && it.targetUnitId == "replacement"
                    }
                    assertEquals(eligible, redirected)
                    val hit = frames.flatMap { it.projectileEvents }.any {
                        it.projectile.objectId == launch.objectId && it.kind == "impact" && it.projectile.targetUnitId == "replacement"
                    }
                    assertEquals(eligible, hit)
                    assertEquals(eligible, visible.any { it.kind == "impact" && it.targetUnitId == "replacement" &&
                        it.sourceWeaponName == "starboard" && it.sourceUnitId == "cutter" })
                } finally { context.close() }
            }
        }

}

internal fun authoredWeaponsRepresentative(fixture: Path, file: String, type: String): UnitDefinition {
    val root = Files.createTempDirectory("authored-special-weapons-")
    val source = fixture.resolve(file).parent
    val destination = root.resolve("body")
    Files.walk(source, 1).use { paths ->
        Files.createDirectories(destination)
        paths.filter(Files::isRegularFile).forEach { Files.copy(it, destination.resolve(it.fileName)) }
    }
    val definition = destination.resolve(Path.of(file).fileName)
    val document = JsonValue.readJSON(Files.readString(definition)).asObject()
    document.get("core").asObject().set("name", type)
    Files.writeString(definition, document.toString())
    val capability = if (file == "bomber.json") "bomber" else "mech-flame"
    Files.writeString(root.resolve("pack.json"), """{
        "format":"rw-reforge.content-pack.v1","id":"authored-specialist","name":"Authored specialist",
        "units":[{"type":"$capability","file":"body/${definition.fileName}"}]
    }""")
    val result = ContentLoader.loadContentPack(root)
    return assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value.units.getValue(type)
}
