package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.*

class EconomicBranchConversionTest {
    @Test fun price_refund_and_actual_credit_are_distinct_transactions() = withWorld(11_000L) { context ->
        fun balance() = context.observePlayer(context.world, "p1").balance
        var sequence = 0
        fun command(key: String, cancel: Boolean = false) = if (cancel)
            context.submit(CancelUpgradeAction("cancel-${++sequence}-$key", "p1", "mine", key, context.world))
        else context.submit(UpgradeAction("start-${++sequence}-$key", "p1", "mine", key, context.world))
        var conversionCredits = 0L
        fun complete(step: Long, limit: Long, type: String): Long {
            for (index in step..limit) {
                val before = checkNotNull(balance())
                val observation = context.advance(Step(index, 1.0, "sim-tick"))
                if (observation.units.single { it.unitId == "mine" }.typeId == type) {
                    conversionCredits = checkNotNull(balance()) - before
                    return index + 1
                }
            }
            fail("No completion of $type by $limit")
        }
        assertIs<ActionResult.Accepted>(command("overclock"))
        assertEquals(3000L, balance())
        assertEquals("duplicate-upgrade", assertIs<ActionResult.Rejected>(command("overclock")).error.code)
        assertIs<ActionResult.Accepted>(command("reinforce"))
        assertEquals(0L, balance())
        assertEquals(2, context.observePlayer(context.world, "p1").upgradeQueues.single().items.size)
        assertTrue(context.observePlayer(context.world, "p2").upgradeQueues.isEmpty())
        val advanced = complete(0, 5100, "overclocked_form")
        assertTrue(conversionCredits in setOf(3000L, 3030L))
        assertTrue(context.observePlayer(context.world, "p1").upgradeQueues.single().items.isEmpty())
        assertEquals("unsupported-upgrade", assertIs<ActionResult.Rejected>(command("reinforce")).error.code)
        val previousIncome = balance()
        for (index in advanced until advanced + 40) context.advance(Step(index, 1.0, "sim-tick"))
        assertEquals(previousIncome!! + 30L, balance())
        val before = balance()
        assertIs<ActionResult.Accepted>(command("refund"))
        assertEquals(before, balance())
        assertIs<ActionResult.Accepted>(command("refund", cancel = true))
        assertEquals(before, balance())
        assertEquals("empty-upgrade-queue", assertIs<ActionResult.Rejected>(command("refund", cancel = true)).error.code)
        assertIs<ActionResult.Accepted>(command("refund"))
        val workStarted = advanced + 40
        val refunded = complete(workStarted, workStarted + 3050, "extractor_form")
        assertTrue(refunded - workStarted in 2995L..3010L)
        assertTrue(conversionCredits in setOf(4000L, 4020L))
        assertTrue(balance()!! >= before!! + 4000L)
        assertEquals("unsupported-upgrade", assertIs<ActionResult.Rejected>(command("refund")).error.code)
    }

