package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.sim.InitResult
import com.rustedwarfare.reforge.core.sim.CoordinateModel
import com.rustedwarfare.reforge.core.sim.MoveAction
import com.rustedwarfare.reforge.core.sim.MovementContract
import com.rustedwarfare.reforge.core.sim.MovementState
import com.rustedwarfare.reforge.core.sim.Player
import com.rustedwarfare.reforge.core.sim.PlayerControl
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.sim.SimulationContext
import com.rustedwarfare.reforge.core.sim.Step
import com.rustedwarfare.reforge.core.sim.TimeModel
import com.rustedwarfare.reforge.core.sim.UnitSpawn
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

/**
 * KR-v0.1 fixed public oracle for owner knowledge-snapshot Move routing.
 *
 * Expected paths are literal values written here first; they are never produced by
 * calling the planner or by reading back the observed result.
 * Geometry: 5x5 tiles, tile 20, radius 1, start (30,50), target (70,50).
 */
class KnowledgePathRoutingTest {

    private val start = Position(30.0, 50.0)
    private val target = Position(70.0, 50.0)

    /** Literal expectations copied from the already-fixed KI oracle, not regenerated. */
    private val straight = listOf(Position(50.0, 50.0), Position(70.0, 50.0))
    private val upper = listOf(
        Position(30.0, 30.0), Position(50.0, 30.0), Position(70.0, 30.0), Position(70.0, 50.0),
    )

    private fun map(vararg blocked: Pair<Int, Int>): MapDefinition {
        val cells = BooleanArray(25)
        blocked.forEach { (column, row) -> cells[row * 5 + column] = true }
        return MapDefinition(5, 5, 20, 20, emptyList(), IntArray(25), BlockingGrid(5, 5, cells))
    }

    private fun unitDefinition(): UnitDefinition = UnitDefinition(
        id = "tank",
        className = "CustomUnitMetadata",
        radius = 1.0,
        displayRadius = 1.0,
        totalFrames = 1,
        imagePath = Path.of("unused.png"),
        imageScale = 1.0,
        movement = MovementProfile(
            type = MovementType.LAND,
            moveSpeed = 1.1,
            moveAccelerationSpeed = 0.07,
            moveDecelerationSpeed = 0.17,
            maxTurnSpeed = 4.1,
            turnAcceleration = 0.25,
            slidingMode = false,
            ignoringBody = false,
        ),
        retainedUnconsumed = emptySet(),
    )

    private fun scenario(map: MapDefinition): Scenario = Scenario(
        scenarioId = "kr-test",
        map = map,
        unitDefinitions = mapOf("tank" to unitDefinition()),
        players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
        units = listOf(UnitSpawn("u1", "p1", "tank", start, 0.0)),
        coordinateModel = CoordinateModel(
            "p001.coordinate.v1", "2d", "x-right", "y-down", "top-left",
            "world-unit", "double", "ieee754-binary64",
        ),
        timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
        movementContract = MovementContract(16.0, 4.0),
    )

