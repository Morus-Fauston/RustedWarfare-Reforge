package com.rustedwarfare.reforge.client

import java.nio.file.Path
import java.util.concurrent.CancellationException

/** An unsuccessful explicit choice never falls back to a different world. */
class TestScenarioMenu {
    var selected: Boolean = false
        private set
    var choice: TestScenarioChoice? = null
        private set
    var error: String? = null
        private set
    val canStart: Boolean get() = !selected || choice != null

    fun choose(seed: Path) {
        selected = true; choice = null; error = null
        try { choice = TestScenarioChoice.open(seed) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: java.io.IOException) {
            error = "TEST_SCENE_INVALID: Missing or inaccessible paired file: ${failure.message ?: seed}"
        }
        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
    }

    fun useCustomContent() { selected = false; choice = null; error = null }
}
