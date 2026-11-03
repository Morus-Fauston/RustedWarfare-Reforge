package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle

/** Uses the current platform's actual audio backend; successful requests are not hearing evidence. */
object GdxPlaybackSoundFactory : PlaybackSoundFactory {
    override fun load(sound: PreparedResourceSound): PlaybackSound {
        val backend = checkNotNull(Gdx.audio) { "Audio backend is unavailable" }
        val loaded = backend.newSound(FileHandle(sound.path.toFile()))
        return object : PlaybackSound {
            override fun play(volume: Float, pitch: Float): Long = loaded.play(volume, pitch, 0f)
            override fun stop() = loaded.stop()
            override fun close() = loaded.dispose()
        }
    }
}
