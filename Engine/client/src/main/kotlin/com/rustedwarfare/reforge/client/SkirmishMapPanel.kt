package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.ScenarioCatalog
import com.rustedwarfare.reforge.core.sim.ScenarioCatalogEntry
import com.rustedwarfare.reforge.core.sim.ScenarioCategory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unified selection state for `.tmx` skirmish maps AND `ScenarioCatalog` built-in/benchmark scenarios
 * (ADR-012 / ENG-P016-010).
 */
class SkirmishMapSelectionState {
    data class SelectedTmxMap(
        val path: Path,
        val displayName: String,
        val map: MapDefinition,
        val thumbnailPath: Path?,
        val playerCount: Int,
    )

    data class SelectedCatalogScenario(
        val entry: ScenarioCatalogEntry,
        val map: MapDefinition,
    )

    var selected: SelectedTmxMap? = null
        private set
    var selectedCatalog: SelectedCatalogScenario? = null
        private set
    var error: String? = null
        private set

    val hasSelection: Boolean get() = selected != null || selectedCatalog != null

    val activeMap: MapDefinition?
        get() = selected?.map ?: selectedCatalog?.map

    val activeDisplayName: String?
        get() = selected?.displayName ?: selectedCatalog?.entry?.displayName

    val activePreviewSummary: String?
        get() = selected?.let {
            "${it.displayName}\n${it.map.widthTiles} x ${it.map.heightTiles} tiles (${it.playerCount}p, ${it.map.startingUnits.size} units)"
        } ?: selectedCatalog?.let {
            val e = it.entry
            val badge = if (e.knownLimitation != null) " [Limit]" else " [${e.group}]"
            "${e.displayName}$badge\n${it.map.widthTiles} x ${it.map.heightTiles} tiles (${e.playerCount}p, ${e.startingUnitCount} units)"
        }

    fun clear() {
        selected = null
        selectedCatalog = null
        error = null
    }

    fun selectCatalog(sceneId: String): Boolean {
        val entry = ScenarioCatalog.byId(sceneId)
        if (entry == null) {
            error = "Unknown scenario catalog id: $sceneId"
            return false
        }
        return selectCatalog(entry)
    }

    fun selectCatalog(entry: ScenarioCatalogEntry): Boolean {
        selected = null
        selectedCatalog = SelectedCatalogScenario(entry, entry.buildMap())
        error = null
        return true
    }

    fun selectPath(rawPath: String, originalDirectory: Path?, displayNameOverride: String? = null): Boolean {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            clear()
            return false
        }
        val parsed = try {
            Path.of(trimmed)
        } catch (e: java.nio.file.InvalidPathException) {
            error = "Invalid TMX path: ${e.message ?: trimmed}"
            return false
        }
        return select(parsed, originalDirectory, displayNameOverride)
    }

    fun select(tmxPath: Path, originalDirectory: Path?, displayNameOverride: String? = null): Boolean {
        val absolute = tmxPath.toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolute)) {
            error = "TMX file not found: $absolute"
            return false
        }
        return when (val loaded = ContentLoader.loadMap(absolute, TmxSkirmishScenarioBuilder.tilesetFallbackRoots(originalDirectory))) {
            is ContentResult.Failure -> {
                error = loaded.error.describe()
                false
            }
            is ContentResult.Success -> {
                val map = loaded.value
                val baseName = absolute.fileName.toString().removeSuffix(".tmx")
                val siblingThumb = absolute.resolveSibling("${baseName}_map.png").takeIf { Files.isRegularFile(it) }
                val teams = map.startingUnits.mapNotNull { it.team?.trim()?.toIntOrNull() }.filter { it >= 0 }.distinct()
                val players = maxOf(2, teams.size)
                selectedCatalog = null
                selected = SelectedTmxMap(
                    path = absolute,
                    displayName = displayNameOverride ?: baseName,
                    map = map,
                    thumbnailPath = siblingThumb,
                    playerCount = players,
                )
                error = null
                true
            }
        }
    }
}

/**
 * Fused categorized scrollable map & scenario picker panel for `StartupPage.MAPS` (ENG-P016-010).
 *
 * Unifies built-in `.tmx` skirmish maps, custom `.tmx` path loading, `ScenarioCatalog` P016
 * benchmarks, and discovered sandbox presets under ADR-014 `UiTheme` styling.
 */
