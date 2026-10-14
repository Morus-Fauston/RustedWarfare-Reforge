package com.rustedwarfare.reforge.core

/** Minimal no-graphics bootstrap boundary; gameplay state is added by later tickets. */
class CoreRuntime private constructor() {
    val moduleId: String = MODULE_ID

    companion object {
        private const val MODULE_ID = "rustedwarfare-reforge-core"

        fun start(): CoreRuntime = CoreRuntime()
    }
}
