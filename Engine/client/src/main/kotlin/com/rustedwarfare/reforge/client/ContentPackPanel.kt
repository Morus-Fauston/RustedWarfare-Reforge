package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import java.nio.file.Path

/** Menu-only, independent of the fixed sandbox folder and visual-source settings. */
class ContentPackPanel(private val state: ContentPackSelectionSession, skin: Skin,
    private val fontFactory: ((String) -> BitmapFont?)? = null, private val changed: () -> Unit) : Group(), AutoCloseable {
    private val directory: TextField
    private val inspect: TextButton
    private val cancel: TextButton
    private val selected: SelectBox<String>
    private val packages: SelectBox<String>
    private val enabled: CheckBox
    private val summary: Label
    private val notice: Label
    private val messages: ScrollPane
    private var editable = true
    private var updating = false
    private var status: String? = null
    private var platformFont: BitmapFont? = null
    private var fontCharacters = emptySet<Char>()
    private var previousMessage: String? = null

    init {
        val background = skin.newDrawable("white", Color(.19f, .24f, .22f, 1f))
        if (!skin.has("content-pack", ScrollPane.ScrollPaneStyle::class.java))
            skin.add("content-pack", ScrollPane.ScrollPaneStyle().apply {
                this.background = background
                vScrollKnob = skin.newDrawable("white", Color(.45f, .55f, .48f, 1f)).apply { minWidth = 8f }
            })
        addActor(Image(skin.newDrawable("white", Color(.12f, .15f, .14f, 1f))).apply { setBounds(0f, 0f, 660f, 440f) })
        fun label(text: String, x: Float, y: Float, width: Float, height: Float) = Label(text, skin).also {
            it.setBounds(x, y, width, height); it.setWrap(true); addActor(it)
        }
        label("Content packs", 20f, 398f, 620f, 28f).setFontScale(1.25f)
        label("Local package directory or pack.json / pack.hjson", 20f, 358f, 620f, 28f)
        directory = TextField(state.inputPath?.toString().orEmpty(), skin).also {
            it.setOnlyFontChars(false); it.setBounds(20f, 314f, 620f, 36f); addActor(it)
        }
        fun button(text: String, x: Float, width: Float, action: () -> Unit) = TextButton(text, skin).also {
            it.setBounds(x, 258f, width, 40f)
            it.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
                if (editable) { action(); availability(); changed() }
            } })
            addActor(it)
        }
        inspect = button("Check package", 20f, 168f) {
            if (!state.busy && capture()) try { state.checkPackage() }
            catch (failure: Exception) { state.fail(failure.message ?: failure.toString()) }
        }
        cancel = button("Cancel check", 204f, 152f, state::cancel)
        button("Disable all packs", 372f, 268f) { state.disableAll(); refresh() }
        packages = SelectBox<String>(skin).also { it.setBounds(20f, 210f, 332f, 36f); addActor(it) }
        selected = SelectBox<String>(skin).also { it.setBounds(20f, 160f, 332f, 40f); addActor(it) }
        enabled = CheckBox(" Use next match", skin).also { it.setBounds(372f, 210f, 268f, 36f); addActor(it) }
        summary = label("", 372f, 160f, 268f, 40f)
        notice = Label("", skin).apply { setWrap(true) }
        messages = ScrollPane(notice, skin, "content-pack").apply {
            setBounds(20f, 20f, 620f, 124f); setScrollingDisabled(true, false); setFadeScrollBars(false)
        }
        addActor(messages)
        directory.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
            if (!updating && editable && !state.busy) { capture(); availability(); changed() }
        } })
        selected.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
            if (!updating && editable && state.canSelect) { state.select(selected.selected); availability(); changed() }
        } })
        packages.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
            if (!updating && editable && !state.busy && state.packages.isNotEmpty()) {
                state.selectPackage(packages.selected); refresh(); changed()
            }
        } })
        enabled.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) {
            if (!updating && editable) { state.enable(enabled.isChecked); refresh(); changed() }
        } })
        refresh()
    }

    private fun capture(): Boolean = try {
        state.edit(directory.text.takeUnless(String::isBlank)?.let(Path::of))
        true
    } catch (failure: Exception) { state.fail(failure.message ?: failure.toString()); false }

    private fun refresh() {
        updating = true
        packages.setItems(*state.packages.map { it.id }.ifEmpty { listOf("Check a package first") }.toTypedArray())
        state.checked?.let { packages.selected = it.id }
        directory.text = state.inputPath?.toString().orEmpty()
        selected.setItems(*state.availableUnits.toList().ifEmpty { listOf("Resolve the enabled set first") }.toTypedArray())
        state.selectedUnitId?.let { selected.selected = it }
        enabled.isChecked = state.enabled
        updating = false
        availability()
    }

    fun setEditable(value: Boolean) { editable = value; availability() }
    fun setStatus(value: String?) { status = value; availability() }
    internal fun closeLists() { packages.hideList(); selected.hideList() }

    /** Read the same actors and labels as the menu; input still goes through the platform. */
    internal fun drawnActions(): List<DrawnAction> {
        val actions = mutableListOf(
        DrawnAction("content-directory", directory, directory.text, enabled = !directory.isDisabled),
        DrawnAction("content-check", inspect, inspect.text.toString(), enabled = !inspect.isDisabled),
        DrawnAction("content-enable", enabled, enabled.text.toString(), enabled = !enabled.isDisabled),
        DrawnAction("content-unit", selected, selected.selected.orEmpty(), enabled = !selected.isDisabled))
        val list = selected.list
        if (list.stage != null && list.isTouchable) {
            val top = list.height - (list.style.background?.topHeight ?: 0f)
            for (index in 0 until list.items.size) {
                val item = list.items[index]
                actions += DrawnAction("content-unit:$item", list, item,
                    enabled = !selected.isDisabled, localBounds = Rectangle(0f,
                        top - (index + 1) * list.itemHeight, list.width, list.itemHeight))
            }
        }
        return actions
    }

    private fun availability() {
        directory.isDisabled = !editable || state.busy
        inspect.isDisabled = !editable || state.busy || directory.text.isBlank()
        cancel.isVisible = editable && state.busy
        selected.isDisabled = !editable || !state.canSelect
        packages.isDisabled = !editable || state.busy || state.packages.isEmpty()
        enabled.isDisabled = !editable || state.busy || !state.enabled && !state.canEnable
        summary.setText("${state.packages.size} checked / ${state.enabledPackages.size} enabled\n${state.availableUnits.size} final units")
        val origin = state.composition?.provenance?.get(state.selectedUnitId)
        val source = origin?.let { "\n${state.selectedUnitId} from ${it.packageId}\n${it.entryPath}" +
            if (it.replacedPackageIds.isEmpty()) "" else "\nReplaces: ${it.replacedPackageIds.joinToString(" -> ")}" }.orEmpty()
        val message = if (state.busy) "Checking metadata, unit capabilities, templates and images..."
            else state.error ?: state.compositionError ?: status ?: when {
                state.enabledPackages.isNotEmpty() && state.canStart -> "Next match: ${state.enabledPackages.joinToString { it.id }}.$source"
                state.canEnable -> "Checked: ${state.checked?.id}. Choose a package above and explicitly enable Use next match for each package. Checking does not enable it."
                state.checked != null -> "The path changed. Check it again, or disable all packs to use the fixed sandbox."
                else -> "Check local declarative packages, then explicitly enable the desired set. Leave all packs off to use the fixed sandbox. Packages cannot execute code."
            }
        notice.setText(message)
        notice.color = if (state.error != null || state.compositionError != null || status != null) Color(1f, .65f, .58f, 1f) else Color(.82f, .88f, .83f, 1f)
        val characters = fontCharacters + directory.text.toSet() + summary.text.toString().toSet() + message.toSet()
        if (fontFactory != null && (platformFont == null || characters != fontCharacters)) {
            fontFactory.invoke(characters.joinToString(""))?.let { next ->
                val old = platformFont
                directory.style = TextField.TextFieldStyle(directory.style).apply { font = next }
                summary.style = Label.LabelStyle(summary.style).apply { font = next }
                notice.style = Label.LabelStyle(notice.style).apply { font = next }
                platformFont = next; fontCharacters = characters; old?.dispose()
            }
        }
        if (message != previousMessage) { messages.scrollY = 0f; previousMessage = message }
    }

    override fun act(delta: Float) {
        if (state.poll()) { refresh(); changed() }
        super.act(delta)
    }

    override fun close() { platformFont?.dispose(); platformFont = null }
}