    @Test fun wrong_balance_and_foreign_commands_cannot_enter_the_queue() = withWorld(2999L) { context ->
        val world = context.world
        val denied = context.submit(UpgradeAction("buy", "p1", "mine", "reinforce", world))
        assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(denied).error.code)
        assertIs<ActionResult.Rejected>(context.submit(UpgradeAction("foreign", "p2", "mine", "reinforce", world)))
        assertEquals(2999L, context.observePlayer(world, "p1").balance)
        assertTrue(context.observePlayer(world, "p1").upgradeQueues.single().items.isEmpty())
    }

    @Test fun enabled_pack_rejects_a_missing_reciprocal_form_and_excess_credit() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val source = fixtures.resolve("economy-branches/package")
        val directory = Files.createTempDirectory("rwr-economic-forms-").toRealPath()
        try {
            Files.list(source).use { files -> files.forEach { Files.copy(it, directory.resolve(it.fileName)) } }
            val form = directory.resolve("overclocked_form.json")
            val original = Files.readString(form)
            for ((broken, expectedField) in listOf(
                original.replace("\"extractor_form\"", "\"missing_form\"") to "convertTo",
                original.replace("4000", "8001") to "addCredits")) {
                assertNotEquals(original, broken)
                Files.writeString(form, broken)
                val rejected = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(directory)))
                assertEquals(ContentErrorCode.BAD_REFERENCE, rejected.error.code)
                assertEquals(form.toString(), rejected.error.file)
                assertEquals("action_refund", rejected.error.section)
                assertEquals(expectedField, rejected.error.field)
            }
            val noActions = JsonValue.readJSON(original).asObject().apply { remove("actions") }
            Files.writeString(form, noActions.toString())
            val empty = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(directory))).error
            assertEquals(form.toString(), empty.file)
            assertEquals("actions", empty.section)
            assertEquals("actions", empty.field)

            val noReturn = original.replace("\"extractor_form\"", "\"reinforced_form\"")
            Files.writeString(form, noReturn)
            val reciprocal = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(directory))).error
            assertEquals(form.toString(), reciprocal.file)
            assertEquals("actions", reciprocal.section)
            assertEquals("actions", reciprocal.field)

            val parent = directory.resolve("refund_parent.json")
            val inherited = JsonValue.readJSON(original).asObject()
            val refund = inherited["actions"].asArray()[0].asObject()
            refund.remove("addCredits")
            inherited.add("copyFrom", JsonValue.readJSON("[\"refund_parent.json\"]"))
            Files.writeString(form, inherited.toString())
            Files.writeString(parent, """{"format":"rw-reforge.unit.v2",
                "actions":[{"name":"refund","addCredits":8001}]}""")
            val misplaced = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(directory))).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, misplaced.code)
            assertEquals(parent.toString(), misplaced.file)
            assertEquals("action_refund", misplaced.section)
            assertEquals("addCredits", misplaced.field)
            assertContains(misplaced.reason, "consumer=[action_refund].addCredits")
            Files.writeString(form, original)
            assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(directory)))
        } finally {
            Files.list(directory).use { files -> files.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    @Test fun hjson_and_json_define_the_same_effective_conversion_and_locate_an_invalid_credit() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val source = fixtures.resolve("economy-branches/package")
        val directory = Files.createTempDirectory("rwr-economic-hjson-").toRealPath()
        try {
            Files.list(source).use { files -> files.forEach { Files.copy(it, directory.resolve(it.fileName)) } }
            val manifest = directory.resolve("pack.json")
            Files.writeString(manifest, Files.readString(manifest).replace("overclocked_form.json", "overclocked_form.hjson"))
            val hjson = directory.resolve("overclocked_form.hjson")
            val original = Files.readString(directory.resolve("overclocked_form.json"))
            val witness = original.replace("\"format\":", "format:").replace("\"actions\":", "actions:")
                .replace("\"addCredits\":", "addCredits:").replace("\"buildSpeed\":", "buildSpeed:")
            Files.writeString(hjson, "// Valid Hjson form with a named 50-second refund.\n" + witness)
            fun load(path: Path): UnitDefinition =
                assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(path))).value
                    .units.getValue("overclocked_form")
            assertEquals(load(source).upgrades!!.actions, load(directory).upgrades!!.actions)
            assertEquals(4000L, load(directory).upgrades!!.actions.single().completionCredits)
            Files.writeString(hjson, witness.replace("4000", "8001"))
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(directory))).error
            assertEquals(hjson.toString(), failure.file)
            assertEquals("action_refund", failure.section)
            assertEquals("addCredits", failure.field)
        } finally {
            Files.list(directory).use { files -> files.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    @Test fun a_paid_branch_does_not_survive_under_a_reused_action_name_in_the_next_form() =
        withWorld(11_000L, reuseActionName = true) { context ->
            val world = context.world
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("first", "p1", "mine", "overclock", world)))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("second", "p1", "mine", "reinforce", world)))
            var converted = false
            for (step in 0L..5100L) {
                val before = checkNotNull(context.observePlayer(world, "p1").balance)
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                if (observation.units.single().typeId == "overclocked_form") {
                    assertTrue(observation.events.any { it.kind == "upgrade-refunded" && it.unitId == "mine" })
                    assertTrue(checkNotNull(context.observePlayer(world, "p1").balance) - before in setOf(3000L, 3030L))
                    assertTrue(context.observePlayer(world, "p1").upgradeQueues.single().items.isEmpty())
                    assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("new", "p1", "mine", "reinforce", world)))
                    converted = true
                    break
                }
            }
            assertTrue(converted)
        }

    @Test fun completion_credits_are_added_even_at_the_periodic_income_ceiling() =
        withWorld(999_999_999L, startingForm = "reinforced_form") { context ->
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("refund", "p1", "mine", "refund", context.world)))
            var completed = false
            for (step in 0L..350L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                if (frame.units.single().typeId == "extractor_form") {
                    assertEquals(1_000_001_999L, context.observePlayer(context.world, "p1").balance)
                    completed = true
                    break
                }
            }
            assertTrue(completed)
        }

    @Test fun reinforcement_adds_a_regenerating_shield_without_changing_income_then_refunds_at_completion() =
        withWorld(3000L, attacker = true) { context ->
            fun mine() = context.observePlayer(context.world, "p1").units.single { it.unitId == "mine" }
            fun balance() = checkNotNull(context.observePlayer(context.world, "p1").balance)
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("reinforce", "p1", "mine", "reinforce", context.world)))
            assertEquals(0L, balance())
            while (step <= 1500 && mine().typeId == "extractor_form") advance()
            assertEquals("reinforced_form", mine().typeId)
            assertEquals(ShieldObservation(800f, 800f), mine().shield)
            val beforeIncome = balance()
            repeat(40) { advance() }
            assertEquals(beforeIncome + 20L, balance())

            val aim = context.submit(AttackAction("test-shield", "p2", "probe", "mine", context.world))
            assertIs<ActionResult.Accepted>(aim, aim.toString())
            var hit: Observation
            val deadline = step + 120
            do { hit = advance() } while (step < deadline && hit.events.none { it.kind == "shield-damaged" && it.unitId == "mine" })
            assertTrue(hit.events.any { it.kind == "shield-damaged" && it.unitId == "mine" })
            val damaged = assertNotNull(mine().shield)
            assertTrue(damaged.current < 800f)
            advance()
            assertEquals(damaged.current + .15f, checkNotNull(mine().shield).current, .0001f)

            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("refund", "p1", "mine", "refund", context.world)))
            val begun = step
            var credits = 0L
            while (step < begun + 350 && mine().typeId == "reinforced_form") {
                val before = balance()
                val frame = advance()
                if (frame.events.any { it.kind == "upgrade-completed" && it.unitId == "mine" }) credits = balance() - before
            }
            assertTrue(step - begun in 333L..335L)
            assertEquals("extractor_form", mine().typeId)
            assertNull(mine().shield)
            assertTrue(credits in setOf(2000L, 2020L))
        }

    private fun withWorld(balance: Long, attacker: Boolean = false, startingForm: String = "extractor_form",
                          reuseActionName: Boolean = false, action: (SimulationContext) -> Unit) {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val input = listOf(root.resolve("economy-branches/package"))
        val loaded = ContentLoader.loadContentPacks(input)
        val definitions = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, loaded.toString()).value.units.toMutableMap()
        if (reuseActionName) {
            val overclocked = definitions.getValue("overclocked_form")
            val refund = overclocked.upgrades!!.actions.single().copy(actionKey = "reinforce")
            definitions[overclocked.id] = UnitDefinition(overclocked.id, overclocked.className, overclocked.radius,
                overclocked.displayRadius, overclocked.totalFrames, overclocked.imagePath, overclocked.imageScale,
                overclocked.movement, overclocked.retainedUnconsumed, overclocked.fogOfWarSightRange,
                durability = overclocked.durability, building = overclocked.building,
                upgrades = UpgradeProfile(listOf(refund)), income = overclocked.income, construction = overclocked.construction)
        }
        val mapResult = ContentLoader.loadMap(root.resolve("build-economy/upgrades/maps/economy-chain.tmx"))
        val map = assertIs<ContentResult.Success<MapDefinition>>(mapResult).value
        val pool = (0 until map.widthTiles * map.heightTiles).first { index ->
            map.isResourcePool(index % map.widthTiles, index / map.widthTiles)
        }
        val position = Position((pool % map.widthTiles + .5) * map.tileWidth, (pool / map.widthTiles + .5) * map.tileHeight)
        val probeHeading = if (position.x > map.widthTiles * map.tileWidth / 2.0) 90.0 else 270.0
        val probeX = if (probeHeading == 90.0) position.x - 150.0 else position.x + 150.0
        val base = WaterTestScene.scenario(map, MovementType.LAND, position)
        val mine = UnitSpawn("mine", "p1", startingForm, position, 0.0)
        val probe = if (!attacker) null else {
            val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
            PointDefenseTestContent.variant(tank, id = "probe", combat = checkNotNull(tank.combat).copy(
                shootDelay = 10000.0, attackRange = 130.0))
        }
        val inputScene = base.copy(unitDefinitions = if (probe == null) definitions else definitions + (probe.id to probe),
            units = listOf(mine) + listOfNotNull(probe?.let {
                UnitSpawn("probe", "p2", it.id, Position(probeX, position.y), probeHeading)
            }),
            players = listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.LOCAL, "two")),
            combatContract = if (probe == null) null else CombatContract(12345, -1, mapOf("mine" to 8L, "probe" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to balance, "p2" to 0L)))
        val initialization = SimulationContext.initialize(inputScene)
        val context = assertIs<InitResult.Accepted>(initialization, initialization.toString()).context
        try { action(context) } finally { context.close() }
    }
}
