package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.Align
import com.rustedwarfare.reforge.core.content.MapDefinition
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Immutable briefing summary displayed on [MatchLoadingScreen] (UI Supplement §6).
 */
data class MatchLoadingSummary(
    val mapId: String,
    val mapTitleKeyOrRaw: String,
    val isMapTitleKey: Boolean,
    val mapSlug: String,
    val mapDefinition: MapDefinition,
    val spawnPointsNormalized: List<Pair<Float, Float>>,
    val seats: List<MatchSeatConfig>,
    val maxSpawns: Int,
    val winMode: SkirmishWinMode,
    val fogMode: SkirmishFogMode,
    val startingCreditsTextProvider: (UiLanguage) -> String,
    val economyText: String,
    val noNukes: Boolean,
    val tipIndex: Int = 1,
    val thumbnailPath: java.nio.file.Path? = null,
) {
    fun localizedMapTitle(language: UiLanguage = UiStrings.currentLanguage): String =
        if (isMapTitleKey) UiStrings.get(mapTitleKeyOrRaw, language) else mapTitleKeyOrRaw

    fun ruleCells(language: UiLanguage = UiStrings.currentLanguage): List<Triple<String, String, String>> = listOf(
        Triple("01", UiStrings.get("loading.rule.victory", language), winMode.localizedLabel(language)),
        Triple(
            "02",
            UiStrings.get("loading.rule.fog", language),
            if (fogMode == SkirmishFogMode.MEMORY) {
                UiStrings.get("lobby.fog.memoryFull", language)
            } else {
                fogMode.localizedLabel(language)
            },
        ),
        Triple("03", UiStrings.get("loading.rule.funds", language), startingCreditsTextProvider(language)),
        Triple("04", UiStrings.get("loading.rule.economy", language), economyText),
        Triple(
            "05",
            UiStrings.get("loading.rule.noNukes", language),
            UiStrings.get(if (noNukes) "common.on" else "common.off", language),
        ),
    )

    fun localizedTip(language: UiLanguage = UiStrings.currentLanguage): String {
        val safeIdx = ((tipIndex - 1).mod(5)) + 1
        return UiStrings.get("loading.tip.$safeIdx", language)
    }

    companion object {
        fun fromLaunchConfig(
            config: SkirmishLaunchConfig,
            mapEntry: MapPickerEntry,
            tipIndex: Int = 1,
        ): MatchLoadingSummary = MatchLoadingSummary(
            mapId = mapEntry.id,
            mapTitleKeyOrRaw = mapEntry.titleKeyOrRaw,
            isMapTitleKey = mapEntry.isTranslationKey,
            mapSlug = config.mapSlug.ifBlank { mapEntry.slug },
            mapDefinition = mapEntry.mapDefinition,
            spawnPointsNormalized = mapEntry.spawnPointsNormalized,
            seats = config.immutableSeats,
            maxSpawns = config.maxSpawns,
            winMode = config.winMode,
            fogMode = config.fogMode,
            startingCreditsTextProvider = { lang -> config.startingCredits.localizedLabel(lang) },
            economyText = config.moreRules.incomeMultiplier.displayText,
            noNukes = config.moreRules.noNukes,
            tipIndex = tipIndex,
            thumbnailPath = mapEntry.thumbnailPath,
        )

        fun fromTestScenario(
            choice: TestScenarioChoice,
            options: SandboxOptions,
            tipIndex: Int = 1,
        ): MatchLoadingSummary {
            val map = choice.scenario.map
            val worldW = (map.widthTiles * map.tileWidth).toDouble().coerceAtLeast(1.0)
            val worldH = (map.heightTiles * map.tileHeight).toDouble().coerceAtLeast(1.0)
            val players = choice.scenario.players.take(MatchSeatConfig.MAX_SEATS)
            val spawns = ArrayList<Pair<Float, Float>>(players.size)
            val seats = ArrayList<MatchSeatConfig>(players.size)

            players.forEachIndexed { idx, player ->
                val pUnits = choice.scenario.units.filter { it.ownerPlayerId == player.playerId }
                val (nx, ny) = if (pUnits.isNotEmpty()) {
                    val avgX = (pUnits.sumOf { it.position.x } / pUnits.size / worldW).toFloat().coerceIn(0.08f, 0.92f)
                    val avgY = (pUnits.sumOf { it.position.y } / pUnits.size / worldH).toFloat().coerceIn(0.08f, 0.92f)
                    avgX to avgY
                } else {
                    val angle = -PI * 0.5 + (2.0 * PI * idx.toDouble() / max(2, players.size).toDouble())
                    (0.50f + 0.34f * cos(angle).toFloat()) to (0.50f + 0.34f * sin(angle).toFloat())
                }
                spawns += nx to ny
                seats += MatchSeatConfig(
                    seatId = idx + 1,
                    kind = if (idx == 0) MatchSeatKind.LOCAL_HUMAN else MatchSeatKind.AI,
                    colorIndex = idx % MatchSeatConfig.MAX_PALETTE_COLORS,
                    ordinalInKind = if (idx == 0) 1 else idx,
                    teamIndex = idx % MatchSeatConfig.MAX_TEAMS,
                    spawnPoint = idx + 1,
                )
            }

            val p1Balance = choice.scenario.economyContract?.initialBalances?.get("p1")
            val creditsText = if (p1Balance != null) {
                String.format(Locale.US, "$%,d", p1Balance)
            } else {
                "—"
            }

            return MatchLoadingSummary(
                mapId = choice.id,
                mapTitleKeyOrRaw = choice.name,
                isMapTitleKey = false,
                mapSlug = choice.id,
                mapDefinition = map,
                spawnPointsNormalized = spawns,
                seats = seats.ifEmpty {
                    listOf(MatchSeatConfig(seatId = 1, kind = MatchSeatKind.LOCAL_HUMAN, colorIndex = 0, spawnPoint = 1))
                },
                maxSpawns = max(2, seats.size),
                winMode = SkirmishWinMode.SANDBOX,
                fogMode = SkirmishFogMode.fromSandboxMode(options.mode),
                startingCreditsTextProvider = { creditsText },
                economyText = SkirmishIncomeMultiplier.X1_0.displayText,
                noNukes = !options.nukesEnabled,
                tipIndex = tipIndex,
            )
        }
    }
}

