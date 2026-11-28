package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

/**
 * Authoritative simulation stage keys for [SimHotspotCollector] and client `LiveHotspotProfiler` (ENG-P017-003).
 *
 * Strictly observational: stage timings never enter [StepState] or [Observation] and never influence simulation outcomes.
 */
enum class SimHotspotStage(val key: String) {
    NAV_FLOW("nav.flow"),
    NAV_PLAN("nav.plan"),
    NAV_AVOID("nav.avoid"),
    SIM_CONTACT("sim.contact"),
    SIM_VISION("sim.vision"),
    SIM_COMBAT("sim.combat"),
    SIM_FORK_COMMIT("sim.forkCommit");

    companion object {
        val ALL: List<SimHotspotStage> = entries
        const val COUNT: Int = 7
    }
}

/**
 * Zero-allocation primitive nanosecond accumulator for simulation substeps within a render frame.
 *
 * Lives outside [StepState] so candidate-step aborts/rollbacks never touch or depend on timing state.
 */
class SimHotspotCollector {
    private val stageNanos = LongArray(SimHotspotStage.COUNT)
    var stepsRecorded: Int = 0
        private set

    fun addNanos(stage: SimHotspotStage, deltaNanos: Long) {
        if (deltaNanos > 0L) {
            stageNanos[stage.ordinal] += deltaNanos
        }
    }

    fun recordStepCompleted() {
        stepsRecorded++
    }

    inline fun <T> measure(stage: SimHotspotStage, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            addNanos(stage, System.nanoTime() - start)
        }
    }

    /** Copies accumulated nanoseconds into [target] (length >= [SimHotspotStage.COUNT]), resets counters, and returns completed step count. */
    fun drainTo(target: LongArray): Int {
        require(target.size >= SimHotspotStage.COUNT) { "Expected target size >= ${SimHotspotStage.COUNT}, got ${target.size}" }
        for (i in 0 until SimHotspotStage.COUNT) {
            target[i] += stageNanos[i]
            stageNanos[i] = 0L
        }
        val count = stepsRecorded
        stepsRecorded = 0
        return count
    }

    fun snapshotNanos(): LongArray = stageNanos.copyOf()

    fun reset() {
        stageNanos.fill(0L)
        stepsRecorded = 0
    }
}
