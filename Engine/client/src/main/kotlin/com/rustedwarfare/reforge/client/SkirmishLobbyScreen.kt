package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Reusable large battlefield map preview box rendering real-time terrain, ore pools,
 * player-colored numbered spawn pins (`1..N`), and dashed empty spawn slots (UI Supplement §4.1 & §5).
 */
class TacticalBattlefieldPreviewActor(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val mapEntryProvider: () -> MapPickerEntry,
    private val seatsProvider: () -> List<MatchSeatConfig>,
    private val maxSpawnsProvider: () -> Int,
) : Actor(), AutoCloseable {

    private data class PreviewTexKey(
        val mapId: String,
        val thumbnailPath: Path?,
        val themeId: String,
        val wellBits: Int,
        val boneHiBits: Int,
        val allyBits: Int,
    )

    private val glyphLayout = GlyphLayout()
    private var cachedKey: PreviewTexKey? = null
    private var cachedTexture: Texture? = null

    init {
        touchable = Touchable.disabled
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (width <= 0f || height <= 0f) return
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")
        val entry = mapEntryProvider()

        OutGameDraw.fillRect(batch, white, x, y, width, height, theme.colors.well, parentAlpha)

        val tex = ensureTexture(entry, theme)
        val mapWorldW = (entry.mapDefinition.widthTiles * entry.mapDefinition.tileWidth).coerceAtLeast(1).toFloat()
        val mapWorldH = (entry.mapDefinition.heightTiles * entry.mapDefinition.tileHeight).coerceAtLeast(1).toFloat()
        val srcW = tex?.width?.takeIf { it > 0 }?.toFloat() ?: mapWorldW
        val srcH = tex?.height?.takeIf { it > 0 }?.toFloat() ?: mapWorldH
        val fitScale = min(width / srcW, height / srcH)
        val fitW = (srcW * fitScale).roundToInt().toFloat().coerceIn(1f, width)
        val fitH = (srcH * fitScale).roundToInt().toFloat().coerceIn(1f, height)
        val fitX = (x + (width - fitW) * 0.5f).roundToInt().toFloat()
        val fitY = (y + (height - fitH) * 0.5f).roundToInt().toFloat()

        if (tex != null) {
            batch.setColor(1f, 1f, 1f, parentAlpha)
            batch.draw(tex, fitX, fitY, fitW, fitH)
        }

        // Resolve normalized spawn coordinates for `1..maxSpawns` inside aspect-fitted map rect
        val totalSpawns = maxSpawnsProvider().coerceIn(2, MatchSeatConfig.MAX_SEATS)
        val baseSpawns = entry.spawnPointsNormalized
        val seatsBySpawn = seatsProvider().associateBy { it.spawnPoint }
        val pinSize = if (min(fitW, fitH) >= 300f) 24f else 20f
        val font = fontsProvider()?.keyBold ?: fontsProvider()?.smallMono ?: fallbackFontProvider()

        for (spawnIdx in 1..totalSpawns) {
            val (nx, ny) = if (spawnIdx - 1 < baseSpawns.size) {
                baseSpawns[spawnIdx - 1]
            } else {
                // Smooth fallback ring coordinates snapped to nearest walkable land tile when seats exceed base map's spawn count
                val angle = -PI * 0.5 + (2.0 * PI * (spawnIdx - 1).toDouble() / totalSpawns.toDouble())
                snapNormalizedToWalkableLand(
                    entry.mapDefinition,
                    (0.50f + 0.34f * cos(angle).toFloat()).coerceIn(0.08f, 0.92f),
                    (0.50f + 0.34f * sin(angle).toFloat()).coerceIn(0.08f, 0.92f),
                )
            }
            val px = (fitX + nx * fitW - pinSize * 0.5f).roundToInt().toFloat()
            val py = (fitY + (1f - ny) * fitH - pinSize * 0.5f).roundToInt().toFloat()
            val holder = seatsBySpawn[spawnIdx]

            if (holder != null) {
                val pCol = theme.palettes.playerColor(holder.colorIndex)
                val frameDark = if (theme.colors.well.r < theme.colors.boneHi.r) theme.colors.well else theme.colors.boneHi
                OutGameDraw.fillRect(batch, white, px - 1f, py - 2f, pinSize + 2f, pinSize + 2f, theme.outGame.panelShadow, parentAlpha)
                OutGameDraw.fillRect(batch, white, px, py, pinSize, pinSize, pCol, parentAlpha)
                OutGameDraw.strokeRect(batch, white, px, py, pinSize, pinSize, 1.5f, frameDark, parentAlpha)

                val numText = spawnIdx.toString()
                glyphLayout.setText(font, numText)
                val luma = 0.2126f * pCol.r + 0.7152f * pCol.g + 0.0722f * pCol.b
                // Ensure sharp dark ink on bright swatches (green/yellow/cyan/orange/silver) and light ink on deep swatches
                val darkInk = if (theme.colors.ink.r < 0.3f) theme.colors.ink else theme.outGame.textStrong
                val lightInk = if (theme.colors.ink.r > 0.7f) theme.colors.ink else theme.outGame.textStrong
                val numColor = if (luma >= 0.44f) darkInk else lightInk
                val prev = font.color.cpy()
                font.setColor(numColor.r, numColor.g, numColor.b, numColor.a * parentAlpha)
                font.draw(batch, numText, px + (pinSize - glyphLayout.width) * 0.5f, py + (pinSize + glyphLayout.height) * 0.5f)
                font.color = prev
            } else {
                // Empty spawn point: subtle translucent fill + crisp dashed border + dim index
                val emptyBg = theme.colors.well.cpy().apply { a = 0.56f }
                OutGameDraw.fillRect(batch, white, px, py, pinSize, pinSize, emptyBg, parentAlpha)
                OutGameButton.drawDashedRect(batch, white, px, py, pinSize, pinSize, theme.colors.dim, parentAlpha, dash = 4f, gap = 3f)
                val numText = spawnIdx.toString()
                glyphLayout.setText(font, numText)
                val prev = font.color.cpy()
                font.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
                font.draw(batch, numText, px + (pinSize - glyphLayout.width) * 0.5f, py + (pinSize + glyphLayout.height) * 0.5f)
                font.color = prev
            }
        }

        OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.line, parentAlpha)
        batch.packedColor = saved
    }

    private fun ensureTexture(entry: MapPickerEntry, theme: UiTheme): Texture? {
        if (com.badlogic.gdx.Gdx.gl == null) return null
        val thumbPath = entry.thumbnailPath?.takeIf { java.nio.file.Files.isRegularFile(it) }
        val key = PreviewTexKey(
            mapId = entry.id,
            thumbnailPath = thumbPath,
            themeId = theme.id,
            wellBits = Color.rgba8888(theme.colors.well),
            boneHiBits = Color.rgba8888(theme.colors.boneHi),
            allyBits = Color.rgba8888(theme.outGame.ally),
        )
        if (cachedKey == key && cachedTexture != null) return cachedTexture
        cachedTexture?.let { runCatching { it.dispose() } }
        val tex = if (thumbPath != null) {
            runCatching {
                Texture(com.badlogic.gdx.Gdx.files.absolute(thumbPath.toString())).also {
                    it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                }
            }.getOrNull()
        } else {
            val mw = entry.mapDefinition.widthTiles.coerceAtLeast(1)
            val mh = entry.mapDefinition.heightTiles.coerceAtLeast(1)
            val scale = 512f / max(mw, mh).toFloat()
            val tw = (mw * scale).roundToInt().coerceIn(128, 512)
            val th = (mh * scale).roundToInt().coerceIn(128, 512)
            val pm = OutGameMapPreviewRenderer.renderTacticalPixmap(entry.mapDefinition, tw, th, theme)
            try {
                Texture(pm).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
            } finally {
                pm.dispose()
            }
        }
        cachedKey = key
        cachedTexture = tex
        return tex
    }

    private fun snapNormalizedToWalkableLand(map: com.rustedwarfare.reforge.core.content.MapDefinition, nx: Float, ny: Float): Pair<Float, Float> {
        val w = map.widthTiles.coerceAtLeast(1)
        val h = map.heightTiles.coerceAtLeast(1)
        val c0 = (nx * w).toInt().coerceIn(0, w - 1)
        val r0 = (ny * h).toInt().coerceIn(0, h - 1)
        if (!map.blocking.isBlocked(c0, r0)) return nx to ny
        val maxR = max(w, h)
        for (rad in 1..maxR step 2) {
            for (dy in -rad..rad step 2) {
                for (dx in -rad..rad step 2) {
                    if (kotlin.math.abs(dx) < rad - 1 && kotlin.math.abs(dy) < rad - 1) continue
                    val cc = c0 + dx
                    val rr = r0 + dy
                    if (cc in 2 until (w - 2) && rr in 2 until (h - 2) && !map.blocking.isBlocked(cc, rr)) {
                        return ((cc + 0.5f) / w).coerceIn(0.06f, 0.94f) to ((rr + 0.5f) / h).coerceIn(0.06f, 0.94f)
                    }
                }
            }
        }
        return nx to ny
    }

    override fun close() {
        cachedTexture?.let { runCatching { it.dispose() } }
        cachedTexture = null
        cachedKey = null
    }
}

