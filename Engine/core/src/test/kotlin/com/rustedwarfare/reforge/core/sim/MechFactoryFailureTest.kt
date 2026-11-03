package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechFactoryFailureTest {
    @Test
    fun builder_failures_preserve_funds_and_do_not_create_a_factory_for_unpaid_or_invalid_sites() {
        for ((money, target, expected) in listOf(
            Triple(999L, Position(270.0, 150.0), "construction-insufficient-funds"),
            Triple(3000L, Position(10.0, 150.0), "construction-unavailable"),
            Triple(3000L, Position(270.0, 370.0), "construction-unavailable")
        )) {
            val base = scene(money)
            val input = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(190.0, 150.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "worker", "mechFactory", target,
                    "a".repeat(32), c.world)))
                val events = mutableSetOf<String>()
                repeat(400) { events += c.advance(Step(it.toLong(), 1.0, "sim-tick")).events.map { event -> event.kind } }
                assertTrue(expected in events, "$target: $events")
                assertEquals(money, c.observePlayer(c.world, "p1").balance)
                assertEquals(listOf("worker"), c.observe().units.map { it.unitId })
            } finally { c.close() }
        }
    }

    @Test
    fun a_legitimate_factory_produces_once_behind_an_external_wall_and_its_mech_can_receive_a_recovery_move() {
        val base = scene()
        val m = base.map
        // This wall is wholly outside the factory footprint and placement footprint.
        val wall = MapDefinition(m.widthTiles, m.heightTiles, m.tileWidth, m.tileHeight, m.tileSets, m.groundGids,
            BlockingGrid(m.widthTiles, m.heightTiles, BooleanArray(m.widthTiles * m.heightTiles) { it / m.widthTiles == 11 }))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(map = wall))).context
        try {
            val nonce = "a".repeat(32)
            val id = "produced:p1:$nonce"
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "factory", "mechGun", nonce, c.world)))
            var step = 0L
            while (step < 1200) c.advance(Step(step++, 1.0, "sim-tick"))
            val trapped = c.observe().units.single { it.unitId == id }
            assertTrue(trapped.position.y < 220.0)
            assertEquals(0f, c.observe().production!!.units.single { it.unitId == id }.birthRemaining)
            assertEquals(1L, c.observe().production!!.lastBirthSerial)
            assertEquals(2400L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("recover", "p1", id, Position(410.0, 190.0), c.world)))
            repeat(450) { c.advance(Step(step++, 1.0, "sim-tick")) }
            val recovered = c.observe().units.single { it.unitId == id }
            assertTrue(recovered.position.x > 390.0, "$trapped -> $recovered")
            assertTrue(recovered.position.y < 220.0)
            assertEquals(1L, c.observe().production!!.lastBirthSerial)
            assertEquals(2400L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }

    @Test
    fun real_queue_commands_preserve_money_nonces_and_owner_privacy_on_failure_and_cancel() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(599))).context
        try {
            val nonce = "a".repeat(32)
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("too-expensive", "p1", "factory", "mechGun", nonce, c.world))).error.code)
            assertEquals(599L, c.observePlayer(c.world, "p1").balance)
            assertEquals("unsupported-production-type", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("wrong-type", "p1", "factory", "c_tank", nonce, c.world))).error.code)
            assertEquals("invalid-unit-nonce", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("wrong-nonce", "p1", "factory", "builder", "bad", c.world))).error.code)
            val enemyBefore = c.observePlayer(c.world, "p2")
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("builder", "p1", "factory", "builder", nonce, c.world)))
            val paid = c.observePlayer(c.world, "p1")
            assertEquals(99L, paid.balance)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("foreign", "p2", "factory", "wrong", "bad", c.world))).error.code)
            assertTrue(c.observePlayer(c.world, "p2").factoryQueues.isEmpty())
            assertEquals(enemyBefore.balance, c.observePlayer(c.world, "p2").balance)
            assertIs<ActionResult.Accepted>(c.submit(CancelProductionAction("cancel", "p1", "factory", "builder", c.world)))
            assertEquals(599L, c.observePlayer(c.world, "p1").balance)
            assertEquals(99L, paid.balance)
            assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("reused", "p1", "factory", "builder", nonce, c.world))).error.code)
            assertTrue(c.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
        } finally { c.close() }
    }

    @Test
    fun actual_consecutive_mechs_alternate_sides_and_each_paid_nonce_creates_exactly_one_unit() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            for ((i, nonce) in listOf("a".repeat(32), "b".repeat(32)).withIndex())
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("queue-$i", "p1", "factory", "mechGun", nonce, c.world)))
            val births = mutableListOf<UnitObservation>()
            var step = 0L
            while (births.size < 2 && step < 1800) {
                val view = c.advance(Step(step++, 1.0, "sim-tick"))
                for (event in view.events.filter { it.kind == "unit-produced" })
                    births += view.units.single { it.unitId == event.unitId }
            }
            assertEquals(listOf(Position(277.0, 149.0), Position(263.0, 149.0)), births.map { it.position })
            assertEquals(2, births.map { it.unitId }.distinct().size)
            assertEquals(2L, c.observe().production!!.lastBirthSerial)
            assertEquals(1800L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
            val oldWorld = c.world
            c.close()
            assertIs<InitResult.Accepted>(c.reopen(scene()))
            assertEquals(0L, c.observe().production!!.lastBirthSerial)
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("stale", "p1", "factory", "mechGun", "a".repeat(32), oldWorld))).error.code)
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("fresh", "p1", "factory", "mechGun", "a".repeat(32), c.world)))
        } finally { c.close() }
    }

    @Test
    fun real_weapon_death_refunds_pending_mech_and_builder_production_once() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(enemy = true))).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack-factory", "p2", "enemy", "factory", c.world)))
            var step = 0L
            while (c.observe().units.single { it.unitId == "factory" }.health!! > 29.0 && step < 4000)
                c.advance(Step(step++, 1.0, "sim-tick"))
            val damaged = c.observe().units.single { it.unitId == "factory" }
            assertTrue(damaged.alive && damaged.health!! <= 29.0)
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("pending-mech", "p1", "factory", "mechGun", "a".repeat(32), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("pending-builder", "p1", "factory", "builder", "b".repeat(32), c.world)))
            assertEquals(1900L, c.observePlayer(c.world, "p1").balance)
            while (c.observe().units.single { it.unitId == "factory" }.alive && step < 4200)
                c.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(c.observe().units.single { it.unitId == "factory" }.alive)
            assertEquals(3000L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
            assertTrue(c.observe().units.none { it.unitId.startsWith("produced:") })
            repeat(10) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(3000L, c.observePlayer(c.world, "p1").balance)
            assertIs<ActionResult.Rejected>(c.submit(ProduceAction("dead", "p1", "factory", "mechGun", "c".repeat(32), c.world)))
        } finally { c.close() }
    }

    @Test
    fun the_finite_exit_helper_checks_the_exact_static_backup_cells_and_retains_initial_birth_if_all_fail() {
        // Mechanism-only ground variants. A successfully placed 3x3 factory cannot
        // initially contain these blocked cells; this is not a claimed PC scene.
        val input = scene()
        val definition = input.unitDefinitions.getValue("mechFactory")
        val product = input.unitDefinitions.getValue("mechGun")
        fun birth(x: Double, blocked: Set<Pair<Int, Int>>): FactoryBirth {
            val m = input.map
            val map = MapDefinition(m.widthTiles, m.heightTiles, m.tileWidth, m.tileHeight, m.tileSets, m.groundGids,
                BlockingGrid(m.widthTiles, m.heightTiles, BooleanArray(m.widthTiles * m.heightTiles) { i ->
                    (i % m.widthTiles to i / m.widthTiles) in blocked }))
            val factory = MutableUnit("factory", "p1", definition.id, definition, Position(x, 150.0), 0.0)
            return FactoryExit.birth(factory, product, map)
        }
        assertEquals(Position(277.0, 149.0), birth(270.0, emptySet()).position)
        assertEquals(Position(287.0, 149.0), birth(270.0, setOf(13 to 7)).position)
        assertEquals(Position(277.0, 149.0), birth(280.0, setOf(14 to 7)).position)
        assertEquals(Position(257.0, 159.0), birth(270.0, setOf(13 to 7, 14 to 7)).position)
        assertEquals(Position(257.0, 139.0), birth(270.0, setOf(13 to 7, 14 to 7, 12 to 7)).position)
        val failed = birth(270.0, setOf(13 to 7, 14 to 7, 12 to 7, 12 to 6))
        assertTrue(failed.fallback)
        assertEquals(Position(270.0, 149.0), failed.position)
        assertEquals(Position(269.0, 270.0), failed.target)
        assertEquals(30f, failed.protection)
    }

    private fun scene(money: Long = 3000L, enemy: Boolean = false): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("mech-production/ini")
        val definitions = assertIs<ContentResult.Success<LoadedContentSet>>(
            ContentLoader.loadContentPacks(listOf(root.resolve("production-package")))).value.units
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))).value
        val spawns = listOf(UnitSpawn("factory", "p1", "mechFactory", Position(270.0, 150.0), 0.0)) +
            if (enemy) listOf(UnitSpawn("enemy", "p2", "mechGun", Position(390.0, 150.0), 270.0)) else emptyList()
        return Scenario("p008-mech-factory-failures", map, definitions,
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)), spawns,
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, u -> u.unitId to 8L + i }.toMap(), 8L + spawns.size),
            economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L)))
    }
}
