package com.rustedwarfare.reforge.testing

/** Sequential monotonic spans; a stage may never contain another measured span. */
internal class RunCosts(private val started: Long) {
    private val nanos = linkedMapOf("loadInitialize" to 0L, "coreSimulation" to 0L,
        "captureArchive" to 0L, "comparison" to 0L)
    private val details = linkedMapOf("sourceLoad" to 0L, "inputValidation" to 0L, "initialization" to 0L,
        "coreAdvanceIncludingHostSample" to 0L, "samplingIncludingPlayerJson" to 0L,
        "serialization" to 0L, "writing" to 0L, "comparison" to 0L)
    private var active = false
    private var detailActive = false
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
    /** Details form their own disjoint partition inside the existing coarse stages. */
    fun <T> detail(stage: String, block: () -> T): T {
        check(!detailActive && stage in details)
        detailActive = true
        val begin = System.nanoTime()
        try { return block() }
        finally {
            details[stage] = details.getValue(stage) + System.nanoTime() - begin
            detailActive = false
        }
    }
    fun json(): kotlinx.serialization.json.JsonObject {
        check(!active && !detailActive)
        val total = System.nanoTime() - started
        return obj("clock" to s("System.nanoTime"), "stagesNanos" to
            kotlinx.serialization.json.JsonObject(nanos.mapValues { n(it.value) }),
            "detailsNanos" to kotlinx.serialization.json.JsonObject(details.mapValues { n(it.value) }),
            "detailBoundary" to s("Disjoint details supplement, not add to, coarse stages. Host observation is included in advance; player sampling includes its JSON preparation. Serialization/writing include trace and documents before the final manifest; guards, file identities and unclassified IO remain outside details."),
            "measuredNanos" to n(total), "otherNanos" to n(total - nanos.values.sum()),
            "buildCost" to s("external-command-only"),
            "boundary" to s("runner entry through manifest preparation; stages disjoint, other includes guards/identity IO; final manifest write and archive close excluded; not platform performance"))
    }
}
