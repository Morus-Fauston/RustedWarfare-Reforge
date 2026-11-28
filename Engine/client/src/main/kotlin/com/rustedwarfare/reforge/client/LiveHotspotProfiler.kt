package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.SimHotspotCollector
import com.rustedwarfare.reforge.core.sim.SimHotspotStage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Unified 12-stage hotspot taxonomy (ENG-P017-003 / Seven-SP Spec §2.5.3):
 * 7 authoritative simulation stages + 5 client observation/rendering stages.
 */
enum class HotspotStage(val key: String, val isSimulation: Boolean) {
    NAV_FLOW("nav.flow", true),
    NAV_PLAN("nav.plan", true),
    NAV_AVOID("nav.avoid", true),
    SIM_CONTACT("sim.contact", true),
    SIM_VISION("sim.vision", true),
    SIM_COMBAT("sim.combat", true),
    SIM_FORK_COMMIT("sim.forkCommit", true),
    CLIENT_OBSERVE("client.observe", false),
    RENDER_TERRAIN("render.terrain", false),
    RENDER_ENTITIES("render.entities", false),
    RENDER_MINIMAP("render.minimap", false),
    RENDER_HUD("render.hud", false);

    companion object {
        val ALL: List<HotspotStage> = entries
        const val COUNT: Int = 12
    }
}

data class HotspotPercentilesMs(
    val mean: Double,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
)

data class SlowFrameSnapshot(
    val frameIndex: Long,
    val stepIndex: Long,
    val frameWorkMs: Double,
    val frameIntervalMs: Double,
    val executedSubsteps: Int,
    val pendingSteps: Double,
    val liveUnits: Int,
    val activeFlowFields: Int,
    val cachedFlowFields: Int,
    val allocatedBytes: Long,
    val gcCountDelta: Long,
    val gcPauseMsDelta: Long,
    val dominantStage: String,
    val stagesMs: Map<String, Double>,
)

data class HotspotSummary(
    val schemaVersion: Int = 1,
    val generatedAtUtc: String,
    val mapId: String,
    val mapWidthTiles: Int,
    val mapHeightTiles: Int,
    val visualSource: String?,
    val visualProfile: String?,
    val visualIdentity: String?,
    val totalMatchFrames: Long,
    val warmupFramesSkipped: Int,
    val steadyStateSampledFrames: Int,
    val slowFrameThresholdMs: Double,
    val slowFrameCount: Long,
    val totalExecutedSubsteps: Long,
    val firstRecordedStepIndex: Long,
    val lastStepIndex: Long,
    val requestedRateMultiplier: Int,
    val effectiveRateMultiplier: Double,
    val maxPendingSteps: Double,
    val finalPendingSteps: Double,
    val meanSubstepsPerFrame: Double,
    val peakLiveUnits: Int,
    val finalLiveUnits: Int,
    val peakActiveFlowFields: Int,
    val peakCachedFlowFields: Int,
    val jvmTelemetrySupported: Boolean,
    val totalAllocatedBytes: Long,
    val allocationRateMbPerSec: Double,
    val meanAllocatedBytesPerFrame: Long,
    val gcCountDelta: Long,
    val gcPauseMsDelta: Long,
    val profilerSelfOverheadMsPerFrame: Double,
    val frameWorkMs: HotspotPercentilesMs,
    val frameIntervalMs: HotspotPercentilesMs,
    val stagesMs: Map<String, HotspotPercentilesMs>,
    val slowFramesTop10: List<SlowFrameSnapshot>,
)

/**
 * Reflection-guarded JVM thread allocation & GC pause reader so `:Engine:client` remains 100% safe
 * on Android runtimes where `java.lang.management.ManagementFactory` is absent.
 */
interface RuntimeMemoryTelemetry {
    val supported: Boolean
    fun currentThreadAllocatedBytes(): Long
    fun totalGcCountAndTimeMs(): Pair<Long, Long>
}

object DefaultJvmMemoryTelemetry : RuntimeMemoryTelemetry {
    private val threadMxBean: Any?
    private val threadAllocMethod: java.lang.reflect.Method?
    private val gcBeans: List<Any>
    private val gcCountMethod: java.lang.reflect.Method?
    private val gcTimeMethod: java.lang.reflect.Method?
    private val emptyArgs = emptyArray<Any>()
    @Volatile
    private var cachedTid: Long = -1L
    @Volatile
    private var cachedTidArgs: Array<Any> = emptyArray()

