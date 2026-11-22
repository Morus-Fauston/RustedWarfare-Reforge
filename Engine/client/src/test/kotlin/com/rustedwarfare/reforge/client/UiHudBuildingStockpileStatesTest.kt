package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UiHudBuildingStockpileStatesTest {

    private val fixturesDir: Path = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()

    @Test
    fun underConstructionBuildingShowsRustProgressBarCancelAndReclaimHidesProductionAndRefundsOnCancel() {
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            val startCredits = session.observation.credits ?: session.observation.balance?.toDouble() ?: 0.0
            val builder = session.observation.units.first { it.typeId == "builder" && it.ownerPlayerId == "p1" }
            session.select(builder.position)
            val sitePos = Position(builder.position.x + 80.0, builder.position.y)
            assertTrue(session.build("landFactory", sitePos) is ActionResult.Accepted)

            var site: UnitObservation? = null
            for (i in 0 until 200) {
                session.tick()
                site = session.observation.units.firstOrNull {
                    it.typeId == "landFactory" && it.ownerPlayerId == "p1" && it.constructionProgress < 0.999f
                }
                if (site != null && site.constructionProgress > 0.05f) break
            }
            val inProgressSite = checkNotNull(site) { "Expected in-progress landFactory site" }
            assertTrue(inProgressSite.constructionProgress < 1f)

            session.select(inProgressSite.position)
            assertEquals(inProgressSite.unitId, session.selectedId)
            assertTrue(session.canCancelConstruction())

            val sideSnap = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            val card = checkNotNull(sideSnap.selectionCard)
            val buildBar = card.statBars.firstOrNull { it.labelKey == "build" }
            assertNotNull(buildBar, "Under-construction building must show construction progress bar")
            assertEquals(HudStatStyle.PROGRESS_RUST, buildBar!!.style)
            assertTrue(buildBar.currentText.endsWith("%"))

            // Save site into control group 1 to verify cancel removes it cleanly from groups too (P1-3)
            session.saveGroup(1)
            assertEquals(1, session.groupCount(1))

            val actSnap = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertEquals(listOf("cancel-construction", "choose-reclaim"), actSnap.exclusiveKeys.map { it.id })
            assertEquals("取消施工", actSnap.exclusiveKeys[0].title)
            assertEquals("rw-cancel", actSnap.exclusiveKeys[0].commandIconName)
            assertTrue(actSnap.exclusiveKeys[0].enabled)
            assertEquals("回收", actSnap.exclusiveKeys[1].title)
            assertEquals("rw-reclaim", actSnap.exclusiveKeys[1].commandIconName)
            // Single-selected site without a builder disables choose-reclaim with clear reason (P1-1)
            assertFalse(actSnap.exclusiveKeys[1].enabled)
            assertEquals(HudActionKeyState.DISABLED, actSnap.exclusiveKeys[1].state)
            assertEquals("需选工人", actSnap.exclusiveKeys[1].noteText)
            assertTrue(actSnap.exclusiveKeys.none { it.id.startsWith("produce:") }, "Must hide production keys before completion")

            // When a builder is also selected alongside the site (site remains primary), choose-reclaim becomes enabled and SandboxInput keeps reclaimMode active
            session.select(builder.position, SelectionMode.ADD)
            assertTrue(session.selectPrimaryByType("landFactory"))
            val actWithBuilder = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertTrue(actWithBuilder.exclusiveKeys[1].enabled)
            assertEquals(HudActionKeyState.READY, actWithBuilder.exclusiveKeys[1].state)
            val mapProj = MapProjection(
                base.map.widthTiles.toDouble() * base.map.tileWidth,
                base.map.heightTiles.toDouble() * base.map.tileHeight,
                MapArea(0.0, 0.0, 960.0, 640.0),
                fillViewport = true,
            )
            val mapInput = SandboxInput(
                session = { session },
                projection = { mapProj },
                uiPoint = { sx, sy -> Position(sx.toDouble(), sy.toDouble()) },
                active = { true },
                editing = { false },
                pressed = { false },
            )
            assertTrue(mapInput.beginReclaim(), "beginReclaim must succeed when a builder is in selection")
            assertTrue(mapInput.reclaimMode, "reclaimMode must stay active after beginReclaim with builder + site primary")

            val cancelRes = session.cancelConstruction()
            assertTrue(cancelRes is ActionResult.Accepted)
            assertTrue(session.observation.units.none { it.unitId == inProgressSite.unitId })
            assertEquals(0, session.groupCount(1), "Cancelled site must be purged from saved control groups")
            val afterCredits = session.observation.credits ?: session.observation.balance?.toDouble() ?: 0.0
            assertTrue(afterCredits >= startCredits - 1.0, "Expected refund back to near starting credits (before=$startCredits, after=$afterCredits)")
        }
    }

    @Test
    fun nukeSiloStockpileRowShowsFilledAndEmptyPipsFullDisablesBuildAndLaunchTargetShowsRemainingCount() {
        val scene = NuclearSandboxScene.scenario()
        SandboxSession(scene).use { session ->
            val siloPos = Position(220.0, 180.0)
            session.select(siloPos)

            assertTrue(session.unitAction("buildNuke") is ActionResult.Accepted)
            repeat(3335) { session.tick() }
            assertTrue(session.unitAction("buildNuke") is ActionResult.Accepted)
            repeat(500) { session.tick() }

            val sideSnap = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            val stock = checkNotNull(sideSnap.stockpileSection)
            assertEquals(1, stock.current)
            assertEquals(4, stock.capacity)

            val layout = InGameHudOverlay.computeLayout(960f, 640f, 148f, sideSnap, 120f)
            val stockRect = checkNotNull(layout.stockpileRect)
            val cardRect = checkNotNull(layout.cardRect)
            assertEquals(cardRect.y, stockRect.y + stockRect.height, 0.01f)

            val actSnap = HudActionProjection.projectActionList(session, null, false, false, false, "launchNuke", false, UiLanguage.ZH)
            assertEquals(listOf("unit-action:buildNuke", "unit-action:launchNuke"), actSnap.exclusiveKeys.map { it.id })
            val buildKey = actSnap.exclusiveKeys[0]
            assertEquals("制造核弹", buildKey.title)
            assertEquals(1, buildKey.queueBadgeCount)
            assertNotNull(buildKey.progress)
            assertTrue(buildKey.enabled)

            val launchKey = actSnap.exclusiveKeys[1]
            assertEquals("发射核弹", launchKey.title)
            assertEquals("可用 1", launchKey.noteText)
            assertEquals(HudActionKeyState.ACTIVE_MODE, launchKey.state)

            val preview = session.previewGroundAction("launchNuke", Position(600.0, 180.0))
            assertNotNull(preview)
            assertTrue(preview!!.areaRadius > 0.0)
            val cardModel = HudActionProjection.projectTargetModeCard(
                session, null, null, false, false, false, 0, "launchNuke", preview, null, false, UiLanguage.ZH,
            )
            assertNotNull(cardModel)
            assertEquals("发射核弹", cardModel!!.title)
            assertEquals("剩余 1 发", cardModel.rightValue)

            // Fill remaining queue slots to capacity (1 stocked + 3 queued = 4/4) -> buildNuke disabled with "储备已满"
            assertTrue(session.unitAction("buildNuke") is ActionResult.Accepted)
            assertTrue(session.unitAction("buildNuke") is ActionResult.Accepted)
            val fullActSnap = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val fullBuildKey = fullActSnap.exclusiveKeys.first { it.id == "unit-action:buildNuke" }
            assertFalse(fullBuildKey.enabled)
            assertEquals(HudActionKeyState.DISABLED, fullBuildKey.state)
            assertEquals("储备已满", fullBuildKey.noteText)
            assertEquals(3, fullBuildKey.queueBadgeCount)
        }
    }

    @Test
    fun turretDualUpgradeBranchesLockOtherBranchWithBranchTakenAndShowUpgradeQueueRow() {
        val base = ControlledScenario.load(fixturesDir.resolve("build-economy/turret-upgrades"))
        SandboxSession(base).use { session ->
            val turret = session.observation.units.first { it.typeId == "c_turret_t1" && it.ownerPlayerId == "p1" }
            session.select(turret.position)

            val beforeAct = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val beforeUpgrades = beforeAct.exclusiveKeys.filter { it.id.startsWith("upgrade:") }
            assertEquals(2, beforeUpgrades.size)
            assertTrue(beforeUpgrades.all { it.enabled })
            assertTrue(beforeUpgrades.all { it.unitIconTypeId != null })

            val chosenKey = beforeUpgrades[0].id.substringAfter(':')
            assertTrue(session.upgrade(chosenKey) is ActionResult.Accepted)
            repeat(10) { session.tick() }

            val sideSnap = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            val queue = checkNotNull(sideSnap.queueSection)
            assertEquals("upgradeQ", queue.labelKey)
            assertEquals(1, queue.slots.size)
            assertNotNull(queue.progressPercent)

            val afterAct = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val chosenModel = afterAct.exclusiveKeys.first { it.id == "upgrade:$chosenKey" }
            val otherModel = afterAct.exclusiveKeys.first { it.id.startsWith("upgrade:") && it.id != "upgrade:$chosenKey" }
            assertEquals(HudActionKeyState.READY, chosenModel.state)
            assertNotNull(chosenModel.progress)
            assertTrue(chosenModel.cancelEnabled)

            assertEquals(HudActionKeyState.DISABLED, otherModel.state)
            assertFalse(otherModel.enabled)
            assertEquals("分支已定", otherModel.noteText)
            assertEquals(UiStrings.get("branchFull", UiLanguage.ZH), otherModel.tooltip.footnote)

            // Cancelling the chosen branch unlocks both branches cleanly (P2-3)
            assertTrue(session.cancelUpgrade(chosenKey) is ActionResult.Accepted)
            val recoveredAct = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val recoveredUpgrades = recoveredAct.exclusiveKeys.filter { it.id.startsWith("upgrade:") }
            assertEquals(2, recoveredUpgrades.size)
            assertTrue(recoveredUpgrades.all { it.enabled && it.noteText == null })
        }
    }

    @Test
    fun submarineSingleButtonToggleShowsCurrentStateNextVerbAndTransitionGuard() {
        val base = NavalSandboxScene.load("json", "attackSubmarine").scenario
        val scene = base.copy(
            units = listOf(UnitSpawn("s1", "p1", "attackSubmarine", Position(450.0, 250.0), 90.0, 1.0)),
            combatContract = CombatContract(12345, -1, mapOf("s1" to 8L), 9L),
        )
        SandboxSession(scene).use { session ->
            val sub = session.observation.units.single { it.unitId == "s1" }
            session.select(sub.position)
            // Native submarines start with surfaceIntent=false; surface it first to test surfaced -> dive -> underwater -> surface
            assertTrue(session.submarineDepth(surface = true) is ActionResult.Accepted)
            repeat(60) { session.tick() }

            val side1 = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            assertEquals("水面", side1.selectionCard?.subtitleText)
            val act1 = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val depthKey1 = act1.exclusiveKeys.single { it.id.startsWith("submarine-") }
            assertEquals("下潜", depthKey1.title)
            assertEquals("rw-dive", depthKey1.commandIconName)
            assertTrue(depthKey1.enabled)
            assertNull(depthKey1.progress)

            assertTrue(session.submarineDepth(surface = false) is ActionResult.Accepted)
            var midObserved = false
            for (i in 0 until 20) {
                session.tick()
                val midSub = session.observation.units.single { it.unitId == sub.unitId }
                if (midSub.submarine?.underwater == false && midSub.submarine?.surfaceIntent == false) {
                    val actMid = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
                    val depthKeyMid = actMid.exclusiveKeys.single { it.id.startsWith("submarine-") }
                    assertFalse(depthKeyMid.enabled, "Duplicate clicks must be disabled while diving")
                    assertNotNull(depthKeyMid.progress, "Transitioning state toggle must show progress bar")
                    midObserved = true
                    break
                }
            }
            assertTrue(midObserved, "Must unconditionally observe submarine mid-dive transition guard")

            repeat(300) { session.tick() }
            val side2 = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            assertEquals("水下", side2.selectionCard?.subtitleText)
            val act2 = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val depthKey2 = act2.exclusiveKeys.single { it.id.startsWith("submarine-") }
            assertEquals("上浮", depthKey2.title)
            assertEquals("rw-surface", depthKey2.commandIconName)
            assertTrue(depthKey2.enabled)
        }
    }

    @Test
    fun bunkerAndAfterburnStateTogglesShowCurrentStateAndTransitionProgress() {
        val loaded = BunkerSandboxScene.load()
        val base = loaded.scenario
        val bunkerSpawn = UnitSpawn("bunker-1", "p1", "mechBunker", Position(220.0, 220.0), 0.0)
        val scene = base.copy(
            units = listOf(bunkerSpawn),
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("bunker-1" to 1L), nextObjectId = 2L),
        )
        SandboxSession(scene).use { session ->
            session.select(bunkerSpawn.position)
            val side1 = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            assertEquals("已收起", side1.selectionCard?.subtitleText)
            val act1 = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val deployKey = act1.exclusiveKeys.single { it.id == "unit-action:deploy" }
            assertEquals("展开", deployKey.title)
            assertTrue(deployKey.enabled)

            // Queue deploy -> progress shown, duplicate clicks disabled
            assertTrue(session.unitAction("deploy") is ActionResult.Accepted)
            repeat(15) { session.tick() }
            val actMid = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val midKey = actMid.exclusiveKeys.single { it.id == "unit-action:deploy" }
            assertFalse(midKey.enabled, "Duplicate deploy click must be disabled while transitioning")
            assertNotNull(midKey.progress)

            // Finish deploying -> subtitle "已展开", single key "收起"
            repeat(220) { session.tick() }
            val side2 = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            assertEquals("已展开", side2.selectionCard?.subtitleText)
            val act2 = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val undeployKey = act2.exclusiveKeys.single { it.id == "unit-action:undeploy" }
            assertEquals("收起", undeployKey.title)
            assertTrue(undeployKey.enabled)
        }
    }

    @Test
    fun pointDefenseEnergyRechargeLockShowsAmberWarningSubtitleAndEnergyBar() {
        val scene = ControlledScenario.load(fixturesDir.resolve("maintenance-defense/point-defense"))
        SandboxSession(scene).use { session ->
            val laser = session.observation.units.first { it.typeId == "laserDefence" && it.ownerPlayerId == "p1" }
            session.select(laser.position)

            // Advance until enemy shells exhaust the point-defense energy (or check live energy bar)
            var exhaustedObserved = false
            for (step in 0 until 600) {
                val u = session.observation.units.firstOrNull { it.unitId == laser.unitId } ?: break
                if (u.pointDefense?.exhausted == true) {
                    val snap = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
                    val card = checkNotNull(snap.selectionCard)
                    assertEquals("▲ 充能中", card.subtitleText)
                    assertTrue(card.subtitleIsWarning)
                    val energyBar = card.statBars.single { it.labelKey == "energy" }
                    assertEquals(HudStatStyle.WARNING_AMBER, energyBar.style)
                    exhaustedObserved = true
                    break
                }
                session.tick()
            }
            // Confirm at minimum the energy bar is present and properly structured; if combat exhausted it, amber lock was asserted
            val snap = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
            assertNotNull(snap.selectionCard?.statBars?.firstOrNull { it.labelKey == "energy" })
            assertTrue(exhaustedObserved, "Expected point-defense fixture combat to trigger energy recharge lock")
        }
    }

    @Test
    fun weaponEnergyRechargeLockDisablesEnergyActionAndShowsAmberWarning() {
        val loaded = AaBeamSandboxScene.load()
        val base = loaded.scenario
        val gunship = UnitSpawn("gunship-1", "p1", "aaBeamGunship", Position(200.0, 200.0), 90.0, 0.0)
        val enemy = UnitSpawn("enemy-1", "p2", "aaBeamGunship", Position(360.0, 200.0), 270.0, 0.0)
        val scene = base.copy(
            units = listOf(gunship, enemy),
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("gunship-1" to 1L, "enemy-1" to 2L), nextObjectId = 3L),
        )
        SandboxSession(scene).use { session ->
            session.select(gunship.position)
            // First check afterburn boosted subtitle
            assertTrue(session.unitAction("afterBurn") is ActionResult.Accepted)
            repeat(5) { session.tick() }
            var sawBoosted = false
            var sawLocked = false
            val uAfter = session.observation.units.firstOrNull { it.unitId == "gunship-1" }
            if (uAfter?.typeId == "aaBeamGunship_afterburn") {
                val sideBoost = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
                assertEquals("加力中", sideBoost.selectionCard?.subtitleText)
                sawBoosted = true
                // Return to normal form so weapon shots exhaust energy into recharge lock
                session.unitAction("cancelBurn")
            }
            for (step in 0 until 400) {
                session.tick()
                val u = session.observation.units.firstOrNull { it.unitId == "gunship-1" } ?: break
                if (u.energy?.recharging == true) {
                    val sideLock = HudSidebarProjection.project(session, false, false, OfflineRate.NORMAL, UiLanguage.ZH)
                    val card = checkNotNull(sideLock.selectionCard)
                    assertEquals("▲ 充能中", card.subtitleText)
                    assertTrue(card.subtitleIsWarning)
                    assertEquals(HudStatStyle.WARNING_AMBER, card.statBars.single { it.labelKey == "energy" }.style)
                    val actLock = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
                    val afterBurnKey = checkNotNull(actLock.exclusiveKeys.firstOrNull { it.id == "unit-action:afterBurn" }) {
                        "Expected unit-action:afterBurn key on locked aaBeamGunship"
                    }
                    assertFalse(afterBurnKey.enabled)
                    assertEquals(HudActionKeyState.DISABLED, afterBurnKey.state)
                    assertEquals("充能中", afterBurnKey.noteText)
                    assertNull(afterBurnKey.priceText, "Disabled action with short reason must not hide note behind price")
                    sawLocked = true
                    break
                }
            }
            assertTrue(sawBoosted, "Expected aaBeamGunship to enter afterburn boosted state")
            assertTrue(sawLocked, "Expected aaBeamGunship to enter energy recharge lock after draining energy")
        }
    }

    @Test
    fun cameraFollowBadgeTracksOfflineCameraControllerAndClearsImmediatelyOnTakeover() {
        val mapProj = MapProjection(1000.0, 1000.0, MapArea(0.0, 0.0, 960.0, 640.0), fillViewport = true)
        val cam = OfflineCameraController(mapProj)
        val events = HudEventStream()

        assertFalse(cam.followsUnit)
        events.setCameraFollowing(cam.followsUnit)
        assertFalse(events.cameraFollowing)

        cam.advance(1L, listOf(OfflineCameraMotion.Follow("u1"))) { Position(400.0, 400.0) }
        assertTrue(cam.followsUnit)
        events.setCameraFollowing(cam.followsUnit)
        assertTrue(events.cameraFollowing)

        cam.takeOver("pan")
        assertFalse(cam.followsUnit)
        events.setCameraFollowing(cam.followsUnit)
        assertFalse(events.cameraFollowing)
    }

    @Test
    fun multiSelectedNuclearSilosUseActiveLauncherStockpileEvenWhenPrimarySiloIsEmpty() {
        val base = NuclearSandboxScene.scenario()
        val scene = base.copy(
            units = base.units + UnitSpawn("silo-loaded", "p1", "nukeLauncherC", Position(320.0, 180.0), 90.0),
            combatContract = base.combatContract!!.copy(
                unitObjectIds = mapOf("builder" to 1L, "silo" to 2L, "silo-loaded" to 3L),
                nextObjectId = 4L,
            ),
        )
        SandboxSession(scene).use { session ->
            // Build 2 nukes on silo-loaded; leave primary "silo" empty (0 nukes)
            session.select(Position(320.0, 180.0))
            repeat(2) { assertTrue(session.unitAction("buildNuke") is ActionResult.Accepted) }
            repeat(6668) { session.tick() }

            // Select both silos and make empty "silo" the primary
            session.select(Position(320.0, 180.0), SelectionMode.REPLACE)
            session.select(Position(220.0, 180.0), SelectionMode.ADD)
            assertEquals("silo", session.selectedId)
            assertEquals(0, session.observation.units.single { it.unitId == "silo" }.unitActions?.stockpile)

            val actSnap = HudActionProjection.projectActionList(session, null, false, false, false, "launchNuke", false, UiLanguage.ZH)
            val launchKey = actSnap.exclusiveKeys.single { it.id == "unit-action:launchNuke" }
            assertTrue(launchKey.enabled)
            assertEquals("可用 2", launchKey.noteText)

            val preview = session.previewGroundAction("launchNuke", Position(620.0, 180.0))
            val cardModel = HudActionProjection.projectTargetModeCard(
                session, null, null, false, false, false, 0, "launchNuke", preview, null, false, UiLanguage.ZH,
            )
            assertEquals("剩余 2 发", cardModel?.rightValue)
        }
    }

    @Test
    fun antiNukeSwitchIconMatchesNextStepVerbPolarity() {
        // isEnabled = currently enabled -> next step is Disable (停用) with rw-disable icon
        assertEquals("停用", HudActionProjection.localizeUnitActionTitle("isEnabled", "Disable", UiLanguage.ZH))
        assertEquals("rw-disable", HudActionProjection.commandIconForAction("isEnabled"))
        // isDisabled = currently disabled -> next step is Enable (启用) with rw-enable icon
        assertEquals("启用", HudActionProjection.localizeUnitActionTitle("isDisabled", "Enable", UiLanguage.ZH))
        assertEquals("rw-enable", HudActionProjection.commandIconForAction("isDisabled"))
    }
}
