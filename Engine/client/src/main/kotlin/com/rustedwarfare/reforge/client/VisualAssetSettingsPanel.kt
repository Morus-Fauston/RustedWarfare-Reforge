package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import java.nio.file.Path

/** Shared pre-match controls. Sources are checked on the worker; GL sees frozen icon paths only. */
class VisualAssetSettingsPanel(private val state: VisualAssetSettingsSession, private val skin: Skin,
    private val fontFactory: ((String) -> BitmapFont?)? = null,
    private val contentChoice: (() -> ContentPackChoice?)? = null, private val changed: () -> Unit) : Group(), AutoCloseable {
    private val list = Table()
    private val detail = Table()
    private val listScroll: ScrollPane
    private val detailScroll: ScrollPane
    private val importPath: TextField
    private val directory: TextField
    private val importButton: TextButton
    private val extractButton: TextButton?
    private val enable: TextButton
    private val up: TextButton
    private val down: TextButton
    private val apply: TextButton
    private val restore: TextButton
    private val cancel: TextButton
    private val notice = Label("", skin).apply { setWrap(true) }
    private val messages: ScrollPane
    private val rowButtons = mutableListOf<TextButton>()
    private val detailLabels = mutableListOf<Label>()
    private var editable = true
    private var updating = false
    private var status: String? = null
    private var previousMessage: String? = null
    private var platformFont: BitmapFont? = null
    private var fontCharacters = emptySet<Char>()
    private var seenRevision = -1L
    private var preview: Texture? = null
    private var previewKey: PreparedResourceImage? = null
    private var previewError: String? = null

    init {
        styles(skin)
        addActor(Image(skin.newDrawable("white", Color(.12f, .15f, .14f, 1f))).apply { setBounds(0f, 0f, 660f, 440f) })
        fun label(text: String, x: Float, y: Float, width: Float, height: Float) = Label(text, skin).also {
            it.setBounds(x, y, width, height); addActor(it)
        }
        label("Resource packs", 16f, 404f, 628f, 24f).setFontScale(1.25f)
        label("Highest priority first. Changes apply to the next match.", 16f, 380f, 628f, 20f)
        fun scroll(table: Table, x: Float, y: Float, width: Float, height: Float) = ScrollPane(table, skin, "visual-assets").also {
            it.setBounds(x, y, width, height); it.setScrollingDisabled(true, false); it.setFadeScrollBars(false); addActor(it)
        }
        list.top().left().pad(5f)
        detail.top().left().pad(7f)
        listScroll = scroll(list, 16f, 206f, 252f, 168f)
        detailScroll = scroll(detail, 280f, 246f, 364f, 128f)
        fun button(text: String, x: Float, y: Float, width: Float, action: () -> Unit) = TextButton(text, skin).also {
            it.setBounds(x, y, width, 32f)
            it.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
                if (editable) { action(); refresh(); this@VisualAssetSettingsPanel.changed() }
            } })
            addActor(it)
        }
        enable = button("Enable", 280f, 206f, 172f) { operate { selected()?.let { state.setEnabled(it.id, !it.enabled) } } }
        up = button("Up", 460f, 206f, 88f) { operate { state.movePack(state.selectedPackId, -1) } }
        down = button("Down", 556f, 206f, 88f) { operate { state.movePack(state.selectedPackId, 1) } }
        val canExtract = state.supportsOriginalResourceExtraction || state.supportsOriginalImageExtraction
        label(if (canExtract) "Resource pack or PC installation directory" else "Local image pack directory or pack.json / pack.hjson", 16f, 180f, 628f, 20f)
        importPath = TextField("", skin, "visual-assets").also {
            it.setOnlyFontChars(false); it.setBounds(16f, 144f, if (canExtract) 320f else 460f, 32f); addActor(it)
        }
        importButton = button("Import pack", if (canExtract) 344f else 484f, 144f, if (canExtract) 120f else 160f) {
            if (!state.busy) try { state.importImagePack(Path.of(importPath.text)) }
            catch (failure: Exception) { state.reportImportError(failure.message ?: failure.toString()) }
        }
        extractButton = if (canExtract) button(if (state.supportsOriginalResourceExtraction) "Extract resources" else "Extract images", 472f, 144f, 172f) {
            if (!state.busy) try {
                if (state.supportsOriginalResourceExtraction) state.extractOriginalResources(Path.of(importPath.text))
                else state.extractOriginalImages(Path.of(importPath.text))
            }
            catch (failure: Exception) { state.reportImportError(failure.message ?: failure.toString()) }
        } else null
        label("Legacy Android unpacked directory (assets/ and res/)", 16f, 120f, 628f, 20f)
        directory = TextField("", skin, "visual-assets").also {
            it.setOnlyFontChars(false); it.setBounds(16f, 86f, 628f, 32f); addActor(it)
        }
        apply = button(if (state.selection.temporary) "Apply (this run)" else "Save and apply", 16f, 48f, 236f) {
            if (!state.busy && capture()) operate { state.apply(contentChoice?.invoke()) }
        }
        restore = button("Restore saved", 260f, 48f, 156f) { operate { state.restoreSaved(contentChoice?.invoke()) } }
        cancel = button("Cancel", 424f, 48f, 112f) { state.cancel(); releasePreview() }
        messages = ScrollPane(notice, skin, "visual-assets").apply {
            setBounds(16f, 4f, 628f, 38f); setScrollingDisabled(true, false); setFadeScrollBars(false)
        }
        addActor(messages)
        directory.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
            if (!updating && editable && !state.busy) { capture(); refresh(); this@VisualAssetSettingsPanel.changed() }
        } })
        importPath.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
            availability()
        } })
        refresh()
    }

    private fun selected() = state.rows.singleOrNull { it.id == state.selectedPackId }
    private fun operate(action: () -> Unit) {
        if (!state.busy) try { action() }
        catch (failure: Exception) { state.fail(failure.message ?: failure.toString()) }
    }
    private fun capture(): Boolean = try {
        val path = directory.text.takeUnless(String::isBlank)?.let(Path::of)
        if (path != state.selection.originalDirectory) state.edit(state.selection.copy(originalDirectory = path))
        true
    } catch (failure: Exception) { state.fail(failure.message ?: failure.toString()); false }

    fun setEditable(value: Boolean) { editable = value; availability() }
    fun setStatus(value: String?) { status = value; availability() }

    private fun refresh() {
        updating = true
        val remembered = state.selection.originalDirectory?.toString().orEmpty()
        if (directory.text != remembered) directory.text = remembered
        updating = false
        val scrollY = listScroll.scrollY
        list.clearChildren(); rowButtons.clear(); detail.clearChildren(); detailLabels.clear()
        for (row in state.rows) {
            val marker = when { row.id == VisualAssetSource.PLACEHOLDER.resourcePackId -> "Base / always last"
                row.enabled -> "On / priority ${state.selection.packs.enabled.indexOf(row.id) + 1}"; else -> "Off" }
            val text = "${row.metadata?.name ?: row.id}\n$marker" + if (row.error != null) " / needs attention" else ""
            val style = TextButton.TextButtonStyle(skin.get(TextButton.TextButtonStyle::class.java)).apply {
                checked = skin.newDrawable("white", Color(.32f, .43f, .35f, 1f))
                platformFont?.let { font = it }
            }
            val button = TextButton(text, style).apply {
                label.setWrap(true); label.setAlignment(Align.left)
                isChecked = row.id == state.selectedPackId
                addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
                    if (editable && !state.busy) { state.selectPack(row.id); refresh(); changed() }
                } })
            }
            rowButtons += button
            list.add(button).width(232f).minHeight(46f).padBottom(4f).fillX().row()
        }
        list.invalidateHierarchy(); listScroll.layout(); listScroll.scrollY = scrollY
        val row = selected()
        updatePreview(row?.icon)
        fun information(text: String) = Label(text, skin).also {
            it.setWrap(true); it.setAlignment(Align.topLeft)
            platformFont?.let { font -> it.style = Label.LabelStyle(it.style).apply { this.font = font } }
            detailLabels += it
        }
        if (row != null) {
            val heading = Table()
            preview?.let { texture ->
                val image = requireNotNull(previewKey)
                val frame = TextureRegion(texture, 0, 0, image.pixelWidth / image.declaration.totalFrames, image.pixelHeight)
                heading.add(Image(TextureRegionDrawable(frame)).apply { setScaling(Scaling.fit) }).size(48f).padRight(8f)
            }
            heading.add(information("${row.metadata?.name ?: row.id}\n${row.id}")).width(if (preview == null) 338f else 282f).left().top()
            detail.add(heading).width(338f).left().padBottom(6f).row()
            row.metadata?.let { metadata ->
                detail.add(information(metadata.description)).width(338f).left().padBottom(6f).row()
                detail.add(information("Format ${metadata.formatVersion} / required ${metadata.requiresResourceFormat}")).width(338f).left().padBottom(6f).row()
            }
            row.manifest?.let { detail.add(information(it.toString())).width(338f).left().padBottom(6f).row() }
            row.error?.let { detail.add(information(it).apply { color = Color(1f, .65f, .58f, 1f) }).width(338f).left().row() }
            previewError?.let { detail.add(information("Icon: $it")).width(338f).left().row() }
        }
        detail.invalidateHierarchy(); detailScroll.layout()
        seenRevision = state.revision
        availability()
    }

    private fun updatePreview(image: PreparedResourceImage?) {
        if (image == previewKey) return
        releasePreview(); previewKey = image
        if (image != null) try { preview = Texture(Gdx.files.absolute(image.path.toString())) }
        catch (failure: Exception) { previewError = failure.message ?: failure.toString() }
    }
    private fun releasePreview() { preview?.dispose(); preview = null; previewKey = null; previewError = null }

    private fun availability() {
        val locked = !editable || state.busy
        directory.isDisabled = locked; importPath.isDisabled = locked
        importButton.isDisabled = locked || importPath.text.isBlank()
        extractButton?.isDisabled = locked || importPath.text.isBlank()
        apply.isDisabled = locked; restore.isDisabled = locked
        cancel.isVisible = editable && state.busy
        rowButtons.forEach { it.isDisabled = locked }
        val row = selected()
        enable.setText(if (row?.enabled == true) "Disable" else "Enable")
        enable.isDisabled = locked || row == null || row.id == VisualAssetSource.PLACEHOLDER.resourcePackId || !row.enabled && !row.canEnable
        val index = state.selection.packs.enabled.indexOf(row?.id)
        up.isDisabled = locked || index <= 0
        down.isDisabled = locked || index < 0 || index == state.selection.packs.enabled.lastIndex
        val message = listOfNotNull(state.operationLabel, state.error, state.diagnostic, status,
            if (state.busy || state.error != null) null else if (state.canStart) "Ready for the next match." else "Apply the selected order before starting.",
            if (state.selection.temporary) "This run only. Saved settings remain unchanged."
            else if (state.hasUnsavedCatalog) "Imported list is not saved. Save and apply to remember it." else null).joinToString("\n")
        notice.setText(message)
        notice.color = if (state.error != null) Color(1f, .65f, .58f, 1f) else Color(.82f, .88f, .83f, 1f)
        val texts = listOf(directory.text, importPath.text, message) + rowButtons.map { it.text.toString() } + detailLabels.map { it.text.toString() }
        val characters = fontCharacters + texts.flatMap { it.toList() }
        if (fontFactory != null && (platformFont == null || characters != fontCharacters)) {
            fontFactory.invoke(characters.joinToString(""))?.let { next ->
                val previous = platformFont
                for (field in listOf(directory, importPath)) field.style = TextField.TextFieldStyle(field.style).apply { font = next }
                for (label in detailLabels + notice) label.style = Label.LabelStyle(label.style).apply { font = next }
                for (button in rowButtons) button.style = TextButton.TextButtonStyle(button.style).apply { font = next }
                platformFont = next; fontCharacters = characters; previous?.dispose()
            }
        }
        if (message != previousMessage) { messages.scrollY = 0f; previousMessage = message }
    }

    override fun act(delta: Float) {
        if (state.poll() || state.revision != seenRevision) { refresh(); changed() }
        if (editable && !state.busy && !state.hasUnappliedChanges && state.error == null) {
            // Content can still be preparing. It must not poison resource selection in that case.
            val content = runCatching { contentChoice?.invoke() }
            if (content.isSuccess && state.ensurePreviews(content.getOrNull())) { refresh(); changed() }
        }
        super.act(delta)
    }

    override fun close() { releasePreview(); platformFont?.dispose(); platformFont = null }

    companion object {
        private fun styles(skin: Skin) {
            if (skin.has("visual-assets", TextField.TextFieldStyle::class.java)) return
            val font = skin.getFont("default-font")
            val text = Color(.9f, .93f, .91f, 1f)
            val background = skin.newDrawable("white", Color(.19f, .24f, .22f, 1f))
            skin.add("visual-assets", TextField.TextFieldStyle(font, text,
                skin.newDrawable("white", Color.WHITE).apply { minWidth = 1f },
                skin.newDrawable("white", Color(.35f, .45f, .38f, 1f)), background).apply {
                disabledFontColor = Color(.55f, .6f, .56f, 1f)
            })
            skin.add("visual-assets", ScrollPane.ScrollPaneStyle().apply {
                this.background = background
                vScrollKnob = skin.newDrawable("white", Color(.45f, .55f, .48f, 1f)).apply { minWidth = 8f }
            })
        }
    }
}
