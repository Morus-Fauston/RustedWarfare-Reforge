package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import java.nio.file.Path
import com.badlogic.gdx.graphics.g2d.BitmapFont

/** Shared pre-match controls for the sandbox and demo; all preparation runs outside the GL thread. */
class VisualAssetSettingsPanel(private val state: VisualAssetSettingsSession, skin: Skin,
    private val fontFactory: ((String) -> BitmapFont?)? = null, private val changed: () -> Unit) : Group(), AutoCloseable {
    private val source: SelectBox<String>
    private val directory: TextField
    private val apply: TextButton
    private val cancel: TextButton
    private val notice: Label
    private val messages: ScrollPane
    private val resolved: Label
    private var editable = true
    private var updating = false
    private var status: String? = null
    private var previousMessage: String? = null
    private var platformFont: BitmapFont? = null
    private var fontCharacters: Set<Char> = emptySet()

    init {
        styles(skin)
        addActor(Image(skin.newDrawable("white", Color(.12f, .15f, .14f, 1f))).apply { setBounds(0f, 0f, 660f, 440f) })
        fun label(text: String, x: Float, y: Float, w: Float, h: Float) = Label(text, skin).also {
            it.setBounds(x, y, w, h); it.setWrap(true); addActor(it)
        }
        label("Visual assets", 20f, 397f, 620f, 28f).setFontScale(1.25f)
        source = SelectBox<String>(skin, "visual-assets").also {
            it.setItems("Public placeholder visuals", "Original visuals (Android 1.15 / 176)")
            it.setBounds(20f, 354f, 620f, 34f); addActor(it)
        }
        label("Original unpacked directory (assets/ and res/)", 20f, 314f, 620f, 28f)
        directory = TextField("", skin, "visual-assets").also {
            it.setOnlyFontChars(false); it.setBounds(20f, 275f, 620f, 34f); addActor(it)
        }
        resolved = Label("", skin).apply { setWrap(true) }
        addActor(ScrollPane(resolved, skin, "visual-assets").apply {
            setBounds(20f, 222f, 620f, 46f); setScrollingDisabled(true, false); setFadeScrollBars(false)
        })
        label(if (state.selection.temporary) "Only this run. Saved settings remain unchanged."
            else "Your original directory is remembered when you switch back to placeholders.", 20f, 184f, 620f, 32f)
        fun button(text: String, x: Float, w: Float, action: () -> Unit) = TextButton(text, skin).also {
            it.setBounds(x, 140f, w, 36f)
            it.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) { action() } })
            addActor(it)
        }
        apply = button(if (state.selection.temporary) "Apply (this run)" else "Save and apply", 20f, 270f) {
            if (editable && !state.busy && capture()) { state.apply(); availability(); changed() }
        }
        cancel = button("Cancel", 306f, 128f) { state.cancel(); refresh(); changed() }
        notice = Label("", skin).apply { setWrap(true) }
        messages = ScrollPane(notice, skin, "visual-assets").apply {
            setBounds(20f, 14f, 620f, 114f); setScrollingDisabled(true, false); setFadeScrollBars(false)
        }
        addActor(messages)
        refresh()
        val listener = object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                if (!updating && editable && !state.busy) { capture(); availability(); this@VisualAssetSettingsPanel.changed() }
            }
        }
        source.addListener(listener); directory.addListener(listener)
    }

    private fun capture(): Boolean = try {
        val path = directory.text.takeUnless { it.isBlank() }?.let(Path::of)
        state.edit(state.selection.copy(source = VisualAssetSource.entries[source.selectedIndex], originalDirectory = path))
        true
    } catch (failure: Exception) { state.fail(failure.message ?: failure.toString()); false }

    fun setEditable(value: Boolean) { editable = value; availability() }
    fun setStatus(value: String?) { status = value; availability() }

    private fun refresh() {
        updating = true
        source.selectedIndex = state.selection.source.ordinal
        directory.text = state.selection.originalDirectory?.toString() ?: ""
        updating = false
        availability()
    }

    private fun availability() {
        source.isDisabled = !editable || state.busy
        directory.isDisabled = !editable || state.busy
        apply.isDisabled = !editable || state.busy
        cancel.isVisible = state.busy && editable
        val resolvedText = "Resolved: " + (state.selection.originalDirectory?.toAbsolutePath()?.normalize()?.toString() ?: "application / Rusted Warfare")
        resolved.setText(resolvedText)
        val message = state.error ?: status ?: if (state.busy) "Validating and preparing visuals..."
            else if (state.canStart) "Ready. Start the match using the selected visuals."
            else "Apply this selection before starting. Source errors never fall back automatically."
        notice.setText(message)
        val characters = fontCharacters + directory.text.toSet() + resolvedText.toSet() + message.toSet()
        if (fontFactory != null && (platformFont == null || characters != fontCharacters)) {
            fontFactory.invoke(characters.joinToString(""))?.let { next ->
                val previous = platformFont
                directory.style = TextField.TextFieldStyle(directory.style).apply { font = next }
                resolved.style = Label.LabelStyle(resolved.style).apply { font = next }
                notice.style = Label.LabelStyle(notice.style).apply { font = next }
                platformFont = next; fontCharacters = characters
                previous?.dispose()
            }
        }
        notice.color = if (state.error != null) Color(1f, .65f, .58f, 1f) else Color(.82f, .88f, .83f, 1f)
        if (message != previousMessage) { messages.scrollY = 0f; previousMessage = message }
    }

    override fun act(delta: Float) {
        if (state.poll()) { refresh(); changed() }
        super.act(delta)
    }

    override fun close() { platformFont?.dispose(); platformFont = null }

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
            val scroll = ScrollPane.ScrollPaneStyle().apply {
                this.background = background
                vScrollKnob = skin.newDrawable("white", Color(.45f, .55f, .48f, 1f)).apply { minWidth = 8f }
            }
            skin.add("visual-assets", scroll)
            val list = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(font, text, text,
                skin.newDrawable("white", Color(.32f, .43f, .35f, 1f)))
            skin.add("visual-assets", SelectBox.SelectBoxStyle(font, text, background, scroll, list))
        }
    }
}
