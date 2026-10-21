package com.rustedwarfare.reforge.android

import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Read-only TEST observer of the real setup actors; no reflection, input or configuration writes. */
internal class SandboxSetupProbe(private val app: AndroidLauncher) {
    data class State(val frame: Long, val mode: Int, val popupAttached: Boolean, val shared: Boolean,
        val paneX: Float, val paneY: Float, val paneWidth: Float, val paneHeight: Float)
    private val graphics=app.graphics
    private val pending=AtomicBoolean()
    private val ready=AtomicReference<State?>()
    private val failure=AtomicReference<Throwable?>()
    private var stageIdentity: Stage?=null
    private var modeIdentity: SelectBox<*>?=null
    private var shareIdentity: CheckBox?=null

    fun poll(): State? {
        failure.get()?.let { throw it }
        ready.getAndSet(null)?.let { pending.set(false);return it }
        if(pending.compareAndSet(false,true)) {
            app.postRunnable {
                try {
                    check(app.graphics===graphics)
                    val multiplexer=app.input.inputProcessor as? InputMultiplexer ?: error("Expected own setup multiplexer")
                    val stages=multiplexer.processors.filterIsInstance<Stage>()
                    check(stages.size==1)
                    val stage=stages.single()
                    val modes=stage.actors.filterIsInstance<SelectBox<*>>()
                    val shares=stage.actors.filterIsInstance<CheckBox>()
                    check(modes.size==1 && shares.size==1)
                    val mode=modes.single();val share=shares.single()
                    if(stageIdentity==null) { stageIdentity=stage;modeIdentity=mode;shareIdentity=share }
                    check(stage===stageIdentity && mode===modeIdentity && share===shareIdentity)
                    check(!mode.isDisabled && !share.isDisabled && mode.items.toList()==listOf("NoFog","Memory","Hardcore"))
                    val pane=mode.scrollPane
                    ready.set(State(graphics.frameId,mode.selectedIndex,pane.parent!=null,share.isChecked,
                        pane.x,pane.y,pane.width,pane.height))
                } catch(problem: Throwable) { failure.compareAndSet(null,problem) }
            }
        }
        return null
    }
}
