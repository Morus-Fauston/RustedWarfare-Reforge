package com.rustedwarfare.reforge.testing

/** Sequential monotonic spans; a stage may never contain another measured span. */
internal class RunCosts(private val started: Long) {
    private val nanos = linkedMapOf("loadInitialize" to 0L, "coreSimulation" to 0L,
        "captureArchive" to 0L, "comparison" to 0L)
    private var active = false
    fun <T> measure(stage: String, block: () -> T): T {
        check(!active && stage in nanos)
        active = true
        val begin = System.nanoTime()
        try { return block() }
        finally {
            nanos[stage] = nanos.getValue(stage) + System.nanoTime() - begin
            active = false
        }
    }
    fun json(): kotlinx.serialization.json.JsonObject {
        check(!active)
        val total = System.nanoTime() - started
        return obj("clock" to s("System.nanoTime"), "stagesNanos" to
            kotlinx.serialization.json.JsonObject(nanos.mapValues { n(it.value) }),
            "measuredNanos" to n(total), "otherNanos" to n(total - nanos.values.sum()),
            "buildCost" to s("external-command-only"),
            "boundary" to s("runner entry through manifest preparation; stages disjoint, other includes guards/identity IO; final manifest write and archive close excluded; not platform performance"))
    }
}
