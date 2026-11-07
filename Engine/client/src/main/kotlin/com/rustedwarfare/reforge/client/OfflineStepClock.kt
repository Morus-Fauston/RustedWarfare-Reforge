package com.rustedwarfare.reforge.client

/** Host presentation pacing; a scheduled callback still advances one unchanged simulation step. */
enum class OfflineRate(val multiplier: Int) { NORMAL(1), DOUBLE(2), QUADRUPLE(4) }

data class OfflinePacingFrame(val executedSteps: Int, val pendingSteps: Double)

class OfflineStepClock {
    var rate = OfflineRate.NORMAL
        private set
    var isPaused = false
        private set
    private var pending = 0.0
    private var measuredWall = 0.0
    private var measuredSteps = 0L
    private var measuredMultiplier: Double? = null
    val actualMultiplier: Double? get() = if (isPaused) 0.0 else measuredMultiplier

    fun setRate(value: OfflineRate) {
        if (value != rate) {
            if (value.multiplier < rate.multiplier) pending = 0.0
            clearMeasurement()
        }
        rate = value
    }
    fun setPaused(value: Boolean) {
        if (isPaused && !value) discardCatchup()
        isPaused = value
    }
    fun discardCatchup() {
        pending = 0.0
        clearMeasurement()
    }
    fun reset() {
        rate = OfflineRate.NORMAL
        isPaused = false
        pending = 0.0
        clearMeasurement()
    }

    private fun clearMeasurement() {
        measuredWall = 0.0
        measuredSteps = 0
        measuredMultiplier = null
    }

    fun advance(realSeconds: Double, tick: () -> Unit): OfflinePacingFrame {
        require(realSeconds.isFinite() && realSeconds >= 0.0) { "Finite nonnegative host frame time required" }
        if (isPaused) return OfflinePacingFrame(0, pending)
        val scheduled = pending + realSeconds * 60.0 * rate.multiplier
        require(scheduled.isFinite()) { "Host step backlog overflow" }
        pending = scheduled
        var completed = 0
        while (pending >= 1.0 && completed < 8) {
            tick()
            pending -= 1.0
            completed++
        }
        measuredWall += realSeconds
        measuredSteps += completed
        if (measuredWall >= .5) {
            measuredMultiplier = measuredSteps / (60.0 * measuredWall)
            measuredWall = 0.0
            measuredSteps = 0
        }
        return OfflinePacingFrame(completed, pending)
    }
}
