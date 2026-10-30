package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BuildingMaintenanceTest {
    @Test fun a_reclaim_card_held_before_patrol_cannot_replace_the_new_mode_on_release() {
        SandboxSession(reclaimCardScene()).use { session ->
            session.select(Position(120.0, 170.0))
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false })
            var choices = 0
            val card = ReclaimButtonInput({ session }, { true }, {
                choices++; assertTrue(input.beginReclaim())
            }, modeRevision = { input.modeRevision })
            val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 54f); addListener(card) }
            fun fire(down: Boolean) = actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
                type = if (down) com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown else com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp
                button = com.badlogic.gdx.Input.Buttons.LEFT; pointer = 0; stageX = 10f; stageY = 10f; touchFocus = false
            })
            fire(true)
            assertTrue(input.keyDown(com.badlogic.gdx.Input.Keys.P))
            assertTrue(input.patrolMode)
            fire(false)
            assertEquals(0, choices, "The old reclaim press cannot replace the later patrol mode")
            assertTrue(input.patrolMode)
            assertFalse(input.reclaimMode)
            assertTrue(session.observation.units.all { it.tasks.isEmpty() })
            fire(true); fire(false)
            assertEquals(1, choices, "A fresh reclaim click can change the mode")
            assertFalse(input.patrolMode)
            assertTrue(input.reclaimMode)
        }
    }

    @Test fun a_reclaim_card_dragged_out_and_back_never_revives_the_old_press() {
        SandboxSession(reclaimCardScene()).use { session ->
            session.select(Position(120.0, 170.0))
            var choices = 0
            val card = ReclaimButtonInput({ session }, { true }, { choices++ })
            val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 54f); addListener(card) }
            fun fire(kind: com.badlogic.gdx.scenes.scene2d.InputEvent.Type, x: Float) = actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
                type = kind; button = com.badlogic.gdx.Input.Buttons.LEFT; pointer = 0
                stageX = x; stageY = 10f; touchFocus = false
            })
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown, 10f)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDragged, 100f)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDragged, 10f)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp, 10f)
            assertEquals(0, choices)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown, 10f)
            fire(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp, 10f)
            assertEquals(1, choices)
        }
    }

    @Test fun maintenance_registration_is_complete_frozen_and_checked_before_opening_the_world() {
        val base = ControlledScenario.load(inputRoot())
        val prices = checkNotNull(base.maintenanceContract).basePrices.toMutableMap()
        val frozen = MaintenanceContract(prices)
        prices["landFactory"] = 1L
        assertEquals(700L, frozen.basePrices["landFactory"])
        assertFailsWith<UnsupportedOperationException> { (frozen.basePrices as MutableMap)["landFactory"] = 2L }
        val good = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(maintenanceContract = frozen)))
        good.context.close()
        for (bad in listOf(frozen.basePrices - "landFactory", frozen.basePrices + ("landFactory" to 701L),
            frozen.basePrices + ("unknown" to 700L), frozen.basePrices + ("builder" to 500L))) {
            assertEquals("unsupported-maintenance", assertIs<InitResult.Rejected>(SimulationContext.initialize(
                base.copy(maintenanceContract = MaintenanceContract(bad)))).error.code)
        }
        assertIs<InitResult.Rejected>(SimulationContext.initialize(base.copy(economyContract = null)))
        val legacyScene = base.copy(maintenanceContract = null, units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
        val legacy = assertIs<InitResult.Accepted>(SimulationContext.initialize(legacyScene)).context
        try {
            assertEquals("cannot-maintain", assertIs<ActionResult.Rejected>(legacy.submit(ReclaimBuildingAction(
                "disabled", "p1", "worker", "factory", legacy.world))).error.code)
            assertEquals("cannot-maintain", assertIs<ActionResult.Rejected>(legacy.submit(RepairBuildingAction(
                "disabled-repair", "p1", "worker", "factory", legacy.world))).error.code)
        } finally { legacy.close() }
    }

    @Test fun no_income_worlds_preserve_the_float_refund_above_the_income_cap() {
        val base = ControlledScenario.load(inputRoot())
        val definitions = base.unitDefinitions.mapValues { (_, d) -> com.rustedwarfare.reforge.core.content.UnitDefinition(
            d.id, d.className, d.radius, d.displayRadius, d.totalFrames, d.imagePath, d.imageScale, d.movement,
            d.retainedUnconsumed, d.fogOfWarSightRange, d.combat, d.durability, d.building, d.production, d.build,
            d.directHitTargetKind, d.pointDefense, null, null, d.builder, d.construction, d.deathExplosion) }
        assertTrue(definitions.values.all { it.income == null })
        for ((money, expected) in listOf(1750L to 2310.0000083446503, 999999990L to 1000000550.0000083)) {
            val scene = base.copy(unitDefinitions = definitions,
                units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
                    UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
                economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L)),
                combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
            val initialized = SimulationContext.initialize(scene)
            val c = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("reclaim", "p1", "worker", "factory", c.world)))
                var terminal = false
                for (step in 0L..1499L) {
                    c.advance(Step(step, 1.0, "sim-tick"))
                    if (c.observe().units.none { it.unitId == "factory" }) {
                        terminal = true
                        assertEquals(expected, c.observePlayer(c.world, "p1").credits)
                        break
                    }
                    assertEquals(money.toDouble(), c.observePlayer(c.world, "p1").credits)
                }
                assertTrue(terminal)
            } finally { c.close() }
        }
    }
    @Test fun reclaimed_host_diagnostics_are_immutable_opt_in_and_never_enter_the_player_sink() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val before = context.observe()
            assertEquals(emptyList(), before.reclaimedUnitIds)
            assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("reclaim", "p1", "worker", "factory", context.world)))
            repeat(1500) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val after = context.observe()
            assertEquals(listOf("factory"), after.reclaimedUnitIds)
            assertFailsWith<UnsupportedOperationException> { (after.reclaimedUnitIds as MutableList<String>).add("forged") }
            assertEquals(emptyList(), before.reclaimedUnitIds)
            assertEquals(listOf("factory"), after.fields()["reclaimedUnitIds"])
            assertFalse("reclaimedUnitIds" in context.observePlayer(context.world, "p1").fields())
        } finally { context.close() }
        val legacy = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene.copy(maintenanceContract = null))).context
        try {
            assertNull(legacy.observe().reclaimedUnitIds)
            assertFalse("reclaimedUnitIds" in legacy.observe().fields())
        } finally { legacy.close() }
    }

    @Test fun reclaim_card_and_map_clicks_keep_their_session_worker_selection_and_target_owners() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L, "factory" to 3L), 4L))
        val sessions = mutableListOf<SandboxSession>()
        fun open() = SandboxSession(scene).also { it.select(Position(120.0, 170.0)); sessions += it }
        var current = open()
        var active = true
        val map = MapProjection(600.0, 400.0)
        val input = SandboxInput({ current }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
            { active }, { false }, { false })
        val card = ReclaimButtonInput({ current }, { active }, { input.beginReclaim() })
        val actor = com.badlogic.gdx.scenes.scene2d.Actor().apply { setBounds(0f, 0f, 84f, 54f); addListener(card) }
        fun fire(down: Boolean, x: Float = 10f) = actor.fire(com.badlogic.gdx.scenes.scene2d.InputEvent().apply {
            type = if (down) com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown else com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp
            button = com.badlogic.gdx.Input.Buttons.LEFT; pointer = 0; stageX = x; stageY = 10f; touchFocus = false
        })
        fun noTask() = assertTrue(current.observation.units.filter { it.typeId == "builder" }.all { it.tasks.isEmpty() })
        val x = map.x(190.0).toInt(); val y = map.y(170.0).toInt()
        try {
            fire(true); fire(false, 100f); assertFalse(input.reclaimMode); noTask()
            fire(true); current.select(Position(120.0, 210.0)); current.select(Position(120.0, 170.0)); fire(false)
            assertFalse(input.reclaimMode); noTask()
            fire(true); active = false; card.cancel(); input.cancel(); active = true; fire(false)
            assertFalse(input.reclaimMode); noTask()
            fire(true); fire(false); assertTrue(input.reclaimMode)
            assertTrue(input.keyDown(com.badlogic.gdx.Input.Keys.ESCAPE)); assertFalse(input.reclaimMode); noTask()
            assertTrue(input.beginReclaim())
            assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.RIGHT))
            assertFalse(input.reclaimMode); noTask()
            assertTrue(input.beginReclaim()); assertTrue(input.beginConstruction("landFactory")); assertFalse(input.reclaimMode)
            assertTrue(input.beginReclaim()); assertNull(input.constructionTypeId)
            assertTrue(input.beginPatrol()); assertFalse(input.reclaimMode)
            assertTrue(input.beginReclaim()); assertFalse(input.patrolMode)
            assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            current.select(Position(120.0, 210.0)); current.select(Position(120.0, 170.0))
            input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
            assertFalse(input.reclaimMode); noTask()
            assertTrue(input.beginReclaim())
            assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            assertTrue(input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            assertFalse(input.reclaimMode)
            assertEquals(UnitTaskKind.RECLAIM, current.observation.units.single { it.unitId == "a" }.tasks.single().kind)
            assertEquals("a", current.selectedId)
            current.select(Position(120.0, 210.0))
            assertTrue(input.beginReclaim())
            assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            repeat(1500) { current.tick() }
            assertTrue(current.observation.units.none { it.unitId == "factory" })
            input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT)
            assertTrue(current.observation.units.single { it.unitId == "b" }.tasks.isEmpty())
            input.cancel()
            fire(true); current.close(); current = open(); fire(false)
            assertFalse(input.reclaimMode); noTask()
        } finally { sessions.forEach { it.close() } }
    }

    @Test fun the_session_uses_right_click_repair_and_reclaim_keeps_the_worker_selected_until_the_target_disappears() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
        val actions = mutableListOf<Map<String, Any?>>()
        SandboxSession(scene, ClientEvidence { kind, values -> if (kind == "action") actions += values }).use { session ->
            session.select(Position(190.0, 170.0)); session.saveGroup(4)
            session.select(Position(120.0, 170.0))
            assertTrue(session.canMaintain)
            assertIs<ActionResult.Accepted>(session.reclaim(Position(190.0, 170.0)))
            repeat(100) { session.tick() }
            session.stop()
            assertTrue(session.observation.units.single { it.unitId == "factory" }.health!! < 1200.0)
            assertIs<ActionResult.Accepted>(session.command(Position(190.0, 170.0)))
            assertEquals("RepairBuildingAction", actions.last()["type"])
            assertEquals("factory", actions.last()["targetUnitId"])
            repeat(1200) { session.tick() }
            assertEquals(1200.0, session.observation.units.single { it.unitId == "factory" }.health)
            assertIs<ActionResult.Accepted>(session.reclaim(Position(190.0, 170.0)))
            assertEquals("ReclaimBuildingAction", actions.last()["type"])
            assertEquals("factory", actions.last()["targetUnitId"])
            repeat(1500) { session.tick() }
            assertEquals("worker", session.selectedId)
            assertTrue(session.observation.units.none { it.unitId == "factory" })
            assertEquals(0, session.groupCount(4))
            assertEquals(2310.0000083446503, session.observation.credits)
            assertNull(session.reclaim(Position(190.0, 170.0)))
            assertEquals("worker", session.selectedId)
            assertEquals(2310.0000083446503, session.observation.credits)
        }
    }

    @Test fun a_second_builder_repairs_real_reclaim_damage_without_charging_or_finishing_an_unfinished_project_early() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 190.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L, "factory" to 3L), 4L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
            fun factory() = context.observe().units.single { it.unitId == "factory" }
            assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("damage", "p1", "a", "factory", context.world)))
            repeat(100) { tick() }
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-reclaim", "p1", "a", context.world)))
            val damage = factory().health!!
            assertTrue(damage in 1.0..<1200.0)
            assertEquals(1750.0, context.observePlayer(context.world, "p1").credits)
            assertIs<ActionResult.Accepted>(context.submit(RepairBuildingAction("repair", "p1", "b", "factory", context.world)))
            repeat(1200) { tick() }
            assertEquals(1200.0, factory().health)
            assertTrue(context.observe().units.single { it.unitId == "b" }.tasks.isEmpty())
            assertEquals(1750.0, context.observePlayer(context.world, "p1").credits)
            assertIs<ActionResult.Accepted>(context.submit(RepairBuildingAction("already-full", "p1", "b", "factory", context.world)))
            tick()
            assertTrue(context.observe().units.single { it.unitId == "b" }.tasks.isEmpty())
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("new-extractor", "p1", "a", "extractorT1",
                Position(190.0, 290.0), "2".repeat(32), context.world)))
            var project: String? = null
            while (project == null && step < 2300L) project = tick().units.firstOrNull { it.typeId == "extractorT1" }?.unitId
            val target = assertNotNull(project)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("pause-extractor", "p1", "a", context.world)))
            assertEquals(800.0, context.observe().units.single { it.unitId == target }.health)
            assertTrue(context.observe().units.single { it.unitId == target }.constructionProgress < 1f)
            assertIs<ActionResult.Accepted>(context.submit(RepairBuildingAction("resume-full-hp", "p1", "b", target, context.world)))
            repeat(1500) { tick() }
            assertEquals(1f, context.observe().units.single { it.unitId == target }.constructionProgress)
            assertTrue(context.observe().units.single { it.unitId == "b" }.tasks.isEmpty())
        } finally { context.close() }
    }

    @Test fun an_unfinished_building_is_reclaimed_without_a_corpse_and_paid_only_once_at_the_end() {
        val base = ControlledScenario.load(inputRoot())
        val scene = base.copy(units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "landFactory",
                Position(190.0, 170.0), "1".repeat(32), context.world)))
            val creation = tick()
            val target = assertNotNull(creation.units.singleOrNull { it.typeId == "landFactory" }, creation.events.toString()).unitId
            while (context.observe().units.single { it.unitId == target }.constructionProgress < .1f && step < 400) tick()
            assertIs<ActionResult.Accepted>(context.submit(StopAction("pause", "p1", "worker", context.world)))
            val before = context.observePlayer(context.world, "p1")
            assertEquals(1050.0, before.credits)
            assertTrue(before.units.single { it.unitId == target }.constructionProgress in .1f..<1f)
            assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("reclaim", "p1", "worker", target, context.world)))
            assertEquals(before.credits, context.observePlayer(context.world, "p1").credits)
            var removed = false
            repeat(500) {
                if (!removed) {
                    val sample = tick()
                    val player = context.observePlayer(context.world, "p1")
                    assertTrue(sample.projectiles.isEmpty())
                    assertTrue(sample.events.none { it.kind == "unit-died" || it.kind == "unit-damaged" })
                    if (sample.units.none { it.unitId == target }) {
                        removed = true
                        assertEquals(1610.0000083446503, player.credits)
                        assertEquals(listOf(target), player.events.filter { it.kind == "building-reclaimed" }.map { it.unitId })
                        assertEquals(listOf("worker"), player.events.filter { it.kind == "maintenance-completed" }.map { it.unitId })
                        assertTrue(sample.units.single { it.unitId == "worker" }.tasks.isEmpty())
                    } else assertEquals(before.credits, player.credits)
                }
            }
            assertTrue(removed, "The actual unfinished project must reach its reclaim terminal")
            assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(context.submit(
                ReclaimBuildingAction("repeat", "p1", "worker", target, context.world))).error.code)
            repeat(20) { tick() }
            assertEquals(1610.0000083446503, context.observePlayer(context.world, "p1").credits)
            assertTrue(before.units.any { it.unitId == target && it.alive })
        } finally { context.close() }
    }

    private fun reclaimCardScene(): Scenario = ControlledScenario.load(inputRoot()).copy(
        units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
        combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))

    private fun inputRoot(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/maintenance") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-005/20261029-implementation-01/input"))
}
