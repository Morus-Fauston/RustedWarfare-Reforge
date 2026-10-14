package com.rustedwarfare.reforge.client

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.viewport.FitViewport
import com.rustedwarfare.reforge.core.CoreRuntime

/** Engineering entry only; gameplay is implemented by subsequent tickets. */
class BootstrapScreen : ApplicationAdapter() {
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var viewport: FitViewport

    override fun create() {
        CoreRuntime.start()
        batch = SpriteBatch()
        font = BitmapFont()
        viewport = FitViewport(480f, 270f)
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun render() {
        Gdx.gl.glClearColor(0.08f, 0.09f, 0.11f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
        batch.begin()
        font.draw(batch, "RustedWarfare-Reforge", 24f, viewport.worldHeight - 32f)
        font.draw(batch, "Engine entry ready.", 24f, viewport.worldHeight - 64f)
        font.draw(batch, "Movement sandbox is not implemented yet.", 24f, viewport.worldHeight - 96f)
        batch.end()
    }

    override fun dispose() {
        font.dispose()
        batch.dispose()
    }
}
