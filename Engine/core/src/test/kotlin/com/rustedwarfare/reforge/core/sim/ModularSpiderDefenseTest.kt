package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.ProjectileProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.*

class ModularSpiderDefenseTest {
    @Test fun three_formats_share_qualified_module_definitions_and_normal_slot_orders() {
        for (format in listOf("ini", "json", "hjson")) {
            withBattle(format = format) { battle ->
                battle.click("selectSlot1")
                battle.click("buildLaserdefense")
                battle.tick()
                assertEquals("modularSpider_laserdefense", battle.slot(1).typeId)
                battle.click("selectSlot2")
                battle.click("buildAntinuke")
                battle.tick()
                assertEquals("modularSpider_antinuke", battle.slot(2).typeId)
                battle.click("buildAntiNuke")
                assertEquals(27000L, battle.balance())
                assertEquals(0, battle.actions().stockpile)
                assertEquals(true, battle.actions().interceptorOnline)
                assertEquals("buildAntiNuke", battle.actions().queue.single().name)
            }
        }
    }


    @Test fun a_structured_slot_can_install_another_interceptor_definition_and_fire_its_paid_round() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("modular-spider-start/json/opening-package")
        val temporary = Files.createTempDirectory("authored-attachment-interception-")
        Files.walk(fixtures).use { paths ->
            paths.forEach { source ->
                val copy = temporary.resolve(fixtures.relativize(source))
                if (!Files.isDirectory(source)) Files.copy(source, copy) else Files.createDirectories(copy)
            }
        }
        val id = "fleet_screen_module"
        val empty = temporary.resolve("units/spider/empty.json")
        Files.writeString(empty, Files.readString(empty).replace("modularSpider_antinuke", id))
        val file = temporary.resolve("units/spider/anti-nuke.json")
        val document = JsonValue.readJSON(Files.readString(file)).asObject()
        document.get("core").asObject().set("name", id)
        val mount = document.get("weapons").asArray().single().asObject()
        mount.set("interceptProjectiles_andTargetingGroundUnderDistance", 850)
        mount.set("interceptProjectiles_andUnderDistance", 1400)
        Files.writeString(file, document.toString())
        val loaded = ContentLoader.loadContentPack(temporary)
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
        val socket = pack.units.getValue(id).attachmentModule?.interception
        assertEquals(850f, socket?.landingRange)
        assertEquals(1400f, socket?.trackingRange)
        withBattle(definitions = pack.units) { battle ->
            battle.click("selectSlot2")
            val slot = battle.slot(2).unitId
            battle.click("buildAntinuke")
            assertEquals(33000L, battle.balance())
            battle.tick()
            assertEquals(id, battle.slot(2).typeId)
            battle.until(1100) { battle.slot(2).progress == 1f }
            val balance = battle.balance()
            battle.click("buildAntiNuke")
            assertEquals(balance - 3000L, battle.balance())
            battle.silo("buildNuke")
            battle.until(3400) { battle.unit("silo").unitActions?.stockpile == 1 }
            assertEquals(1, battle.actions().stockpile)
            battle.silo("launchNuke", Position(440.0, 160.0))
            var intercepted = false
            battle.until(1500) {
                intercepted = intercepted || battle.context.observe().projectileEvents.any {
                    it.kind == "intercepted" && it.interceptorUnitId == slot &&
                        it.projectile.appearance == ProjectileAppearance.NUCLEAR
                }
                intercepted
            }
            assertEquals(0, battle.actions().stockpile)
            assertEquals(slot, battle.slot(2).unitId)
        }
        mount.set("interceptProjectiles_andUnderDistance", 0)
        Files.writeString(file, document.toString())
        assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(temporary))
    }

    @Test fun laser_uses_its_current_slot_and_its_own_energy_on_real_enemy_rounds() =
        withBattle { battle ->
            battle.click("selectSlot1")
            val slotId = battle.slot(1).unitId
            battle.click("buildLaserdefense")
            assertEquals(37000L, battle.balance())
            battle.tick()
            assertEquals("modularSpider_laserdefense", battle.slot(1).typeId)
            assertTrue(battle.slot(1).progress < 1f)
            battle.until(1100) { battle.slot(1).progress == 1f }
            assertEquals(1f, battle.unit(slotId).pointDefense?.energy)
            battle.move("p1", "spider", Position(358.0, 160.0))
            var first: Observation? = null
            battle.until(600) {
                val observation = battle.context.observe()
                if (observation.projectileEvents.any { it.kind == "deflected" && it.interceptorUnitId == slotId }) {
                    first = observation
                }
                first != null
            }
            val hit = assertNotNull(first).projectileEvents.single { it.kind == "deflected" }
            assertEquals("gunner", hit.projectile.sourceUnitId)
            assertTrue(hit.projectile.age > 7f)
            assertEquals(.9503f, assertNotNull(battle.unit(slotId).pointDefense).energy, .000001f)
            assertNull(battle.unit("spider").pointDefense)
            assertTrue(battle.unit(slotId).position.x > 212.0)
            assertEquals(slotId, battle.slot(1).unitId)
        }

    @Test fun an_offline_interceptor_can_build_rounds_and_reenable_on_the_same_moving_slot() =
        withBattle(nukesEnabled = false) { battle ->
            battle.click("selectSlot2")
            val slotId = battle.slot(2).unitId
            battle.click("buildAntinuke")
            assertEquals(33000L, battle.balance())
            battle.tick()
            assertEquals("modularSpider_antinuke", battle.slot(2).typeId)
            val beforeOrders = battle.balance()
            battle.click("buildAntiNuke")
            battle.click("buildAntiNuke")
            assertEquals(beforeOrders - 6000L, battle.balance())
            battle.click("buildAntiNuke", cancel = true)
            assertEquals(beforeOrders - 3000L, battle.balance())
            repeat(30) { battle.tick() }
            assertEquals(0f, battle.actions().queue.single().progress)
            battle.until(1100) { battle.slot(2).progress == 1f }
            battle.click("isEnabled")
            battle.tick()
            assertTrue(battle.actions().actions.any { it.name == "isDisabled" && it.available })
            assertEquals(0, battle.actions().stockpile)
            battle.silo("buildNuke")
            battle.until(3400) { battle.unit("silo").unitActions?.stockpile == 1 }
            assertEquals(1, battle.actions().stockpile)
            battle.silo("launchNuke", Position(440.0, 160.0))
            battle.until(350) {
                battle.context.observe().projectiles.any { it.appearance == ProjectileAppearance.NUCLEAR && it.height > 50 }
            }
            assertEquals(1, battle.actions().stockpile)
            assertTrue(battle.context.observe().projectiles.none { it.appearance == ProjectileAppearance.INTERCEPTOR })
            battle.move("p1", "spider", Position(324.0, 160.0))
            battle.click("isDisabled")
            battle.until(20) {
                battle.context.observe().projectiles.any {
                    it.appearance == ProjectileAppearance.INTERCEPTOR && it.sourceUnitId == slotId
                }
            }
            assertEquals(0, battle.actions().stockpile)
            assertEquals(slotId, battle.slot(2).unitId)
            battle.until(1800) { battle.context.observe().projectiles.none { it.appearance == ProjectileAppearance.NUCLEAR } }
            assertTrue(battle.context.observe().projectileEvents.any { it.kind == "intercepted" })
            val oldOwner = battle.context.observePlayer(battle.context.world, "p2").units.single { it.unitId == "spider" }
            assertNull(oldOwner.unitActions)
            assertNull(oldOwner.attachmentOverview)
        }

    @Test fun reclaim_runs_between_paid_rounds_and_refunds_only_the_round_that_cannot_finish() =
        withBattle { battle ->
            battle.click("selectSlot2")
            val identity = battle.slot(2).unitId
            battle.click("buildAntinuke")
            battle.tick()
            battle.until(1100) { battle.slot(2).progress == 1f }
            battle.click("buildAntiNuke")
            repeat(150) { battle.tick() }
            val progress = battle.actions().queue.single().progress
            battle.click("reclaim")
            battle.click("buildAntiNuke")
            battle.click("isEnabled")
            assertEquals(listOf("isEnabled", "buildAntiNuke", "reclaim", "buildAntiNuke"),
                battle.actions().queue.map { it.name })
            assertEquals("unit-action-not-cancellable", assertIs<ActionResult.Rejected>(
                battle.request("isEnabled", cancel = true)).error.code)
            battle.tick()
            assertEquals(false, battle.actions().interceptorOnline)
            assertEquals(progress, battle.actions().queue.first().progress)
            battle.until(1429) { battle.actions().stockpile == 1 }
            assertEquals(listOf("reclaim", "buildAntiNuke"), battle.actions().queue.map { it.name })
            repeat(179) { battle.tick() }
            val before = battle.balance()
            battle.tick()
            assertEquals("modularSpider_emptySlot", battle.slot(2).typeId)
            assertEquals(identity, battle.slot(2).unitId)
            assertTrue(battle.balance() - before in listOf(6500L, 6530L))
            assertNull(battle.actions().stockpile)
            battle.click("buildAntinuke")
            battle.tick()
            assertEquals(1, battle.actions().stockpile)
            assertEquals(false, battle.actions().interceptorOnline)
        }

    @Test fun real_nuke_landing_enters_range_of_the_moving_slot_before_the_chassis_or_its_old_slot() =
        withBattle(wideField = true) { battle ->
            battle.click("selectSlot2")
            val slotId = battle.slot(2).unitId
            battle.click("buildAntinuke")
            battle.tick()
            battle.click("buildAntiNuke")
            battle.silo("buildNuke")
            battle.until(3400) { battle.unit("silo").unitActions?.stockpile == 1 }
            assertEquals(1, battle.actions().stockpile)
            val landing = Position(1230.0, 160.0)
            val oldSlot = battle.unit(slotId).position
            battle.silo("launchNuke", landing)
            battle.until(350) {
                battle.context.observe().projectiles.any {
                    it.appearance == ProjectileAppearance.NUCLEAR && it.height > 50.0
                }
            }
            assertEquals(1, battle.actions().stockpile)
            battle.move("p1", "spider", Position(440.0, 160.0))
            var prior = battle.context.observe()
            var frame = battle.tick()
            repeat(160) {
                if (frame.projectileEvents.none { e -> e.kind == "born" && e.projectile.appearance == ProjectileAppearance.INTERCEPTOR }) {
                    prior = frame
                    frame = battle.tick()
                }
            }
            val interceptor = frame.projectileEvents.single {
                it.kind == "born" && it.projectile.appearance == ProjectileAppearance.INTERCEPTOR
            }.projectile
            fun distance(a: Position, b: Position) = kotlin.math.hypot(a.x - b.x, a.y - b.y)
            val currentSlot = frame.units.single { it.unitId == slotId }.position
            val priorSlot = prior.units.single { it.unitId == slotId }.position
            val parent = frame.units.single { it.unitId == "spider" }
            val incoming = prior.projectiles.single { it.appearance == ProjectileAppearance.NUCLEAR }
            assertEquals(slotId, interceptor.sourceUnitId)
            assertTrue(distance(oldSlot, landing) > 1000.0)
            assertTrue(distance(parent.position, landing) > 1000.0 && parent.speed > 0.0)
            assertTrue(distance(priorSlot, landing) >= 1000.0)
            assertTrue(distance(currentSlot, landing) < 1000.0)
            assertTrue(distance(incoming.position, priorSlot) < 1500.0 && incoming.height > 50.0)
            assertEquals(1, prior.units.single { it.unitId == "spider" }.unitActions?.stockpile)
            assertEquals(0, parent.unitActions?.stockpile)
            println("Anti-nuke landing distances at first fire: old=${distance(oldSlot, landing)}, " +
                "prior=${distance(priorSlot, landing)}, current=${distance(currentSlot, landing)}, " +
                "chassis=${distance(parent.position, landing)}; prior enemy height=${incoming.height}")
            battle.until(1800) { battle.context.observe().projectiles.none { it.appearance == ProjectileAppearance.NUCLEAR } }
            assertTrue(battle.context.observe().projectileEvents.any { it.kind == "intercepted" && it.interceptorUnitId == slotId })
        }

    @Test fun a_fired_interceptor_still_hits_after_its_chassis_and_source_slot_are_removed() =
        withBattle(chassisKiller = true) { battle ->
            battle.click("selectSlot2")
            val slotId = battle.slot(2).unitId
            battle.click("buildAntinuke")
            battle.tick()
            battle.click("buildAntiNuke")
            battle.silo("buildNuke")
            battle.until(3400) { battle.unit("silo").unitActions?.stockpile == 1 }
            assertEquals(1, battle.actions().stockpile)
            battle.silo("launchNuke", Position(620.0, 160.0))
            battle.until(350) {
                battle.context.observe().projectiles.any {
                    it.appearance == ProjectileAppearance.INTERCEPTOR && it.sourceUnitId == slotId
                }
            }
            val shot = battle.context.observe().projectiles.single { it.appearance == ProjectileAppearance.INTERCEPTOR }
            battle.move("p2", "gunner", Position(125.0, 160.0))
            battle.until(20) { !battle.unit("spider").alive }
            val afterDeath = battle.context.observe()
            assertTrue(afterDeath.units.none { it.unitId == slotId })
            assertTrue(afterDeath.projectiles.any { it.objectId == shot.objectId })
            battle.until(1800) { battle.context.observe().projectiles.none { it.objectId == shot.objectId } }
            val events = battle.context.observe().projectileEvents
            assertTrue(events.any { it.kind == "intercepted" && it.interceptorUnitId == slotId })
            assertTrue(events.any { it.kind == "impact" && it.projectile.objectId == shot.objectId })
        }

    @Test fun twelve_paid_or_completed_rounds_gate_admission_without_counting_the_switch() =
        withBattle(credits = 50000L) { battle ->
            battle.click("selectSlot2")
            battle.click("buildAntinuke")
            battle.tick()
            repeat(11) { battle.click("buildAntiNuke") }
            battle.click("isEnabled")
            battle.click("buildAntiNuke")
            assertEquals("ammo-queue-limit", assertIs<ActionResult.Rejected>(battle.request("buildAntiNuke")).error.code)
            val before = battle.balance()
            battle.click("buildAntiNuke", cancel = true)
            assertEquals(before + 3000L, battle.balance())
            assertEquals(12, battle.actions().queue.size)
            assertTrue(battle.actions().queue.first().name == "isEnabled")
        }

    private fun withBattle(nukesEnabled: Boolean = true, format: String = "ini", credits: Long = 40000L,
                           chassisKiller: Boolean = false, wideField: Boolean = false,
                           definitions: Map<String, UnitDefinition>? = null, run: (Battle) -> Unit) =
        WaterTestScene.withMap { inputMap ->
            val map = if (wideField) {
                val width = 80
                val height = inputMap.heightTiles
                val ground = inputMap.groundGids.first()
                MapDefinition(width, height, inputMap.tileWidth, inputMap.tileHeight, inputMap.tileSets,
                    IntArray(width * height) { ground }, BlockingGrid(width, height, BooleanArray(width * height)))
            } else inputMap
            val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            val spider = definitions ?: kotlin.run {
                val loaded = ContentLoader.loadContentPack(fixture.resolve("modular-spider-start/$format/opening-package"))
                assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
            }
            val base = NuclearScene.scenario(map)
            val gunner = UnitDefinition("gunnerType", "CustomUnitMetadata", 6.0, 6.0, 1,
                spider.getValue("mechGun").imagePath, 1.0, WaterTestScene.unit(MovementType.LAND).movement.let {
                    if (chassisKiller) it.copy(moveSpeed = 2.0, moveAccelerationSpeed = 2.0) else it
                }, emptySet(), combat = if (chassisKiller)
                    CombatProfile(20000.0, 89.0, 80.0, 0.0, 8.0, 2.0,
                        ProjectileProfile(16000.0, 80.0, 15.0, instant = true), null, null, 1.0)
                else CombatProfile(20000.0, 175.0, 30.0, 0.0, 8.0, 2.0,
                    ProjectileProfile(60.0, 100.0, 8.0), null, null, 1.0))
            val spawns = listOf(
                UnitSpawn("spider", "p1", "modularSpider", Position(180.0, 160.0), 90.0),
                UnitSpawn("silo", "p2", "nukeLauncherC", Position(100.0, 260.0), 90.0),
                UnitSpawn("gunner", "p2", gunner.id,
                    if (chassisKiller) Position(90.0, 160.0) else Position(379.0, 149.0),
                    if (chassisKiller) 90.0 else 270.0),
            )
            val context = NuclearScene.open(base.copy(unitDefinitions = base.unitDefinitions + spider + (gunner.id to gunner),
                players = base.players.map { it.copy(control = PlayerControl.LOCAL) }, units = spawns,
                nukesEnabled = nukesEnabled, economyContract = EconomyContract(mapOf("p1" to credits, "p2" to 40000L)),
                combatContract = CombatContract(12345, -1, mapOf("spider" to 8L, "silo" to 9L, "gunner" to 10L), 11L)))
            try { run(Battle(context)) } finally { context.close() }
        }

    private class Battle(val context: SimulationContext) {
        private var index = 0L
        private var command = 0
        fun tick() = context.advance(Step(index++, 1.0, "sim-tick"))
        fun unit(id: String) = context.observe().units.single { it.unitId == id }
        fun slot(number: Int) = assertNotNull(unit("spider").attachmentOverview).slots.single { it.number == number }
        fun actions() = assertNotNull(unit("spider").unitActions)
        fun balance() = assertNotNull(context.observe().economy).balances.getValue("p1")
        fun request(name: String, cancel: Boolean = false) =
            context.submit(UnitAction("defense-${command++}", "p1", "spider", name, context.world, cancel))
        fun click(name: String, cancel: Boolean = false) {
            val result = request(name, cancel)
            assertIs<ActionResult.Accepted>(result, "$name: $result")
        }
        fun silo(name: String, target: Position? = null) {
            assertIs<ActionResult.Accepted>(context.submit(
                UnitAction("silo-${command++}", "p2", "silo", name, context.world, target = target)))
        }
        fun move(player: String, unit: String, target: Position) {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-${command++}", player, unit, target, context.world)))
        }
        fun until(maximum: Int, reached: () -> Boolean) {
            repeat(maximum) {
                if (reached()) return
                tick()
            }
            assertTrue(reached(), "Expected state within $maximum steps")
        }
    }
}
