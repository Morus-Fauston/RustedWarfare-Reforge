package com.rustedwarfare.reforge.testing

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TestArchivesTest {
    @Test fun archive_cases_keep_the_requested_outer_root_and_reject_reuse() {
        val first = TestArchives.directory("RWR_AUTHOR_WATER_ARCHIVE_ROOT", javaClass, "isolation-first")
        val second = TestArchives.directory("RWR_AUTHOR_WATER_ARCHIVE_ROOT", javaClass, "isolation-second")
        assertNotEquals(first, second)
        assertTrue(Files.isDirectory(first))
        System.getenv("RWR_AUTHOR_WATER_ARCHIVE_ROOT")?.let {
            assertTrue(first.startsWith(Path.of(it).toAbsolutePath().normalize()))
        }
        assertTrue(first.toString().contains(javaClass.name))
        assertTrue(first.parent.parent.fileName.toString().startsWith("worker-"))
        assertFailsWith<FileAlreadyExistsException> {
            TestArchives.directory("RWR_AUTHOR_WATER_ARCHIVE_ROOT", javaClass, "isolation-first")
        }
        assertFailsWith<IllegalArgumentException> {
            TestArchives.directory("RWR_AUTHOR_WATER_ARCHIVE_ROOT", javaClass, "../escape")
        }
    }
}
