package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PackManagerKind {
    RESOURCE_PACKS,
    CONTENT_PACKS,
}

enum class PackIconBadgeStyle {
    BASE_RESOURCE,
    ORIGINAL_RESOURCE,
    CUSTOM_RESOURCE,
    BASE_CONTENT,
    CUSTOM_CONTENT,
}

/**
 * View-model entry rendered inside [DualColumnPackManagerView] (UI Supplement §7).
 */
data class DualColumnPackEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val lockedBottom: Boolean = false,
    val canReorder: Boolean = !lockedBottom,
    val canDisable: Boolean = !lockedBottom,
    val canEnable: Boolean = true,
    val badgeStyle: PackIconBadgeStyle,
    val customIconPath: Path? = null,
    val errorText: String? = null,
    val options: List<ResourcePackOption> = emptyList(),
) {
    val hasOptions: Boolean get() = options.isNotEmpty()
    val hasInMatchEditableOptions: Boolean get() = options.any { !it.affectsMediaAssets }
    val hasPureUiThemeOptionsOnly: Boolean get() = options.isNotEmpty() && options.none { it.affectsMediaAssets }
}

/**
 * Shared Minecraft-style left-enabled / right-available dual-column pack manager
 * (`资源包` & `内容包`, UI Supplement §7 & `UI-P004-005`).
 *
 * Features:
 * - Left column: `已启用` (`自上而下覆盖`), ordered high-to-low with the mandatory base pack locked at the bottom (`基础常驻`).
 * - Right column: `未启用` (`count`), listing disabled packs ready to move left (`◀`).
 * - Each card: left `44×44` `1:1` square icon thumbnail, center title (`displayText_zh` priority) + subtitle,
 *   right stacked `▲ / ▼` priority arrows + `▶` disable arrow (on unlocked enabled rows) or `◀` enable arrow (on available rows).
 * - Crisp 4-direction supersampled vector triangle icons (`▲ / ▼ / ◀ / ▶`) immune to font glyph gaps.
 * - `inMatchMode = true` locks all pack mutations and renders a clear read-only notice banner.
 */
