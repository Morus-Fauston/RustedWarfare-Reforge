package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class P001SuiteTest {
    private fun frame(step:Long)=Observation("s$step","w:1","literal",if(step<0) 0 else step,if(step<0) 0.0 else step+1.0,emptyList(),emptyList(),emptyList())
    @Test fun `sampling fails closed for missing reordered or incomplete records`() {
        val literal=listOf(frame(-1),frame(0),frame(1),frame(2))
        assertEquals(0,P001Suite.samplingCode(literal,3,true))
        assertEquals(3,P001Suite.samplingCode(literal.dropLast(1),3,true))
        assertEquals(3,P001Suite.samplingCode(literal,3,false))
        assertEquals(3,P001Suite.samplingCode(listOf(literal[0],literal[1],literal[3],literal[2]),3,true))
    }
    @Test fun `suite archive rejects missing completion and changed evidence`() {
        val directory=java.nio.file.Files.createTempDirectory("p001-archive-test-")
        val trace=directory.resolve("trace.jsonl")
        java.nio.file.Files.writeString(trace,"literal\n")
        val hash=com.rustedwarfare.reforge.testing.sha256(trace)
        java.nio.file.Files.writeString(directory.resolve("manifest.json"),"""{"schemaVersion":1,"kind":"p001-suite-manifest","complete":true,"expectedCases":16,"observedCases":16,"exitCode":0,"files":[{"path":"trace.jsonl","sha256":"$hash"}]}""")
        assertEquals(0,P001Suite.verifyArchive(directory))
        java.nio.file.Files.writeString(trace,"changed\n")
        assertEquals(3,P001Suite.verifyArchive(directory))
        java.nio.file.Files.writeString(directory.resolve("manifest.json"),"""{"schemaVersion":1,"kind":"p001-suite-manifest","complete":false,"expectedCases":16,"observedCases":15,"exitCode":0,"files":[]}""")
        assertEquals(3,P001Suite.verifyArchive(directory))
    }

    @Test fun `aggregate does not hide identity failure behind a gameplay failure`() {
        assertEquals(0,P001Suite.aggregate(listOf(0,0)))
        assertEquals(3,P001Suite.aggregate(listOf(2,3)))
        assertEquals(4,P001Suite.aggregate(listOf(3,4)))
        assertEquals(64,P001Suite.aggregate(listOf(2,64,4)))
        assertEquals(3,P001Suite.aggregate(emptyList()))
    }
}
