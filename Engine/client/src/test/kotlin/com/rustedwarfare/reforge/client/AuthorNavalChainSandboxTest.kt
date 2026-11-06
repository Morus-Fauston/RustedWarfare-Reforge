package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class AuthorNavalChainSandboxTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun load(format: String): LoadedSandbox {
        val root = fixtures.resolve("author-naval-chain/$format")
        val attempt = ContentLoader.loadContentPack(root.resolve("package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(attempt, "$format: $attempt").value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "spbuilder"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("Normal dual-ship content choice did not load")
        }
    }

    @Test fun explicitly_enabled_placement_uses_only_the_inherited_underwater_capable_slot_to_hit_a_fixed_target() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = load(format)
            SandboxSession(loaded.scenario, options = SandboxOptions(deploymentEnabled = true)).use { session ->
                assertTrue(session.canDeploy)
                assertEquals(SandboxCommandStatus.QUEUED,
                    session.submitSandboxCommand("/spawn spfrigate 250 440 p1 heading=90").status)
                assertEquals(SandboxCommandStatus.QUEUED,
                    session.submitSandboxCommand("/spawn navalUnderwaterTarget 300 480 p2").status)
                assertTrue(session.observation.units.none { it.typeId == "spfrigate" || it.typeId == "navalUnderwaterTarget" })
                session.tick()
                assertEquals(SandboxCommandStatus.APPLIED, session.lastSandboxReceipt!!.status)
                val hull = session.observation.units.single { it.typeId == "spfrigate" }
                val target = session.observation.units.single { it.typeId == "navalUnderwaterTarget" }
                assertEquals("p1", hull.ownerPlayerId)
                assertEquals("p2", target.ownerPlayerId)
                assertEquals(-8.0, target.height)
                assertEquals(1000.0, target.health)
                session.select(hull.position)
                assertEquals(hull.unitId, session.selectedId)
                assertIs<ActionResult.Accepted>(session.command(target.position))
                val born = mutableSetOf<String>()
                var impacted = false
                repeat(300) {
                    session.tick()
                    val events = session.observation.projectileEvents.filter {
                        it.sourceUnitId == hull.unitId && it.targetUnitId == target.unitId
                    }
                    born += events.filter { it.kind == "born" }.mapNotNull { it.sourceWeaponName }
                    impacted = impacted || events.any { it.kind == "impact" && it.sourceWeaponName == "starboard" }
                }
                assertEquals(setOf("starboard"), born)
                assertTrue(impacted, "$format underwater projectile must actually impact")
                val damaged = session.observation.units.single { it.unitId == target.unitId }
                assertEquals(-8.0, damaged.height)
                assertTrue(damaged.health!! < 1000.0)
                assertEquals(3000L, session.observation.balance, "Placement is a separate free sandbox case, not paid acquisition")
            }
        }
    }

    @Test fun normal_paid_shore_upgrade_and_fifo_create_both_new_ships_then_sail_and_fire() {
        for (format in listOf("ini", "json", "hjson")) SandboxSession(load(format).scenario).use { session ->
            fun until(limit: Int, description: String, ready: () -> Boolean) {
                var count = 0
                while (!ready() && count++ < limit) session.tick()
                assertTrue(ready(), "$format: $description after $count ticks")
            }
            session.select(Position(110.0, 210.0))
            assertIs<ActionResult.Accepted>(session.move(Position(170.0, 150.0)))
            repeat(180) { session.tick() }
            assertIs<ActionResult.Accepted>(session.build("spdock", Position(250.0, 150.0)))
            until(1000, "paid construction starts") { session.observation.units.any { it.typeId == "spdock" } }
            val site = session.observation.units.single { it.typeId == "spdock" }
            assertEquals(2300L, session.observation.balance)
            assertTrue(site.constructionProgress < 1f)
            session.select(site.position)
            assertTrue(session.productionActions.isEmpty())
            assertTrue(session.actionMenu.upgrades.isEmpty())
            until(1000, "construction finishes") {
                session.observation.units.single { it.unitId == site.unitId }.constructionProgress == 1f
            }
            session.select(site.position)
            assertEquals(listOf("spcorvette"), session.productionActions.map { it.typeId })
            assertNull(session.produce("spfrigate"))
            assertEquals(2300L, session.observation.balance)
            assertEquals(900L, session.actionMenu.upgrades.single().price)
            assertIs<ActionResult.Accepted>(session.upgrade("spdockt2"))
            assertEquals(1400L, session.observation.balance)
            until(400, "same factory becomes T2") {
                session.observation.units.single { it.unitId == site.unitId }.typeId == "spdockt2"
            }
            val upgraded = session.observation.units.single { it.unitId == site.unitId }
            assertEquals(1800.0, upgraded.health)
            assertEquals(site.position, upgraded.position)
            assertEquals(listOf("spcorvette", "spfrigate"), session.productionActions.map { it.typeId })
            assertIs<ActionResult.Accepted>(session.produce("spcorvette"))
            assertEquals(1050L, session.observation.balance)
            assertIs<ActionResult.Accepted>(session.produce("spfrigate"))
            assertEquals(600L, session.observation.balance)
            until(700, "first paid ship is born") { session.observation.units.any { it.typeId == "spcorvette" } }
            assertTrue(session.observation.units.none { it.typeId == "spfrigate" }, "The second paid FIFO item must not be born first")
            val first = session.observation.units.single { it.typeId == "spcorvette" }
            assertEquals("p1", first.ownerPlayerId)
            session.select(first.position)
            assertEquals(first.unitId, session.selectedId)
            val coast = Position(300.0, 210.0)
            assertIs<ActionResult.Accepted>(session.move(coast))
            val fired = mutableSetOf<String>()
            fun collect() {
                fired += session.observation.projectileEvents.filter { it.kind == "born" && it.sourceUnitId == first.unitId }
                    .mapNotNull { it.sourceWeaponName }
            }
            var sailed = false
            for (tick in 0 until 1000) {
                session.tick(); collect()
                val hull = session.observation.units.single { it.unitId == first.unitId }
                if (hypot(hull.position.x - coast.x, hull.position.y - coast.y) <= 4.0) { sailed = true; break }
            }
            assertTrue(sailed, "$format first ship must arrive at the existing four-unit radius")
            assertTrue(session.observation.units.single { it.unitId == "b" }.alive)
            assertIs<ActionResult.Accepted>(session.command(Position(350.0, 210.0)))
            repeat(400) { session.tick(); collect() }
            assertEquals(setOf("port", "starboard"), fired)
            assertTrue(session.observation.units.single { it.unitId == "b" }.health!! < 170.0)
            until(1200, "second paid ship is born") { session.observation.units.any { it.typeId == "spfrigate" } }
            val second = session.observation.units.single { it.typeId == "spfrigate" }
            assertEquals("p1", second.ownerPlayerId)
            session.select(second.position)
            assertEquals(second.unitId, session.selectedId)
            val destination = Position(250.0, 430.0)
            assertIs<ActionResult.Accepted>(session.move(destination))
            var normallyCompleted = false
            repeat(800) {
                session.tick()
                normallyCompleted = normallyCompleted || session.observation.events.any {
                    it.kind == "movement-arrived" && it.unitId == second.unitId
                }
            }
            assertTrue(normallyCompleted, "$format must expose the existing authorized normal arrival event")
            val moved = session.observation.units.single { it.unitId == second.unitId }
            // Command completion and physical stopping are separate in the existing movement
            // policy. The generic targetRadius metadata is not a universal final stop radius.
            assertEquals(MovementState.IDLE, moved.movementState)
            assertEquals(0.0, moved.speed)
            assertNull(moved.moveTarget)
            assertTrue(moved.tasks.isEmpty())
            // This owned fixture declares water at x=[180,340), over its full600 height.
            assertTrue(moved.position.x - 30.0 >= 180.0 && moved.position.x + 30.0 < 340.0)
            assertTrue(moved.position.y - 30.0 >= 0.0 && moved.position.y + 30.0 < 600.0)
            repeat(30) { session.tick() }
            assertEquals(moved.position, session.observation.units.single { it.unitId == second.unitId }.position)
            assertNotNull(moved.waterPose)
            assertTrue(moved.alive)
            assertEquals(600L, session.observation.balance)
            assertEquals(1, session.observation.units.count { it.typeId == "spdockt2" })
            assertEquals(2, session.observation.units.count { it.unitId.startsWith("produced:") })
            for (id in listOf(first.unitId, second.unitId))
                assertEquals(listOf("port", "starboard"), session.observation.weapons.filter { it.unitId == id }.map { it.weaponName })
        }
    }

    @Test fun normal_constructor_choice_exposes_two_inherited_ship_types_and_a_separate_underwater_target() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = load(format)
            val units = loaded.scenario.unitDefinitions
            assertEquals(setOf("spbuilder", "spdock", "spdockt2", "spcorvette", "spfrigate", "navalUnderwaterTarget"), units.keys)
            assertEquals(listOf("spcorvette"), units.getValue("spdock").production!!.unitTypeIds)
            assertEquals(listOf("spcorvette", "spfrigate"), units.getValue("spdockt2").production!!.unitTypeIds)
            assertEquals(MovementType.WATER, units.getValue("spdock").foundationMovementType)
            assertEquals(MovementType.WATER, units.getValue("spdockt2").foundationMovementType)
            for ((id, price) in listOf("spcorvette" to 350L, "spfrigate" to 450L)) {
                val unit = units.getValue(id)
                assertTrue(unit.authoredWaterCombat)
                assertEquals(price, unit.build!!.price)
                assertEquals(30.0, unit.radius)
                assertEquals(70.0, unit.combat!!.attackRange)
                val weapons = unit.combat!!.namedWeapons!!.ordered
                assertEquals(listOf("port", "starboard"), weapons.map { it.name })
                assertEquals(listOf(70.0, 100.0), weapons.map { it.shootDelay })
                assertFalse(weapons[0].canAttackUnderwaterUnits)
                assertTrue(weapons[1].canAttackUnderwaterUnits)
            }
            assertEquals(-8.0, units.getValue("navalUnderwaterTarget").fixedUnderwaterTarget!!.height)
            SandboxSession(loaded.scenario).use { session ->
                assertEquals(3000L, session.observation.balance)
                assertTrue(session.observation.units.all { it.typeId == "spbuilder" })
                assertTrue(session.observation.weapons.isEmpty())
                session.select(Position(110.0, 210.0))
                assertEquals("a", session.selectedId)
                assertEquals(listOf("spdock"), session.constructionActions.map { it.typeId })
            }
        }
    }
}
