package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.Actor
import java.nio.file.Path

/** A normal menu choice; resources are still selected in the existing resource tab. */
internal class TestScenarioPanel(private val state: TestScenarioMenu, private val seed: Path?,
    private val alternateStart: Path?, skin: Skin,
    private val fontFactory: ((String) -> BitmapFont?)?, private val changed: () -> Unit) : Group(), AutoCloseable {
    private val summary = Label("", skin)
    private val notice = Label("", skin)
    private val choose = TextButton(if (alternateStart == null) "Use this scene" else "Command center", skin)
    private val spider = TextButton("Modular Spider", skin)
    private val custom = TextButton("Custom content", skin)
    private var font: BitmapFont? = null
    private var characters = emptySet<Char>()
    private var editable = true

    init {
        summary.setBounds(24f, 208f, 612f, 172f); summary.setWrap(true); addActor(summary)
        choose.setBounds(24f, 144f, 264f, 40f); addActor(choose)
        if (alternateStart == null) {
            custom.setBounds(312f, 144f, 264f, 40f); addActor(custom)
        } else {
            spider.setBounds(312f, 144f, 264f, 40f); addActor(spider)
        }
        notice.setBounds(24f, 12f, 612f, 112f); notice.setWrap(true); addActor(notice)
        choose.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) { seed?.let(state::choose); changed() }
        })
        custom.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) { state.useCustomContent(); changed() }
        })
        spider.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) { alternateStart?.let(state::choose); changed() }
        })
        refresh(null)
    }

    fun refresh(status: String?) {
        val choice = state.choice
        summary.setText(if (choice == null) "Test scene\nUse this scene to check its map, content and starting units."
            else "${choice.name}\nMap: ${choice.scenario.map.widthTiles * choice.scenario.map.tileWidth} x ${choice.scenario.map.heightTiles * choice.scenario.map.tileHeight}\nContent: ${choice.content.pack.name}\nStart: ${choice.content.unitId} | Credits: ${choice.scenario.economyContract?.initialBalances?.get("p1")}")
        notice.setText(status ?: state.error ?: if (state.selected) "Scene selected. Choose resources in Resource packs, then Start." else "Resources are selected separately in Resource packs.")
        choose.isDisabled = !editable || seed == null
        spider.isDisabled = !editable || alternateStart == null
        custom.isDisabled = !editable || !state.selected
        val nextCharacters = (summary.text.toString() + notice.text.toString()).toSet()
        if (nextCharacters != characters) fontFactory?.invoke(nextCharacters.joinToString(""))?.let { next ->
            val old = font; font = next; characters = nextCharacters
            summary.style = Label.LabelStyle(summary.style).apply { font = next }
            notice.style = Label.LabelStyle(notice.style).apply { font = next }
            old?.dispose()
        }
    }

    fun setEditable(value: Boolean) { editable = value }
    fun drawnActions(): List<DrawnAction> = listOf(
        DrawnAction("test-scene-use", choose, choose.text.toString(), enabled = !choose.isDisabled),
        if (alternateStart == null) DrawnAction("test-scene-custom", custom, custom.text.toString(), enabled = !custom.isDisabled)
        else DrawnAction("test-scene-spider", spider, spider.text.toString(), enabled = !spider.isDisabled))
    override fun close() { font?.dispose(); font = null }
}
