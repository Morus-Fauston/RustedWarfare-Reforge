package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.FlightProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.OrdinarySplashProfile
import com.rustedwarfare.reforge.core.content.ProjectileProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonArray
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ModularSpiderWeaponModuleTest {
    @field:TempDir
    lateinit var workspace: Path

    private val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun pack(format: String): LoadedContentPack {
        val loaded = ContentLoader.loadContentPack(fixtures.resolve("modular-spider-start/$format/opening-package"))
        return assertIs<ContentResult.Success<LoadedContentPack>>(loaded,
            (loaded as? ContentResult.Failure)?.error?.describe()).value
    }

    @Test fun normal_three_format_pack_loads_the_qualified_weapon_forms_and_mounts() {
        for (format in listOf("ini", "json", "hjson")) {
            val pack = pack(format)
            fun unit(suffix: String) = pack.units.getValue("modularSpider_$suffix")
            fun mounts(suffix: String) = assertNotNull(unit(suffix).combat?.namedWeapons).ordered

            val expectedHealth = mapOf("smallgunturret" to 1000.0, "smallgunturretT2" to 2000.0,
                "gunturretT2" to 3000.0, "antiair" to 2000.0, "antiairT2" to 3000.0, "antiairFlak" to 3000.0)
            for ((name, health) in expectedHealth) assertEquals(health, unit(name).maximumHealth, name)
            assertEquals(listOf("buildGun", "buildSmallLaser", "buildAntiair"),
                unit("emptySlot").attachmentModule?.installs?.map { it.action })
            assertEquals(1000L, unit("antiair").attachmentModule?.cancel?.refund)
            assertEquals(500L, unit("antiair").attachmentModule?.reclaim?.refund)
            val laser = mounts("smallgunturret").single()
            val advancedLaser = mounts("smallgunturretT2").single()
            assertTrue(laser.canAttackLandUnits && laser.canAttackFlyingUnits)
            assertEquals(80.0, laser.shootDelay)
            assertEquals(30.0, advancedLaser.shootDelay)
            assertEquals(5.0, advancedLaser.imageOffsetY)
            assertEquals(70.0, advancedLaser.projectile.directDamage)
            assertEquals(13.0, mounts("gunturretT2").single().shootDelay)
            assertEquals(40.0, mounts("gunturretT2").single().projectile.directDamage)
            val missile = mounts("antiair").single()
            assertFalse(missile.canAttackLandUnits)
            assertTrue(missile.canAttackFlyingUnits && missile.projectile.retargetOnDeath)
            assertEquals(6.0, missile.projectile.acceleration?.targetSpeed)
            val antiAir = mounts("antiairT2")
            val flak = mounts("antiairFlak")
            for (assembly in listOf(antiAir, flak)) {
                assertEquals(listOf("1", "2", "3"), assembly.map { it.name })
                assertFalse(assembly.first().canShoot)
                assertTrue(assembly.drop(1).all { it.attachedTo == "1" && it.slave && it.invisible })
                assertEquals("2", assembly.last().linkDelayWithTurret)
            }
            assertEquals(25.0, antiAir.last().warmup)
            assertEquals(55.0, flak.last().warmup)
            assertEquals(OrdinarySplashProfile(100f, 70f), flak.last().projectile.splash)
            for (file in listOf("small-laser", "small-laser-t2", "gun-t2", "aa", "aa-t2", "flak"))
                assertTrue(pack.sources.any { it.path.fileName.toString() == "$file.$format" }, file)
        }
    }

    @Test fun paid_early_upgrade_waits_for_self_build_and_survives_page_and_stop_commands() =
        withBattle(26000) { battle ->
            val id = battle.slot(1).unitId
            battle.click("selectSlot1")
            battle.click("buildSmallLaser")
            battle.tick()
            val laser = battle.unit(id)
            assertEquals("modularSpider_smallgunturret", laser.typeId)
            assertTrue(laser.constructionProgress < 1f)
            val beforeUpgrade = battle.balance()
            battle.click("upgradeT2")
            assertEquals(beforeUpgrade - 3000, battle.balance())
            repeat(35) { battle.tick() }
            assertEquals(0f, battle.action("upgradeT2").progress)
            assertNotNull(battle.action("upgradeT2").queueIndex)
            battle.click("selectSlot2")
            battle.click("buildGun")
            battle.tick()
            assertEquals("modularSpider_gunturret", battle.slot(2).typeId)
            assertIs<ActionResult.Accepted>(battle.context.submit(StopAction("stop", "p1", "spider", battle.context.world)))
            battle.click("selectSlot1")
            battle.until { battle.slot(1).progress == 1f }
            assertEquals("modularSpider_smallgunturret", battle.slot(1).typeId)
            assertEquals(1f / 960f, assertNotNull(battle.action("upgradeT2").progress), .000001f)
            repeat(958) { battle.tick() }
            assertEquals("modularSpider_smallgunturret", battle.slot(1).typeId)
            val conversion = battle.tick()
            assertEquals("modularSpider_smallgunturretT2", battle.slot(1).typeId)
            assertEquals(id, battle.slot(1).unitId)
            assertEquals(2000.0, battle.unit(id).health)
            assertEquals(1f, battle.slot(1).progress)
            assertTrue(conversion.events.any { it.kind == "attachment-upgrade-completed" && it.unitId == id })
        }

    @Test fun anti_air_build_and_queued_upgrade_have_separate_cancellation_refunds() =
        withBattle(26000) { battle ->
            battle.click("selectSlot1")
            val id = battle.slot(1).unitId
            battle.click("buildAntiair")
            assertEquals(24000L, battle.balance())
            battle.tick()
            battle.click("upgradeT2")
            assertEquals(8000L, battle.balance())
            assertEquals("attachment-slot-busy", assertIs<ActionResult.Rejected>(
                battle.request("upgradeFlak")).error.code)
            assertTrue(battle.action("cancel").available)
            battle.click("upgradeT2", right = true)
            assertEquals(24000L, battle.balance())
            assertEquals("modularSpider_antiair", battle.unit(id).typeId)
            battle.click("upgradeFlak")
            assertEquals(14000L, battle.balance())
            battle.click("cancel")
            assertEquals(14000L, battle.balance())
            battle.tick()
            assertEquals(25000L, battle.balance())
            assertEquals(id, battle.slot(1).unitId)
            assertEquals("modularSpider_emptySlot", battle.slot(1).typeId)
            assertEquals("attachment-slot-cooldown", assertIs<ActionResult.Rejected>(
                battle.request("buildGun")).error.code)
            repeat(119) { battle.tick() }
            assertFalse(battle.action("buildGun").available)
            battle.tick()
            assertTrue(battle.action("buildGun").available)
        }

    @Test fun reclaim_refunds_the_current_core_and_replacement_uses_the_same_slot() =
        withBattle(26000) { battle ->
            val id = battle.slot(1).unitId
            battle.click("selectSlot1")
            battle.click("buildAntiair")
            battle.tick()
            battle.until { battle.slot(1).progress == 1f }
            battle.click("reclaim")
            repeat(179) { battle.tick() }
            assertEquals("modularSpider_antiair", battle.slot(1).typeId)
            val beforeReturn = battle.balance()
            battle.tick()
            assertTrue(battle.balance() - beforeReturn in listOf(500L, 530L))
            assertEquals("modularSpider_emptySlot", battle.slot(1).typeId)
            battle.click("buildAntiair")
            battle.until { battle.slot(1).typeId == "modularSpider_antiair" && battle.slot(1).progress == 1f }
            battle.click("upgradeT2")
            repeat(959) { battle.tick() }
            assertEquals("modularSpider_antiair", battle.slot(1).typeId)
            battle.tick()
            assertEquals("modularSpider_antiairT2", battle.slot(1).typeId)
            assertEquals(id, battle.slot(1).unitId)
            assertTrue(battle.action("reclaim").available)
            assertIs<ActionResult.Rejected>(battle.request("upgradeFlak"))
        }

    @Test fun linked_flak_mounts_fire_at_air_and_splash_air_only_after_normal_upgrade() =
        withBattle(25000, witnesses = true) { battle ->
            battle.click("selectSlot1")
            val id = battle.slot(1).unitId
            battle.click("buildAntiair")
            battle.tick()
            battle.click("upgradeFlak")
            battle.until(maxSteps = 2100) { battle.slot(1).typeId == "modularSpider_antiairFlak" }
            val born = mutableMapOf<String, Long>()
            val beforeAir = assertNotNull(battle.unit("nearAir").health)
            val beforeGround = battle.unit("ground").health
            val frames = listOf(battle.context.observe()) + List(170) { battle.tick() }
            for (frame in frames) {
                frame.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == id }.forEach {
                    assertTrue(it.projectile.targetUnitId in setOf("air", "nearAir"))
                    born.putIfAbsent(assertNotNull(it.projectile.sourceWeaponName), frame.stepIndex)
                }
            }
            assertEquals(setOf("2", "3"), born.keys)
            assertEquals(55L, born.getValue("3") - born.getValue("2"))
            assertEquals(beforeGround, battle.unit("ground").health)
            assertTrue(assertNotNull(battle.unit("nearAir").health) < beforeAir)
            assertIs<ActionResult.Accepted>(
                battle.context.submit(AttackAction("aim-air", "p1", "spider", "air", battle.context.world)))
        }

    @Test fun destroying_a_module_clears_and_refunds_the_paid_upgrade_and_preserves_the_slot() =
        withBattle(26000, hostileShot = HostileShot(Position(312.0, 149.0), 270.0, 7000.0)) { battle ->
            battle.click("selectSlot1")
            val id = battle.slot(1).unitId
            battle.click("buildAntiair")
            battle.tick()
            battle.click("upgradeT2")
            assertIs<ActionResult.Accepted>(battle.context.submit(
                MoveAction("enter-module-range", "p2", "sniper", Position(278.0, 149.0), battle.context.world)))
            var destruction: Observation? = null
            repeat(100) {
                val before = battle.balance()
                if (destruction == null) {
                    val frame = battle.tick()
                    if (frame.events.any { it.kind == "attachment-slot-destroyed" && it.unitId == id }) {
                        assertTrue(battle.balance() - before in listOf(16000L, 16030L))
                        destruction = frame
                    }
                }
            }
            assertNotNull(destruction)
            assertEquals(id, battle.slot(1).unitId)
            assertEquals("modularSpider_emptySlot", battle.slot(1).typeId)
            assertTrue(battle.unit("spider").alive)
            assertTrue(battle.action("buildSmallLaser").available)
        }

    @Test fun a_launched_flak_shell_keeps_its_splash_after_the_chassis_and_source_are_removed() =
        withBattle(25000, witnesses = true, hostileShot = HostileShot(Position(90.0, 160.0), 90.0, 16000.0)) { battle ->
            battle.click("selectSlot1")
            val id = battle.slot(1).unitId
            battle.click("buildAntiair")
            battle.tick()
            battle.click("upgradeFlak")
            battle.until(maxSteps = 2100) { battle.slot(1).typeId == "modularSpider_antiairFlak" }
            fun shell(frame: Observation) = frame.projectileEvents.firstOrNull {
                it.kind == "born" && it.projectile.sourceUnitId == id && it.projectile.sourceWeaponName == "2"
            }?.projectile
            var flak = shell(battle.context.observe())
            repeat(50) {
                if (flak == null) flak = shell(battle.tick())
            }
            val shot = assertNotNull(flak)
            val health = battle.unit("nearAir").health
            assertIs<ActionResult.Accepted>(battle.context.submit(
                MoveAction("enter-chassis-range", "p2", "sniper", Position(125.0, 160.0), battle.context.world)))
            battle.until(7) { !battle.unit("spider").alive }
            val sourceGone = battle.context.observe()
            assertTrue(sourceGone.units.none { it.unitId == id })
            assertTrue(sourceGone.projectiles.any { it.objectId == shot.objectId })
            val impact = List(20) { battle.tick() }.flatMap { it.projectileEvents }.singleOrNull {
                it.kind == "impact" && it.projectile.objectId == shot.objectId
            }
            assertNotNull(impact)
            assertTrue(assertNotNull(battle.unit("nearAir").health) < assertNotNull(health))
            assertEquals(20000.0, battle.unit("ground").health)
        }

    @Test fun gun_t2_fires_at_land_and_the_small_laser_fires_at_air_with_its_own_cadence() =
        withBattle(60000, witnesses = true) { battle ->
            val gun = battle.slot(1).unitId
            val laser = battle.slot(2).unitId
            battle.click("selectSlot1")
            battle.click("buildGun")
            battle.tick()
            battle.click("upgradeT2")
            battle.click("selectSlot2")
            battle.click("buildSmallLaser")
            battle.until { battle.slot(2).typeId == "modularSpider_smallgunturret" && battle.slot(2).progress == 1f }
            assertIs<ActionResult.Accepted>(
                battle.context.submit(AttackAction("guide-air", "p1", "spider", "air", battle.context.world)))
            var laserShots = 0
            val laserFrames = List(300) {
                battle.tick().also { frame ->
                    val born = frame.projectileEvents.firstOrNull {
                        it.kind == "born" && it.projectile.sourceUnitId == laser && it.projectile.targetUnitId == "air"
                    }
                    if (born != null) {
                        val muzzle = born.projectile.position
                        val fullGain = laserShots++ % 2 == 0
                        val listener = if (fullGain) muzzle else Position(muzzle.x + 84.0, muzzle.y)
                        val audio = battle.context.consumeSounds(battle.context.world, "p1", SoundListener(listener, 75f))
                        val laserSounds = audio.sounds.filter { it.eventId == "reforge:events/scout-plasma-fire" }
                        assertEquals(if (fullGain) listOf(.05f) else emptyList(), laserSounds.map { it.volume })
                    }
                }
            }
            val laserBirths = laserFrames.filter { frame -> frame.projectileEvents.any {
                it.kind == "born" && it.projectile.sourceUnitId == laser && it.projectile.targetUnitId == "air" &&
                    it.projectile.sourceWeaponName == "smallLaser1"
            } }
            assertTrue(laserBirths.size >= 3)
            assertTrue(laserBirths.zipWithNext().all { (a, b) -> b.stepIndex - a.stepIndex == 80L })
            assertTrue(laserBirths.all { frame -> frame.soundTriggers.any { it.eventId == "reforge:events/scout-plasma-fire" && it.volume == .05f } })
            assertTrue(laserFrames.any { frame -> frame.projectileEvents.any {
                it.kind == "impact" && it.projectile.sourceUnitId == laser && it.projectile.targetUnitId == "air" && it.damage == 70.0
            } })
            battle.until { battle.slot(1).typeId == "modularSpider_gunturretT2" }
            val gunFrames = List(80) { battle.tick() }
            val gunBirths = gunFrames.filter { frame -> frame.projectileEvents.any {
                it.kind == "born" && it.projectile.sourceUnitId == gun
            } }
            assertTrue(gunBirths.size >= 3)
            assertTrue(gunBirths.zipWithNext().all { (a, b) -> b.stepIndex - a.stepIndex == 13L })
            assertTrue(gunBirths.all { frame -> frame.projectileEvents.any {
                it.kind == "born" && it.projectile.sourceUnitId == gun && it.projectile.targetUnitId == "ground"
            } && frame.soundTriggers.any { it.eventId == "reforge:events/spider-gun-fire" && it.volume == .15f } })
            assertTrue(gunFrames.any { frame -> frame.projectileEvents.any {
                it.kind == "impact" && it.projectile.sourceUnitId == gun && it.projectile.targetUnitId == "ground" && it.damage == 40.0
            } })
        }

    @Test fun anti_air_t2_pods_reach_beyond_t1_and_an_in_flight_missile_retargets_when_its_victim_dies() =
        withBattle(25000, witnesses = true, airX = 570.0, firstAirHealth = 150.0) { battle ->
            battle.click("selectSlot1")
            val id = battle.slot(1).unitId
            battle.click("buildAntiair")
            battle.tick()
            battle.click("upgradeT2")
            battle.until { battle.slot(1).progress == 1f }
            repeat(120) {
                assertTrue(battle.tick().projectileEvents.none { it.kind == "born" && it.projectile.sourceUnitId == id })
            }
            battle.until { battle.slot(1).typeId == "modularSpider_antiairT2" }
            val frames = listOf(battle.context.observe()) + List(175) { battle.tick() }
            val first = linkedMapOf<String, Pair<Long, ProjectileObservation>>()
            for (frame in frames) for (event in frame.projectileEvents) {
                val shot = event.projectile
                if (event.kind == "born" && shot.sourceUnitId == id)
                    first.putIfAbsent(assertNotNull(shot.sourceWeaponName), frame.stepIndex to shot)
            }
            assertEquals(listOf("2", "3"), first.keys.toList())
            assertEquals(25L, first.getValue("3").first - first.getValue("2").first)
            val followup = first.getValue("3").second
            assertEquals("air", followup.targetUnitId)
            assertFalse(battle.unit("air").alive)
            assertTrue(frames.any { frame -> frame.projectileEvents.any {
                it.kind == "impact" && it.projectile.objectId == followup.objectId &&
                    it.projectile.targetUnitId == "nearAir" && it.damage == 200.0
            } })
            assertEquals(20000.0, battle.unit("ground").health)
        }

    @Test fun an_air_missile_retargets_using_the_same_slots_current_empty_form() =
        withBattle(25000, witnesses = true, hostileShot = HostileShot(Position(312.0, 149.0), 270.0, 9000.0),
            airX = 570.0, firstAirHealth = 150.0, groundX = 556.0) { battle ->
            battle.click("selectSlot1")
            val id = battle.slot(1).unitId
            battle.click("buildAntiair")
            battle.tick()
            battle.click("upgradeT2")
            battle.until(maxSteps = 2100) { battle.slot(1).typeId == "modularSpider_antiairT2" }
            var followup: ProjectileObservation? = null
            repeat(80) {
                if (followup == null) followup = battle.tick().projectileEvents.firstOrNull {
                    it.kind == "born" && it.projectile.sourceUnitId == id && it.projectile.sourceWeaponName == "3"
                }?.projectile
            }
            val shot = assertNotNull(followup)
            assertEquals("air", shot.targetUnitId)
            assertIs<ActionResult.Accepted>(battle.context.submit(
                MoveAction("enter-module-range", "p2", "sniper", Position(278.0, 149.0), battle.context.world)))
            battle.until(25) { battle.slot(1).typeId == "modularSpider_emptySlot" }
            assertEquals(id, battle.slot(1).unitId)
            assertTrue(battle.unit("spider").alive)
            assertTrue(battle.unit("air").alive)
            val frames = List(175) { battle.tick() }
            assertFalse(battle.unit("air").alive)
            assertTrue(frames.flatMap { it.projectileEvents }.any {
                it.kind == "impact" && it.projectile.objectId == shot.objectId &&
                    it.projectile.targetUnitId == "ground" && it.damage == 200.0
            }, "The in-flight missile must be able to select the nearby ground unit after its source becomes empty")
        }

    @Test fun invalid_upgrade_and_linked_mount_references_report_their_own_declarations() {
        for (format in listOf("ini", "json", "hjson")) {
            val copy = copyPack(format)
            val aa = copy.resolve("units/spider/aa.$format")
            val declaration = Files.readString(aa)
            Files.writeString(aa, declaration.replace("modularSpider_antiairFlak", "missing-flak"))
            val badUpgrade = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(copy)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, badUpgrade.code, badUpgrade.describe())
            assertEquals("action_upgradeFlak", badUpgrade.section)
            assertEquals("convertTo", badUpgrade.field)
            assertEquals(aa.toString(), badUpgrade.file)
            Files.writeString(aa, declaration)

            val flak = copy.resolve("units/spider/flak.$format")
            val original = Files.readString(flak)
            Files.writeString(flak, original.replace("linkDelayWithTurret", "unqualifiedMountLink"))
            val unknown = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(copy)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FIELD, unknown.code, unknown.describe())
            assertEquals("unqualifiedMountLink", unknown.field)
            assertEquals(flak.toString(), unknown.file)
        }
    }

    @Test fun a_missing_qualified_upgrade_target_points_to_its_effective_action_in_packages_and_composition() {
        for (format in listOf("ini", "json", "hjson")) {
            val copy = copyPack(format)
            val aa = copy.resolve("units/spider/aa.$format")
            val origin = if (format == "ini") aa.resolveSibling("flak-link.ini") else aa
            if (origin != aa) {
                val link = "convertTo: modularSpider_antiairFlak"
                val original = Files.readString(aa)
                assertTrue(link in original)
                Files.writeString(origin, "[core]\ndont_load: true\n[action_upgradeFlak]\n$link\n")
                Files.writeString(aa, original.replaceFirst("[core]", "[core]\ncopyFrom: flak-link.ini")
                    .replace("$link\n", "").replace("$link\r\n", ""))
            }
            val manifest = copy.resolve("pack.json")
            val metadata = JsonValue.readJSON(Files.readString(manifest)).asObject()
            val units = metadata.get("units").asArray()
            assertEquals("units/spider/flak.$format", units[units.size() - 1].asObject().get("file").asString())
            units.remove(units.size() - 1)
            Files.writeString(manifest, metadata.toString())

            val dependency = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                fixtures.resolve("flame-mech-production/$format/production-package"))).value
            val local = ContentLoader.loadContentPack(copy)
            units.remove(0) // The dependency already owns mechGun.
            metadata.add("dependencies", JsonArray().add(dependency.id))
            Files.writeString(manifest, metadata.toString())
            val deferred = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(copy)).value
            val composed = ContentLoader.composeContentPacks(listOf(deferred, dependency))
            for (result in listOf(local, composed)) {
                val error = assertIs<ContentResult.Failure>(result).error
                assertEquals(ContentErrorCode.BAD_REFERENCE, error.code, error.describe())
                assertEquals("action_upgradeFlak", error.section)
                assertEquals("convertTo", error.field)
                assertEquals("modularSpider_antiairFlak", error.objectRef)
                assertEquals(origin.toString(), error.file)
                if (origin != aa) {
                    assertContains(error.reason, "inheritance=$aa -> $origin")
                    assertContains(error.reason, "consumer=[action_upgradeFlak].convertTo")
                }
            }
        }
    }

    @Test fun an_anti_air_t1_installed_in_the_normal_slot_hits_air_while_the_nearer_ground_enemy_is_ineligible() =
        withBattle(6000, witnesses = true) { battle ->
            val id = battle.slot(1).unitId
            battle.click("selectSlot1")
            battle.click("buildAntiair")
            assertEquals(4000L, battle.balance())
            battle.tick()
            assertEquals("modularSpider_antiair", battle.slot(1).typeId)
            repeat(80) {
                assertTrue(battle.tick().projectileEvents.none { it.kind == "born" && it.projectile.sourceUnitId == id })
            }
            battle.until { battle.slot(1).progress == 1f }
            val frames = listOf(battle.context.observe()) + List(100) { battle.tick() }
            val births = frames.flatMap { it.projectileEvents }
                .filter { it.kind == "born" && it.projectile.sourceUnitId == id }
            assertTrue(births.isNotEmpty())
            assertTrue(births.all { it.projectile.sourceWeaponName == "1" && it.projectile.targetUnitId == "air" })
            val first = births.first().projectile
            assertTrue(frames.flatMap { it.projectileEvents }.any {
                it.kind == "impact" && it.projectile.objectId == first.objectId &&
                    it.projectile.targetUnitId == "air" && it.damage == 180.0
            })
            assertTrue(frames.any { it.soundTriggers.any { sound ->
                sound.eventId == "reforge:events/naval-missile-fire" && sound.volume == .3f
            } })
            assertEquals(20000.0, battle.unit("ground").health)
            assertEquals(id, battle.slot(1).unitId)
        }

    private fun copyPack(format: String): Path {
        val source = fixtures.resolve("modular-spider-start/$format/opening-package")
        val copy = workspace.resolve(format)
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = copy.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
        return copy
    }

    private data class HostileShot(val position: Position, val heading: Double, val damage: Double)

    private fun withBattle(credits: Long, witnesses: Boolean = false, hostileShot: HostileShot? = null,
                           airX: Double = 350.0, firstAirHealth: Double = 20000.0, groundX: Double = 347.0,
                           test: (Battle) -> Unit) =
        WaterTestScene.withMap { map ->
            val spider = pack("ini").units
            val origin = Position(180.0, 160.0)
            val base = WaterTestScene.scenario(map, MovementType.OVER_CLIFF_WATER, origin)
            fun passive(id: String, flying: Boolean, health: Double = 20000.0): UnitDefinition {
                val aircraft = if (flying) HelicopterCombatScene.unit() else null
                val movement = aircraft?.movement?.copy(
                    flight = FlightProfile(20.0, 0.0, 0.0, 20.0)) ?: WaterTestScene.unit(MovementType.HOVER).movement
                return UnitDefinition(id, "CustomUnitMetadata", 6.0, 6.0, 1, spider.getValue("mechGun").imagePath,
                    1.0, movement, emptySet(), durability = DurabilityProfile(health, null),
                    rotor = aircraft?.rotor)
            }
            val targets = if (witnesses) listOf(passive("airTarget", true, firstAirHealth), passive("nearAirTarget", true),
                passive("groundTarget", false)) else emptyList()
            val sniper = hostileShot?.let {
                UnitDefinition("sniperType", "CustomUnitMetadata", 6.0, 6.0, 1, spider.getValue("mechGun").imagePath,
                    1.0, WaterTestScene.unit(MovementType.LAND).movement.copy(moveSpeed = 2.0, moveAccelerationSpeed = 2.0),
                    emptySet(), combat = CombatProfile(20000.0, 89.0, 80.0, 0.0, 8.0, 2.0,
                        ProjectileProfile(it.damage, 80.0, 15.0, instant = true), null, null, 1.0))
            }
            val witnessesSpawns = if (witnesses) listOf(
                    UnitSpawn("air", "p2", "airTarget", Position(airX, 150.0), 270.0),
                    UnitSpawn("nearAir", "p2", "nearAirTarget", Position(airX + 16.0, 150.0), 270.0),
                    UnitSpawn("ground", "p2", "groundTarget", Position(groundX, 156.0), 270.0),
                ) else emptyList()
            val spawns = listOf(UnitSpawn("spider", "p1", "modularSpider", origin, 90.0)) + witnessesSpawns +
                listOfNotNull(hostileShot?.let { UnitSpawn("sniper", "p2", "sniperType", it.position, it.heading) })
            val scenario = base.copy(unitDefinitions = spider + (targets + listOfNotNull(sniper)).associateBy { it.id },
                players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
                units = spawns, economyContract = EconomyContract(mapOf("p1" to credits, "p2" to 500L)),
                combatContract = CombatContract(173, -1,
                    spawns.mapIndexed { index, spawn -> spawn.unitId to (index + 8L) }.toMap(), 8L + spawns.size))
            val result = SimulationContext.initialize(scenario)
            val context = assertIs<InitResult.Accepted>(result, result.toString()).context
            try { test(Battle(context)) } finally { context.close() }
        }

    private class Battle(val context: SimulationContext) {
        private var step = 0L
        private var command = 0
        fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
        fun unit(id: String) = context.observe().units.single { it.unitId == id }
        fun slot(number: Int) = assertNotNull(unit("spider").attachmentOverview).slots.single { it.number == number }
        fun action(name: String) = assertNotNull(unit("spider").unitActions).actions.single { it.name == name }
        fun balance() = assertNotNull(context.observe().economy).balances.getValue("p1")
        fun request(name: String, right: Boolean = false) =
            context.submit(UnitAction("module-${command++}", "p1", "spider", name, context.world, cancelQueued = right))
        fun click(name: String, right: Boolean = false) {
            val result = request(name, right)
            assertIs<ActionResult.Accepted>(result, "$name: $result")
        }
        fun until(maxSteps: Int = 1100, condition: () -> Boolean) {
            repeat(maxSteps) {
                if (condition()) return
                tick()
            }
            assertTrue(condition(), "State did not reach its boundary within $maxSteps steps")
        }
    }
}
