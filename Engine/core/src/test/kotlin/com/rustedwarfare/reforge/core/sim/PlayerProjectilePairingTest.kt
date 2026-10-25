package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.*

class PlayerProjectilePairingTest {
    @Test
    fun one_public_child_can_leave_and_reenter_while_its_sibling_stays_visible_without_rebinding_history() {
        val base = CombatTestContent.scenario(sight = 2,
            rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val original = base.unitDefinitions.getValue("c_tank")
        val observer = UnitDefinition("observer", original.className, 8.0, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale,
            original.movement.copy(moveSpeed = 60.0, moveAccelerationSpeed = 60.0,
                moveDecelerationSpeed = 60.0, maxTurnSpeed = 360.0, turnAcceleration = 0.0), original.retainedUnconsumed, 2)
        fun source(id: String, speed: Double) = UnitDefinition(id, original.className, original.radius,
            original.displayRadius, original.totalFrames, original.imagePath, original.imageScale,
            original.movement, original.retainedUnconsumed, 15, checkNotNull(original.combat).let {
                it.copy(attackRange = 200.0, projectile = it.projectile.copy(speed = speed)) })
        val contexts = listOf(false, true).map { swapped ->
            val near = if (swapped) "y" else "x"
            val far = if (swapped) "x" else "y"
            val scene = base.copy(unitDefinitions = base.unitDefinitions + mapOf("observer" to observer,
                "near" to source("near", 5.0), "far" to source("far", 7.0)),
                players = base.players + Player("p3", PlayerControl.NONE, "three"),
                units = listOf(UnitSpawn("observer", "p1", "observer", Position(240.0, 230.0), 270.0),
                    UnitSpawn("target", "p3", "c_tank", Position(240.0, 210.0), 0.0),
                    UnitSpawn(near, "p2", "near", Position(110.0, 210.0), 90.0),
                    UnitSpawn(far, "p2", "far", Position(82.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("observer" to 10L, "target" to 11L, near to 8L, far to 9L), 12L))
            assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        }
        try {
            contexts.forEach { it.observePlayer(it.world, "p1") }
            var parent: String? = null
            var nearChild: String? = null
            var farChild: String? = null
            var hiddenFrame: PlayerObservation? = null
            for (step in 0L..18L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(signature(views[0]), signature(views[1]), "Partial-members visibility pairing step=$step")
                if (step == 13L) {
                    parent = views[0].projectiles.single().token
                    assertEquals(2, views[0].projectiles.single().multiplicity)
                    contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(
                        MoveAction("partial-left", "p1", "observer", Position(180.0, 230.0), it.world))) }
                }
                if (step == 14L) {
                    nearChild = views[0].projectiles.single { it.position == Position(205.0, 210.0) }.token
                    farChild = views[0].projectiles.single { it.position == Position(207.0, 210.0) }.token
                    assertTrue(views[0].projectiles.all { it.parentToken == parent && it.multiplicity == 1 })
                }
                if (step == 16L) {
                    assertTrue(views[0].projectiles.any { it.token == nearChild })
                    assertTrue(views[0].projectiles.none { it.token == farChild })
                    hiddenFrame = views[0]
                    contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(
                        MoveAction("partial-return", "p1", "observer", Position(240.0, 230.0), it.world))) }
                }
                if (step == 18L) {
                    val returned = views[0].projectiles.single { it.token == farChild }
                    assertEquals(parent, returned.parentToken)
                    assertEquals(1, returned.multiplicity)
                    assertTrue(views[0].projectiles.any { it.token == nearChild })
                    assertTrue(checkNotNull(hiddenFrame).projectiles.none { it.token == farChild })
                }
            }
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun an_undistinguished_two_member_class_keeps_its_token_when_the_viewer_leaves_and_returns() {
        val base = CombatTestContent.scenario(sight = 2,
            rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val original = base.unitDefinitions.getValue("c_tank")
        val mobile = UnitDefinition("mobile", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale,
            original.movement.copy(moveSpeed = 100.0, moveAccelerationSpeed = 100.0,
                moveDecelerationSpeed = 100.0, maxTurnSpeed = 360.0, turnAcceleration = 0.0),
            original.retainedUnconsumed, 2, original.combat)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + ("mobile" to mobile), units = listOf(
            UnitSpawn("observer", "p1", "mobile", Position(220.0, 210.0), 270.0),
            UnitSpawn("x", "p2", "c_tank", Position(110.0, 210.0), 90.0),
            UnitSpawn("y", "p2", "c_tank", Position(110.0, 210.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("observer" to 10L, "x" to 8L, "y" to 9L), 11L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var view = context.observePlayer(context.world, "p1")
            for (step in 0L..9L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                view = context.observePlayer(context.world, "p1")
            }
            val initialClass = view.projectiles.single { it.multiplicity == 2 }
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("leave-view", "p1", "observer", Position(220.0, 110.0), context.world)))
            var step = 10L
            var hidden = false
            while (step <= 14L && !hidden) {
                context.advance(Step(step++, 1.0, "sim-tick"))
                view = context.observePlayer(context.world, "p1")
                hidden = view.projectiles.none { it.token == initialClass.token } &&
                    view.projectileEvents.none { it.token == initialClass.token }
            }
            assertTrue(hidden, "The real current-vision class must disappear before testing reentry")
            val hiddenFrame = view
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("return-view", "p1", "observer", Position(220.0, 210.0), context.world)))
            var returned = false
            while (step <= 22L && !returned) {
                context.advance(Step(step++, 1.0, "sim-tick"))
                view = context.observePlayer(context.world, "p1")
                returned = view.projectiles.any { it.token == initialClass.token && it.multiplicity == 2 } ||
                    view.projectileEvents.any { it.token == initialClass.token && it.occurrences == 2 }
            }
            assertTrue(returned, "A still-indistinguishable class must retain its public history on reentry")
            assertTrue(hiddenFrame.projectiles.none { it.token == initialClass.token })
            assertEquals(2, initialClass.multiplicity)
        } finally { context.close() }
    }