/**
 * Out-game match loading screen (`OutGameRoute.LOADING`, UI Supplement §6 & `UI-P004-004`).
 *
 * Structure & invariants:
 * - Centered solid briefing card over `DynamicMapBackdrop`:
 *   1) Top `16px` header strip + constructivist tick ruler + `1px` `line` divider (zero text clutter);
 *   2) Left `1:1` square tactical map thumbnail with player-colored numbered spawn badges (`1..N`);
 *   3) Right: large bold localized map title + baseline mono English slug + `01–05` 5-cell rule summary
 *      (`胜负 / 迷雾 / 资金 / 经济 / 禁核`) + `TIPS` tactical hint box (no repeated seat table);
 *   4) Bottom progress zone: fixed-X bold large `LOADING` + animated `.` -> `..` -> `...` appended to the right
 *      (guaranteeing 0.0px horizontal shift of `LOADING`), right-aligned mono percentage, `rust` progress bar,
 *      and bottom tick ruler.
 * - Safe loading cancellation via top-left `← 取消` (`DrawnAction("exit-match", ...)`) and `Esc` / `BACK` (`D-060`).
 */
class MatchLoadingScreen(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onCancelLoading: () -> Unit,
) : Group(), AutoCloseable {

    private data class ThumbTexKey(
        val mapId: String,
        val thumbnailPath: java.nio.file.Path?,
        val widthTiles: Int,
        val heightTiles: Int,
        val themeId: String,
        val wellBits: Int,
        val boneHiBits: Int,
        val allyBits: Int,
    )

    private val glyphLayout = GlyphLayout()
    private var cachedKey: ThumbTexKey? = null
    private var cachedTexture: Texture? = null

    var currentSummary: MatchLoadingSummary? = null
        private set

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    var animationSeconds: Float = 0f
        private set

    /** Cycles `1 -> 2 -> 3` every `0.45s` while keeping [loadingWordAnchorX] 100% invariant. */
    val activeDotCount: Int
        get() = (((animationSeconds.toDouble() + 1e-4) / DOT_STEP_SECONDS.toDouble()).toInt().coerceAtLeast(0) % 3) + 1

    val activeLoadingDots: String
        get() = ".".repeat(activeDotCount)

    // Deterministic card & text anchors exposed for unit test assertions
    var cardX: Float = 0f
        private set
    var cardY: Float = 0f
        private set
    var cardW: Float = 880f
        private set
    var cardH: Float = 424f
        private set
    var loadingWordAnchorX: Float = 0f
        private set
    var loadingDotsAnchorX: Float = 0f
        private set

    val cancelButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("loading.cancelBtn") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = onCancelLoading,
    )

    private val briefingCardActor = object : Actor() {
        init {
            touchable = Touchable.disabled
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val summary = currentSummary ?: return
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")

            // Subtle drop shadow + solid briefing card shell
            OutGameDraw.fillRect(batch, white, cardX + 2f, cardY - 6f, cardW, cardH, theme.outGame.panelShadow, parentAlpha)
            OutGameDraw.fillRect(batch, white, cardX, cardY, cardW, cardH, theme.colors.solid, parentAlpha)

            // 1. Top 16px header strip + bottom tick ruler + 1px line divider (strictly zero text)
            val headH = 16f
            val headY = cardY + cardH - headH
            OutGameDraw.fillRect(batch, white, cardX + 1f, headY, cardW - 2f, headH - 1f, theme.outGame.railSolid, parentAlpha)
            OutGameDraw.drawRuler(batch, white, cardX + 1f, headY + 1f, cardW - 2f, height = 6f, theme = theme, flipVertical = false, parentAlpha = parentAlpha)
            OutGameDraw.fillRect(batch, white, cardX + 1f, headY, cardW - 2f, 1f, theme.colors.line, parentAlpha)

            // 2. Bottom progress zone (`bf-prog`)
            val progZoneH = 92f
            val progTopY = cardY + progZoneH
            OutGameDraw.fillRect(batch, white, cardX + 1f, progTopY, cardW - 2f, 1f, theme.colors.hair, parentAlpha)

            val padX = 20f
            val innerW = cardW - padX * 2f
            val rulerBottomY = cardY + 14f
            OutGameDraw.drawRuler(batch, white, cardX + padX, rulerBottomY, innerW, height = 6f, theme = theme, flipVertical = true, parentAlpha = parentAlpha)

            val barH = 10f
            val barY = rulerBottomY + 10f
            OutGameDraw.fillRect(batch, white, cardX + padX, barY, innerW, barH, theme.colors.well, parentAlpha)
            val fillW = ((innerW - 2f) * progress).roundToInt().toFloat().coerceIn(0f, innerW - 2f)
            if (fillW > 0f) {
                OutGameDraw.fillRect(batch, white, cardX + padX + 1f, barY + 1f, fillW, barH - 2f, theme.colors.rust, parentAlpha)
            }
            OutGameDraw.strokeRect(batch, white, cardX + padX, barY, innerW, barH, 1f, theme.colors.line, parentAlpha)

            // Fixed-X bold large `LOADING` + animated `.` / `..` / `...` on its right (0.0px shift!)
            val ldFont = fontsProvider()?.subPageTitle ?: fontsProvider()?.creditsMono ?: fallbackFontProvider()
            val strong = theme.outGame.textStrong
            val prevLd = ldFont.color.cpy()
            ldFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
            val ldBaselineY = barY + barH + 28f
            var charX = loadingWordAnchorX
            for (ch in LOADING_WORD) {
                val s = ch.toString()
                glyphLayout.setText(ldFont, s)
                ldFont.draw(batch, s, charX, ldBaselineY)
                charX += glyphLayout.width + LOADING_LETTER_SPACING
            }
            var dotX = loadingDotsAnchorX
            for (i in 0 until activeDotCount) {
                glyphLayout.setText(ldFont, ".")
                ldFont.draw(batch, ".", dotX, ldBaselineY)
                dotX += glyphLayout.width + 3f
            }

            val pctFont = fontsProvider()?.creditsMono ?: ldFont
            val prevPct = pctFont.color.cpy()
            pctFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
            val pctText = "${(progress * 100f).roundToInt().coerceIn(0, 100)}%"
            glyphLayout.setText(pctFont, pctText)
            pctFont.draw(batch, pctText, cardX + cardW - padX - glyphLayout.width, ldBaselineY)
            pctFont.color = prevPct
            ldFont.color = prevLd

            // 3. Middle left-right split (`bf-body`)
            val bodyTopY = headY - 18f
            val bodyBottomY = progTopY + 16f
            val availBodyH = max(180f, bodyTopY - bodyBottomY)
            val thumbSize = min(248f, availBodyH).roundToInt().toFloat()
            val thumbX = cardX + padX
            val thumbY = bodyTopY - thumbSize

            // 1:1 square tactical map thumbnail + player-colored numbered spawn pins
            OutGameDraw.fillRect(batch, white, thumbX, thumbY, thumbSize, thumbSize, theme.colors.well, parentAlpha)
            val tex = ensureThumbTexture(summary, theme)
            if (tex != null) {
                batch.setColor(1f, 1f, 1f, parentAlpha)
                batch.draw(tex, thumbX, thumbY, thumbSize, thumbSize)
            }
            drawSquareSpawnPins(batch, white, summary, theme, thumbX, thumbY, thumbSize, parentAlpha)
            OutGameDraw.strokeRect(batch, white, thumbX, thumbY, thumbSize, thumbSize, 1f, theme.colors.line, parentAlpha)

            // Right side: Map Title + English Slug, 01–05 Rule Summary, TIPS Box
            val rightX = thumbX + thumbSize + 20f
            val rightW = max(220f, cardX + cardW - padX - rightX)

            val titleFont = fontsProvider()?.subPageTitle ?: fontsProvider()?.sectionTitle ?: fallbackFontProvider()
            val mapTitle = summary.localizedMapTitle()
            glyphLayout.setText(titleFont, mapTitle)
            val titleBaselineY = bodyTopY - 4f
            val prevTitle = titleFont.color.cpy()
            titleFont.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
            titleFont.draw(batch, mapTitle, rightX, titleBaselineY)
            val titleW = glyphLayout.width
            titleFont.color = prevTitle

            val slugFont = fontsProvider()?.smallMono ?: fontsProvider()?.small ?: fallbackFontProvider()
            val maxSlugW = (rightW - titleW - 12f).coerceAtLeast(0f)
            if (maxSlugW >= 24f && summary.mapSlug.isNotBlank()) {
                var slugToDraw = summary.mapSlug
                glyphLayout.setText(slugFont, slugToDraw)
                if (glyphLayout.width > maxSlugW) {
                    while (slugToDraw.length > 1) {
                        slugToDraw = slugToDraw.dropLast(1)
                        glyphLayout.setText(slugFont, "$slugToDraw…")
                        if (glyphLayout.width <= maxSlugW) {
                            slugToDraw += "…"
                            break
                        }
                    }
                }
                val prevSlug = slugFont.color.cpy()
                slugFont.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
                slugFont.draw(batch, slugToDraw, rightX + titleW + 12f, titleBaselineY - 2f)
                slugFont.color = prevSlug
            }

            val nameDivY = titleBaselineY - 30f
            OutGameDraw.fillRect(batch, white, rightX, nameDivY, rightW, 1f, theme.colors.line, parentAlpha)

            // `01–05` 5-cell rule summary grid
            val rsumH = 52f
            val rsumY = nameDivY - 14f - rsumH
            OutGameDraw.strokeRect(batch, white, rightX, rsumY, rightW, rsumH, 1f, theme.colors.hair, parentAlpha)

            val cells = summary.ruleCells()
            val cellW = rightW / cells.size.toFloat()
            val idxFont = fontsProvider()?.smallMono ?: slugFont
            val lblFont = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
            val valCjkFont = fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
            val valMonoFont = fontsProvider()?.countMono ?: valCjkFont

            cells.forEachIndexed { i, (num, label, value) ->
                val cx = rightX + i * cellW
                if (i > 0) {
                    OutGameDraw.fillRect(batch, white, cx.roundToInt().toFloat(), rsumY + 1f, 1f, rsumH - 2f, theme.colors.hair, parentAlpha)
                }
                val prevIdx = idxFont.color.cpy()
                idxFont.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
                glyphLayout.setText(idxFont, num)
                idxFont.draw(batch, num, cx + 10f, rsumY + rsumH - 9f)
                val numW = glyphLayout.width
                idxFont.color = prevIdx

                val prevLbl = lblFont.color.cpy()
                lblFont.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
                lblFont.draw(batch, label, cx + 10f + numW + 4f, rsumY + rsumH - 9f)
                lblFont.color = prevLbl

                val pureAscii = value.all { it.code in 32..126 || it == '×' || it == '—' }
                val vFont = if (pureAscii) valMonoFont else valCjkFont
                val prevV = vFont.color.cpy()
                vFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
                vFont.draw(batch, value, cx + 10f, rsumY + 19f)
                vFont.color = prevV
            }

            // `TIPS` tactical hint box
            val tipsTopY = rsumY - 14f
            val tipsBottomY = thumbY
            val tipsH = max(68f, tipsTopY - tipsBottomY)
            val tipsY = tipsTopY - tipsH
            OutGameDraw.fillRect(batch, white, rightX, tipsY, rightW, tipsH, theme.colors.well, parentAlpha)
            OutGameDraw.strokeRect(batch, white, rightX, tipsY, rightW, tipsH, 1f, theme.colors.hair, parentAlpha)

            val tipsHdFont = fontsProvider()?.countMono ?: idxFont
            val prevHd = tipsHdFont.color.cpy()
            tipsHdFont.setColor(theme.colors.amber.r, theme.colors.amber.g, theme.colors.amber.b, theme.colors.amber.a * parentAlpha)
            tipsHdFont.draw(batch, "TIPS", rightX + 14f, tipsY + tipsH - 12f)
            tipsHdFont.color = prevHd

            val tipBodyFont = fontsProvider()?.body ?: fallbackFontProvider()
            val prevTip = tipBodyFont.color.cpy()
            tipBodyFont.setColor(theme.colors.text.r, theme.colors.text.g, theme.colors.text.b, theme.colors.text.a * parentAlpha)
            val wrappedLines = OutGameDraw.wrapTextCjk(tipBodyFont, summary.localizedTip(), rightW - 28f, glyphLayout)
            val lineStep = 21f
            wrappedLines.take(3).forEachIndexed { idx, line ->
                tipBodyFont.draw(batch, line, rightX + 14f, tipsY + tipsH - 35f - idx * lineStep)
            }
            tipBodyFont.color = prevTip

            OutGameDraw.strokeRect(batch, white, cardX, cardY, cardW, cardH, 1f, theme.colors.line, parentAlpha)
            batch.packedColor = saved
        }
    }

    init {
        setTransform(false)
        addActor(briefingCardActor)
        addActor(cancelButton)
    }

    private fun drawSquareSpawnPins(
        batch: Batch,
        white: com.badlogic.gdx.graphics.g2d.TextureRegion,
        summary: MatchLoadingSummary,
        theme: UiTheme,
        thumbX: Float,
        thumbY: Float,
        thumbSize: Float,
        parentAlpha: Float,
    ) {
        val seatsBySpawn = summary.seats.associateBy { it.spawnPoint }
        val baseSpawns = summary.spawnPointsNormalized
        val totalSpawns = summary.maxSpawns.coerceIn(2, MatchSeatConfig.MAX_SEATS)
        val pinSize = 22f
        val font = fontsProvider()?.keyBold ?: fontsProvider()?.smallMono ?: fallbackFontProvider()

        val mapWorldW = (summary.mapDefinition.widthTiles * summary.mapDefinition.tileWidth).coerceAtLeast(1).toFloat()
        val mapWorldH = (summary.mapDefinition.heightTiles * summary.mapDefinition.tileHeight).coerceAtLeast(1).toFloat()
        val fitScale = if (summary.thumbnailPath != null) min(thumbSize / mapWorldW, thumbSize / mapWorldH) else 1f
        val fitW = if (summary.thumbnailPath != null) (mapWorldW * fitScale).coerceIn(1f, thumbSize) else thumbSize
        val fitH = if (summary.thumbnailPath != null) (mapWorldH * fitScale).coerceIn(1f, thumbSize) else thumbSize
        val fitX = thumbX + (thumbSize - fitW) * 0.5f
        val fitY = thumbY + (thumbSize - fitH) * 0.5f

        for (spawnIdx in 1..totalSpawns) {
            val holder = seatsBySpawn[spawnIdx] ?: continue
            val (nx, ny) = if (spawnIdx - 1 < baseSpawns.size) {
                baseSpawns[spawnIdx - 1]
            } else {
                val angle = -PI * 0.5 + (2.0 * PI * (spawnIdx - 1).toDouble() / totalSpawns.toDouble())
                (0.50f + 0.34f * cos(angle).toFloat()) to (0.50f + 0.34f * sin(angle).toFloat())
            }
            val px = (fitX + nx * fitW - pinSize * 0.5f).roundToInt().toFloat()
            val py = (fitY + (1f - ny) * fitH - pinSize * 0.5f).roundToInt().toFloat()
            val pCol = theme.palettes.playerColor(holder.colorIndex)

            OutGameDraw.fillRect(batch, white, px, py, pinSize, pinSize, theme.colors.solid, parentAlpha)
            OutGameDraw.strokeRect(batch, white, px, py, pinSize, pinSize, 2f, pCol, parentAlpha)

            val numText = spawnIdx.toString()
            glyphLayout.setText(font, numText)
            val prev = font.color.cpy()
            val fg = theme.outGame.textStrong
            font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
            font.draw(batch, numText, px + (pinSize - glyphLayout.width) * 0.5f, py + (pinSize + glyphLayout.height) * 0.5f)
            font.color = prev
        }
    }

    private fun ensureThumbTexture(summary: MatchLoadingSummary, theme: UiTheme): Texture? {
        if (Gdx.gl == null) return null
        val thumbPath = summary.thumbnailPath?.takeIf { java.nio.file.Files.isRegularFile(it) }
        val key = ThumbTexKey(
            mapId = summary.mapId,
            thumbnailPath = thumbPath,
            widthTiles = summary.mapDefinition.widthTiles,
            heightTiles = summary.mapDefinition.heightTiles,
            themeId = theme.id,
            wellBits = Color.rgba8888(theme.colors.well),
            boneHiBits = Color.rgba8888(theme.colors.boneHi),
            allyBits = Color.rgba8888(theme.outGame.ally),
        )
        if (cachedKey == key && cachedTexture != null) return cachedTexture
        cachedTexture?.let { runCatching { it.dispose() } }
        val synthEntry = MapPickerEntry(
            id = summary.mapId,
            slug = summary.mapSlug,
            titleKeyOrRaw = summary.mapTitleKeyOrRaw,
            isTranslationKey = summary.isMapTitleKey,
            tmxPath = null,
            mapDefinition = summary.mapDefinition,
            spawnPointsNormalized = summary.spawnPointsNormalized,
            thumbnailPath = thumbPath,
        )
        val pm = if (thumbPath != null) {
            OutGameMapPreviewRenderer.loadOrRenderSquareCardPixmap(synthEntry, 384, theme)
        } else {
            OutGameMapPreviewRenderer.renderTacticalPixmap(summary.mapDefinition, 384, 384, theme)
        }
        val filter = if (thumbPath != null) Texture.TextureFilter.Linear else Texture.TextureFilter.Nearest
        val tex = try {
            Texture(pm).also { it.setFilter(filter, filter) }
        } finally {
            pm.dispose()
        }
        cachedKey = key
        cachedTexture = tex
        return tex
    }

    fun beginLoading(summary: MatchLoadingSummary, initialProgress: Float = 0.08f) {
        currentSummary = summary
        progress = initialProgress
        animationSeconds = 0f
        if (width > 0f && height > 0f) {
            updateLayout(width, height)
        }
    }

    fun stepAnimation(deltaSeconds: Float) {
        if (deltaSeconds > 0f && deltaSeconds.isFinite()) {
            animationSeconds += deltaSeconds
        }
    }

    fun updateLayout(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        briefingCardActor.setBounds(0f, 0f, stageWidth, stageHeight)

        cardW = min(880f, max(640f, stageWidth - 48f)).roundToInt().toFloat()
        cardH = min(428f, max(376f, stageHeight - 112f)).roundToInt().toFloat()
        cardX = ((stageWidth - cardW) * 0.5f).roundToInt().toFloat()
        cardY = ((stageHeight - cardH) * 0.5f).roundToInt().toFloat()

        loadingWordAnchorX = cardX + 20f
        val ldFont = fontsProvider()?.subPageTitle ?: fontsProvider()?.creditsMono ?: fallbackFontProvider()
        var trackedWordW = 0f
        for (ch in LOADING_WORD) {
            glyphLayout.setText(ldFont, ch.toString())
            trackedWordW += (if (glyphLayout.width > 0f) glyphLayout.width else 12f) + LOADING_LETTER_SPACING
        }
        loadingDotsAnchorX = loadingWordAnchorX + trackedWordW + 2f

        val btnFont = fontsProvider()?.command ?: fallbackFontProvider()
        glyphLayout.setText(btnFont, UiStrings.get("loading.cancelBtn"))
        val cancelW = max(86f, (glyphLayout.width + 28f).roundToInt().toFloat())
        cancelButton.setBounds(24f, stageHeight - 46f, cancelW, 32f)
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        return listOf(
            DrawnAction("exit-match", cancelButton, UiStrings.get("loading.cancelBtn"), enabled = cancelButton.enabled),
        )
    }

    override fun close() {
        cachedTexture?.let { runCatching { it.dispose() } }
        cachedTexture = null
        cachedKey = null
    }

    companion object {
        const val LOADING_WORD: String = "LOADING"
        const val LOADING_LETTER_SPACING: Float = 2.0f
        const val DOT_STEP_SECONDS: Float = 0.45f
    }
}
