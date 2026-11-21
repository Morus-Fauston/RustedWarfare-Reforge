package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import java.nio.file.Files
import java.nio.file.Path

/** Selection state for independent .tmx skirmish map launch (separate from TestScenarioPanel). */
class SkirmishMapSelectionState {
    data class SelectedTmxMap(
        val path: Path,
        val displayName: String,
        val map: MapDefinition,
        val thumbnailPath: Path?,
        val playerCount: Int,
    )

    var selected: SelectedTmxMap? = null
        private set
    var error: String? = null
        private set

    fun clear() {
        selected = null
        error = null
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

/** Independent TMX skirmish map picker & thumbnail preview panel for StartupPage.MAPS. */
internal class SkirmishMapPanel(
    private val state: SkirmishMapSelectionState,
    private val skin: Skin,
    private val originalDirectoryProvider: () -> Path?,
    private val onChanged: () -> Unit,
) : Table(skin), AutoCloseable {
    private val pathField = TextField("", skin).apply {
        setOnlyFontChars(false)
        maxLength = 512
        messageText = "Enter .tmx map path..."
    }
    private val infoLabel = Label("No custom .tmx map selected (using content pack default).", skin).apply {
        setWrap(true)
        setAlignment(Align.topLeft)
    }
    private val statusLabel = Label("", skin).apply {
        setWrap(true)
        setAlignment(Align.topLeft)
    }
    private var previewTexture: Texture? = null
    private var previewMapPath: Path? = null

    private val previewActor = object : Actor() {
        override fun draw(batch: Batch, parentAlpha: Float) {
            val current = state.selected ?: return
            ensurePreviewTexture(current)
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
        background = skin.newDrawable("white", Color(0.11f, 0.14f, 0.12f, 0.96f))
        pad(14f)
        defaults().left().pad(4f)

        val builtIns = TilesetAtlasResolver.builtInSkirmishMaps()
        val builtInRow = Table(skin)
        for (demo in builtIns) {
            val btn = TextButton(demo.displayName, skin)
            btn.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    pathField.text = demo.path.toString()
                    state.select(demo.path, originalDirectoryProvider(), demo.displayName)
                    refresh()
                    onChanged()
                }
            })
            builtInRow.add(btn).height(34f).padRight(8f)
        }
        val clearBtn = TextButton("Clear TMX", skin).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    state.clear()
                    disposePreview()
                    pathField.text = ""
                    refresh()
                    onChanged()
                }
            })
        }
        builtInRow.add(clearBtn).height(34f)

        val loadPathBtn = TextButton("Load .tmx", skin).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    val raw = pathField.text.trim()
                    if (raw.isEmpty()) {
                        state.clear()
                        disposePreview()
                    } else {
                        state.selectPath(raw, originalDirectoryProvider())
                    }
                    refresh()
                    onChanged()
                }
            })
        }

        add(Label("Skirmish Maps (.tmx) — Built-in Demos & Custom Path", skin)).colspan(2).row()
        add(builtInRow).colspan(2).padBottom(6f).row()
        add(pathField).width(510f).height(34f)
        add(loadPathBtn).width(110f).height(34f).row()
        add(previewActor).size(240f, 170f).padTop(8f)
        val details = Table(skin).apply {
            defaults().left().top().pad(4f)
            add(infoLabel).width(360f).row()
            add(statusLabel).width(360f).row()
        }
        add(details).top().left().padTop(8f).expand().fill().row()
        refresh()
    }

    fun refresh() {
        val current = state.selected
        if (current != null) {
            infoLabel.setText(
                "${current.displayName}\n" +
                    "Size: ${current.map.widthTiles} x ${current.map.heightTiles} tiles\n" +
                    "Players: ${current.playerCount}p | Starting units: ${current.map.startingUnits.size}\n" +
                    "Visual layers: ${current.map.visualLayers.joinToString(", ") { it.name }}"
            )
            statusLabel.setText(state.error ?: "Ready to start match on this .tmx map.")
        } else {
            disposePreview()
            infoLabel.setText("No custom .tmx map selected (match will use content pack default map).")
            statusLabel.setText(state.error ?: "Pick a built-in demo map or enter any standard .tmx file path.")
        }
    }

    private fun ensurePreviewTexture(selected: SkirmishMapSelectionState.SelectedTmxMap) {
        if (previewMapPath == selected.path && previewTexture != null) return
        disposePreview()
        previewMapPath = selected.path
        val thumb = selected.thumbnailPath
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
        val map = selected.map
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
                        map.blocking.isBlocked(col, row) -> 0x3A5838FF
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
        previewMapPath = null
    }

    override fun close() {
        disposePreview()
    }
}
