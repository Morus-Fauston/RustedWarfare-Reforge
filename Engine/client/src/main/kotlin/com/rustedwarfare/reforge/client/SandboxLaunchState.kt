package com.rustedwarfare.reforge.client

import java.nio.file.Path

data class SandboxConfiguration(val root: Path, val options: SandboxOptions)
data class SandboxLoad(val generation: Long, val configuration: SandboxConfiguration)

/** Owned by the presentation thread; a cancelled generation can never become a match. */
class SandboxLaunchState(initial: SandboxConfiguration) {
    private enum class Phase { EDITING, LOADING, RUNNING }
    private var phase = Phase.EDITING
    private var generation = 0L
    var configuration: SandboxConfiguration = initial
        private set
    val canEdit: Boolean get() = phase == Phase.EDITING

    fun configure(value: SandboxConfiguration) {
        check(canEdit) { "Exit the match before changing its configuration" }
        configuration = value
    }

    fun start(): SandboxLoad {
        check(canEdit) { "A match is already loading or running" }
        return beginLoad()
    }

    fun reopen(): SandboxLoad {
        check(!canEdit) { "Start a match before reopening it" }
        return beginLoad()
    }

    private fun beginLoad(): SandboxLoad {
        generation++
        phase = Phase.LOADING
        return SandboxLoad(generation, configuration)
    }

    fun complete(candidate: Long): Boolean {
        if (phase != Phase.LOADING || candidate != generation) return false
        phase = Phase.RUNNING
        return true
    }

    fun fail(candidate: Long): Boolean {
        if (phase != Phase.LOADING || candidate != generation) return false
        exit()
        return true
    }

    fun exit() {
        generation++
        phase = Phase.EDITING
    }
}