    private fun variant(original: UnitDefinition, id: String, speed: Double, life: Double) =
        UnitDefinition(id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, checkNotNull(original.combat).let {
                it.copy(projectile = it.projectile.copy(speed = speed, life = life)) })

    @Test
    fun a_projectile_born_and_expired_in_one_window_keeps_its_public_event_identity() {
        val base = CombatTestContent.scenario()
        val tank = variant(base.unitDefinitions.getValue("c_tank"), "c_tank", 5.0, 1.0)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
                if (it.unitId == "b") it.copy(position = Position(220.0, 210.0), heading = 90.0) else it
            }))).context
        try {
            context.advance(Step(0, 1.0, "sim-tick"))
            val view = context.observePlayer(context.world, "p1")
            assertTrue(view.projectiles.isEmpty())
            assertEquals(listOf("born", "expired"), view.projectileEvents.map { it.kind })
            assertEquals(1, view.projectileEvents.map { it.token }.distinct().size)
            assertEquals(listOf(Position(130.0, 210.0), Position(135.0, 210.0)), view.projectileEvents.map { it.position })
            assertTrue(view.projectileEvents.all { it.occurrences == 1 && it.sourceUnitId == "a" && it.targetUnitId == "b" })
            assertSame(view, context.observePlayer(context.world, "p1"))
        } finally { context.close() }
    }

    @Test
    fun identical_event_only_objects_from_hidden_sources_are_one_class_with_aggregated_occurrences() {
        val base = CombatTestContent.scenario(sight = 2,
            rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val original = base.unitDefinitions.getValue("c_tank")
        val contexts = listOf(false, true).map { swapped ->
            val near = if (swapped) "y" else "x"
            val far = if (swapped) "x" else "y"
            val scene = base.copy(unitDefinitions = base.unitDefinitions + mapOf(
                "near" to variant(original, "near", 60.0, 1.0), "far" to variant(original, "far", 70.0, 1.0)),
                units = listOf(UnitSpawn("observer", "p1", "c_tank", Position(220.0, 210.0), 0.0),
                    UnitSpawn(near, "p2", "near", Position(110.0, 210.0), 90.0),
                    UnitSpawn(far, "p2", "far", Position(100.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("observer" to 10L, near to 8L, far to 9L), 11L))
            assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        }
        try {
            contexts.forEach { it.observePlayer(it.world, "p1"); it.advance(Step(0, 1.0, "sim-tick")) }
            contexts[1].observePlayer(contexts[1].world, "p2")
            val views = contexts.map { it.observePlayer(it.world, "p1") }
            assertEquals(signature(views[0]), signature(views[1]))
            assertTrue(views[0].projectiles.isEmpty())
            assertEquals(1, views[0].projectileEvents.size, "Same visible expiry must be one aggregated event class")
            val event = views[0].projectileEvents.single()
            assertEquals("expired", event.kind)
            assertEquals(2, event.occurrences)
            assertEquals(Position(190.0, 210.0), event.position)
            assertNull(event.sourceUnitId)
            assertEquals("observer", event.targetUnitId)
        } finally { contexts.forEach { it.close() } }
    }

    private fun converging(swapped: Boolean, newcomer: Boolean, farX: Double = 100.0, farSpeed: Double = 6.0): Scenario {
        val base = CombatTestContent.scenario(sight = 2,
            rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val original = base.unitDefinitions.getValue("c_tank")
        val fast = UnitDefinition("fast", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, 2, checkNotNull(original.combat).let {
                it.copy(projectile = it.projectile.copy(speed = farSpeed)) })
        val near = if (swapped) "b-hidden" else "a-hidden"
        val far = if (swapped) "a-hidden" else "b-hidden"
        val units = listOf(UnitSpawn("observer", "p1", "c_tank", Position(220.0, 210.0), 270.0),
            UnitSpawn(near, "p2", "c_tank", Position(110.0, 210.0), 90.0),
            UnitSpawn(far, "p2", "fast", Position(farX, 210.0), 90.0)) +
            if (newcomer) listOf(UnitSpawn("z-hidden", "p2", "fast", Position(99.0, 210.0), 90.0)) else emptyList()
        val ids = mapOf("observer" to 10L, near to 8L, far to 9L) + if (newcomer) mapOf("z-hidden" to 11L) else emptyMap()
        return base.copy(unitDefinitions = base.unitDefinitions + ("fast" to fast), units = units,
            combatContract = CombatContract(12345, -1, ids, if (newcomer) 12L else 11L))
    }

    @Test
    fun previously_distinct_public_aliases_remain_distinct_when_their_visible_impacts_overlap() {
        val contexts = listOf(false, true).map {
            assertIs<InitResult.Accepted>(SimulationContext.initialize(converging(it, false, 102.0, 5.5))).context }
        try {
            contexts.forEach { it.observePlayer(it.world, "p1") }
            val incomingTokens = mutableSetOf<String>()
            for (step in 0L..17L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(signature(views[0]), signature(views[1]), "Known overlap pairing step=$step")
                incomingTokens += views[0].projectiles.filter { it.heading == 90.0 }.map { it.token }
                if (step == 17L) {
                    val impacts = views[0].projectileEvents.filter { it.kind == "impact" && it.targetUnitId == "observer" }
                    assertEquals(2, impacts.size)
                    assertEquals(incomingTokens, impacts.map { it.token }.toSet())
                    assertTrue(impacts.all { it.position == Position(220.0, 210.0) && it.occurrences == 1 && it.sourceUnitId == null })
                }
            }
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun unseen_extra_births_and_another_players_hidden_stop_do_not_change_public_tokens_or_revision() {
        val base = converging(false, false)
        val extra = base.copy(players = base.players + listOf(Player("p3", PlayerControl.LOCAL, "three"), Player("p4", PlayerControl.NONE, "four")),
            units = base.units + listOf(UnitSpawn("x-unseen", "p3", "c_tank", Position(500.0, 100.0), 90.0),
                UnitSpawn("y-unseen", "p4", "c_tank", Position(550.0, 100.0), 270.0)),
            combatContract = checkNotNull(base.combatContract).let { it.copy(unitObjectIds = it.unitObjectIds + mapOf("x-unseen" to 11L, "y-unseen" to 12L), nextObjectId = 13L) })
        val contexts = listOf(base, extra).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(it)).context }
        try {
            contexts.forEach { it.observePlayer(it.world, "p1") }
            for (step in 0L..20L) {
                val host = contexts.map { it.advance(Step(step, 1.0, "sim-tick")) }
                if (step == 0L) assertNotEquals(host[0].projectiles.first().objectId, host[1].projectiles.first().objectId)
                contexts[1].observePlayer(contexts[1].world, "p3")
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(signature(views[0]), signature(views[1]), "Extra invisible effects step=$step")
                if (step == 5L) {
                    assertIs<ActionResult.Accepted>(contexts[1].submit(StopAction("hidden-stop", "p3", "x-unseen", contexts[1].world)))
                    assertSame(views[1], contexts[1].observePlayer(contexts[1].world, "p1"))
                    assertEquals(signature(views[0]), signature(contexts[1].observePlayer(contexts[1].world, "p1")))
                }
            }
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun a_known_projectile_keeps_its_alias_after_crossing_an_unseen_gap_between_owned_vision_sources() {
        val base = CombatTestContent.scenario(sight = 2,
            rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val original = base.unitDefinitions.getValue("c_tank")
        val ally = UnitDefinition("ally", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed, 2)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + ("ally" to ally), units = listOf(
            UnitSpawn("observer", "p1", "c_tank", Position(220.0, 210.0), 270.0),
            UnitSpawn("target", "p2", "c_tank", Position(110.0, 210.0), 90.0),
            UnitSpawn("ally", "p1", "ally", Position(80.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("observer" to 8L, "target" to 9L, "ally" to 10L), 11L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var token: String? = null
            for (step in 0L..16L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                val view = context.observePlayer(context.world, "p1")
                val known = view.projectiles.filter { it.heading == 270.0 }
                if (step == 0L) token = known.single().token
                if (step in 4L..15L) assertTrue(known.isEmpty(), "Actual invisible interval step=$step")
                if (step == 16L) {
                    assertEquals(token, known.single().token)
                    assertEquals(Position(115.0, 210.0), known.single().position)
                }
            }
        } finally { context.close() }
    }

    @Test
    fun same_value_first_visibility_is_one_class_then_splits_by_public_values_without_impersonating_newcomers() {
        for (newcomer in listOf(false, true)) {
            val contexts = listOf(false, true).map {
                assertIs<InitResult.Accepted>(SimulationContext.initialize(converging(it, newcomer))).context }
            try {
                contexts.forEach { it.observePlayer(it.world, "p1") }
                var parent: String? = null
                for (step in 0L..11L) {
                    contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                    contexts[1].observePlayer(contexts[1].world, "p2")
                    val views = contexts.map { it.observePlayer(it.world, "p1") }
                    assertEquals(signature(views[0]), signature(views[1]), "Full same-class history step=$step newcomer=$newcomer")
                    val incoming = views[0].projectiles.filter { it.heading == 90.0 }
                    if (step < 9) assertTrue(incoming.isEmpty())
                    if (step == 9L) {
                        assertEquals(1, incoming.size)
                        assertEquals(Position(180.0, 210.0), incoming.single().position)
                        assertEquals(2, incoming.single().multiplicity)
                        parent = incoming.single().token
                    }
                    if (step == 10L) {
                        val children = incoming.filter { it.parentToken == parent }
                        assertEquals(setOf(Position(185.0, 210.0), Position(186.0, 210.0)), children.map { it.position }.toSet())
                        assertTrue(children.all { it.multiplicity == 1 && it.token != parent })
                        if (newcomer) {
                            val samePosition = incoming.filter { it.position == Position(185.0, 210.0) }
                            assertEquals(2, samePosition.size, "A new identical object must not impersonate or merge with known history")
                            assertTrue(samePosition.all { it.multiplicity == 1 })
                            assertEquals(1, samePosition.count { it.parentToken == null })
                        }
                    }
                }
            } finally { contexts.forEach { it.close() } }
        }
    }

    private fun signature(view: PlayerObservation): List<Any?> = listOf(view.sampleId, view.worldId,
        view.scenarioId, view.playerId, view.stepIndex, view.simulationTime, view.units, view.projectiles,
        view.events, view.actionResults, view.projectileEvents, view.vision?.worldId, view.vision?.playerId, view.vision?.epoch,
        view.vision?.basicCells, view.vision?.effectiveCells, view.knowledge?.worldId, view.knowledge?.playerId,
        view.knowledge?.width, view.knowledge?.height, view.knowledge?.revision, view.knowledge?.cells)

    private fun twoRows(swapped: Boolean): Scenario {
        val base = CombatTestContent.scenario(sight = 2,
            rules = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val upper = if (swapped) "y" else "x"
        val lower = if (swapped) "x" else "y"
        return base.copy(units = listOf(UnitSpawn("a", "p1", "c_tank", Position(220.0, 110.0), 0.0),
            UnitSpawn("c", "p1", "c_tank", Position(220.0, 310.0), 0.0),
            UnitSpawn(upper, "p2", "c_tank", Position(110.0, 110.0), 90.0),
            UnitSpawn(lower, "p2", "c_tank", Position(110.0, 310.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 10L, "c" to 11L, upper to 8L, lower to 9L), 12L))
    }

    @Test
    fun hidden_creation_order_does_not_reorder_visible_damage_or_change_complete_player_history() {
        val contexts = listOf(false, true).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(twoRows(it))).context }
        try {
            assertEquals(signature(contexts[0].observePlayer(contexts[0].world, "p1")),
                signature(contexts[1].observePlayer(contexts[1].world, "p1")))
            for (step in 0L..17L) {
                val host = contexts.map { it.advance(Step(step, 1.0, "sim-tick")) }
                if (step == 0L) {
                    assertEquals(listOf("a", "c"), host[0].projectiles.map { it.targetUnitId })
                    assertEquals(listOf("c", "a"), host[1].projectiles.map { it.targetUnitId })
                }
                // Another player's observation order must not allocate or advance p1 public aliases.
                contexts[1].observePlayer(contexts[1].world, "p2")
                val views = contexts.map { it.observePlayer(it.world, "p1") }
                assertEquals(signature(views[0]), signature(views[1]), "Complete DTO/token/event pairing at step $step")
                assertSame(views[0], contexts[0].observePlayer(contexts[0].world, "p1"))
                if (step == 17L) {
                    assertEquals(listOf("a", "c"), views[0].events.filter { it.kind == "unit-damaged" }.map { it.unitId })
                    assertTrue(views[0].units.all { it.health == 185.0 })
                }
            }
        } finally { contexts.forEach { it.close() } }
    }
}