    init {
        var tBean: Any? = null
        var tMethod: java.lang.reflect.Method? = null
        var gBeans: List<Any> = emptyList()
        var gCount: java.lang.reflect.Method? = null
        var gTime: java.lang.reflect.Method? = null
        runCatching {
            val mfClass = Class.forName("java.lang.management.ManagementFactory")
            val rawThreadBean = mfClass.getMethod("getThreadMXBean").invoke(null)
            val sunThreadInterface = Class.forName("com.sun.management.ThreadMXBean")
            if (sunThreadInterface.isInstance(rawThreadBean)) {
                val isSupported = sunThreadInterface.getMethod("isThreadAllocatedMemorySupported")
                    .invoke(rawThreadBean) as? Boolean ?: false
                if (isSupported) {
                    runCatching { sunThreadInterface.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(rawThreadBean, true) }
                    tBean = rawThreadBean
                    tMethod = sunThreadInterface.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                }
            }
            val getGcBeans = mfClass.getMethod("getGarbageCollectorMXBeans")
            val gcClass = Class.forName("java.lang.management.GarbageCollectorMXBean")
            gCount = gcClass.getMethod("getCollectionCount")
            gTime = gcClass.getMethod("getCollectionTime")
            @Suppress("UNCHECKED_CAST")
            gBeans = (getGcBeans.invoke(null) as? List<Any>).orEmpty().toList()
        }
        threadMxBean = tBean
        threadAllocMethod = tMethod
        gcBeans = gBeans
        gcCountMethod = gCount
        gcTimeMethod = gTime
    }

    override val supported: Boolean
        get() = threadMxBean != null && threadAllocMethod != null && gcBeans.isNotEmpty()

    @Suppress("DEPRECATION")
    override fun currentThreadAllocatedBytes(): Long {
        val bean = threadMxBean ?: return 0L
        val method = threadAllocMethod ?: return 0L
        val tid = Thread.currentThread().id
        var args = cachedTidArgs
        if (tid != cachedTid || args.isEmpty()) {
            args = arrayOf(tid)
            cachedTid = tid
            cachedTidArgs = args
        }
        return runCatching { (method.invoke(bean, *args) as? Long)?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)
    }

    override fun totalGcCountAndTimeMs(): Pair<Long, Long> {
        if (gcBeans.isEmpty()) return 0L to 0L
        val cMethod = gcCountMethod ?: return 0L to 0L
        val tMethod = gcTimeMethod ?: return 0L to 0L
        return runCatching {
            var count = 0L
            var timeMs = 0L
            for (i in gcBeans.indices) {
                val bean = gcBeans[i]
                val c = (cMethod.invoke(bean, *emptyArgs) as? Long) ?: -1L
                val t = (tMethod.invoke(bean, *emptyArgs) as? Long) ?: -1L
                if (c > 0L) count += c
                if (t > 0L) timeMs += t
            }
            count to timeMs
        }.getOrDefault(0L to 0L)
    }
}

/**
 * Always-on, zero-heavy-IO segmented hotspot profiler (ENG-P017-003).
 *
 * Uses fixed primitive ring buffers (no per-frame heap allocation on the steady path),
 * supports live `F3+H` readout, captures slow frames (`> 33 ms`) Top-10 snapshots, and
 * emits a compact `hotspot-summary.json` receipt on match exit / disposal.
 */
