package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import org.hjson.JsonObject
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentSlotOffsetTest {
    @field:TempDir
    lateinit var workspace: Path

    private fun pack(): LoadedContentPack =
        com.rustedwarfare.reforge.core.content.ReadOnlyTestContentPacks.loadDerivedImmutablePack(
            "AttachmentSlotOffsetTest.opening-package.v1",
        ) { target ->
            val fixtures = com.rustedwarfare.reforge.core.content.ReadOnlyTestContentPacks.publicFixturesRoot()
            val source = fixtures.resolve("modular-spider-start/json/opening-package")
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    val destination = target.resolve(source.relativize(path))
                    if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
                }
            }
            val chassis = target.resolve("units/spider/spider.json")
            val declaration = JsonValue.readJSON(Files.readString(chassis)).asObject()
            val actions = declaration.get("actions").asArray()
            actions.add(JsonObject().add("name", "shiftGun").add("attachmentSlot", 1)
                .add("attachmentX", 22).add("attachmentY", 71))
            actions.add(JsonObject().add("name", "outsideMap").add("attachmentSlot", 1)
                .add("attachmentX", 22).add("attachmentY", 900))
            actions.add(JsonObject().add("name", "extendEast").add("attachmentSlot", 1)
                .add("attachmentX", 0).add("attachmentY", 580))
            actions.add(JsonObject().add("name", "extendForTurn").add("attachmentSlot", 1)
                .add("attachmentX", 0).add("attachmentY", 225))
            actions.add(JsonObject().add("name", "extendForShot").add("attachmentSlot", 1)
                .add("attachmentX", 0).add("attachmentY", 500))
            actions.add(JsonObject().add("name", "extendAcrossPeer").add("attachmentSlot", 1)
                .add("attachmentX", 0).add("attachmentY", 180))
            Files.writeString(chassis, declaration.toString())
        }

    @Test fun scheduled_unit_action_moves_only_its_own_mounted_slot_and_rejects_outside_offsets() =
        WaterTestScene.withMap { map ->
            withSpiders(map) { match ->
                val first = match.mount("alpha")
                val second = match.mount("beta")
                assertEquals(Position(212.0, 149.0), match.unit(first).position)
                assertEquals(Position(392.0, 269.0), match.unit(second).position)
                assertTrue(match.unit("alpha").unitActions!!.actions.any { it.name == "shiftGun" && it.available })

                val step = match.step
                val before = match.unit(first)
                val scheduled = assertIs<ActionResult.Accepted>(match.action("shift", "alpha", "shiftGun", atStep = step))
                assertEquals(step, scheduled.queuedForStep)
                assertEquals(before.position, match.unit(first).position)
                val frame = match.tick()
                assertTrue(frame.actionResults.any { it.actionId == "shift" && it is ActionResult.Accepted })
                assertEquals(Position(251.0, 182.0), match.unit(first).position)
                assertEquals(Position(392.0, 269.0), match.unit(second).position)
                assertEquals(first, match.slot("alpha").unitId)
                println("offset step=$step alpha=${match.unit(first).position} beta=${match.unit(second).position}")

                assertEquals("attachment-outside-map",
                    assertIs<ActionResult.Rejected>(match.action("outside", "alpha", "outsideMap")).error.code)
                assertEquals(Position(251.0, 182.0), match.unit(first).position)
                assertEquals(Position(392.0, 269.0), match.unit(second).position)
            }
        }

    @Test fun inherited_translation_stops_at_the_mounted_guns_legal_edge() = WaterTestScene.withMap { map ->
        withSpiders(map) { match ->
            val gun = match.mount("alpha")
            assertIs<ActionResult.Accepted>(match.action("extend", "alpha", "extendEast"))
            assertIs<ActionResult.Accepted>(match.move("east", "alpha", Position(450.0, 160.0)))
            repeat(220) {
                match.tick()
                val parent = match.unit("alpha")
                val child = match.unit(gun)
                assertEquals(parent.position.x + 580.0, child.position.x, .000001)
                assertEquals(160.0, child.position.y, .000001)
                assertTrue(child.position.x <= 785.0)
            }
            assertEquals(205.0, match.unit("alpha").position.x, .000001)
            assertEquals(785.0, match.unit(gun).position.x, .000001)
            println("translation parent=${match.unit("alpha").position} mount=${match.unit(gun).position}")
        }
    }

    @Test fun inherited_turn_stops_on_the_arc_before_the_gun_crosses_the_map_edge() = WaterTestScene.withMap { map ->
        withSpiders(map) { match ->
            val gun = match.mount("alpha")
            assertIs<ActionResult.Accepted>(match.action("extend", "alpha", "extendForTurn"))
            assertIs<ActionResult.Accepted>(match.move("north", "alpha", Position(180.0, 65.0)))
            repeat(300) {
                match.tick()
                assertTrue(match.unit(gun).position.y >= 15.0)
            }
            val parent = match.unit("alpha")
            assertTrue(parent.heading in 5.0..85.0, "Expected a partial turn, got ${parent.heading}")
            assertEquals(15.0, match.unit(gun).position.y, .001)
            println("rotation parent=${parent.position} heading=${parent.heading} mount=${match.unit(gun).position}")
        }
    }

    @Test fun a_legal_end_heading_cannot_skip_an_illegal_part_of_the_rotation() {
        val center = Position(150.0, 130.0)
        val offset = Position(0.0, 130.0)
        fun inside(heading: Double) = MapBounds.contains(800.0, 400.0,
            MechMotion.offset(center, heading, offset.x, offset.y), 15.0)
        assertTrue(inside(315.0) && inside(45.0))
        assertFalse(inside(0.0))
        val limited = RectangularTurn.pointWithinMap(center, offset, 15.0, 800.0, 400.0, 315.0, 45.0, ::inside)
        assertTrue(limited in 330.0..340.0 && inside(limited) && !inside(limited + .000001))
    }

    @Test fun the_new_offset_drives_range_and_fog_in_the_action_step_and_the_actual_muzzle() =
        WaterTestScene.withMap { map ->
            withSpiders(map, withWitness = true) { match ->
                val gun = match.mount("alpha")
                val peerGun = match.mount("beta")
                assertNull(match.weapon(gun).targetUnitId)
                assertFalse(match.visible("watch"))
                val step = match.step
                assertIs<ActionResult.Accepted>(match.action("deploy", "alpha", "extendForShot", atStep = step))
                val first = match.tick()
                assertTrue(first.events.any { it.kind == "unit-action-attachment-offset-changed" && it.unitId == "alpha" })
                val center = match.unit(gun).position
                assertEquals(680.0, center.x, .000001)
                assertEquals(160.0, center.y, .000001)
                assertTrue(match.visible("watch"))
                val weapon = match.weapon(gun)
                assertEquals("watch", weapon.targetUnitId)
                assertEquals(680.0, weapon.pivot.x, .000001)
                assertEquals(160.0, weapon.pivot.y, .000001)
                assertTrue(weapon.muzzle.x > 700.0 && weapon.muzzle.y in 150.0..172.0)
                assertNull(match.weapon(peerGun).targetUnitId)

                val shot = (sequenceOf(first) + generateSequence { match.tick() }.take(40))
                    .flatMap { it.projectileEvents.asSequence() }
                    .first { it.kind == "born" && it.projectile.sourceUnitId == gun }.projectile
                assertEquals("watch", shot.targetUnitId)
                assertTrue(shot.position.x in 700.0..704.0 && shot.position.y in 153.0..167.0,
                    "Expected the born projectile at the adjusted gun's east barrel, got ${shot.position}")
                println("aim and vision step=$step mount=$center target=${shot.targetUnitId} muzzle=${weapon.muzzle} born=${shot.position}")
            }
        }

    @Test fun a_peer_crosses_the_adjusted_module_and_contacts_the_parent_body() = WaterTestScene.withMap { map ->
        withSpiders(map) { match ->
            val gun = match.mount("alpha")
            assertIs<ActionResult.Accepted>(match.action("extend", "alpha", "extendAcrossPeer"))
            assertIs<ActionResult.Accepted>(match.move("north", "beta", Position(360.0, 160.0)))
            repeat(740) { match.tick() }
            val pass = match.unit("beta").position
            assertTrue(kotlin.math.hypot(pass.x - 360.0, pass.y - 160.0) < 16.0, "Peer ended at $pass")
            assertEquals(Position(360.0, 160.0), match.unit(gun).position)

            assertIs<ActionResult.Accepted>(match.move("west", "beta", Position(70.0, 160.0)))
            var touchedBody = false
            repeat(860) {
                val frame = match.tick()
                val a = match.unit("alpha").position
                val b = match.unit("beta").position
                val distance = kotlin.math.hypot(b.x - a.x, b.y - a.y)
                assertTrue(distance >= 97.999999, "Parent bodies overlap by ${98.0 - distance}")
                if (!touchedBody && distance <= 98.001 &&
                    frame.events.any { it.kind == "movement-blocked" && it.unitId == "beta" }) {
                    touchedBody = true
                    println("parent contact step=${frame.stepIndex} separation=$distance peer crossed mount at $pass")
                }
            }
            assertTrue(touchedBody, "Expected contact at the combined 98-unit parent body radii")
        }
    }

    private fun withSpiders(map: MapDefinition, withWitness: Boolean = false, test: (Match) -> Unit) {
        val start = Position(180.0, 160.0)
        val spawns = listOf(UnitSpawn("alpha", "p1", "modularSpider", start, 90.0),
            UnitSpawn("beta", "p1", "modularSpider", Position(360.0, 280.0), 90.0)) +
            if (withWitness) listOf(UnitSpawn("watch", "p2", "water-test", Position(770.0, 160.0), 270.0))
            else emptyList()
        val model = WaterTestScene.unit(MovementType.HOVER)
        val witness = UnitDefinition("water-test", model.className, 6.0, 6.0, 1, model.imagePath, 1.0,
            model.movement, emptySet(), fogOfWarSightRange = 1, durability = DurabilityProfile(20000.0, null))
        val input = WaterTestScene.scenario(map, MovementType.OVER_CLIFF_WATER, start).copy(
            unitDefinitions = pack().units + if (withWitness) mapOf(witness.id to witness) else emptyMap(),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            units = spawns,
            automaticVision = if (withWitness)
                KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false) else null,
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 500L)),
            combatContract = CombatContract(173, -1,
                spawns.mapIndexed { index, spawn -> spawn.unitId to (8L + index) }.toMap(), 8L + spawns.size))
        val result = SimulationContext.initialize(input)
        val context = assertIs<InitResult.Accepted>(result, result.toString()).context
        try {
            test(Match(context))
        } finally {
            context.close()
        }
    }

    private class Match(val context: SimulationContext) {
        var step = 0L
        fun tick(): Observation = context.advance(Step(step++, 1.0, "sim-tick"))
        fun unit(id: String) = context.observePlayer(context.world, "p1").units.single { it.unitId == id }
        fun weapon(id: String) = context.observePlayer(context.world, "p1").weapons.single { it.unitId == id }
        fun visible(id: String) = context.observePlayer(context.world, "p1").units.any { it.unitId == id }
        fun slot(parent: String) = assertNotNull(unit(parent).attachmentOverview).slots.first { it.number == 1 }
        fun action(id: String, parent: String, name: String, atStep: Long? = null, owner: String = "p1") =
            context.submit(UnitAction(id, owner, parent, name, context.world, atStep = atStep))
        fun move(id: String, parent: String, point: Position) =
            context.submit(MoveAction(id, "p1", parent, point, context.world))

        fun mount(parent: String): String {
            assertIs<ActionResult.Accepted>(action("select-$parent", parent, "selectSlot1"))
            assertIs<ActionResult.Accepted>(action("build-$parent", parent, "buildGun"))
            repeat(1200) {
                if (slot(parent).progress == 1f && slot(parent).typeId == "modularSpider_gunturret")
                    return slot(parent).unitId
                tick()
            }
            error("Gun was not completed at step $step")
        }
    }
}
