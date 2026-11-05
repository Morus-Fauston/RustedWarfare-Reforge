package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class NavalPlacementTest {
    @Test fun authorized_preview_and_real_builder_agree_on_water_dry_mixed_edge_and_enemy_occupied_sites() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
            val folder = root.resolve("naval-production/$format")
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(folder.resolve("maps/naval-shore.tmx"))).value
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(folder.resolve("production-package"))).value
            for ((kind, worker, target) in listOf(
                Triple("water", Position(390.0, 150.0), Position(450.0, 150.0)),
                Triple("dry", Position(290.0, 150.0), Position(350.0, 150.0)),
                Triple("mixed", Position(370.0, 150.0), Position(430.0, 150.0)),
                Triple("edge", Position(390.0, 30.0), Position(450.0, 30.0)),
                Triple("enemy", Position(390.0, 150.0), Position(450.0, 150.0)))) {
                val spawns = listOf(UnitSpawn("worker", "p1", "builder", worker, 90.0)) +
                    if (kind == "enemy") listOf(UnitSpawn("occupied", "p2", "seaFactory", target, 0.0)) else emptyList()
                val scene = Scenario("naval-placement", map, pack.units,
                    listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)), spawns,
                    CoordinateModel("naval.coordinates", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
                    TimeModel("naval.time", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
                    combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, spawn -> spawn.unitId to i + 8L }.toMap(), 10L),
                    economyContract = EconomyContract(mapOf("p1" to 12000L, "p2" to 0L)))
                SandboxSession(scene).use { session ->
                    session.select(worker)
                    val preview = assertNotNull(session.previewConstruction("seaFactory", target))
                    assertEquals(if (kind == "water") PlacementState.CLEAR else PlacementState.BLOCKED, preview.state, "$format/$kind")
                    if (kind == "water") assertEquals((6..11).flatMap { row -> (20..24).map { col -> col to row } }, preview.cells)
                    if (kind == "water") assertIs<ActionResult.Accepted>(session.build("seaFactory", target))
                    else assertNull(session.build("seaFactory", target), "Blocked previews do not dispatch a client build")
                    session.tick()
                    val created = session.observation.units.filter { it.ownerPlayerId == "p1" && it.typeId == "seaFactory" }
                    assertEquals(if (kind == "water") listOf(target) else emptyList(), created.map { it.position }, "$format/$kind")
                    assertEquals(if (kind == "water") 11000L else 12000L, session.observation.balance, "$format/$kind")
                }
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                try {
                    assertIs<ActionResult.Accepted>(context.submit(BuildAction("direct", "p1", "worker", "seaFactory",
                        target, "a".repeat(32), context.world)))
                    val frame = context.advance(Step(0, 1.0, "sim-tick"))
                    assertEquals(if (kind == "water") listOf(target) else emptyList(), frame.units.filter {
                        it.ownerPlayerId == "p1" && it.typeId == "seaFactory" }.map { it.position }, "$format/$kind core")
                    if (kind != "water") assertTrue(frame.events.any { it.kind == "construction-unavailable" }, "$format/$kind core")
                    assertEquals(if (kind == "water") 11000L else 12000L, context.observePlayer(context.world, "p1").balance)
                } finally { context.close() }
            }
        }
    }
}