class LiveHotspotProfiler(
    val ringCapacity: Int = DEFAULT_RING_CAPACITY,
    val warmupFramesToSkip: Int = DEFAULT_WARMUP_FRAMES,
    val slowFrameThresholdMs: Double = DEFAULT_SLOW_FRAME_THRESHOLD_MS,
    private val telemetry: RuntimeMemoryTelemetry = DefaultJvmMemoryTelemetry,
) {
    init {
        require(ringCapacity >= 16) { "ringCapacity must be >= 16, got $ringCapacity" }
        require(warmupFramesToSkip >= 0) { "warmupFramesToSkip must be >= 0" }
        require(slowFrameThresholdMs > 0.0) { "slowFrameThresholdMs must be > 0.0" }
    }

    private val frameWorkRing = LongArray(ringCapacity)
    private val frameIntervalRing = LongArray(ringCapacity)
    private val stageRing = LongArray(ringCapacity * HotspotStage.COUNT)
    private val scratchFrameStages = LongArray(HotspotStage.COUNT)
    private val scratchSort = LongArray(ringCapacity)

    private var ringWriteIndex = 0
    private var ringCount = 0

    private var mapId: String = "sandbox"
    private var mapWidthTiles: Int = 0
    private var mapHeightTiles: Int = 0
    private var visualSource: String? = null
    private var visualProfile: String? = null
    private var visualIdentity: String? = null

    private var activeFrameStartNanos: Long = 0L
    private var previousMatchFrameStartNanos: Long = 0L
    private var matchFirstFrameNanos: Long = 0L
    private var matchLastFrameEndNanos: Long = 0L
    private var prevThreadAllocBytes: Long = 0L
    private var prevGcCount: Long = 0L
    private var prevGcTimeMs: Long = 0L
    private var frameOpen: Boolean = false

    var totalMatchFrames: Long = 0L
        private set
    private var skippedWarmupCount: Int = 0
    var slowFrameCount: Long = 0L
        private set
    var totalExecutedSubsteps: Long = 0L
        private set
    private var firstStepIndex: Long = -1L
    private var lastStepIndex: Long = 0L
    private var requestedRateMultiplier: Int = 1
    private var latestEffectiveMultiplier: Double? = null
    private var maxPendingSteps: Double = 0.0
    private var finalPendingSteps: Double = 0.0
    private var peakLiveUnits: Int = 0
    private var finalLiveUnits: Int = 0
    private var peakActiveFlowFields: Int = 0
    private var peakCachedFlowFields: Int = 0
    private var totalAllocatedBytes: Long = 0L
    private var totalGcCountDelta: Long = 0L
    private var totalGcPauseMsDelta: Long = 0L
    private var totalSelfOverheadNanos: Long = 0L

    private val slowFramesTop10 = ArrayList<SlowFrameSnapshot>(MAX_SLOW_FRAMES)

    fun resetMatch(
        mapId: String = "sandbox",
        widthTiles: Int = 0,
        heightTiles: Int = 0,
        visualSource: String? = null,
        visualProfile: String? = null,
        visualIdentity: String? = null,
    ) {
        this.mapId = mapId
        this.mapWidthTiles = widthTiles
        this.mapHeightTiles = heightTiles
        this.visualSource = visualSource
        this.visualProfile = visualProfile
        this.visualIdentity = visualIdentity
        ringWriteIndex = 0
        ringCount = 0
        scratchFrameStages.fill(0L)
        activeFrameStartNanos = 0L
        previousMatchFrameStartNanos = 0L
        matchFirstFrameNanos = 0L
        matchLastFrameEndNanos = 0L
        frameOpen = false
        totalMatchFrames = 0L
        skippedWarmupCount = 0
        slowFrameCount = 0L
        totalExecutedSubsteps = 0L
        firstStepIndex = -1L
        lastStepIndex = 0L
        requestedRateMultiplier = 1
        latestEffectiveMultiplier = null
        maxPendingSteps = 0.0
        finalPendingSteps = 0.0
        peakLiveUnits = 0
        finalLiveUnits = 0
        peakActiveFlowFields = 0
        peakCachedFlowFields = 0
        totalAllocatedBytes = 0L
        totalGcCountDelta = 0L
        totalGcPauseMsDelta = 0L
        totalSelfOverheadNanos = 0L
        slowFramesTop10.clear()
        prevThreadAllocBytes = telemetry.currentThreadAllocatedBytes()
        val (gcC, gcT) = telemetry.totalGcCountAndTimeMs()
        prevGcCount = gcC
        prevGcTimeMs = gcT
    }

    fun updateVisualIdentity(source: String?, profile: String?, identity: String?) {
        if (source != null) visualSource = source
        if (profile != null) visualProfile = profile
        if (identity != null) visualIdentity = identity
    }

    fun beginMatchFrame(nowNanos: Long = System.nanoTime()) {
        scratchFrameStages.fill(0L)
        activeFrameStartNanos = nowNanos
        frameOpen = true
    }

    fun addClientStageNanos(stage: HotspotStage, deltaNanos: Long) {
        if (frameOpen && deltaNanos > 0L) {
            scratchFrameStages[stage.ordinal] += deltaNanos
        }
    }

    inline fun <T> measureClientStage(stage: HotspotStage, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            addClientStageNanos(stage, System.nanoTime() - start)
        }
    }

    fun absorbSimCollector(collector: SimHotspotCollector): Int =
        collector.drainTo(scratchFrameStages)

    fun endMatchFrame(
        stepIndex: Long,
        executedSubsteps: Int,
        pendingSteps: Double,
        requestedRate: Int,
        actualMultiplier: Double?,
        liveUnits: Int,
        activeFlowFields: Int,
        cachedFlowFields: Int,
        endNanos: Long = System.nanoTime(),
    ) {
        if (!frameOpen) return
        val overheadStart = System.nanoTime()
        frameOpen = false

        val workNanos = max(0L, endNanos - activeFrameStartNanos)
        val intervalNanos = if (previousMatchFrameStartNanos > 0L) {
            max(workNanos, activeFrameStartNanos - previousMatchFrameStartNanos)
        } else {
            workNanos
        }
        previousMatchFrameStartNanos = activeFrameStartNanos
        if (matchFirstFrameNanos == 0L) matchFirstFrameNanos = activeFrameStartNanos
        matchLastFrameEndNanos = endNanos

        val frameIndex = totalMatchFrames++
        if (firstStepIndex < 0L) firstStepIndex = stepIndex
        lastStepIndex = stepIndex
        totalExecutedSubsteps += max(0, executedSubsteps)
        requestedRateMultiplier = requestedRate
        if (actualMultiplier != null && actualMultiplier.isFinite()) {
            latestEffectiveMultiplier = actualMultiplier
        }
        if (pendingSteps.isFinite()) {
            finalPendingSteps = pendingSteps
            if (pendingSteps > maxPendingSteps) maxPendingSteps = pendingSteps
        }
        finalLiveUnits = liveUnits
        if (liveUnits > peakLiveUnits) peakLiveUnits = liveUnits
        if (activeFlowFields > peakActiveFlowFields) peakActiveFlowFields = activeFlowFields
        if (cachedFlowFields > peakCachedFlowFields) peakCachedFlowFields = cachedFlowFields

        val workMs = nanosToMs(workNanos)
        val isSlow = workMs > slowFrameThresholdMs
        var allocDelta = 0L
        var gcCountDelta = 0L
        var gcPauseDelta = 0L
        val sampleJvmTelemetry = (frameIndex and 63L) == 0L ||
            (isSlow && (slowFramesTop10.size < MAX_SLOW_FRAMES || workMs > slowFramesTop10.last().frameWorkMs))
        if (sampleJvmTelemetry) {
            val allocNow = telemetry.currentThreadAllocatedBytes()
            allocDelta = max(0L, allocNow - prevThreadAllocBytes)
            prevThreadAllocBytes = allocNow
            totalAllocatedBytes += allocDelta

            val (gcC, gcT) = telemetry.totalGcCountAndTimeMs()
            gcCountDelta = max(0L, gcC - prevGcCount)
            gcPauseDelta = max(0L, gcT - prevGcTimeMs)
            prevGcCount = gcC
            prevGcTimeMs = gcT
            totalGcCountDelta += gcCountDelta
            totalGcPauseMsDelta += gcPauseDelta
        }

        if (frameIndex < warmupFramesToSkip) {
            skippedWarmupCount++
        } else {
            val slot = ringWriteIndex
            frameWorkRing[slot] = workNanos
            frameIntervalRing[slot] = intervalNanos
            val base = slot * HotspotStage.COUNT
            for (s in 0 until HotspotStage.COUNT) {
                stageRing[base + s] = scratchFrameStages[s]
            }
            ringWriteIndex = (slot + 1) % ringCapacity
            if (ringCount < ringCapacity) ringCount++

            val steadyOverheadEnd = System.nanoTime()
            totalSelfOverheadNanos += max(0L, steadyOverheadEnd - overheadStart)

            if (isSlow) {
                slowFrameCount++
                recordSlowFrame(
                    frameIndex = frameIndex,
                    stepIndex = stepIndex,
                    workMs = workMs,
                    intervalMs = nanosToMs(intervalNanos),
                    executedSubsteps = executedSubsteps,
                    pendingSteps = pendingSteps,
                    liveUnits = liveUnits,
                    activeFlowFields = activeFlowFields,
                    cachedFlowFields = cachedFlowFields,
                    allocatedBytes = allocDelta,
                    gcCountDelta = gcCountDelta,
                    gcPauseMsDelta = gcPauseDelta,
                )
            }
        }
    }

    private fun recordSlowFrame(
        frameIndex: Long,
        stepIndex: Long,
        workMs: Double,
        intervalMs: Double,
        executedSubsteps: Int,
        pendingSteps: Double,
        liveUnits: Int,
        activeFlowFields: Int,
        cachedFlowFields: Int,
        allocatedBytes: Long,
        gcCountDelta: Long,
        gcPauseMsDelta: Long,
    ) {
        if (slowFramesTop10.size >= MAX_SLOW_FRAMES && workMs <= slowFramesTop10.last().frameWorkMs) {
            return
        }
        var dominantIdx = 0
        var dominantNanos = -1L
        val stageMap = LinkedHashMap<String, Double>(HotspotStage.COUNT)
        for (i in 0 until HotspotStage.COUNT) {
            val st = HotspotStage.ALL[i]
            val ns = scratchFrameStages[i]
            stageMap[st.key] = round4(nanosToMs(ns))
            if (ns > dominantNanos) {
                dominantNanos = ns
                dominantIdx = i
            }
        }
        val snap = SlowFrameSnapshot(
            frameIndex = frameIndex,
            stepIndex = stepIndex,
            frameWorkMs = round4(workMs),
            frameIntervalMs = round4(intervalMs),
            executedSubsteps = executedSubsteps,
            pendingSteps = round4(pendingSteps),
            liveUnits = liveUnits,
            activeFlowFields = activeFlowFields,
            cachedFlowFields = cachedFlowFields,
            allocatedBytes = allocatedBytes,
            gcCountDelta = gcCountDelta,
            gcPauseMsDelta = gcPauseMsDelta,
            dominantStage = HotspotStage.ALL[dominantIdx].key,
            stagesMs = stageMap,
        )
        var insertAt = slowFramesTop10.size
        for (i in slowFramesTop10.indices) {
            val existing = slowFramesTop10[i]
            if (snap.frameWorkMs > existing.frameWorkMs ||
                (snap.frameWorkMs == existing.frameWorkMs && snap.frameIndex < existing.frameIndex)
            ) {
                insertAt = i
                break
            }
        }
        slowFramesTop10.add(insertAt, snap)
        while (slowFramesTop10.size > MAX_SLOW_FRAMES) {
            slowFramesTop10.removeAt(slowFramesTop10.lastIndex)
        }
    }

    /** Formats compact monospace lines for the live `F3+H` overlay panel. */
    fun formatOverlayLines(): List<String> {
        if (ringCount == 0) {
            return listOf("hotspots [F3+H] · collecting steady-state frames ($totalMatchFrames / $warmupFramesToSkip)...")
        }
        val work = computePercentiles(frameWorkRing, ringCount)
        fun st(stage: HotspotStage): HotspotPercentilesMs = computeStagePercentiles(stage.ordinal, ringCount)
        val flow = st(HotspotStage.NAV_FLOW)
        val plan = st(HotspotStage.NAV_PLAN)
        val avoid = st(HotspotStage.NAV_AVOID)
        val contact = st(HotspotStage.SIM_CONTACT)
        val vision = st(HotspotStage.SIM_VISION)
        val combat = st(HotspotStage.SIM_COMBAT)
        val fork = st(HotspotStage.SIM_FORK_COMMIT)
        val obs = st(HotspotStage.CLIENT_OBSERVE)
        val ter = st(HotspotStage.RENDER_TERRAIN)
        val ent = st(HotspotStage.RENDER_ENTITIES)
        val mini = st(HotspotStage.RENDER_MINIMAP)
        val hud = st(HotspotStage.RENDER_HUD)

        val elapsedSec = max(1e-3, (matchLastFrameEndNanos - matchFirstFrameNanos) / 1_000_000_000.0)
        val allocMbPerSec = (totalAllocatedBytes / (1024.0 * 1024.0)) / elapsedSec
        fun fmt(p: HotspotPercentilesMs): String =
            String.format(Locale.ROOT, "%.1f/%.1f/%.1f/%.1f", p.p50, p.p95, p.p99, p.max)

        return listOf(
            String.format(
                Locale.ROOT,
                "hotspots [F3+H] · window %d frames · work %.2f/%.2f/%.2f/%.2f ms (P50/95/99/Max)",
                ringCount, work.p50, work.p95, work.p99, work.max,
            ),
            String.format(
                Locale.ROOT,
                "sim nav  flow %s · plan %s · avoid %s",
                fmt(flow), fmt(plan), fmt(avoid),
            ),
            String.format(
                Locale.ROOT,
                "sim core contact %s · vision %s · combat %s · fork %s",
                fmt(contact), fmt(vision), fmt(combat), fmt(fork),
            ),
            String.format(
                Locale.ROOT,
                "client   observe %s · terrain %s · entities %s",
                fmt(obs), fmt(ter), fmt(ent),
            ),
            String.format(
                Locale.ROOT,
                "ui/mem   minimap %s · hud %s · alloc %.1f MB/s · gc %d (%d ms) · slow %d",
                fmt(mini), fmt(hud), allocMbPerSec, totalGcCountDelta, totalGcPauseMsDelta, slowFrameCount,
            ),
        )
    }

    fun buildSummary(): HotspotSummary {
        if (totalMatchFrames > 0L) {
            val allocNow = telemetry.currentThreadAllocatedBytes()
            totalAllocatedBytes += max(0L, allocNow - prevThreadAllocBytes)
            prevThreadAllocBytes = allocNow

            val (gcC, gcT) = telemetry.totalGcCountAndTimeMs()
            totalGcCountDelta += max(0L, gcC - prevGcCount)
            totalGcPauseMsDelta += max(0L, gcT - prevGcTimeMs)
            prevGcCount = gcC
            prevGcTimeMs = gcT
        }
        val n = ringCount
        val workStats = computePercentiles(frameWorkRing, n)
        val intervalStats = computePercentiles(frameIntervalRing, n)
        val stagesMap = LinkedHashMap<String, HotspotPercentilesMs>(HotspotStage.COUNT)
        for (i in 0 until HotspotStage.COUNT) {
            val st = HotspotStage.ALL[i]
            stagesMap[st.key] = computeStagePercentiles(i, n)
        }
        val elapsedSec = if (matchLastFrameEndNanos > matchFirstFrameNanos) {
            (matchLastFrameEndNanos - matchFirstFrameNanos) / 1_000_000_000.0
        } else 0.0
        val computedEffectiveRate = if (elapsedSec > 1e-3 && totalExecutedSubsteps > 0L) {
            (totalExecutedSubsteps.toDouble() / elapsedSec) / 60.0
        } else {
            latestEffectiveMultiplier ?: 0.0
        }
        val allocMbSec = if (elapsedSec > 1e-3) (totalAllocatedBytes / (1024.0 * 1024.0)) / elapsedSec else 0.0
        val meanAllocFrame = if (totalMatchFrames > 0L) totalAllocatedBytes / totalMatchFrames else 0L
        val meanSubsteps = if (totalMatchFrames > 0L) totalExecutedSubsteps.toDouble() / totalMatchFrames.toDouble() else 0.0
        val steadyTotalFrames = max(0L, totalMatchFrames - skippedWarmupCount.toLong())
        val selfOverheadMs = if (steadyTotalFrames > 0L) nanosToMs(totalSelfOverheadNanos) / steadyTotalFrames.toDouble() else 0.0

        return HotspotSummary(
            schemaVersion = 1,
            generatedAtUtc = Instant.now().toString(),
            mapId = mapId,
            mapWidthTiles = mapWidthTiles,
            mapHeightTiles = mapHeightTiles,
            visualSource = visualSource,
            visualProfile = visualProfile,
            visualIdentity = visualIdentity,
            totalMatchFrames = totalMatchFrames,
            warmupFramesSkipped = skippedWarmupCount,
            steadyStateSampledFrames = n,
            slowFrameThresholdMs = slowFrameThresholdMs,
            slowFrameCount = slowFrameCount,
            totalExecutedSubsteps = totalExecutedSubsteps,
            firstRecordedStepIndex = max(0L, firstStepIndex),
            lastStepIndex = lastStepIndex,
            requestedRateMultiplier = requestedRateMultiplier,
            effectiveRateMultiplier = round4(computedEffectiveRate),
            maxPendingSteps = round4(maxPendingSteps),
            finalPendingSteps = round4(finalPendingSteps),
            meanSubstepsPerFrame = round4(meanSubsteps),
            peakLiveUnits = peakLiveUnits,
            finalLiveUnits = finalLiveUnits,
            peakActiveFlowFields = peakActiveFlowFields,
            peakCachedFlowFields = peakCachedFlowFields,
            jvmTelemetrySupported = telemetry.supported,
            totalAllocatedBytes = totalAllocatedBytes,
            allocationRateMbPerSec = round4(allocMbSec),
            meanAllocatedBytesPerFrame = meanAllocFrame,
            gcCountDelta = totalGcCountDelta,
            gcPauseMsDelta = totalGcPauseMsDelta,
            profilerSelfOverheadMsPerFrame = round4(selfOverheadMs),
            frameWorkMs = workStats,
            frameIntervalMs = intervalStats,
            stagesMs = stagesMap,
            slowFramesTop10 = slowFramesTop10.toList(),
        )
    }

    fun toSummaryJson(summary: HotspotSummary = buildSummary()): String {
        val sb = StringBuilder(4096)
        fun esc(s: String): String = buildString(s.length + 4) {
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
        fun strOrNull(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""
        fun num(d: Double): String = String.format(Locale.ROOT, "%.4f", if (d.isFinite()) d else 0.0)
        fun pctObj(p: HotspotPercentilesMs): String =
            """{"mean":${num(p.mean)},"p50":${num(p.p50)},"p95":${num(p.p95)},"p99":${num(p.p99)},"max":${num(p.max)}}"""

        sb.append("{\n")
        sb.append("  \"schemaVersion\": ").append(summary.schemaVersion).append(",\n")
        sb.append("  \"generatedAtUtc\": ").append(strOrNull(summary.generatedAtUtc)).append(",\n")
        sb.append("  \"mapId\": ").append(strOrNull(summary.mapId)).append(",\n")
        sb.append("  \"mapTiles\": {\"width\": ").append(summary.mapWidthTiles).append(", \"height\": ").append(summary.mapHeightTiles).append("},\n")
        sb.append("  \"visualAssets\": {\"source\": ").append(strOrNull(summary.visualSource))
            .append(", \"profile\": ").append(strOrNull(summary.visualProfile))
            .append(", \"identity\": ").append(strOrNull(summary.visualIdentity)).append("},\n")
        sb.append("  \"frames\": {\n")
        sb.append("    \"totalMatchFrames\": ").append(summary.totalMatchFrames).append(",\n")
        sb.append("    \"warmupFramesSkipped\": ").append(summary.warmupFramesSkipped).append(",\n")
        sb.append("    \"steadyStateSampledFrames\": ").append(summary.steadyStateSampledFrames).append(",\n")
        sb.append("    \"slowFrameThresholdMs\": ").append(num(summary.slowFrameThresholdMs)).append(",\n")
        sb.append("    \"slowFrameCount\": ").append(summary.slowFrameCount).append("\n")
        sb.append("  },\n")
        sb.append("  \"pacing\": {\n")
        sb.append("    \"totalExecutedSubsteps\": ").append(summary.totalExecutedSubsteps).append(",\n")
        sb.append("    \"firstRecordedStepIndex\": ").append(summary.firstRecordedStepIndex).append(",\n")
        sb.append("    \"lastStepIndex\": ").append(summary.lastStepIndex).append(",\n")
        sb.append("    \"requestedRateMultiplier\": ").append(summary.requestedRateMultiplier).append(",\n")
        sb.append("    \"effectiveRateMultiplier\": ").append(num(summary.effectiveRateMultiplier)).append(",\n")
        sb.append("    \"maxPendingSteps\": ").append(num(summary.maxPendingSteps)).append(",\n")
        sb.append("    \"finalPendingSteps\": ").append(num(summary.finalPendingSteps)).append(",\n")
        sb.append("    \"meanSubstepsPerFrame\": ").append(num(summary.meanSubstepsPerFrame)).append("\n")
        sb.append("  },\n")
        sb.append("  \"resources\": {\n")
        sb.append("    \"peakLiveUnits\": ").append(summary.peakLiveUnits).append(",\n")
        sb.append("    \"finalLiveUnits\": ").append(summary.finalLiveUnits).append(",\n")
        sb.append("    \"peakActiveFlowFields\": ").append(summary.peakActiveFlowFields).append(",\n")
        sb.append("    \"peakCachedFlowFields\": ").append(summary.peakCachedFlowFields).append(",\n")
        sb.append("    \"jvmTelemetrySupported\": ").append(summary.jvmTelemetrySupported).append(",\n")
        sb.append("    \"totalAllocatedBytes\": ").append(summary.totalAllocatedBytes).append(",\n")
        sb.append("    \"allocationRateMbPerSec\": ").append(num(summary.allocationRateMbPerSec)).append(",\n")
        sb.append("    \"meanAllocatedBytesPerFrame\": ").append(summary.meanAllocatedBytesPerFrame).append(",\n")
        sb.append("    \"gcCountDelta\": ").append(summary.gcCountDelta).append(",\n")
        sb.append("    \"gcPauseMsDelta\": ").append(summary.gcPauseMsDelta).append("\n")
        sb.append("  },\n")
        sb.append("  \"profilerSelfOverheadMsPerFrame\": ").append(num(summary.profilerSelfOverheadMsPerFrame)).append(",\n")
        sb.append("  \"frameWorkMs\": ").append(pctObj(summary.frameWorkMs)).append(",\n")
        sb.append("  \"frameIntervalMs\": ").append(pctObj(summary.frameIntervalMs)).append(",\n")
        sb.append("  \"stagesMs\": {\n")
        val stageEntries = summary.stagesMs.entries.toList()
        for (i in stageEntries.indices) {
            val (k, v) = stageEntries[i]
            sb.append("    \"").append(esc(k)).append("\": ").append(pctObj(v))
            if (i + 1 < stageEntries.size) sb.append(',')
            sb.append('\n')
        }
        sb.append("  },\n")
        sb.append("  \"slowFramesTop10\": [")
        if (summary.slowFramesTop10.isEmpty()) {
            sb.append("]\n")
        } else {
            sb.append('\n')
            for (i in summary.slowFramesTop10.indices) {
                val sf = summary.slowFramesTop10[i]
                sb.append("    {\"frameIndex\": ").append(sf.frameIndex)
                    .append(", \"stepIndex\": ").append(sf.stepIndex)
                    .append(", \"frameWorkMs\": ").append(num(sf.frameWorkMs))
                    .append(", \"frameIntervalMs\": ").append(num(sf.frameIntervalMs))
                    .append(", \"executedSubsteps\": ").append(sf.executedSubsteps)
                    .append(", \"pendingSteps\": ").append(num(sf.pendingSteps))
                    .append(", \"liveUnits\": ").append(sf.liveUnits)
                    .append(", \"activeFlowFields\": ").append(sf.activeFlowFields)
                    .append(", \"cachedFlowFields\": ").append(sf.cachedFlowFields)
                    .append(", \"allocatedBytes\": ").append(sf.allocatedBytes)
                    .append(", \"gcCountDelta\": ").append(sf.gcCountDelta)
                    .append(", \"gcPauseMsDelta\": ").append(sf.gcPauseMsDelta)
                    .append(", \"dominantStage\": ").append(strOrNull(sf.dominantStage))
                    .append(", \"stagesMs\": {")
                val sfStages = sf.stagesMs.entries.toList()
                for (j in sfStages.indices) {
                    val (sk, sv) = sfStages[j]
                    sb.append("\"").append(esc(sk)).append("\":").append(num(sv))
                    if (j + 1 < sfStages.size) sb.append(',')
                }
                sb.append("}}")
                if (i + 1 < summary.slowFramesTop10.size) sb.append(',')
                sb.append('\n')
            }
            sb.append("  ]\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    fun writeSummaryJson(path: Path, summary: HotspotSummary = buildSummary()) {
        val parent = path.toAbsolutePath().normalize().parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp-${System.nanoTime()}")
        try {
            Files.writeString(tmp, toSummaryJson(summary))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun computeStagePercentiles(stageOrdinal: Int, count: Int): HotspotPercentilesMs {
        if (count <= 0) return HotspotPercentilesMs(0.0, 0.0, 0.0, 0.0, 0.0)
        var sumNanos = 0L
        for (i in 0 until count) {
            val v = stageRing[i * HotspotStage.COUNT + stageOrdinal]
            scratchSort[i] = v
            sumNanos += v
        }
        scratchSort.sort(0, count)
        return percentilesFromSorted(scratchSort, count, sumNanos)
    }

    private fun computePercentiles(source: LongArray, count: Int): HotspotPercentilesMs {
        if (count <= 0) return HotspotPercentilesMs(0.0, 0.0, 0.0, 0.0, 0.0)
        var sumNanos = 0L
        for (i in 0 until count) {
            val v = source[i]
            scratchSort[i] = v
            sumNanos += v
        }
        scratchSort.sort(0, count)
        return percentilesFromSorted(scratchSort, count, sumNanos)
    }

    private fun percentilesFromSorted(sorted: LongArray, count: Int, sumNanos: Long): HotspotPercentilesMs {
        fun rank(p: Double): Double {
            val idx = (ceil(p * count).toInt() - 1).coerceIn(0, count - 1)
            return round4(nanosToMs(sorted[idx]))
        }
        return HotspotPercentilesMs(
            mean = round4(nanosToMs(sumNanos) / count.toDouble()),
            p50 = rank(0.50),
            p95 = rank(0.95),
            p99 = rank(0.99),
            max = round4(nanosToMs(sorted[count - 1])),
        )
    }

    private fun nanosToMs(nanos: Long): Double = nanos.toDouble() / 1_000_000.0
    private fun round4(value: Double): Double = kotlin.math.round(value * 10000.0) / 10000.0

    companion object {
        const val DEFAULT_RING_CAPACITY: Int = 4096
        const val DEFAULT_WARMUP_FRAMES: Int = 5
        const val DEFAULT_SLOW_FRAME_THRESHOLD_MS: Double = 33.0
        const val MAX_SLOW_FRAMES: Int = 10
    }
}
