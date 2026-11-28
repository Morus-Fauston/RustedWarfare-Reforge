package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
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
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared fullscreen map selector page (`MapPicker.open(currentMapId, filter, onMapSelected)`, UI Supplement §4.3).
 *
 * Structure:
 * - Symmetric wide sub-page layout ([SubPageTopBar] + [OutGamePanelShell]);
 * - Scrollable multi-column grid of **`1:1` square map cards** showing terrain, ore pools, and colored spawn markers;
 * - Bottom-right localized map title (`dark` theme bottom gradient shadow vs `white`/`bone` crisp label pill);
 * - `2px` `theme.colors.rust` selected highlight; cancelling via `← 返回` or `Esc`/`BACK` preserves previous selection.
 */
class MapPickerScreen(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onNavigateOpen: () -> Unit,
    private val onNavigateClose: () -> Unit,
) : Group(), AutoCloseable {

    var selectedMapId: String = OutGameMapCatalog.defaultEntry().id
        private set

    var activeFilter: MapPickerFilter = MapPickerFilter.SKIRMISH
        private set

    private data class CardTextureKey(
        val mapId: String,
        val thumbnailPath: java.nio.file.Path?,
        val themeId: String,
        val wellBits: Int,
        val boneHiBits: Int,
        val allyBits: Int,
        val p3Bits: Int,
    )

    private var selectionCallback: ((MapPickerEntry) -> Unit)? = null
    private var entries: List<MapPickerEntry> = OutGameMapCatalog.entries(activeFilter)
    private val textureCache = LinkedHashMap<CardTextureKey, Texture>()
    private val cardActors = mutableListOf<MapCardActor>()
    private var lastGridCols = -1
    private var lastGridCardSize = -1f
    private var lastEntriesSignature = ""
    private var lastAppliedScrollTheme: UiTheme? = null

    val topBar = SubPageTopBar(
        skin = skin,
        titleKey = "mapPicker.title",
        widthMode = OutGameWidthMode.SYMMETRIC_WIDE,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        onBack = { cancel() },
    )

    private val panelShell = OutGamePanelShell(
        skin = skin,
        widthMode = OutGameWidthMode.SYMMETRIC_WIDE,
        themeProvider = themeProvider,
    )

    private var gridPrefWidth: Float = 400f
    private var gridPrefHeight: Float = 400f

    private val gridContainer = object : WidgetGroup() {
        override fun getPrefWidth(): Float = gridPrefWidth
        override fun getPrefHeight(): Float = gridPrefHeight
    }.apply {
        setTransform(false)
    }

    private val scrollPane: ScrollPane?

    private fun safeWhiteDrawable(color: Color, minW: Float = 0f, minH: Float = 0f): Drawable {
        val base = runCatching { skin.newDrawable("white", color) }.getOrElse { BaseDrawable() }
        if (minW > 0f) base.minWidth = minW
        if (minH > 0f) base.minHeight = minH
        return base
    }

    init {
        setTransform(false)
        val initTheme = themeProvider.currentTheme()
        scrollPane = if (Gdx.app != null) {
            ScrollPane(
                gridContainer,
                ScrollPane.ScrollPaneStyle().apply {
                    vScroll = safeWhiteDrawable(initTheme.colors.scrollTrack, minW = 6f)
                    vScrollKnob = safeWhiteDrawable(initTheme.colors.dim, minW = 6f, minH = 28f)
                },
            ).apply {
                setScrollingDisabled(true, false)
                setFadeScrollBars(false)
                setSmoothScrolling(false)
                setOverscroll(false, false)
                addListener(object : InputListener() {
                    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                        if (pointer == -1) stage?.scrollFocus = this@apply
                    }
                })
            }.also { panelShell.addActor(it) }
        } else {
            panelShell.addActor(gridContainer)
            null
        }
        addActor(panelShell)
        addActor(topBar)
    }

    /**
     * Public reusable `MapPicker.open(currentMapId, filter, onMapSelected)` contract (UI Supplement §4.3).
     */
    fun open(
        currentMapId: String?,
        filter: MapPickerFilter = MapPickerFilter.SKIRMISH,
        extraEntries: List<MapPickerEntry> = emptyList(),
        onMapSelected: (MapPickerEntry) -> Unit,
    ) {
        activeFilter = filter
        val base = OutGameMapCatalog.entries(filter)
        val merged = ArrayList<MapPickerEntry>(base.size + extraEntries.size)
        for (extra in extraEntries) {
            if (base.none { it.id == extra.id }) merged += extra
        }
        merged.addAll(base)
        entries = merged
        selectedMapId = currentMapId?.takeIf { id -> entries.any { it.id == id || it.slug == id } }
            ?.let { id -> entries.first { it.id == id || it.slug == id }.id }
            ?: currentMapId
            ?: entries.firstOrNull()?.id.orEmpty()
        selectionCallback = onMapSelected
        lastEntriesSignature = ""
        onNavigateOpen()
    }

    /** Selects [mapId] programmatically or via card click, invoking the registered callback and returning to caller. */
    fun selectMap(mapId: String): Boolean {
        val chosen = entries.firstOrNull { it.id == mapId || it.slug == mapId } ?: return false
        selectedMapId = chosen.id
        val cb = selectionCallback
        selectionCallback = null
        cb?.invoke(chosen)
        onNavigateClose()
        return true
    }

    /** Cancels map selection without mutating the caller's previously selected map. */
    fun cancel() {
        clearPendingSelectionCallback()
        onNavigateClose()
    }

    /** Clears any pending selection callback when leaving `MAP_PICKER` via Esc / system Back without re-popping route. */
    fun clearPendingSelectionCallback() {
        selectionCallback = null
    }

    val hasPendingSelectionCallback: Boolean
        get() = selectionCallback != null

    fun visibleEntries(): List<MapPickerEntry> = entries

    fun updateLayout(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        topBar.updateBounds(stageWidth, stageHeight)
        panelShell.updateShellBounds(stageWidth, stageHeight)

        val theme = themeProvider.currentTheme()
        if (lastAppliedScrollTheme !== theme && scrollPane != null) {
            lastAppliedScrollTheme = theme
            scrollPane.style = ScrollPane.ScrollPaneStyle(scrollPane.style).apply {
                vScroll = safeWhiteDrawable(theme.colors.scrollTrack, minW = 6f)
                vScrollKnob = safeWhiteDrawable(theme.colors.dim, minW = 6f, minH = 28f)
            }
        }

        val padX = if (panelShell.width >= 860f) 28f else 18f
        val padY = if (panelShell.height >= 540f) 24f else 16f
        val viewportW = max(120f, panelShell.width - 4f)
        val viewportH = max(120f, panelShell.height - 4f)
        if (scrollPane != null) {
            scrollPane.setBounds(2f, 2f, viewportW, viewportH)
        } else {
            gridContainer.setBounds(2f, 2f, viewportW, viewportH)
        }

        val cols = if (panelShell.width >= 800f) 4 else 3
        val gap = if (cols == 4) 20f else 14f
        val innerAvailW = max(180f, viewportW - padX * 2f - 10f)
        val cardSize = floor((innerAvailW - gap * (cols - 1)) / cols.toFloat()).coerceAtLeast(110f)
        val sig = entries.joinToString("|") { it.id }

        if (cols != lastGridCols || cardSize != lastGridCardSize || sig != lastEntriesSignature) {
            lastGridCols = cols
            lastGridCardSize = cardSize
            lastEntriesSignature = sig
            rebuildGrid(cols, cardSize, gap, padX, padY, viewportW, viewportH)
        }
    }

    private fun rebuildGrid(cols: Int, cardSize: Float, gap: Float, padX: Float, padY: Float, viewportW: Float, viewportH: Float) {
        gridContainer.clearChildren()
        cardActors.clear()

        if (entries.isEmpty()) {
            gridPrefWidth = viewportW
            gridPrefHeight = viewportH
            gridContainer.setSize(gridPrefWidth, gridPrefHeight)
            gridContainer.invalidateHierarchy()
            return
        }

        val rows = ceil(entries.size / cols.toDouble()).toInt().coerceAtLeast(1)
        val contentH = padY * 2f + rows * cardSize + (rows - 1) * gap
        gridPrefWidth = max(viewportW - 8f, padX * 2f + cols * cardSize + (cols - 1) * gap)
        gridPrefHeight = max(viewportH, contentH)
        gridContainer.setSize(gridPrefWidth, gridPrefHeight)

        entries.forEachIndexed { idx, entry ->
            val colIdx = idx % cols
            val rowIdx = idx / cols
            val card = MapCardActor(entry)
            val cx = padX + colIdx * (cardSize + gap)
            val cy = gridPrefHeight - padY - cardSize - rowIdx * (cardSize + gap)
            card.setBounds(cx, cy, cardSize, cardSize)
            cardActors += card
            gridContainer.addActor(card)
        }
        gridContainer.invalidateHierarchy()
        scrollPane?.layout()
    }

    private fun ensureCardTexture(entry: MapPickerEntry, theme: UiTheme): Texture {
        val key = CardTextureKey(
            mapId = entry.id,
            thumbnailPath = entry.thumbnailPath,
            themeId = theme.id,
            wellBits = Color.rgba8888(theme.colors.well),
            boneHiBits = Color.rgba8888(theme.colors.boneHi),
            allyBits = Color.rgba8888(theme.outGame.ally),
            p3Bits = Color.rgba8888(theme.palettes.playerColor(2)),
        )
        textureCache[key]?.let { return it }
        if (textureCache.size >= 64) {
            val oldest = textureCache.entries.first()
            oldest.value.dispose()
            textureCache.remove(oldest.key)
        }
        val pm = OutGameMapPreviewRenderer.loadOrRenderSquareCardPixmap(entry, 256, theme)
        val tex = try {
            Texture(pm).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
        } finally {
            pm.dispose()
        }
        textureCache[key] = tex
        return tex
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        val out = ArrayList<DrawnAction>(cardActors.size + 1)
        out += topBar.drawnBackAction()
        for (card in cardActors) {
            out += DrawnAction("map-picker-card:${card.entry.id}", card, card.entry.localizedTitle(), enabled = true)
        }
        return out
    }

    override fun close() {
        for (tex in textureCache.values) {
            runCatching { tex.dispose() }
        }
        textureCache.clear()
    }

    private inner class MapCardActor(val entry: MapPickerEntry) : Actor() {
        private val glyphLayout = GlyphLayout()
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
                    if (pointer != 0) return false
                    pressed = true
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0) return
                    val wasPressed = pressed
                    pressed = false
                    if (wasPressed && x in 0f..width && y in 0f..height) {
                        selectMap(entry.id)
                    }
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val savedColor = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val tex = ensureCardTexture(entry, theme)

            // 1:1 tactical map terrain fill (native `*_map.png` thumbnails already bake numbered spawn markers)
            batch.setColor(1f, 1f, 1f, parentAlpha)
            batch.draw(tex, x, y, width, height)

            if (entry.thumbnailPath == null) {
                // Spawn point player-colored square pins for procedural tactical fallback maps
                val pinOuter = 12f
                val pinInner = 8f
                val pinFrameColor = if (theme.colors.well.r < theme.colors.boneHi.r) theme.colors.well else theme.colors.boneHi
                entry.spawnPointsNormalized.forEachIndexed { idx, (nx, ny) ->
                    val px = x + nx * width
                    // Normalized spawn Y is top-down [0..1]; Stage Y is bottom-up
                    val py = y + (1f - ny) * height
                    val pCol = theme.palettes.playerColor(idx)
                    OutGameDraw.fillRect(batch, white, px - pinOuter * 0.5f, py - pinOuter * 0.5f, pinOuter, pinOuter, pinFrameColor, parentAlpha)
                    OutGameDraw.fillRect(batch, white, px - pinInner * 0.5f, py - pinInner * 0.5f, pinInner, pinInner, pCol, parentAlpha)
                }
            }

            // Bottom-right localized map title:
            // - Light themes (`mapCardLabelPillBorder.a >= 0.05f`): crisp bordered pill badge (`white` & `bone`)
            // - Dark theme (`mapCardLabelPillBorder.a < 0.05f`): smooth bottom dark gradient band + shadowed text
            val mapTitle = entry.localizedTitle()
            val pillBg = theme.outGame.mapCardLabelPillBg
            val pillBorder = theme.outGame.mapCardLabelPillBorder
            val font = fontsProvider()?.tooltipTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
            glyphLayout.setText(font, mapTitle)

            if (isPillBadgeStyle(theme)) {
                val padH = 9f
                val padV = 5f
                val pillW = glyphLayout.width + padH * 2f
                val pillH = glyphLayout.height + padV * 2f + 2f
                val pillX = x + width - 8f - pillW
                val pillY = y + 8f
                OutGameDraw.fillRect(batch, white, pillX, pillY, pillW, pillH, pillBg, parentAlpha)
                OutGameDraw.strokeRect(batch, white, pillX, pillY, pillW, pillH, 1f, pillBorder, parentAlpha)

                val prev = font.color.cpy()
                val fg = theme.outGame.mapCardLabelFg
                font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
                font.draw(batch, mapTitle, pillX + padH, pillY + (pillH + glyphLayout.height) * 0.5f)
                font.color = prev
            } else {
                // Smooth bottom dark gradient band (`height = 56f`, mockup `.mp-card::after`)
                val bandH = 56f.coerceAtMost(height * 0.42f)
                val strips = 16
                val stripH = bandH / strips.toFloat()
                val maxAlpha = pillBg.a.coerceIn(0.65f, 0.92f)
                for (s in 0 until strips) {
                    val t = 1f - (s.toFloat() / strips.toFloat())
                    val alpha = (t * t * maxAlpha).coerceIn(0f, 0.92f)
                    val col = Color(pillBg.r, pillBg.g, pillBg.b, alpha)
                    OutGameDraw.fillRect(batch, white, x, y + s * stripH, width, stripH + 0.5f, col, parentAlpha)
                }

                val tx = x + width - 12f - glyphLayout.width
                val ty = y + 11f + glyphLayout.height
                val prev = font.color.cpy()
                font.setColor(pillBg.r, pillBg.g, pillBg.b, 0.94f * parentAlpha)
                font.draw(batch, mapTitle, tx + 1f, ty - 1f)
                val fg = theme.outGame.mapCardLabelFg
                font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
                font.draw(batch, mapTitle, tx, ty)
                font.color = prev
            }

            // Card border: 2px `rust` highlight when selected, 1px `value` on hover, 1px `line` default
            val isSelected = entry.id == selectedMapId
            when {
                isSelected -> {
                    val halo = theme.colors.rust.cpy().apply { a = 0.28f }
                    OutGameDraw.strokeRect(batch, white, x - 1f, y - 1f, width + 2f, height + 2f, 1f, halo, parentAlpha)
                    OutGameDraw.strokeRect(batch, white, x, y, width, height, 2f, theme.colors.rust, parentAlpha)
                }
                hovered -> {
                    OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.value, parentAlpha)
                }
                else -> {
                    OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.line, parentAlpha)
                }
            }
            batch.packedColor = savedColor
        }
    }

    companion object {
        /** True when [theme] styles map card labels as a bordered pill (`white`, `bone`), false for bottom gradient (`dark`). */
        fun isPillBadgeStyle(theme: UiTheme): Boolean = theme.outGame.mapCardLabelPillBorder.a >= 0.05f
    }
}