class DualColumnPackManagerView(
    private val skin: Skin,
    val kind: PackManagerKind,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val showExtractOriginalButton: Boolean = false,
    private val onMovePriority: (packId: String, deltaIndex: Int) -> Unit,
    private val onSetPackEnabled: (packId: String, enabled: Boolean) -> Unit,
    private val onOpenPackOptions: (DualColumnPackEntry) -> Unit = {},
    private val onImportPath: (rawPath: String) -> Unit = {},
    private val onExtractOriginalPath: (rawPath: String) -> Unit = {},
) : Group(), AutoCloseable {

    enum class ArrowDir { UP, DOWN, LEFT, RIGHT }

    private val glyphLayout = GlyphLayout()
    private val enabledEntries = ArrayList<DualColumnPackEntry>()
    private val availableEntries = ArrayList<DualColumnPackEntry>()
    private val cardGroups = ArrayList<PackCardGroup>()

    var inMatchMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncCardInteractivity()
            }
        }

    var busy: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncCardInteractivity()
            }
        }

    var statusNotice: String? = null
    var statusIsError: Boolean = false
    var showPathImportBar: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncCardInteractivity()
                layoutContent()
            }
        }

    val pathInput = OutGameTextInput(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        placeholderKey = "settings.packs.importPathPlaceholder",
        onEnterPressed = { triggerImport() },
    )

    val importButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("settings.packs.importBtn") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = { triggerImport() },
    )

    val extractButton: OutGameButton? = if (showExtractOriginalButton) {
        OutGameButton(
            skin = skin,
            themeProvider = themeProvider,
            fontsProvider = fontsProvider,
            fallbackFontProvider = fallbackFontProvider,
            labelProvider = { UiStrings.get("settings.packs.extractBtn") },
            variant = OutGameButton.Variant.SECONDARY,
            onClick = {
                val raw = pathInput.text.trim()
                if (raw.isNotEmpty() && !inMatchMode && !busy) {
                    onExtractOriginalPath(raw)
                }
            },
        )
    } else {
        null
    }

    private var colW = 360f
    private var colH = 300f
    private var colY = 44f
    private var leftColX = 0f
    private var rightColX = 376f
    private var lockBannerY = 0f
    private var lockBannerH = 0f
    private var leftScrollOffset = 0f
    private var rightScrollOffset = 0f

    private val arrowTexCache = LinkedHashMap<ArrowDir, Texture>()
    private var gearTex: Texture? = null
    private val badgeTexCache = LinkedHashMap<Pair<PackIconBadgeStyle, String>, Texture>()
    private val customIconTexCache = LinkedHashMap<Path, Texture>()

    private val chromeBackdropActor = object : Actor() {
        init {
            touchable = Touchable.disabled
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val ox = x
            val oy = y

            if (inMatchMode && lockBannerH > 0f) {
                val warnBg = theme.colors.well
                OutGameDraw.fillRect(batch, white, ox, oy + lockBannerY, this@DualColumnPackManagerView.width, lockBannerH, warnBg, parentAlpha)
                OutGameDraw.strokeRect(batch, white, ox, oy + lockBannerY, this@DualColumnPackManagerView.width, lockBannerH, 1f, theme.colors.amber, parentAlpha)
                val font = fontsProvider()?.keyBold ?: fontsProvider()?.body ?: fallbackFontProvider()
                val lockText = UiStrings.get("settings.packs.inMatchLocked")
                glyphLayout.setText(font, lockText)
                val prev = font.color.cpy()
                font.setColor(theme.colors.amber.r, theme.colors.amber.g, theme.colors.amber.b, theme.colors.amber.a * parentAlpha)
                font.draw(batch, lockText, ox + 12f, oy + lockBannerY + (lockBannerH + glyphLayout.height) * 0.5f)
                font.color = prev
            }

            // Left (`已启用`) and Right (`未启用`) column shells
            drawColumnShell(
                batch = batch,
                white = white,
                theme = theme,
                cx = ox + leftColX,
                cy = oy + colY,
                cw = colW,
                ch = colH,
                title = UiStrings.get("settings.packs.enabled"),
                rightHint = UiStrings.get("settings.packs.enabledHint"),
                parentAlpha = parentAlpha,
            )
            drawColumnShell(
                batch = batch,
                white = white,
                theme = theme,
                cx = ox + rightColX,
                cy = oy + colY,
                cw = colW,
                ch = colH,
                title = UiStrings.get("settings.packs.available"),
                rightHint = availableEntries.size.toString(),
                parentAlpha = parentAlpha,
            )

            val notice = statusNotice?.takeIf { it.isNotBlank() }
            if (notice != null && !inMatchMode) {
                val nFont = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
                val prev = nFont.color.cpy()
                val col = if (statusIsError) theme.colors.foe else theme.colors.dim
                nFont.setColor(col.r, col.g, col.b, col.a * parentAlpha)
                nFont.draw(batch, notice, ox, oy + colY - 6f)
                nFont.color = prev
            }

            batch.packedColor = saved
        }

        private fun drawColumnShell(
            batch: Batch,
            white: TextureRegion,
            theme: UiTheme,
            cx: Float,
            cy: Float,
            cw: Float,
            ch: Float,
            title: String,
            rightHint: String,
            parentAlpha: Float,
        ) {
            OutGameDraw.fillRect(batch, white, cx, cy, cw, ch, theme.outGame.railSolid, parentAlpha)
            OutGameDraw.strokeRect(batch, white, cx, cy, cw, ch, 1f, theme.colors.line, parentAlpha)

            val hdH = 34f
            val hdBottomY = cy + ch - hdH
            OutGameDraw.fillRect(batch, white, cx + 1f, hdBottomY, cw - 2f, 1f, theme.colors.line, parentAlpha)

            val hdFont = fontsProvider()?.command ?: fontsProvider()?.body ?: fallbackFontProvider()
            val prevHd = hdFont.color.cpy()
            hdFont.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
            glyphLayout.setText(hdFont, title)
            hdFont.draw(batch, title, cx + 12f, hdBottomY + (hdH + glyphLayout.height) * 0.5f)
            hdFont.color = prevHd

            val pureAscii = rightHint.all { it.code in 32..126 }
            val subFont = if (pureAscii) (fontsProvider()?.smallMono ?: hdFont) else (fontsProvider()?.small ?: hdFont)
            glyphLayout.setText(subFont, rightHint)
            val prevSub = subFont.color.cpy()
            subFont.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
            subFont.draw(batch, rightHint, cx + cw - 12f - glyphLayout.width, hdBottomY + (hdH + glyphLayout.height) * 0.5f)
            subFont.color = prevSub
        }
    }

    init {
        setTransform(false)
        touchable = Touchable.enabled
        addListener(object : InputListener() {
            override fun mouseMoved(event: InputEvent, x: Float, y: Float): Boolean {
                stage?.scrollFocus = this@DualColumnPackManagerView
                return false
            }

            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean {
                if (amountY == 0f) return false
                val deltaPx = amountY * 36f
                if (x < rightColX) {
                    leftScrollOffset = (leftScrollOffset + deltaPx).coerceAtLeast(0f)
                } else {
                    rightScrollOffset = (rightScrollOffset + deltaPx).coerceAtLeast(0f)
                }
                layoutContent()
                return true
            }
        })
        addActor(chromeBackdropActor)
        addActor(pathInput)
        addActor(importButton)
        extractButton?.let(::addActor)
    }

    val currentEnabledEntries: List<DualColumnPackEntry>
        get() = enabledEntries.toList()

    val currentAvailableEntries: List<DualColumnPackEntry>
        get() = availableEntries.toList()

    fun setEntries(entries: List<DualColumnPackEntry>) {
        enabledEntries.clear()
        availableEntries.clear()
        val unlockedEnabled = entries.filter { it.enabled && !it.lockedBottom }
        val lockedBottoms = entries.filter { it.enabled && it.lockedBottom }
        enabledEntries.addAll(unlockedEnabled)
        enabledEntries.addAll(lockedBottoms)
        availableEntries.addAll(entries.filter { !it.enabled })
        rebuildCards()
        layoutContent()
    }

    private fun triggerImport() {
        val raw = pathInput.text.trim()
        if (raw.isNotEmpty() && !inMatchMode && !busy) {
            onImportPath(raw)
        }
    }

    private fun rebuildCards() {
        for (card in cardGroups) {
            card.remove()
        }
        cardGroups.clear()

        enabledEntries.forEachIndexed { idx, entry ->
            val canUp = entry.canReorder && idx > 0 && enabledEntries[idx - 1].canReorder
            val canDown = entry.canReorder && idx + 1 < enabledEntries.size && enabledEntries[idx + 1].canReorder
            val card = PackCardGroup(
                entry = entry,
                inEnabledColumn = true,
                canMoveUp = canUp,
                canMoveDown = canDown,
            )
            cardGroups += card
            addActor(card)
        }
        for (entry in availableEntries) {
            val card = PackCardGroup(
                entry = entry,
                inEnabledColumn = false,
                canMoveUp = false,
                canMoveDown = false,
            )
            cardGroups += card
            addActor(card)
        }
        syncCardInteractivity()
    }

    private fun syncCardInteractivity() {
        val allowEdit = !inMatchMode && !busy
        val showBar = showPathImportBar && !inMatchMode
        pathInput.isVisible = showBar
        importButton.isVisible = showBar
        extractButton?.isVisible = showBar
        pathInput.enabled = allowEdit && showBar
        importButton.enabled = allowEdit && showBar
        extractButton?.enabled = allowEdit && showBar
        for (card in cardGroups) {
            card.syncEnabledState(allowEdit)
        }
    }

    override fun setBounds(x: Float, y: Float, width: Float, height: Float) {
        super.setBounds(x, y, width, height)
        layoutContent()
    }

    private fun layoutContent() {
        if (width <= 0f || height <= 0f) return
        chromeBackdropActor.setBounds(0f, 0f, width, height)

        val topAvailY = if (inMatchMode) {
            lockBannerH = 32f
            lockBannerY = height - lockBannerH
            lockBannerY - 10f
        } else {
            lockBannerH = 0f
            lockBannerY = height
            height
        }

        val showBar = showPathImportBar && !inMatchMode
        val hasNotice = !inMatchMode && !statusNotice.isNullOrBlank()
        val pathBarH = if (showBar) 32f else 0f
        val noticeGap = if (showBar || hasNotice) 22f else 0f
        colY = pathBarH + noticeGap
        colH = max(140f, topAvailY - colY)

        val colGap = 16f
        colW = max(160f, ((width - colGap) * 0.5f).roundToInt().toFloat())
        leftColX = 0f
        rightColX = colW + colGap

        if (showBar) {
            val importW = 88f
            val extractW = if (extractButton != null) 88f else 0f
            val buttonsW = importW + if (extractW > 0f) (extractW + 6f) else 0f
            val inputW = max(120f, width - buttonsW - 6f)
            pathInput.setBounds(0f, 0f, inputW, pathBarH)
            importButton.setBounds(inputW + 6f, 0f, importW, pathBarH)
            extractButton?.setBounds(inputW + 6f + importW + 6f, 0f, extractW, pathBarH)
        }

        val cardPad = 8f
        val cardW = max(120f, colW - cardPad * 2f)
        val cardH = 56f
        val cardGap = 7f
        val listTopY = colY + colH - 34f - cardPad
        val listBottomY = colY + 4f
        val viewportH = max(cardH, listTopY - listBottomY)

        val leftTotalH = max(0f, enabledEntries.size * (cardH + cardGap) - cardGap)
        val rightTotalH = max(0f, availableEntries.size * (cardH + cardGap) - cardGap)
        leftScrollOffset = leftScrollOffset.coerceIn(0f, max(0f, leftTotalH - viewportH))
        rightScrollOffset = rightScrollOffset.coerceIn(0f, max(0f, rightTotalH - viewportH))

        var leftCursorY = listTopY + leftScrollOffset
        var rightCursorY = listTopY + rightScrollOffset
        for (card in cardGroups) {
            if (card.inEnabledColumn) {
                leftCursorY -= cardH
                card.setBounds(leftColX + cardPad, leftCursorY, cardW, cardH)
                card.isVisible = leftCursorY >= listBottomY - 2f && leftCursorY + cardH <= listTopY + 2f
                leftCursorY -= cardGap
            } else {
                rightCursorY -= cardH
                card.setBounds(rightColX + cardPad, rightCursorY, cardW, cardH)
                card.isVisible = rightCursorY >= listBottomY - 2f && rightCursorY + cardH <= listTopY + 2f
                rightCursorY -= cardGap
            }
        }
    }

    /** Trigger helper for headless unit tests (`up`, `down`, `enable`, `disable`, `options`). */
    fun triggerPackActionForTest(packId: String, action: String): Boolean {
        val card = cardGroups.firstOrNull { it.entry.id == packId } ?: return false
        if (action == "options") {
            if (busy && !card.entry.hasPureUiThemeOptionsOnly) return false
            if (inMatchMode && !card.entry.hasInMatchEditableOptions) return false
            return card.triggerAction(action)
        }
        if (inMatchMode || busy) return false
        return card.triggerAction(action)
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        val prefix = if (kind == PackManagerKind.RESOURCE_PACKS) "resource" else "content"
        val out = ArrayList<DrawnAction>(32)
        if (showPathImportBar && !inMatchMode) {
            if (kind == PackManagerKind.RESOURCE_PACKS) {
                out += DrawnAction("resource-directory", pathInput, pathInput.text, enabled = pathInput.enabled)
                out += DrawnAction("resource-import", importButton, UiStrings.get("settings.packs.importBtn"), enabled = importButton.enabled)
                extractButton?.let {
                    out += DrawnAction("resource-extract", it, UiStrings.get("settings.packs.extractBtn"), enabled = it.enabled)
                }
            } else {
                out += DrawnAction("content-directory", pathInput, pathInput.text, enabled = pathInput.enabled)
                out += DrawnAction("content-check", importButton, UiStrings.get("settings.packs.importBtn"), enabled = importButton.enabled)
            }
        }
        for (card in cardGroups) {
            if (card.isVisible) {
                out += card.drawnActions(prefix)
            }
        }
        return out
    }

    private fun ensureArrowRegion(dir: ArrowDir): TextureRegion? {
        if (Gdx.gl == null) return null
        arrowTexCache[dir]?.let { return TextureRegion(it) }
        val size = 24
        val sub = 4
        val pm = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val tex = try {
            for (py in 0 until size) {
                for (px in 0 until size) {
                    var inside = 0
                    for (sy in 0 until sub) {
                        val ny = (py + (sy + 0.5f) / sub) / size.toFloat()
                        for (sx in 0 until sub) {
                            val nx = (px + (sx + 0.5f) / sub) / size.toFloat()
                            val hit = when (dir) {
                                ArrowDir.DOWN -> ny in 0.22f..0.78f && kotlin.math.abs(nx - 0.5f) <= ((0.78f - ny) / 0.56f) * 0.36f
                                ArrowDir.UP -> ny in 0.22f..0.78f && kotlin.math.abs(nx - 0.5f) <= ((ny - 0.22f) / 0.56f) * 0.36f
                                ArrowDir.LEFT -> nx in 0.22f..0.78f && kotlin.math.abs(ny - 0.5f) <= ((nx - 0.22f) / 0.56f) * 0.36f
                                ArrowDir.RIGHT -> nx in 0.22f..0.78f && kotlin.math.abs(ny - 0.5f) <= ((0.78f - nx) / 0.56f) * 0.36f
                            }
                            if (hit) inside++
                        }
                    }
                    if (inside > 0) {
                        pm.drawPixel(px, py, Color.rgba8888(1f, 1f, 1f, inside.toFloat() / (sub * sub).toFloat()))
                    }
                }
            }
            Texture(pm).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
        } finally {
            pm.dispose()
        }
        arrowTexCache[dir] = tex
        return TextureRegion(tex)
    }

    private fun ensureGearRegion(): TextureRegion? {
        if (Gdx.gl == null) return null
        gearTex?.let { return TextureRegion(it) }
        val size = 24
        val sub = 4
        val pm = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val tex = try {
            for (py in 0 until size) {
                for (px in 0 until size) {
                    var inside = 0
                    for (sy in 0 until sub) {
                        val ny = (py + (sy + 0.5f) / sub) / size.toFloat() - 0.5f
                        for (sx in 0 until sub) {
                            val nx = (px + (sx + 0.5f) / sub) / size.toFloat() - 0.5f
                            val r = kotlin.math.sqrt(nx * nx + ny * ny)
                            val theta = kotlin.math.atan2(ny.toDouble(), nx.toDouble())
                            val toothWave = kotlin.math.cos(theta * 8.0)
                            val outerR = if (toothWave >= 0.25) 0.42f else 0.31f
                            if (r in 0.14f..outerR) inside++
                        }
                    }
                    if (inside > 0) {
                        pm.drawPixel(px, py, Color.rgba8888(1f, 1f, 1f, inside.toFloat() / (sub * sub).toFloat()))
                    }
                }
            }
            Texture(pm).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
        } finally {
            pm.dispose()
        }
        gearTex = tex
        return TextureRegion(tex)
    }

    private fun ensureBadgeTexture(entry: DualColumnPackEntry, theme: UiTheme): Texture? {
        if (Gdx.gl == null) return null
        val customPath = entry.customIconPath
        if (customPath != null) {
            customIconTexCache[customPath]?.let { return it }
            val loaded = runCatching {
                Texture(Gdx.files.absolute(customPath.toString())).also {
                    it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                }
            }.getOrNull()
            if (loaded != null) {
                customIconTexCache[customPath] = loaded
                return loaded
            }
        }

        val key = entry.badgeStyle to theme.id
        badgeTexCache[key]?.let { return it }
        val s = 44
        val pm = Pixmap(s, s, Pixmap.Format.RGBA8888)
        fun fill(rx: Int, ry: Int, rw: Int, rh: Int, col: Color) {
            pm.setColor(col.r, col.g, col.b, 1f)
            pm.fillRectangle(rx, ry, rw, rh)
        }
        val tex = try {
            fill(0, 0, s, s, theme.outGame.packThumbBg)
            when (entry.badgeStyle) {
                PackIconBadgeStyle.BASE_RESOURCE -> {
                    fill(6, 8, 4, 28, theme.colors.rust)
                    fill(14, 13, 22, 18, theme.colors.line)
                    fill(18, 17, 14, 10, theme.colors.boneHi)
                }
                PackIconBadgeStyle.ORIGINAL_RESOURCE -> {
                    fill(8, 11, 24, 5, theme.colors.dim)
                    fill(8, 28, 24, 5, theme.colors.dim)
                    fill(11, 15, 18, 14, theme.outGame.ally)
                    fill(24, 20, 14, 4, theme.colors.boneHi)
                }
                PackIconBadgeStyle.CUSTOM_RESOURCE -> {
                    fill(5, 6, 34, 9, theme.colors.boneHi)
                    fill(5, 19, 15, 19, theme.colors.rust)
                    fill(23, 19, 16, 8, theme.colors.value)
                    fill(23, 30, 16, 8, theme.colors.line)
                }
                PackIconBadgeStyle.BASE_CONTENT -> {
                    fill(5, 6, 5, 32, theme.colors.rust)
                    fill(14, 10, 23, 7, theme.colors.boneHi)
                    fill(14, 20, 17, 6, theme.colors.rust)
                    fill(14, 29, 23, 7, theme.colors.boneHi)
                }
                PackIconBadgeStyle.CUSTOM_CONTENT -> {
                    fill(11, 8, 22, 14, theme.colors.amber)
                    fill(15, 12, 14, 6, theme.colors.boneHi)
                    fill(13, 25, 7, 11, theme.colors.value)
                    fill(24, 25, 7, 11, theme.colors.value)
                }
            }
            Texture(pm).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        } finally {
            pm.dispose()
        }
        badgeTexCache[key] = tex
        return tex
    }

    override fun close() {
        arrowTexCache.values.forEach { runCatching { it.dispose() } }
        arrowTexCache.clear()
        gearTex?.let { runCatching { it.dispose() } }
        gearTex = null
        badgeTexCache.values.forEach { runCatching { it.dispose() } }
        badgeTexCache.clear()
        customIconTexCache.values.forEach { runCatching { it.dispose() } }
        customIconTexCache.clear()
    }

    private inner class PackCardGroup(
        val entry: DualColumnPackEntry,
        val inEnabledColumn: Boolean,
        val canMoveUp: Boolean,
        val canMoveDown: Boolean,
    ) : Group() {

        private val optionsActor: GearSquareButton? = if (inEnabledColumn && entry.hasOptions) {
            GearSquareButton { onOpenPackOptions(entry) }
        } else null

        private val upActor: IconSquareButton? = if (inEnabledColumn && !entry.lockedBottom) {
            IconSquareButton(ArrowDir.UP) { onMovePriority(entry.id, -1) }
        } else null

        private val downActor: IconSquareButton? = if (inEnabledColumn && !entry.lockedBottom) {
            IconSquareButton(ArrowDir.DOWN) { onMovePriority(entry.id, +1) }
        } else null

        private val transferActor: IconSquareButton? = if (!entry.lockedBottom) {
            if (inEnabledColumn) {
                IconSquareButton(ArrowDir.RIGHT) { onSetPackEnabled(entry.id, false) }
            } else {
                IconSquareButton(ArrowDir.LEFT) { onSetPackEnabled(entry.id, true) }
            }
        } else null

        init {
            setTransform(false)
            optionsActor?.let(::addActor)
            upActor?.let(::addActor)
            downActor?.let(::addActor)
            transferActor?.let(::addActor)
        }

        fun syncEnabledState(allowEdit: Boolean) {
            optionsActor?.enabled = (entry.hasPureUiThemeOptionsOnly || !busy) && (!inMatchMode || entry.hasInMatchEditableOptions)
            upActor?.enabled = allowEdit && canMoveUp
            downActor?.enabled = allowEdit && canMoveDown
            transferActor?.enabled = allowEdit && (if (inEnabledColumn) entry.canDisable else entry.canEnable)
        }

        fun triggerAction(action: String): Boolean = when (action) {
            "options" -> optionsActor?.triggerClick() == true
            "up" -> upActor?.triggerClick() == true
            "down" -> downActor?.triggerClick() == true
            "disable" -> if (inEnabledColumn) transferActor?.triggerClick() == true else false
            "enable" -> if (!inEnabledColumn) transferActor?.triggerClick() == true else false
            else -> false
        }

        override fun setBounds(x: Float, y: Float, width: Float, height: Float) {
            super.setBounds(x, y, width, height)
            val rightX = width - 8f
            if (inEnabledColumn && entry.lockedBottom) {
                optionsActor?.setBounds(rightX - 28f, (height - 32f) * 0.5f, 28f, 32f)
            } else if (inEnabledColumn && !entry.lockedBottom) {
                transferActor?.setBounds(rightX - 26f, (height - 44f) * 0.5f, 26f, 44f)
                val udX = rightX - 26f - 5f - 26f
                val baseY = (height - 44f) * 0.5f
                downActor?.setBounds(udX, baseY, 26f, 21f)
                upActor?.setBounds(udX, baseY + 23f, 26f, 21f)
                optionsActor?.setBounds(udX - 5f - 26f, baseY, 26f, 44f)
            } else if (!inEnabledColumn) {
                transferActor?.setBounds(rightX - 26f, (height - 44f) * 0.5f, 26f, 44f)
            }
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")

            val bg = if (entry.lockedBottom) theme.outGame.lockedPackBg else theme.colors.well
            OutGameDraw.fillRect(batch, white, x, y, width, height, bg, parentAlpha)
            OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.hair, parentAlpha)

            // Left 44x44 1:1 square pack icon thumbnail
            val thumbSize = 44f
            val thumbX = x + 6f
            val thumbY = y + (height - thumbSize) * 0.5f
            OutGameDraw.fillRect(batch, white, thumbX, thumbY, thumbSize, thumbSize, theme.outGame.packThumbBg, parentAlpha)
            val badgeTex = ensureBadgeTexture(entry, theme)
            if (badgeTex != null) {
                batch.setColor(1f, 1f, 1f, parentAlpha)
                batch.draw(badgeTex, thumbX, thumbY, thumbSize, thumbSize)
            }
            OutGameDraw.strokeRect(batch, white, thumbX, thumbY, thumbSize, thumbSize, 1f, theme.colors.line, parentAlpha)

            // Right reserved width for buttons or `基础常驻` tag
            val rightReservedW = when {
                entry.lockedBottom && optionsActor != null -> 108f
                entry.lockedBottom -> 74f
                inEnabledColumn && optionsActor != null -> 99f
                inEnabledColumn -> 68f
                else -> 38f
            }
            val textX = thumbX + thumbSize + 10f
            val maxTextW = max(60f, width - (textX - x) - rightReservedW)

            val titleFont = fontsProvider()?.command ?: fontsProvider()?.body ?: fallbackFontProvider()
            val titleStr = ellipsizeSingleLine(titleFont, entry.title, maxTextW)
            val prevT = titleFont.color.cpy()
            val strong = theme.outGame.textStrong
            titleFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
            titleFont.draw(batch, titleStr, textX, y + height - 13f)
            titleFont.color = prevT

            val subFont = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
            val rawSub = entry.errorText ?: entry.subtitle
            val subStr = ellipsizeSingleLine(subFont, rawSub, maxTextW)
            val prevS = subFont.color.cpy()
            val subCol = if (entry.errorText != null) theme.colors.foe else theme.colors.dim
            subFont.setColor(subCol.r, subCol.g, subCol.b, subCol.a * parentAlpha)
            subFont.draw(batch, subStr, textX, y + 20f)
            subFont.color = prevS

            if (entry.lockedBottom) {
                val pinText = UiStrings.get("settings.packs.baseLocked")
                glyphLayout.setText(subFont, pinText)
                val prevP = subFont.color.cpy()
                subFont.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
                val pinRightOffset = if (optionsActor != null) 44f else 12f
                subFont.draw(batch, pinText, x + width - pinRightOffset - glyphLayout.width, y + (height + glyphLayout.height) * 0.5f)
                subFont.color = prevP
            }

            batch.packedColor = saved
            super.draw(batch, parentAlpha)
        }

        private fun ellipsizeSingleLine(font: BitmapFont, raw: String, maxW: Float): String {
            glyphLayout.setText(font, raw)
            if (glyphLayout.width <= maxW) return raw
            var trimmed = raw
            while (trimmed.length > 1) {
                trimmed = trimmed.dropLast(1)
                glyphLayout.setText(font, "$trimmed…")
                if (glyphLayout.width <= maxW) return "$trimmed…"
            }
            return raw.take(1)
        }

        fun drawnActions(prefix: String): List<DrawnAction> = buildList {
            optionsActor?.let { add(DrawnAction("$prefix-pack-options:${entry.id}", it, UiStrings.get("settings.packs.optionsBtn"), enabled = it.enabled)) }
            upActor?.let { add(DrawnAction("$prefix-pack-up:${entry.id}", it, UiStrings.get("settings.packs.moveUp"), enabled = it.enabled)) }
            downActor?.let { add(DrawnAction("$prefix-pack-down:${entry.id}", it, UiStrings.get("settings.packs.moveDown"), enabled = it.enabled)) }
            transferActor?.let {
                val actName = if (inEnabledColumn) "disable" else "enable"
                val label = UiStrings.get(if (inEnabledColumn) "settings.packs.disable" else "settings.packs.enable")
                add(DrawnAction("$prefix-pack-$actName:${entry.id}", it, label, enabled = it.enabled))
            }
        }
    }

    private inner class GearSquareButton(
        private val onClick: () -> Unit,
    ) : Actor() {
        var enabled: Boolean = true
        private var hovered = false
        private var pressed = false

        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) hovered = true
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) hovered = false
                }

                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || !enabled) return false
                    pressed = true
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0) return
                    val wasPressed = pressed
                    pressed = false
                    if (wasPressed && enabled && x in 0f..width && y in 0f..height) {
                        onClick()
                    }
                }
            })
        }

        fun triggerClick(): Boolean {
            if (!enabled) return false
            onClick()
            return true
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val activeHover = enabled && (hovered || pressed)

            val bg = when {
                !enabled -> theme.colors.well
                activeHover -> theme.colors.cellHover
                else -> theme.colors.cell
            }
            val border = when {
                !enabled -> theme.colors.hair
                activeHover -> theme.colors.value
                else -> theme.colors.line
            }
            val fg = when {
                !enabled -> theme.colors.dim
                activeHover -> theme.colors.boneHi
                else -> theme.colors.text
            }
            val alphaMul = if (enabled) parentAlpha else parentAlpha * 0.48f

            OutGameDraw.fillRect(batch, white, x, y, width, height, bg, alphaMul)
            OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, border, alphaMul)

            val gearReg = ensureGearRegion()
            if (gearReg != null) {
                val iconS = min(15f, min(width, height) - 6f)
                batch.setColor(fg.r, fg.g, fg.b, fg.a * alphaMul)
                batch.draw(gearReg, x + (width - iconS) * 0.5f, y + (height - iconS) * 0.5f, iconS, iconS)
            }
            batch.packedColor = saved
        }
    }

    private inner class IconSquareButton(
        private val dir: ArrowDir,
        private val onClick: () -> Unit,
    ) : Actor() {
        var enabled: Boolean = true
        private var hovered = false
        private var pressed = false

        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) hovered = true
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) hovered = false
                }

                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || !enabled) return false
                    pressed = true
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0) return
                    val wasPressed = pressed
                    pressed = false
                    if (wasPressed && enabled && x in 0f..width && y in 0f..height) {
                        onClick()
                    }
                }
            })
        }

        fun triggerClick(): Boolean {
            if (!enabled) return false
            onClick()
            return true
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val activeHover = enabled && (hovered || pressed)

            val bg = when {
                !enabled -> theme.colors.well
                activeHover -> theme.colors.cellHover
                else -> theme.colors.cell
            }
            val border = when {
                !enabled -> theme.colors.hair
                activeHover -> theme.colors.value
                else -> theme.colors.line
            }
            val fg = when {
                !enabled -> theme.colors.dim
                activeHover -> theme.colors.boneHi
                else -> theme.colors.text
            }
            val alphaMul = if (enabled) parentAlpha else parentAlpha * 0.48f

            OutGameDraw.fillRect(batch, white, x, y, width, height, bg, alphaMul)
            OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, border, alphaMul)

            val arrowReg = ensureArrowRegion(dir)
            if (arrowReg != null) {
                val iconS = min(13f, min(width, height) - 6f)
                batch.setColor(fg.r, fg.g, fg.b, fg.a * alphaMul)
                batch.draw(arrowReg, x + (width - iconS) * 0.5f, y + (height - iconS) * 0.5f, iconS, iconS)
            }
            batch.packedColor = saved
        }
    }
}