    private fun open(map: MapDefinition): SimulationContext =
        assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario(map))).context

    private fun rules(pathSource: PathInformationSource, retention: TerrainRetention = TerrainRetention.MEMORY) =
        KnowledgeRules(retention, pathSource, shareAlliedVision = false)

    /**
     * KR02: two different truth maps (open vs center blocked), both PLAYER_KNOWLEDGE with empty
     * history, must yield the same straight path. If the routing wrongly consumed truth, the
     * blocked map would produce [upper] instead.
     */
    @Test
    fun kr02_player_knowledge_empty_history_ignores_true_center_block() {
        val openContext = open(map())
        val blockedContext = open(map(2 to 2))
        try {
            openContext.enableKnowledge(openContext.world, rules(PathInformationSource.PLAYER_KNOWLEDGE))
            blockedContext.enableKnowledge(blockedContext.world, rules(PathInformationSource.PLAYER_KNOWLEDGE))

            openContext.submit(MoveAction("m-open", "p1", "u1", target, openContext.world))
            blockedContext.submit(MoveAction("m-blocked", "p1", "u1", target, blockedContext.world))

            assertEquals(straight, openContext.observe().units.single().pathPoints)
            assertEquals(
                straight,
                blockedContext.observe().units.single().pathPoints,
                "PLAYER_KNOWLEDGE with empty history must not read true blocking; truth is (2,2) blocked",
            )
        } finally {
            openContext.close()
            blockedContext.close()
        }
    }

    /**
     * KR01: same true center (2,2) blocked. A non-enabled context keeps the legacy no-fog route,
     * while MEMORY + ORIGINAL_COMPATIBILITY with empty history also stays on the legacy adapter.
     * Both must produce the same literal upper detour.
     */
    @Test
    fun kr01_original_compatibility_and_not_enabled_keep_no_fog_truth_route() {
        val notEnabled = open(map(2 to 2))
        val legacyMode = open(map(2 to 2))
        try {
            legacyMode.enableKnowledge(legacyMode.world, rules(PathInformationSource.ORIGINAL_COMPATIBILITY))

            notEnabled.submit(MoveAction("m-plain", "p1", "u1", target, notEnabled.world))
            legacyMode.submit(MoveAction("m-legacy", "p1", "u1", target, legacyMode.world))

            assertEquals(upper, notEnabled.observe().units.single().pathPoints)
            assertEquals(
                upper,
                legacyMode.observe().units.single().pathPoints,
                "ORIGINAL_COMPATIBILITY must keep the legacy no-fog adapter, not player knowledge",
            )
        } finally {
            notEnabled.close()
            legacyMode.close()
        }
    }

    /**
     * KR03: only the owning player may move the unit. p2 (not owner) is rejected with not-owner;
     * p1's own Move uses p1's empty history (straight) even though p2 has learned that the center
     * is blocked, i.e. p2's projection is never synthesized into p1's planning route; after p1
     * learns the center itself, a new p1 Move detours up. No shared-control field exists here.
     */
    @Test
    fun kr03_owner_snapshot_routes_move_and_non_owner_is_rejected() {
        val context = open(map())
        try {
            val world = context.world
            context.enableKnowledge(world, rules(PathInformationSource.PLAYER_KNOWLEDGE))

            // p2 (not the owner) learns that the center is blocked; this must stay p2-local.
            context.acceptVisibleTerrain(world, "p2", listOf(VisibleTerrainCell(2, 2, true)))

            val rejected = context.submit(MoveAction("m-p2", "p2", "u1", target, world))
            assertIs<com.rustedwarfare.reforge.core.sim.ActionResult.Rejected>(rejected)
            assertEquals("not-owner", rejected.error.code)

            // p1 has an empty history: p2's blocked cell must not leak into p1's route.
            context.submit(MoveAction("m-p1", "p1", "u1", target, world))
            assertEquals(
                straight,
                context.observe().units.single().pathPoints,
                "p2's own knowledge must not be synthesized into the owner's planning snapshot",
            )

            context.acceptVisibleTerrain(world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            context.submit(MoveAction("m-p1b", "p1", "u1", target, world))
            assertEquals(
                upper,
                context.observe().units.single().pathPoints,
                "after the owner learned (2,2) blocked, the owner snapshot must route around it",
            )
        } finally {
            context.close()
        }
    }

    /**
     * KR04: MEMORY keeps the last known block after an empty projection; HARDCORE_FORGET drops it.
     */
    @Test
    fun kr04_memory_retains_and_hardcore_forgets_known_block() {
        val memory = open(map())
        val hardcore = open(map())
        try {
            val mw = memory.world
            memory.enableKnowledge(mw, rules(PathInformationSource.PLAYER_KNOWLEDGE, TerrainRetention.MEMORY))
            memory.acceptVisibleTerrain(mw, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            memory.acceptVisibleTerrain(mw, "p1", emptyList())
            memory.submit(MoveAction("m-mem", "p1", "u1", target, mw))
            assertEquals(upper, memory.observe().units.single().pathPoints, "MEMORY keeps the last known blocked cell")

            val hw = hardcore.world
            hardcore.enableKnowledge(hw, rules(PathInformationSource.PLAYER_KNOWLEDGE, TerrainRetention.HARDCORE_FORGET))
            hardcore.acceptVisibleTerrain(hw, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            hardcore.acceptVisibleTerrain(hw, "p1", emptyList())
            hardcore.submit(MoveAction("m-hard", "p1", "u1", target, hw))
            assertEquals(straight, hardcore.observe().units.single().pathPoints, "HARDCORE_FORGET clears knowledge out of view")
        } finally {
            memory.close()
            hardcore.close()
        }
    }

    /**
     * KR05: a known-blocked goal is accepted as an action but planned as unreachable, which cancels
     * the old movement and clears the previous target/path.
     */
    @Test
    fun kr05_known_blocked_goal_is_accepted_but_cancels_movement() {
        val reachable = open(map())
        try {
            val world = reachable.world
            reachable.enableKnowledge(world, rules(PathInformationSource.PLAYER_KNOWLEDGE))

            reachable.submit(MoveAction("m-first", "p1", "u1", target, world))
            assertEquals(straight, reachable.observe().units.single().pathPoints)

            // Goal cell (3,2) is learned blocked; an unknown goal is still planned, a known one is not.
            reachable.acceptVisibleTerrain(world, "p1", listOf(VisibleTerrainCell(3, 2, true)))
            val result = reachable.submit(MoveAction("m-goal", "p1", "u1", target, world))
            assertIs<com.rustedwarfare.reforge.core.sim.ActionResult.Accepted>(result)

            val unit = reachable.observe().units.single()
            assertNull(unit.moveTarget)
            assertTrue(unit.pathPoints.isEmpty())
            assertEquals(
                listOf(
                    com.rustedwarfare.reforge.core.sim.Event("movement-cancelled", "u1"),
                    com.rustedwarfare.reforge.core.sim.Event("path-unreachable", "u1"),
                ),
                result.events,
            )
        } finally {
            reachable.close()
        }
    }

    /**
     * KR06: an unknown-but-truly-blocked goal plans as reachable (identical to the truth-open case),
     * yet real movement still uses the true map: fixed finite steps must never push the unit through
     * the blocked cell, and the real collision must be reported as movement-blocked.
     *
     * The geometric check recomputes the circle-vs-rectangle relation independently from each
     * observed position and the scenario radius; it never reads an expected value back.
     *
     * Step budget note: the unit spends its first steps turning in place (speed stays 0), so the
     * budget below is deliberately generous; it is a fixed literal, not tuned to a measured result.
     */
    @Test
    fun kr06_unknown_blocked_goal_is_planned_but_real_collision_still_stops() {
        val unknownTruthBlocked = open(map(3 to 2))
        val truthOpen = open(map())
        try {
            unknownTruthBlocked.enableKnowledge(unknownTruthBlocked.world, rules(PathInformationSource.PLAYER_KNOWLEDGE))
            truthOpen.enableKnowledge(truthOpen.world, rules(PathInformationSource.PLAYER_KNOWLEDGE))

            unknownTruthBlocked.submit(MoveAction("m-u", "p1", "u1", target, unknownTruthBlocked.world))
            truthOpen.submit(MoveAction("m-o", "p1", "u1", target, truthOpen.world))

            assertEquals(straight, truthOpen.observe().units.single().pathPoints)
            assertEquals(
                straight,
                unknownTruthBlocked.observe().units.single().pathPoints,
                "unknown goal is planned as reachable even though truth has (3,2) blocked",
            )

            // (3,2) spans x 60..80, y 40..60; radius is 1.0 from the definition built above.
            val radius = 1.0
            var sawBlocked = false
            var worstDistance = Double.MAX_VALUE
            for (stepIndex in 0L until 200L) {
                val observation = unknownTruthBlocked.advance(Step(stepIndex, 1.0, "sim-tick"))
                val unit = observation.units.single()

                // Independent geometric invariant, checked every step: the unit circle must never
                // intersect the truly blocked rectangle.
                val closestX = unit.position.x.coerceIn(60.0, 80.0)
                val closestY = unit.position.y.coerceIn(40.0, 60.0)
                val distance = hypot(unit.position.x - closestX, unit.position.y - closestY)
                worstDistance = minOf(worstDistance, distance)
                assertTrue(
                    distance >= radius,
                    "must never intersect truly blocked (3,2) at step $stepIndex: distance=$distance at ${unit.position}",
                )

                if (unit.movementState == MovementState.BLOCKED) {
                    assertTrue(
                        observation.events.any { it.kind == "movement-blocked" && it.unitId == "u1" },
                        "reaching truly blocked (3,2) must emit movement-blocked",
                    )
                    assertEquals(0.0, unit.speed, "a blocked unit must lose its speed")
                    assertEquals(0.0, unit.angularVelocity, "a blocked unit must lose its angular velocity")
                    sawBlocked = true
                    break
                }
            }
            assertTrue(sawBlocked, "advancing into truly blocked (3,2) must reach the BLOCKED state (worstDistance=$worstDistance)")
        } finally {
            unknownTruthBlocked.close()
            truthOpen.close()
        }
    }

    /**
     * KR07: enable -> close -> reopen. The reopened world is not enabled, so Move uses no-fog truth
     * again; the old world stays stale and the old snapshot remains readable.
     */
    @Test
    fun kr07_reopen_starts_disabled_and_old_world_stays_stale() {
        val context = open(map(2 to 2))
        val source = scenario(map(2 to 2))
        try {
            val oldWorld = context.world
            context.enableKnowledge(oldWorld, rules(PathInformationSource.PLAYER_KNOWLEDGE))
            val oldSnapshot = context.knowledgeSnapshot(oldWorld, "p1")

            context.close()
            assertIs<InitResult.Accepted>(context.reopen(source))
            val newWorld = context.world
            assertNotSame(oldWorld, newWorld)

            context.submit(MoveAction("m-new", "p1", "u1", target, newWorld))
            assertEquals(
                upper,
                context.observe().units.single().pathPoints,
                "reopened world is not knowledge-enabled, so no-fog truth blocking applies",
            )

            // The pre-existing submit contract reports a stale world as Rejected, not as a thrown
            // exception; that behaviour is unchanged by this slice.
            val stale = context.submit(MoveAction("m-old", "p1", "u1", target, oldWorld))
            assertIs<com.rustedwarfare.reforge.core.sim.ActionResult.Rejected>(stale)
            assertEquals("stale-world", stale.error.code)

            assertEquals("p1", oldSnapshot.playerId)
            assertEquals(0L, oldSnapshot.revision)
        } finally {
            context.close()
        }
    }

    /**
     * KR08: a successful enable publishes both stores and rules; a repeated enable is rejected and
     * must not republish or overwrite the first rules.
     */
    @Test
    fun kr08_repeated_enable_is_rejected_and_does_not_overwrite_rules() {
        val context = open(map(2 to 2))
        try {
            val world = context.world
            context.enableKnowledge(world, rules(PathInformationSource.PLAYER_KNOWLEDGE, TerrainRetention.MEMORY))

            val repeated = assertFailsWith<KnowledgeException> {
                context.enableKnowledge(world, rules(PathInformationSource.ORIGINAL_COMPATIBILITY, TerrainRetention.HARDCORE_FORGET))
            }
            assertEquals("already-enabled", repeated.code)

            // First rules must still govern: PLAYER_KNOWLEDGE with empty history ignores truth blocking.
            context.submit(MoveAction("m-keep", "p1", "u1", target, world))
            assertEquals(
                straight,
                context.observe().units.single().pathPoints,
                "rejected re-enable must not overwrite the originally committed rules",
            )
        } finally {
            context.close()
        }
    }
}
