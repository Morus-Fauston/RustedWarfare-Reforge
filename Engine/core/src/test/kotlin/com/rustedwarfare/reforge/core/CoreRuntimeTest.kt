package com.rustedwarfare.reforge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreRuntimeTest {
    @Test
    fun startsWithoutGraphicsOrAndroidRuntime() {
        val runtime = CoreRuntime.start()

        assertEquals("rustedwarfare-reforge-core", runtime.moduleId)
    }
}