/**
 * Left-narrow / right-wide Skirmish Lobby screen (`遭遇战`, UI Supplement §4.1–§4.2).
 *
 * Preserves full configuration across `MAIN_MENU` round-trips and post-match `returnToLobby()`.
 */
class SkirmishLobbyScreen(
    private val skin: Skin,
    private val appShell: AppShell,
    val lobbyState: SkirmishLobbyState,
    private val themeProvider: UiThemeProvider,
    private val iconsProvider: () -> OutGameControlIcons?,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val canStartProvider: () -> Boolean = { true },
    private val statusMessageProvider: () -> String? = { null },
    private val onNavigateBack: () -> Unit,
    private val onOpenMapPicker: (currentMapId: String, onChosen: (MapPickerEntry) -> Unit) -> Unit,
    private val onLoadCustomTmxPath: (rawPath: String) -> MapPickerEntry?,
    private val onStartMatch: (SkirmishLaunchConfig) -> Unit,
    private val onConfigMutated: () -> Unit = {},
) : Group(), AutoCloseable {

    private val glyphLayout = GlyphLayout()
    private var localErrorNotice: String? = null

    val topBar = SubPageTopBar(
        skin = skin,
        titleKey = "lobby.title",
        widthMode = OutGameWidthMode.FULL_INSET,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        onBack = {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            stage?.unfocusAll()
            onNavigateBack()
        },
    )

    private val panelShell = OutGamePanelShell(
        skin = skin,
        widthMode = OutGameWidthMode.FULL_INSET,
        themeProvider = themeProvider,
    )

    val winModeSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SkirmishWinMode.entries,
        initialSelected = lobbyState.winMode,
        labelOf = { it.localizedLabel() },
        onSelected = { mode ->
            lobbyState.updateWinMode(mode)
            onConfigMutated()
        },
    )

    val fogModeSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SkirmishFogMode.entries,
        initialSelected = lobbyState.fogMode,
        labelOf = { it.localizedLabel() },
        onSelected = { mode ->
            lobbyState.fogMode = mode
            onConfigMutated()
        },
    )

    val startingCreditsDropdown = UnifiedDropdown(
        skin = skin,
        themeProvider = themeProvider,
        iconsProvider = iconsProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SkirmishStartingCredits.SKIRMISH_OPTIONS,
        initialSelected = lobbyState.startingCredits,
        labelOf = { it.localizedLabel() },
        useMonoFont = true,
        onSelected = { credits ->
            lobbyState.startingCredits = credits
            onConfigMutated()
        },
    )

    val moreSettingsButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("lobby.moreSettings") },
        variant = OutGameButton.Variant.SECONDARY,
        indicatorDotProvider = { lobbyState.moreRules.hasNonDefault },
        onClick = { moreSettingsModal.open() },
    )

    val slotTableView = MatchSlotTableView(
        skin = skin,
        lobbyState = lobbyState,
        themeProvider = themeProvider,
        iconsProvider = iconsProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        onStateChanged = { onConfigMutated() },
    )

    val previewActor = TacticalBattlefieldPreviewActor(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        mapEntryProvider = { lobbyState.selectedMap },
        seatsProvider = { lobbyState.seats },
        maxSpawnsProvider = { lobbyState.availableSpawnCount },
    )

    val selectMapButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("lobby.selectMap") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            stage?.unfocusAll()
            onOpenMapPicker(lobbyState.selectedMap.id) { chosen ->
                applySelectedMap(chosen)
            }
        },
    )

    val tmxPathInput = OutGameTextInput(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        placeholderKey = "lobby.tmxPathPlaceholder",
        onEnterPressed = { loadTmxFromInput() },
    )

    val loadTmxButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("common.load") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = { loadTmxFromInput() },
    )

    val startMatchButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("lobby.startMatch") },
        variant = OutGameButton.Variant.PRIMARY,
        onClick = {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            stage?.unfocusAll()
            if (canStartProvider()) {
                onStartMatch(lobbyState.buildLaunchConfig())
            }
        },
    )

    val moreSettingsModal = MoreMatchSettingsModal(
        skin = skin,
        appShell = appShell,
        lobbyState = lobbyState,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        onRulesUpdated = { onConfigMutated() },
    )

    // Cached layout coordinates inside panelShell
    private var leftColW = 572f
    private var rulesDividerY = 0f
    private var winLabelX = 0f
    private var winLabelY = 0f
    private var fogLabelX = 0f
    private var fogLabelY = 0f
    private var fundsLabelX = 0f
    private var fundsLabelY = 0f
    private var rightPadX = 0f
    private var rightInnerW = 400f
    private var rightHeaderBaselineY = 0f

    private val panelOverlayLabels = object : Actor() {
        init {
            touchable = Touchable.disabled
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val ox = x
            val oy = y

            // Vertical divider between left narrow column and right wide preview column
            OutGameDraw.fillRect(batch, white, ox + leftColW, oy + 1f, 1f, panelShell.height - 2f, theme.colors.line, parentAlpha)
            // Horizontal divider beneath top rules row in left column
            OutGameDraw.fillRect(batch, white, ox + 20f, oy + rulesDividerY, leftColW - 40f, 1f, theme.colors.line, parentAlpha)

            // Top rule labels (`胜负模式`, `战争迷雾`, `初始资金`)
            val lblFont = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
            val prevLbl = lblFont.color.cpy()
            val dim = theme.colors.dim
            lblFont.setColor(dim.r, dim.g, dim.b, dim.a * parentAlpha)
            lblFont.draw(batch, UiStrings.get("lobby.winMode"), ox + winLabelX, oy + winLabelY)
            lblFont.draw(batch, UiStrings.get("lobby.fog"), ox + fogLabelX, oy + fogLabelY)
            lblFont.draw(batch, UiStrings.get("lobby.startingFunds"), ox + fundsLabelX, oy + fundsLabelY)
            lblFont.color = prevLbl

            // Right column header: left `战场地图` + right `{NAME} · {N}P`
            val secFont = fontsProvider()?.sectionTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
            val prevSec = secFont.color.cpy()
            val strong = theme.outGame.textStrong
            val mapHeaderTitle = UiStrings.get("lobby.battlefieldMap")
            glyphLayout.setText(secFont, mapHeaderTitle)
            val mapTitleW = glyphLayout.width
            secFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
            secFont.draw(batch, mapHeaderTitle, ox + rightPadX, oy + rightHeaderBaselineY)
            secFont.color = prevSec

            val infoFont = fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
            val mapSummary = UiStrings.format(
                "lobby.mapPlayersFormat",
                "NAME" to lobbyState.selectedMap.localizedTitle(),
                "N" to lobbyState.availableSpawnCount,
            )
            val fittedSummary = OutGameDraw.ellipsizeMiddle(infoFont, mapSummary, (rightInnerW - mapTitleW - 14f).coerceAtLeast(48f), glyphLayout)
            glyphLayout.setText(infoFont, fittedSummary)
            val prevInfo = infoFont.color.cpy()
            infoFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
            infoFont.draw(batch, fittedSummary, ox + rightPadX + rightInnerW - glyphLayout.width, oy + rightHeaderBaselineY)
            infoFont.color = prevInfo

            // Optional fallback/error banner (`requiredObjectives` fallback or invalid `.tmx` path)
            val bannerMsg = localErrorNotice
                ?: statusMessageProvider()?.takeIf { it.isNotBlank() }
                ?: lobbyState.winConditionFallbackNoticeKey?.let { UiStrings.get(it) }
            if (!bannerMsg.isNullOrBlank()) {
                val noteFont = fontsProvider()?.small ?: lblFont
                glyphLayout.setText(noteFont, bannerMsg)
                val prevN = noteFont.color.cpy()
                val col = if (localErrorNotice != null) theme.colors.foe else theme.colors.amber
                noteFont.setColor(col.r, col.g, col.b, col.a * parentAlpha)
                noteFont.draw(batch, bannerMsg, ox + rightPadX + 8f, oy + previewActor.y + 18f)
                noteFont.color = prevN
            }

            batch.packedColor = saved
        }
    }

    init {
        setTransform(false)
        panelShell.addActor(panelOverlayLabels)
        panelShell.addActor(winModeSegmented)
        panelShell.addActor(fogModeSegmented)
        panelShell.addActor(startingCreditsDropdown)
        panelShell.addActor(moreSettingsButton)
        panelShell.addActor(slotTableView)
        panelShell.addActor(previewActor)
        panelShell.addActor(selectMapButton)
        panelShell.addActor(tmxPathInput)
        panelShell.addActor(loadTmxButton)
        panelShell.addActor(startMatchButton)

        addActor(panelShell)
        addActor(topBar)
        addActor(moreSettingsModal)
    }

    fun applySelectedMap(entry: MapPickerEntry) {
        localErrorNotice = null
        lobbyState.selectMapEntry(entry)
        winModeSegmented.setSelectedSilently(lobbyState.winMode)
        slotTableView.syncRows(forceRebuild = true)
        onConfigMutated()
    }

    fun loadTmxFromInput(): Boolean {
        val raw = tmxPathInput.text.trim()
        if (raw.isEmpty()) return false
        val loaded = onLoadCustomTmxPath(raw)
        return if (loaded != null) {
            applySelectedMap(loaded)
            true
        } else {
            localErrorNotice = statusMessageProvider()
            onConfigMutated()
            false
        }
    }

    fun syncControlsFromState() {
        winModeSegmented.setOptionsAndSelection(SkirmishWinMode.entries, lobbyState.winMode)
        fogModeSegmented.setOptionsAndSelection(SkirmishFogMode.entries, lobbyState.fogMode)
        startingCreditsDropdown.setOptionsAndSelection(SkirmishStartingCredits.SKIRMISH_OPTIONS, lobbyState.startingCredits)
        slotTableView.syncRows(forceRebuild = true)
        if (moreSettingsModal.isVisible) {
            moreSettingsModal.syncFromState()
        }
    }

    fun updateLayout(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        topBar.updateBounds(stageWidth, stageHeight)
        panelShell.updateShellBounds(stageWidth, stageHeight)
        moreSettingsModal.updateModalBounds(stageWidth, stageHeight)

        startMatchButton.enabled = canStartProvider()

        val shellW = panelShell.width
        val shellH = panelShell.height
        panelOverlayLabels.setBounds(0f, 0f, shellW, shellH)

        // Left narrow column (`612px` on 1280x720+, proportional on 960x640; keeps right preview wider)
        leftColW = when {
            shellW >= 1220f -> 612f
            shellW >= 1120f -> 584f
            else -> (shellW * 0.56f).roundToInt().toFloat().coerceIn(488f, 584f)
        }
        val leftPad = 20f
        val leftInnerW = leftColW - leftPad * 2f

        val font = fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
        fun measureSegmentedWidth(labels: List<String>, extraPerSeg: Float = 14f): Float {
            var sum = 0f
            for (l in labels) {
                glyphLayout.setText(font, l)
                sum += glyphLayout.width + extraPerSeg
            }
            return sum.roundToInt().toFloat()
        }

        val winW = measureSegmentedWidth(SkirmishWinMode.entries.map { it.localizedLabel() }, extraPerSeg = 14f)
        val fogW = measureSegmentedWidth(SkirmishFogMode.entries.map { it.localizedLabel() }, extraPerSeg = 13f)
        val fundsW = 84f
        glyphLayout.setText(font, UiStrings.get("lobby.moreSettings"))
        val moreW = max(78f, (glyphLayout.width + if (lobbyState.moreRules.hasNonDefault) 28f else 20f).roundToInt().toFloat())
        val ctrlH = 30f
        val gap = 8f

        if (winW + fogW + fundsW + moreW + gap * 3f <= leftInnerW) {
            // Standard widescreen single-row top rules bar (exact mockup layout)
            val ctrlY = shellH - 18f - 18f - ctrlH
            val lblY = ctrlY + ctrlH + 15f
            var cx = leftPad
            winLabelX = cx
            winLabelY = lblY
            winModeSegmented.setBounds(cx, ctrlY, winW, ctrlH)
            cx += winW + gap

            fogLabelX = cx
            fogLabelY = lblY
            fogModeSegmented.setBounds(cx, ctrlY, fogW, ctrlH)
            cx += fogW + gap

            fundsLabelX = cx
            fundsLabelY = lblY
            startingCreditsDropdown.setBounds(cx, ctrlY, fundsW, ctrlH)

            moreSettingsButton.setBounds(leftPad + leftInnerW - moreW, ctrlY, moreW, ctrlH)
            rulesDividerY = ctrlY - 14f
        } else {
            // Compact narrow-window (`960x640` / long English labels) 2-row fallback with zero overlap
            val row1CtrlY = shellH - 16f - 16f - ctrlH
            val row1LblY = row1CtrlY + ctrlH + 13f
            winLabelX = leftPad
            winLabelY = row1LblY
            winModeSegmented.setBounds(leftPad, row1CtrlY, winW.coerceAtMost(leftInnerW - fundsW - moreW - 16f), ctrlH)

            fundsLabelX = winModeSegmented.x + winModeSegmented.width + gap
            fundsLabelY = row1LblY
            startingCreditsDropdown.setBounds(fundsLabelX, row1CtrlY, fundsW, ctrlH)
            moreSettingsButton.setBounds(leftPad + leftInnerW - moreW, row1CtrlY, moreW, ctrlH)

            val row2CtrlY = row1CtrlY - 24f - ctrlH
            fogLabelX = leftPad
            fogLabelY = row2CtrlY + ctrlH + 14f
            fogModeSegmented.setBounds(leftPad, row2CtrlY, fogW.coerceAtMost(leftInnerW), ctrlH)
            rulesDividerY = row2CtrlY - 12f
        }

        val tableBottomPad = 14f
        val tableTopY = rulesDividerY - 14f
        slotTableView.setBounds(leftPad, tableBottomPad, leftInnerW, max(140f, tableTopY - tableBottomPad))

        // Right wide column: header + large tactical map preview + `选择地图` + `.tmx` input/`载入` + `开始对局`
        val rPad = 22f
        rightPadX = leftColW + rPad
        rightInnerW = max(220f, shellW - leftColW - rPad * 2f)
        rightHeaderBaselineY = shellH - 18f

        val startBtnH = 42f
        val startBtnY = 18f
        startMatchButton.setBounds(rightPadX, startBtnY, rightInnerW, startBtnH)

        val pathRowH = 32f
        val pathRowY = startBtnY + startBtnH + 10f
        val loadBtnW = 64f
        tmxPathInput.setBounds(rightPadX, pathRowY, rightInnerW - loadBtnW - 8f, pathRowH)
        loadTmxButton.setBounds(rightPadX + rightInnerW - loadBtnW, pathRowY, loadBtnW, pathRowH)

        val selectMapBtnH = 34f
        val selectMapBtnY = pathRowY + pathRowH + 8f
        selectMapButton.setBounds(rightPadX, selectMapBtnY, rightInnerW, selectMapBtnH)

        val previewTopY = shellH - 42f
        val previewBottomY = selectMapBtnY + selectMapBtnH + 12f
        previewActor.setBounds(rightPadX, previewBottomY, rightInnerW, max(120f, previewTopY - previewBottomY))
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        if (moreSettingsModal.isVisible) {
            return moreSettingsModal.drawnActions()
        }
        val out = ArrayList<DrawnAction>(48)
        out += topBar.drawnBackAction()
        out += DrawnAction("lobby-win-mode", winModeSegmented, lobbyState.winMode.localizedLabel(), enabled = winModeSegmented.enabled)
        out += DrawnAction("match-vision-mode", fogModeSegmented, lobbyState.fogMode.localizedLabel(), enabled = fogModeSegmented.enabled)
        out += DrawnAction("lobby-starting-credits", startingCreditsDropdown, lobbyState.startingCredits.localizedLabel(), enabled = startingCreditsDropdown.enabled)
        out += DrawnAction("lobby-more-settings", moreSettingsButton, UiStrings.get("lobby.moreSettings"), enabled = moreSettingsButton.enabled)
        out += slotTableView.drawnActions("lobby")
        out += DrawnAction("skirmish-maps", selectMapButton, UiStrings.get("lobby.selectMap"), enabled = selectMapButton.enabled)
        out += DrawnAction("lobby-tmx-input", tmxPathInput, tmxPathInput.text, enabled = tmxPathInput.enabled)
        out += DrawnAction("lobby-tmx-load", loadTmxButton, UiStrings.get("common.load"), enabled = loadTmxButton.enabled)
        out += DrawnAction("start-match", startMatchButton, UiStrings.get("lobby.startMatch"), enabled = startMatchButton.enabled)
        return out
    }

    override fun close() {
        UnifiedDropdown.closeAllOpenDropdowns(stage)
        previewActor.close()
    }
}
