package com.rustedwarfare.reforge.client

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener

internal class CollisionBoxDevelopmentPanel(skin: Skin, showing: Boolean, changed: (Boolean) -> Unit) : Group() {
    private val entry = TextButton("Development", skin)
    private val toggle = CheckBox("Collision boxes", skin)

    init {
        setBounds(654f, 596f, 290f, 40f)
        isVisible = false
        entry.setBounds(164f, 0f, 126f, 40f)
        toggle.setBounds(0f, 0f, 156f, 40f)
        toggle.setProgrammaticChangeEvents(false)
        toggle.isVisible = false
        toggle.isChecked = showing
        entry.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                toggle.isVisible = !toggle.isVisible
            }
        })
        toggle.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) = changed(toggle.isChecked)
        })
        addActor(entry)
        addActor(toggle)
    }

    fun reset() {
        toggle.isChecked = false
        toggle.isVisible = false
        isVisible = false
    }

    fun drawnActions(): List<DrawnAction> = listOf(
        DrawnAction("collision-development-panel", entry, entry.text.toString(), enabled = isVisible),
        DrawnAction("collision-box-view", toggle, toggle.text.toString(), enabled = isVisible && toggle.isVisible),
    )
}
