package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Windows filesystem control is separate from the portable mechanism suite. */
class MotionV2CoreInputsWindowsTest {
    @TempDir lateinit var directory: Path
    @Test @EnabledOnOs(OS.WINDOWS)
    fun actual_windows_junction_component_is_rejected_before_dependency_selection() {
        val source=MotionV2CoreInputsTest().also { it.directory=directory };val f=source.fixture();val target=Files.createDirectory(directory.resolve("target"))
        val saved=target.resolve("tile.tsx");Files.copy(f.tsx,saved);val hash=sha256(saved)
        val link=directory.resolve("redirect")
        val process=ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start()
        val finished=process.waitFor(10,TimeUnit.SECONDS)
        if(!finished) { process.destroyForcibly();error("Private junction creation timed out") }
        val output=process.inputStream.bufferedReader().readText()
        assertEquals(0,process.exitValue(),output)
        try {
            val alias=link.resolve("tile.tsx")
            source.manifest(f,mapOf("files" to array(listOf(f.map,f.unit,alias,f.png).map(source::identity))))
            val error=assertFails { MotionV2CoreInputs.load(f.manifest,f.request) }
            assertTrue(error.message.orEmpty().contains("Reparse input component") || error.message.orEmpty().contains("Redirected input component"),error.toString())
            assertEquals(hash,sha256(saved))
        } finally {
            // Delete only this one generated junction, never recurse into its target.
            check(link.toAbsolutePath().normalize().parent==directory.toAbsolutePath().normalize())
            Files.delete(link)
        }
        assertEquals(hash,sha256(saved))
    }
}
