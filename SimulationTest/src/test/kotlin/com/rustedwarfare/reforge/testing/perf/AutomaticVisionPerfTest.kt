package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Historical protocol stays separate from the explicitly versioned development capture. */
class AutomaticVisionPerfTest {
    @Test fun historical_qualifiers_refuse_development_protocol_without_reinterpreting_it() {
        val fixture=AutomaticVisionPerf.hiddenDiscoveryFixture(TerrainRetention.MEMORY)
        val current=AutomaticVisionDevelopment.captureDevelopment(fixture)
        val directory=Files.createTempDirectory("eng017-avp-legacy-protocol-")
        println("eng017-avp-legacy-protocol-raw=$directory")
        Files.write(directory.resolve("development-raw.jsonl"),current.rows.map { it.toString() })
        AutomaticVisionDevelopment.qualifyDevelopment(current)
        val historical=AutomaticVisionPerfRun(fixture,true,current.rows)
        assertFailsWith<IllegalArgumentException> { AutomaticVisionPerf.validate(historical) }
        assertFailsWith<IllegalArgumentException> { AutomaticVisionPerf.qualifyContinuousRoute(historical) }
    }
}