internal class SkirmishMapPanel(
    private val state: SkirmishMapSelectionState,
    private val skin: Skin,
    private val originalDirectoryProvider: () -> Path?,
    private val themeProvider: UiThemeProvider = UiThemeProvider { UiTheme.DEFAULT },
    private val sandboxPresetSeeds: List<Pair<String, Path>> = emptyList(),
    private val onSelectSandboxSeed: ((Path) -> Unit)? = null,
    private val onClearSandboxSeed: (() -> Unit)? = null,
    private val onChanged: () -> Unit,
) : Table(skin), AutoCloseable {

    private enum class FilterTab(val label: String) {
        ALL("All"),
        SKIRMISH("Skirmish"),
        BENCHMARKS("P016"),
        SANDBOX("Sandbox"),
    }

    private sealed class ListItem(val id: String, val label: String, val filter: FilterTab) {
        class TmxDemo(val demo: TilesetAtlasResolver.BuiltInDemoMap) :
            ListItem("tmx:${demo.id}", "[TMX] ${demo.displayName}", FilterTab.SKIRMISH)

        class CatalogBench(val entry: ScenarioCatalogEntry) :
            ListItem(
                "cat:${entry.id}",
                (if (entry.knownLimitation != null) "[Limit] " else "[${entry.group}] ") + entry.displayName,
                FilterTab.BENCHMARKS,
            )

        class SandboxSeed(val title: String, val seed: Path) :
            ListItem("sb:$title", "[Sandbox] $title", FilterTab.SANDBOX)
    }

    private var activeFilter: FilterTab = FilterTab.ALL
    private var activeSandboxTitle: String? = null
    private var lastAppliedTheme: UiTheme? = null
    private var lastRenderedListSignature: String? = null
    private val allItems: List<ListItem>
    private val filterButtons = LinkedHashMap<FilterTab, TextButton>()
    private val titleLabel = Label("Maps & Scenarios (Skirmish .tmx / P016 Benchmarks / Sandbox)", skin)
    private val listTable = Table(skin)
    private val listScroll: ScrollPane
    private val pathField = TextField("", skin).apply {
        setOnlyFontChars(false)
        maxLength = 512
        messageText = "Enter custom .tmx map path..."
    }
    private val infoLabel = Label("Select a map or benchmark scenario from the list.", skin).apply {
        setWrap(true)
        setAlignment(Align.topLeft)
    }
    private val statusLabel = Label("", skin).apply {
        setWrap(true)
        setAlignment(Align.topLeft)
    }
    private var previewTexture: Texture? = null
    private var previewKey: String? = null

    private val previewActor = object : Actor() {
        override fun draw(batch: Batch, parentAlpha: Float) {
            val map = state.activeMap ?: return
            val key = state.selected?.path?.toString() ?: state.selectedCatalog?.entry?.id ?: return
            val thumb = state.selected?.thumbnailPath
            ensurePreviewTexture(key, map, thumb)
            val tex = previewTexture ?: return
            val vp = stage?.viewport
            val pxX = if (vp != null && vp.worldWidth > 0f && vp.screenWidth > 0) vp.screenWidth.toFloat() / vp.worldWidth else 1f
            val pxY = if (vp != null && vp.worldHeight > 0f && vp.screenHeight > 0) vp.screenHeight.toFloat() / vp.worldHeight else 1f
            val pxRatio = minOf((width * pxX) / tex.width.toFloat(), (height * pxY) / tex.height.toFloat())
            val drawW = (tex.width * pxRatio) / pxX
            val drawH = (tex.height * pxRatio) / pxY
            val dx = x + (width - drawW) * 0.5f
            val dy = y + (height - drawH) * 0.5f
            val oldColor = batch.packedColor
            batch.setColor(1f, 1f, 1f, parentAlpha)
            batch.draw(tex, dx, dy, drawW, drawH)
            batch.packedColor = oldColor
        }
    }

    init {
        val theme = themeProvider.currentTheme()
        background = skin.newDrawable("white", Color(theme.colors.rail.r, theme.colors.rail.g, theme.colors.rail.b, 0.96f))
        pad(12f)
        defaults().left().pad(3f)

        allItems = buildList {
            for (demo in TilesetAtlasResolver.builtInSkirmishMaps()) {
                add(ListItem.TmxDemo(demo))
            }
            for (entry in ScenarioCatalog.devBenchmarkEntries()) {
                add(ListItem.CatalogBench(entry))
            }
            for ((title, seed) in sandboxPresetSeeds) {
                if (Files.isRegularFile(seed)) {
                    add(ListItem.SandboxSeed(title, seed))
                }
            }
        }

        val filterRow = Table(skin)
        for (tab in FilterTab.entries) {
            val btn = TextButton(tab.label, skin)
            btn.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    activeFilter = tab
                    rebuildList()
                }
            })
            filterButtons[tab] = btn
            filterRow.add(btn).height(30f).padRight(6f)
        }
        val clearBtn = TextButton("Clear", skin).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    activeSandboxTitle = null
                    state.clear()
                    disposePreview()
                    pathField.text = ""
                    onClearSandboxSeed?.invoke()
                    refresh()
                    onChanged()
                }
            })
        }
        filterRow.add(clearBtn).height(30f)

        listTable.top().left()
        listScroll = ScrollPane(listTable, ScrollPane.ScrollPaneStyle().apply {
            background = skin.newDrawable("white", theme.colors.well)
            vScroll = skin.newDrawable("white", theme.colors.scrollTrack).apply { minWidth = 7f }
            vScrollKnob = skin.newDrawable("white", theme.colors.amber).apply { minWidth = 7f; minHeight = 24f }
        }).apply {
            setScrollingDisabled(true, false)
            setFadeScrollBars(false)
            setSmoothScrolling(false)
            setOverscroll(false, false)
            addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) stage?.scrollFocus = this@apply
                }
            })
        }

        val loadPathBtn = TextButton("Load .tmx", skin).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    activeSandboxTitle = null
                    val raw = pathField.text.trim()
                    if (raw.isEmpty()) {
                        state.clear()
                        disposePreview()
                        onClearSandboxSeed?.invoke()
                    } else {
                        state.selectPath(raw, originalDirectoryProvider())
                    }
                    refresh()
                    onChanged()
                }
            })
        }

        titleLabel.color = theme.colors.text
        infoLabel.color = theme.colors.text
        infoLabel.setFontScale(0.86f)
        statusLabel.color = theme.colors.amber
        statusLabel.setFontScale(0.84f)

        val rightPane = Table(skin).apply {
            clip = true
            top().left()
            defaults().left().top().pad(2f)
            add(previewActor).size(240f, 104f).padBottom(4f).row()
            add(infoLabel).width(276f).row()
            add(statusLabel).width(276f).padTop(2f).row()
        }

        val pathRow = Table(skin).apply {
            defaults().left().padRight(6f)
            add(pathField).width(512f).height(30f)
            add(loadPathBtn).width(110f).height(30f)
        }

        add(titleLabel).colspan(2).row()
        add(filterRow).colspan(2).padBottom(3f).row()
        add(listScroll).width(338f).height(282f).top().left().padRight(8f)
        add(rightPane).width(284f).height(282f).top().left().row()
        add(pathRow).colspan(2).padTop(4f).row()

        rebuildList()
        refresh()
    }

    private fun rebuildList() {
        val theme = themeProvider.currentTheme()
        for ((tab, btn) in filterButtons) {
            btn.color = if (tab == activeFilter) theme.colors.amber else Color.WHITE
        }
        listTable.clearChildren()
        val visible = allItems.filter { activeFilter == FilterTab.ALL || it.filter == activeFilter }
        val selectedTmxPath = state.selected?.path
        val selectedCatId = state.selectedCatalog?.entry?.id
        for (item in visible) {
            val isSelected = when (item) {
                is ListItem.TmxDemo -> selectedTmxPath != null && selectedTmxPath == item.demo.path.toAbsolutePath().normalize()
                is ListItem.CatalogBench -> selectedCatId == item.entry.id
                is ListItem.SandboxSeed -> activeSandboxTitle == item.title && !state.hasSelection
            }
            val rowBtn = TextButton(item.label, skin).apply {
                label.setAlignment(Align.left)
                label.setEllipsis(true)
                label.setFontScale(0.88f)
                pad(4f, 8f, 4f, 8f)
                color = if (isSelected) theme.colors.amber else Color.WHITE
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent, actor: Actor) {
                        when (item) {
                            is ListItem.TmxDemo -> {
                                activeSandboxTitle = null
                                pathField.text = item.demo.path.toString()
                                state.select(item.demo.path, originalDirectoryProvider(), item.demo.displayName)
                            }
                            is ListItem.CatalogBench -> {
                                activeSandboxTitle = null
                                pathField.text = ""
                                state.selectCatalog(item.entry)
                            }
                            is ListItem.SandboxSeed -> {
                                activeSandboxTitle = item.title
                                pathField.text = ""
                                state.clear()
                                disposePreview()
                                onSelectSandboxSeed?.invoke(item.seed)
                            }
                        }
                        refresh()
                        onChanged()
                    }
                })
            }
            listTable.add(rowBtn).width(316f).height(29f).padBottom(3f).left().row()
        }
    }

    private fun applyThemeIfChanged(theme: UiTheme) {
        if (lastAppliedTheme == theme) return
        lastAppliedTheme = theme
        background = skin.newDrawable("white", Color(theme.colors.rail.r, theme.colors.rail.g, theme.colors.rail.b, 0.96f))
        titleLabel.color = theme.colors.text
        infoLabel.color = theme.colors.text
        statusLabel.color = theme.colors.amber
        listScroll.style = ScrollPane.ScrollPaneStyle(listScroll.style).apply {
            background = skin.newDrawable("white", theme.colors.well)
            vScroll = skin.newDrawable("white", theme.colors.scrollTrack).apply { minWidth = 7f }
            vScrollKnob = skin.newDrawable("white", theme.colors.amber).apply { minWidth = 7f; minHeight = 24f }
        }
        lastRenderedListSignature = null
    }

    fun refresh() {
        val theme = themeProvider.currentTheme()
        applyThemeIfChanged(theme)
        val sig = "${activeFilter.name}|${state.selected?.path}|${state.selectedCatalog?.entry?.id}|$activeSandboxTitle|${theme.id}"
        if (sig != lastRenderedListSignature) {
            lastRenderedListSignature = sig
            rebuildList()
        }
        val currentTmx = state.selected
        val currentCat = state.selectedCatalog
        when {
            currentTmx != null -> {
                infoLabel.setText(
                    "${currentTmx.displayName}\n" +
                        "Category: Skirmish (.tmx)\n" +
                        "Size: ${currentTmx.map.widthTiles} x ${currentTmx.map.heightTiles} tiles\n" +
                        "Players: ${currentTmx.playerCount}p | Starting units: ${currentTmx.map.startingUnits.size}"
                )
                statusLabel.setText(state.error ?: "Ready — click Back then Start.")
            }
            currentCat != null -> {
                val e = currentCat.entry
                infoLabel.setText(
                    "${e.displayName}\n" +
                        "Family: ${e.family} (Group ${e.group})\n" +
                        "Size: ${currentCat.map.widthTiles} x ${currentCat.map.heightTiles} tiles | ${e.playerCount}p | ${e.startingUnitCount} units\n" +
                        e.description
                )
                statusLabel.setText(
                    state.error
                        ?: e.knownLimitation
                        ?: "Ready — click Back then Start."
                )
            }
            activeSandboxTitle != null -> {
                disposePreview()
                infoLabel.setText("Sandbox Preset: $activeSandboxTitle")
                statusLabel.setText("Selected via Development preset. Return to Setup and click Start.")
            }
            else -> {
                disposePreview()
                infoLabel.setText("No custom map or benchmark selected (match uses default content pack map).")
                statusLabel.setText(state.error ?: "Pick any Skirmish .tmx map, P016 benchmark scenario, or enter a .tmx path.")
            }
        }
    }

    private fun ensurePreviewTexture(key: String, map: MapDefinition, thumb: Path?) {
        if (previewKey == key && previewTexture != null) return
        disposePreview()
        previewKey = key
        if (thumb != null && Files.isRegularFile(thumb)) {
            val loadedThumb = runCatching {
                val pm = Pixmap(com.badlogic.gdx.Gdx.files.absolute(thumb.toString()))
                try {
                    Texture(pm).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
                } finally {
                    pm.dispose()
                }
            }.getOrNull()
            if (loadedThumb != null) {
                previewTexture = loadedThumb
                return
            }
        }
        val w = map.widthTiles.coerceAtLeast(1)
        val h = map.heightTiles.coerceAtLeast(1)
        val pm = Pixmap(w, h, Pixmap.Format.RGBA8888)
        try {
            pm.blending = Pixmap.Blending.None
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val rgba = when {
                        map.isResourcePool(col, row) -> 0xE2BA3EFF.toInt()
                        !map.waterBlocking.isBlocked(col, row) && map.blocking.isBlocked(col, row) -> 0x286A9CFF
                        map.hoverBlocking.isBlocked(col, row) && !map.overCliffWaterBlocking.isBlocked(col, row) -> 0x625E58FF
                        map.blocking.isBlocked(col, row) -> 0x4A4E48FF
                        else -> 0x4E7A44FF
                    }
                    pm.drawPixel(col, row, rgba)
                }
            }
            previewTexture = Texture(pm).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        } finally {
            pm.dispose()
        }
    }

    fun disposePreview() {
        previewTexture?.dispose()
        previewTexture = null
        previewKey = null
    }

    override fun close() {
        disposePreview()
    }
}
