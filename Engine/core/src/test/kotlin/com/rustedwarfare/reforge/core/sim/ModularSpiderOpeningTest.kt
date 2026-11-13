package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.ProjectileProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Path
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ModularSpiderOpeningTest {
    @field:TempDir
    lateinit var workspace: Path

    private val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun pack(format: String): LoadedContentPack {
        val input = fixtures.resolve("modular-spider-start/$format/opening-package")
        val loaded = ContentLoader.loadContentPack(input)
        return assertIs<ContentResult.Success<LoadedContentPack>>(loaded,
            (loaded as? ContentResult.Failure)?.error?.describe()).value
    }

    private fun start(map: MapDefinition, credits: Long, enemyDamage: Double? = null, fogWitness: Position? = null): SimulationContext {
        val content = pack("ini")
        val origin = Position(180.0, 160.0)
        val base = WaterTestScene.scenario(map, MovementType.OVER_CLIFF_WATER, origin)
        val target = UnitDefinition("witness", "CustomUnitMetadata", 6.0, 6.0, 1, content.units.getValue("mechGun").imagePath,
            1.0, WaterTestScene.unit(MovementType.LAND).movement.copy(moveSpeed = 1.1, moveAccelerationSpeed = 1.1),
            emptySet(), fogOfWarSightRange = if (fogWitness == null) 15 else 1,
            durability = DurabilityProfile(20000.0, null),
            combat = enemyDamage?.let { damage -> CombatProfile(20000.0, 78.0, 20.0, 0.0, 8.0, 2.0,
                ProjectileProfile(damage, 80.0, 12.0), null, null, 1.0) })
        val input = base.copy(unitDefinitions = content.units + (target.id to target),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("spider", "p1", "modularSpider", origin, 90.0),
                UnitSpawn("witness", "p2", target.id, fogWitness ?: Position(365.0, 160.0), 270.0)),
            economyContract = EconomyContract(mapOf("p1" to credits, "p2" to 500L)),
            automaticVision = fogWitness?.let {
                KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false)
            },
            combatContract = CombatContract(173, -1, mapOf("spider" to 8L, "witness" to 9L), 10L))
        val result = SimulationContext.initialize(input)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }

    @Test fun all_three_input_formats_admit_the_chassis_empty_slot_and_first_weapon() {
        for (format in listOf("ini", "json", "hjson")) {
            val pack = pack(format)
            val spider = pack.units.getValue("modularSpider")
            assertEquals(MovementType.OVER_CLIFF_WATER, spider.movement.type)
            assertEquals(10000.0, spider.maximumHealth)
            assertEquals(5000f, spider.shield?.maximum)
            assertEquals(8, spider.mech?.legs?.size)
            assertEquals(3000.0, pack.units.getValue("modularSpider_emptySlot").maximumHealth)
            val gun = assertNotNull(pack.units.getValue("modularSpider_gunturret").combat)
            assertEquals(290.0, gun.attackRange)
            assertEquals(44.0, gun.projectile.directDamage)
            assertTrue(pack.sources.any { it.path.fileName.toString() == "gun.$format" })
        }
    }

    @Test fun invalid_attachment_fields_and_missing_module_definitions_report_the_owning_declaration() {
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("modular-spider-start/$format/opening-package")
            val pack = workspace.resolve(format)
            Files.walk(source).use { paths -> paths.forEach { path ->
                val output = pack.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(output) else Files.copy(path, output)
            } }
            val chassis = pack.resolve("units/spider/spider.$format")
            val original = Files.readString(chassis)
            Files.writeString(chassis, original.replaceFirst("modularSpider_emptySlot", "missingSlot"))
            val wrongSlot = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(pack)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, wrongSlot.code, wrongSlot.describe())
            assertEquals("attachment_slot1", wrongSlot.section)
            assertEquals("onCreateSpawnUnitOf", wrongSlot.field)

            Files.writeString(chassis, original.replaceFirst("lockRotation", "unknownSlotRotation"))
            val unknown = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(pack)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FIELD, unknown.code, unknown.describe())
            assertEquals("unknownSlotRotation", unknown.field)
            Files.writeString(chassis, original)

            val manifest = pack.resolve("pack.json")
            val gunEntry = Regex(""",\s*\{\s*"type":\s*"modular-spider",\s*"file":\s*"units/spider/gun\.$format"\s*}""")
            val complete = Files.readString(manifest)
            assertEquals(1, gunEntry.findAll(complete).count())
            Files.writeString(manifest, complete.replace(gunEntry, ""))
            val absent = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(pack)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, absent.code, absent.describe())
            assertEquals("convertTo", absent.field)
            assertEquals(pack.resolve("units/spider/empty.$format").toString(), absent.file)
        }
    }

    @Test fun normal_initial_registration_fills_six_owned_slots_at_the_falling_chassis_pose() = WaterTestScene.withMap { map ->
        val owned = pack("ini")
        val center = Position(180.0, 160.0)
        val input = WaterTestScene.scenario(map, MovementType.OVER_CLIFF_WATER, center).copy(
            unitDefinitions = owned.units, units = listOf(UnitSpawn("spider", "p1", "modularSpider", center, 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 500L, "p2" to 500L)),
            combatContract = CombatContract(173, -1, mapOf("spider" to 8L), 9L))
        val result = SimulationContext.initialize(input)
        val context = assertIs<InitResult.Accepted>(result, result.toString()).context
        try {
            val state = context.observe()
            val parent = state.units.single { it.typeId == "modularSpider" }
            assertEquals(170.0, parent.height)
            val slots = state.units.filter { it.typeId == "modularSpider_emptySlot" }
            assertEquals(6, slots.size)
            assertEquals(6, slots.map { it.unitId }.toSet().size)
            assertEquals(setOf(Position(212.0, 149.0), Position(212.0, 171.0), Position(186.0, 149.0),
                Position(186.0, 171.0), Position(160.0, 150.0), Position(160.0, 171.0)), slots.map { it.position }.toSet())
            assertTrue(slots.all { it.ownerPlayerId == "p1" && it.carriedBy == null && it.height == parent.height })
            assertNull(parent.transport)
            val later = context.advance(Step(0, 1.0, "sim-tick"))
            val nextHeight = later.units.single { it.typeId == "modularSpider" }.height
            assertEquals(168.47, nextHeight, .001)
            assertTrue(later.units.filter { it.typeId == "modularSpider_emptySlot" }.all { it.height == nextHeight })
        } finally { context.close() }
    }

    @Test fun owner_slot_action_rejects_short_balance_and_modules_cannot_be_selected_directly() = WaterTestScene.withMap { map ->
        val context = start(map, 1999L)
        try {
            val initial = context.observe()
            val slot = initial.units.single { it.position == Position(212.0, 149.0) }
            assertIs<ActionResult.Rejected>(context.submit(SelectAction("direct", "p1", slot.unitId, context.world)))
            assertIs<ActionResult.Rejected>(context.submit(UnitAction("wrong-owner", "p2", "spider", "selectSlot1", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("slot", "p1", "spider", "selectSlot1", context.world)))
            val result = assertIs<ActionResult.Rejected>(
                context.submit(UnitAction("buy", "p1", "spider", "buildGun", context.world)))
            assertEquals("insufficient-funds", result.error.code)
            val final = context.observe()
            assertEquals(1999L, final.economy?.balances?.getValue("p1"))
            assertEquals(slot, final.units.single { it.unitId == slot.unitId })
        } finally { context.close() }
    }

    @Test fun fog_hides_the_chassis_relationship_without_changing_the_slots_public_target_identity() =
        WaterTestScene.withMap { map ->
            // At 20 units per cell the chassis and front slot centers are (190,170) and (210,150).
            // A one-cell sight radius from these witness positions covers both, only the slot, or neither.
            val witnessPositions = listOf(Position(200.0, 160.0), Position(225.0, 150.0), Position(265.0, 150.0))
            for ((index, point) in witnessPositions.withIndex()) {
                val context = start(map, 3000L, enemyDamage = 1.0, fogWitness = point)
                try {
                    fun ownerSlot() = context.observePlayer(context.world, "p1").units.single { it.unitId == "spider" }
                        .attachmentOverview!!.slots.first()
                    fun enemy() = context.observePlayer(context.world, "p2")
                    fun attack(id: String, target: String) =
                        context.submit(AttackAction(id, "p2", "witness", target, context.world))

                    val slotId = ownerSlot().unitId
                    assertEquals(index == 0, enemy().units.any { it.unitId == "spider" })
                    assertEquals(index < 2, enemy().units.any { it.unitId == slotId })
                    assertIs<ActionResult.Rejected>(attack("empty-slot", slotId))
                    assertIs<ActionResult.Accepted>(
                        context.submit(UnitAction("select-slot", "p1", "spider", "selectSlot1", context.world)))
                    assertIs<ActionResult.Accepted>(
                        context.submit(UnitAction("buy-gun", "p1", "spider", "buildGun", context.world)))
                    assertIs<ActionResult.Accepted>(
                        context.submit(AttackAction("aim-gun", "p1", "spider", "witness", context.world)))
                    val enemyEvents = mutableListOf<Event>()
                    val enemyProjectiles = mutableListOf<VisibleProjectileEvent>()
                    repeat(1200) { step ->
                        context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                        val view = enemy()
                        enemyEvents += view.events
                        enemyProjectiles += view.projectileEvents
                    }
                    assertEquals(slotId, ownerSlot().unitId)
                    assertEquals("modularSpider_gunturret", ownerSlot().typeId)
                    assertEquals(1f, ownerSlot().progress)
                    val view = enemy()
                    val child = view.units.singleOrNull { it.unitId == slotId }
                    assertEquals(index < 2, child != null)
                    assertEquals(index == 0, view.units.any { it.unitId == "spider" })
                    assertEquals(index < 2, view.weapons.any { it.unitId == slotId })
                    assertEquals(index < 2, enemyEvents.any { it.kind == "attachment-install-completed" && it.unitId == slotId })
                    assertEquals(index < 2, enemyProjectiles.any { it.sourceUnitId == slotId },
                        "Projected Gun T1 source at witness $point")
                    if (child != null) {
                        assertNull(child.unitActions)
                        assertNull(child.attachmentOverview)
                        assertEquals(if (index == 0) AttachedUnitObservation("spider", 1, false) else null, child.attachment)
                        assertIs<ActionResult.Accepted>(attack("filled-slot", child.unitId))
                        assertEquals(slotId, enemy().units.single { it.unitId == "witness" }.tasks.single().targetUnitId)
                    } else {
                        assertIs<ActionResult.Rejected>(attack("hidden-slot", slotId))
                    }
                    if (index != 0) {
                        assertFalse(enemyEvents.any { it.unitId == "spider" })
                        assertFalse(enemyProjectiles.any { it.sourceUnitId == "spider" || it.targetUnitId == "spider" })
                        assertIs<ActionResult.Rejected>(attack("hidden-chassis", "spider"))
                    }
                    if (index == 1) assertFalse(slotId.contains("spider"), "Slot ID reveals the hidden chassis: $slotId")
                } finally { context.close() }
            }
        }

    @Test fun an_authorized_gun_is_paid_cancelled_rebuilt_and_fires_from_the_same_slot() = WaterTestScene.withMap { map ->
        val context = start(map, 5000L)
        var index = 0L
        fun tick() = context.advance(Step(index++, 1.0, "sim-tick"))
        fun click(id: String, action: String) = context.submit(UnitAction(id, "p1", "spider", action, context.world))
        fun money() = context.observe().economy?.balances?.getValue("p1")
        try {
            val slotId = context.observe().units.single { it.position == Position(212.0, 149.0) }.unitId
            assertIs<ActionResult.Accepted>(click("select", "selectSlot1"))
            assertIs<ActionResult.Accepted>(click("buy", "buildGun"))
            assertEquals(3000L, money())
            assertIs<ActionResult.Rejected>(click("duplicate", "buildGun"))
            assertEquals(3000L, money())
            val building = tick().units.single { it.unitId == slotId }
            assertEquals("modularSpider_gunturret", building.typeId)
            assertTrue(building.constructionProgress < 1f)
            assertIs<ActionResult.Accepted>(click("cancel", "cancel"))
            val cancelled = tick().units.single { it.unitId == slotId }
            assertEquals("modularSpider_emptySlot", cancelled.typeId)
            assertEquals(1f, cancelled.constructionProgress)
            assertEquals(5000L, money())
            assertIs<ActionResult.Rejected>(click("cooldown", "buildGun"))
            repeat(121) { tick() }
            val available = checkNotNull(money())
            assertIs<ActionResult.Accepted>(click("rebuild", "buildGun"))
            assertEquals(available - 2000L, money())
            assertIs<ActionResult.Accepted>(
                context.submit(AttackAction("attack", "p1", "spider", "witness", context.world)))
            val events = ArrayList<ProjectileEventObservation>()
            repeat(1110) {
                val frame = tick()
                val gun = frame.units.single { it.unitId == slotId }
                if (gun.constructionProgress < 1f)
                    assertTrue(frame.projectileEvents.none { it.projectile.sourceUnitId == slotId })
                events += frame.projectileEvents
            }
            assertTrue(events.any { it.kind == "born" && it.projectile.sourceUnitId == slotId })
            assertTrue(events.none { it.kind == "born" && it.projectile.sourceUnitId == "spider" })
            assertTrue(context.observe().units.single { it.unitId == "witness" }.health!! < 20000.0)
            assertIs<ActionResult.Accepted>(click("reclaim", "reclaim"))
            repeat(180) { tick() }
            assertEquals("modularSpider_emptySlot", context.observe().units.single { it.unitId == slotId }.typeId)
        } finally { context.close() }
    }

    @Test fun hostile_slot_damage_uses_the_parent_shield_then_empties_the_same_slot_and_parent_death_releases_it() =
        WaterTestScene.withMap { map ->
            val context = start(map, 3000L, enemyDamage = 4500.0)
            var index = 0L
            fun tick() = context.advance(Step(index++, 1.0, "sim-tick"))
            fun attack(id: String, target: String) = context.submit(AttackAction(id, "p2", "witness", target, context.world))
            try {
                val slotId = context.observe().units.single { it.position == Position(212.0, 149.0) }.unitId
                assertIs<ActionResult.Rejected>(attack("empty", slotId))
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("select", "p1", "spider", "selectSlot1", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("buy", "p1", "spider", "buildGun", context.world)))
                repeat(70) { tick() }
                assertIs<ActionResult.Accepted>(attack("filled", slotId))
                val hits = mutableListOf<Observation>()
                repeat(240) {
                    val frame = tick()
                    if (frame.projectileEvents.any { it.kind == "impact" && it.projectile.targetUnitId == slotId }) hits += frame
                }
                assertTrue(hits.size >= 2)
                val firstParent = hits.first().units.single { it.unitId == "spider" }
                val firstChild = hits.first().units.single { it.unitId == slotId }
                assertEquals(500f, firstParent.shield?.current)
                assertEquals(10000.0, firstParent.health)
                assertEquals(2000.0, firstChild.health)
                val emptied = hits.last().units.single { it.unitId == slotId }
                assertEquals("modularSpider_emptySlot", emptied.typeId)
                assertEquals(1f, emptied.constructionProgress)
                assertIs<ActionResult.Accepted>(attack("parent", "spider"))
                repeat(200) { tick() }
                val ended = context.observe().units
                assertFalse(ended.single { it.unitId == "spider" }.alive)
                assertTrue(ended.none { it.typeId == "modularSpider_emptySlot" || it.typeId == "modularSpider_gunturret" })
            } finally { context.close() }
        }
}
