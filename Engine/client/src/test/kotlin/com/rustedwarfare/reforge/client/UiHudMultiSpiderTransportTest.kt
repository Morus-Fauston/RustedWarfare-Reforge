package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UiHudMultiSpiderTransportTest {

    private val fixturesDir: Path = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()

    @Test
    fun selectionStateSwitchesPrimaryWithoutMutatingSelectedIdsAndReconcilesCleanly() {
        val state = SelectionState()
        state.select(listOf("u1", "u2", "u3"))
        assertEquals(setOf("u1", "u2", "u3"), state.selectedIds)
        assertEquals("u3", state.primaryId)
        val revBefore = state.revision

        assertTrue(state.setPrimary("u1"))
        assertEquals("u1", state.primaryId)
        assertEquals(setOf("u1", "u2", "u3"), state.selectedIds)
        assertTrue(state.revision > revBefore)

        // Setting an unselected ID fails without changing state
        assertFalse(state.setPrimary("u999"))
        assertEquals("u1", state.primaryId)

        // Reconcile when explicit primary dies falls back cleanly to remaining member
        state.reconcile(visibleAliveIds = setOf("u2", "u3"), observedDeadIds = setOf("u1"))
        assertEquals(setOf("u2", "u3"), state.selectedIds)
        assertEquals("u3", state.primaryId)
    }

    @Test
    fun mixedSelectionTypeChipsSwitchPrimaryAndUpdateCardAndOwnerSubtitle() {
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            val extractorPos = Position(190.0, 290.0)
            val builderPos = Position(270.0, 270.0)
            session.select(extractorPos, SelectionMode.REPLACE)
            session.select(builderPos, SelectionMode.ADD)
            val originalIds = session.selectedIds
            assertEquals(2, originalIds.size)

            val snap1 = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            val chips1 = checkNotNull(snap1.typeChips)
            assertEquals(2, chips1.chips.size)
            assertEquals(0, chips1.overflowCount)
            assertEquals("已选 2", snap1.selectionCard?.countText)
            assertEquals("builder", chips1.chips.single { it.isPrimary }.typeId)

            // Switch primary to extractor without changing selectedIds
            assertTrue(session.selectPrimaryByType("extractorT1"))
            assertEquals(originalIds, session.selectedIds)

            val snap2 = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            val chips2 = checkNotNull(snap2.typeChips)
            assertEquals("extractorT1", chips2.chips.single { it.isPrimary }.typeId)
            assertEquals("extractorT1", snap2.selectionCard?.typeId)

            val actionSnap = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertNotNull(actionSnap.ownerSubtitle)
            assertTrue(actionSnap.ownerSubtitle!!.contains("的动作"))
            assertTrue(actionSnap.exclusiveKeys.any { it.id.startsWith("upgrade:") })
        }
    }

    @Test
    fun typeChipsCapAtSixWithOverflowAndAlwaysKeepPrimaryVisible() {
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            val template = session.observation.units.first()
            val synthetic = (1..8).map { idx ->
                template.copy(unitId = "u$idx", typeId = "type_$idx")
            }
            val chips = HudSidebarProjection.buildTypeChips(synthetic, primaryTypeId = "type_8") { it }
            assertNotNull(chips)
            assertEquals(6, chips!!.chips.size)
            assertEquals(2, chips.overflowCount)
            assertTrue(chips.chips.any { it.typeId == "type_8" && it.isPrimary })
        }
    }

    @Test
    fun modularSpiderProjectsSixUniformSlotsAndSwitchesModuleActions() {
        val root = fixturesDir.resolve("modular-spider-start/hjson")
        val loaded = ContentLoader.loadContentPack(root.resolve("opening-package"))
        assertTrue(loaded is ContentResult.Success<LoadedContentPack>)
        val pack = (loaded as ContentResult.Success<LoadedContentPack>).value
        val scene = contentPackScenario(root, ContentPackChoice(pack, "modularSpider"))
        SandboxSession(scene).use { session ->
            repeat(100) { session.tick() }
            val spider = session.observation.units.single { it.typeId == "modularSpider" }
            session.select(spider.position)

            val before = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertTrue(before.showCommonBar)
            assertEquals(6, before.attachmentSlots.size)
            assertTrue(before.attachmentSlots.all { !it.selected })
            // selectSlot1..6 are presented as 3x2 slot grid, not duplicated as normal keys
            assertTrue(before.exclusiveKeys.none { it.id.startsWith("unit-action:selectSlot") && it.id != "unit-action:selectSlotBuild" })

            // Select slot 2 -> slot 2 highlighted, module install keys appear with prices
            assertTrue(session.unitAction("selectSlot2") is ActionResult.Accepted)
            val afterSlot2 = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertEquals(6, afterSlot2.attachmentSlots.size)
            assertEquals(2, afterSlot2.attachmentSlots.single { it.selected }.slotNumber)
            assertTrue(afterSlot2.exclusiveKeys.any { it.priceText != null })
        }
    }

    @Test
    fun transportCargoObservationExposesPassengerTypeIdsAndMergesCargoChips() {
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        val loaded = ContentLoader.loadContentPack(fixturesDir.resolve("authored-roles/package"))
        assertTrue(loaded is ContentResult.Success<LoadedContentPack>)
        val pack = (loaded as ContentResult.Success<LoadedContentPack>).value
        val carrierPos = Position(210.0, 230.0)
        val scout1Pos = Position(224.0, 230.0)
        val scout2Pos = Position(210.0, 244.0)
        val scene = base.copy(
            unitDefinitions = pack.units,
            units = listOf(
                com.rustedwarfare.reforge.core.sim.UnitSpawn("support", "p1", "support_hover", carrierPos, 90.0),
                com.rustedwarfare.reforge.core.sim.UnitSpawn("cargo1", "p1", "scout_hull", scout1Pos, 90.0),
                com.rustedwarfare.reforge.core.sim.UnitSpawn("cargo2", "p1", "scout_hull", scout2Pos, 90.0),
            ),
            combatContract = com.rustedwarfare.reforge.core.sim.CombatContract(12345, -1, mapOf("support" to 8L, "cargo1" to 9L, "cargo2" to 10L), 11L),
            maintenanceContract = null,
            economyContract = com.rustedwarfare.reforge.core.sim.EconomyContract(base.players.associate { it.playerId to if (it.playerId == "p1") 4000L else 0L }),
        )
        SandboxSession(scene).use { session ->
            // Board both scouts into support_hover
            session.select(scout1Pos)
            assertTrue(session.command(carrierPos) is ActionResult.Accepted)
            session.select(scout2Pos)
            assertTrue(session.command(carrierPos) is ActionResult.Accepted)
            repeat(60) { session.tick() }

            val loadedCarrier = session.observation.units.single { it.unitId == "support" }
            val tr = checkNotNull(loadedCarrier.transport)
            assertEquals(2, tr.passengerCount)
            assertEquals(listOf("scout_hull", "scout_hull"), tr.passengerTypeIds)

            session.select(loadedCarrier.position)
            val hudSnap = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            val cargo = checkNotNull(hudSnap.cargoSection)
            assertEquals(tr.occupiedSlots, cargo.occupiedSlots)
            assertEquals(tr.capacity, cargo.capacity)
            assertEquals(1, cargo.items.size)
            assertEquals("scout_hull", cargo.items.single().typeId)
            assertEquals(2, cargo.items.single().count)

            val actions = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertTrue(actions.exclusiveKeys.any { it.id == "transport-unload-target" && it.isTargetAction })
            assertTrue(actions.exclusiveKeys.any { it.id == "transport-unload" && !it.isTargetAction })

            // Target-mode hint card renders cleanly for transport-unload-target
            val card = HudActionProjection.projectTargetModeCard(
                session = session,
                constructionTypeId = null, constructionPreview = null,
                reclaimMode = false, repairMode = false, patrolMode = false, patrolPointCount = 0,
                groundActionName = "transport-unload-target", groundActionPreview = null,
                sandboxPick = null, touchControls = false, language = UiLanguage.ZH,
            )
            assertNotNull(card)
            assertEquals(UiStrings.get("unload", UiLanguage.ZH), card!!.title)

            // Trigger immediate unload and verify cancel-unload key appears
            assertTrue(session.unload(true) is ActionResult.Accepted)
            val unloadingActions = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertTrue(unloadingActions.exclusiveKeys.any { it.id == "transport-cancel-unload" })
            assertTrue(session.unload(false) is ActionResult.Accepted)
        }
    }

    @Test
    fun mixedSelectionSwitchingPrimaryExposesBuilderAndTransportExclusiveActions() {
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            val extractorPos = Position(190.0, 290.0)
            val builderPos = Position(270.0, 270.0)
            session.select(extractorPos, SelectionMode.REPLACE)
            session.select(builderPos, SelectionMode.ADD)

            // Primary is builder -> builder's own construction & reclaim/repair exclusive actions appear with owner subtitle!
            val builderActions = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertTrue(builderActions.showCommonBar)
            assertNotNull(builderActions.ownerSubtitle)
            assertTrue(builderActions.ownerSubtitle!!.contains("的动作"))
            assertTrue(builderActions.exclusiveKeys.any { it.id == "choose-reclaim" })
            assertTrue(builderActions.exclusiveKeys.any { it.id.startsWith("choose-construction:") })

            // Switch primary to extractor -> upgrade actions appear, builder construction disappears, selectedIds unchanged!
            val savedIds = session.selectedIds
            assertTrue(session.selectPrimaryByType("extractorT1"))
            assertEquals(savedIds, session.selectedIds)
            val extActions = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertTrue(extActions.exclusiveKeys.any { it.id.startsWith("upgrade:") })
            assertTrue(extActions.exclusiveKeys.none { it.id.startsWith("choose-construction:") })
        }
    }

    @Test
    fun sidebarSubtitleUsesRealUiStringsKeysWithoutRawKeyLeak() {
        // Verify charging / submerged / surfaced keys exist in both ZH and EN tables
        assertEquals("充能中", UiStrings.get("charging", UiLanguage.ZH))
        assertEquals("Charging", UiStrings.get("charging", UiLanguage.EN))
        assertEquals("水下", UiStrings.get("submerged", UiLanguage.ZH))
        assertEquals("Submerged", UiStrings.get("submerged", UiLanguage.EN))
        assertEquals("水面", UiStrings.get("surfaced", UiLanguage.ZH))
        assertEquals("Surfaced", UiStrings.get("surfaced", UiLanguage.EN))
    }

    @Test
    fun transportUnloadAtMovesAndUnloadsOnArrivalAndCancelsCleanlyOnNewMoveOrStop() {
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        val loaded = ContentLoader.loadContentPack(fixturesDir.resolve("authored-roles/package"))
        assertTrue(loaded is ContentResult.Success<LoadedContentPack>)
        val pack = (loaded as ContentResult.Success<LoadedContentPack>).value
        val carrierPos = Position(210.0, 230.0)
        val scoutPos = Position(224.0, 230.0)
        val scene = base.copy(
            unitDefinitions = pack.units,
            units = listOf(
                com.rustedwarfare.reforge.core.sim.UnitSpawn("support", "p1", "support_hover", carrierPos, 90.0),
                com.rustedwarfare.reforge.core.sim.UnitSpawn("cargo1", "p1", "scout_hull", scoutPos, 90.0),
                com.rustedwarfare.reforge.core.sim.UnitSpawn("foe_watch", "p2", "scout_hull", Position(260.0, 230.0), 270.0),
            ),
            combatContract = com.rustedwarfare.reforge.core.sim.CombatContract(12345, -1, mapOf("support" to 8L, "cargo1" to 9L, "foe_watch" to 10L), 11L),
            maintenanceContract = null,
            economyContract = com.rustedwarfare.reforge.core.sim.EconomyContract(base.players.associate { it.playerId to if (it.playerId == "p1") 4000L else 0L }),
        )

        // 1. Move overriding unloadAt cancels pending unload so arriving at the new point does NOT unload!
        SandboxSession(scene).use { session ->
            session.select(scoutPos)
            assertTrue(session.command(carrierPos) is ActionResult.Accepted)
            repeat(40) { session.tick() }

            val carrier = session.observation.units.single { it.unitId == "support" }
            session.select(carrier.position)
            assertTrue(session.canTargetUnload())

            // Issue unloadAt far away -> pending unload active -> cancel key visible
            assertTrue(session.unloadAt(Position(320.0, 230.0)) is ActionResult.Accepted)
            assertTrue(session.hasPendingUnload("support"))
            val movingActions = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertTrue(movingActions.exclusiveKeys.any { it.id == "transport-cancel-unload" && it.enabled })

            // Override with ordinary move -> pendingUnload cleared immediately -> arriving does NOT unload
            assertTrue(session.move(Position(250.0, 230.0)) is ActionResult.Accepted)
            assertFalse(session.hasPendingUnload("support"))
            repeat(80) { session.tick() }
            val afterMove = session.observation.units.single { it.unitId == "support" }
            assertEquals(1, checkNotNull(afterMove.transport).passengerCount)
            assertFalse(checkNotNull(afterMove.transport).unloading)

            // Now issue unloadAt and let carrier arrive -> auto-unloads passenger!
            assertTrue(session.unloadAt(Position(310.0, 230.0)) is ActionResult.Accepted)
            assertTrue(session.hasPendingUnload("support"))
            repeat(180) { session.tick() }
            val afterUnload = session.observation.units.single { it.unitId == "support" }
            assertFalse(session.hasPendingUnload("support"))
            assertEquals(0, checkNotNull(afterUnload.transport).passengerCount)
        }

        // 2. Core privacy check: enemy p2 observing p1's loaded carrier sees transport == null (no passengerTypeIds leak!)
        com.rustedwarfare.reforge.core.sim.OfflineSandboxHost(scene, false, 1L, emptyList()).use { host ->
            val sim = host.context
            val p1Carrier = sim.observePlayer(sim.world, "p1").units.single { it.unitId == "support" }
            assertNotNull(p1Carrier.transport)
            val p2ViewOfCarrier = sim.observePlayer(sim.world, "p2").units.firstOrNull { it.unitId == "support" }
            assertNotNull(p2ViewOfCarrier)
            assertNull(p2ViewOfCarrier!!.transport, "Enemy observation must redact transport & passengerTypeIds completely")
        }
    }

}
