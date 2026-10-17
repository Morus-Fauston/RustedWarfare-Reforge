package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import kotlin.test.*

class XmlSecurityTest {
    @Test fun `rejects internal external and utf16 DTDs and remains usable`() {
        val documents = listOf(
            "<!DOCTYPE map [<!ENTITY x 'expanded'>]><map>&x;</map>",
            "<!DOCTYPE map SYSTEM 'file:///must-not-be-read'><map/>",
            "<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///must-not-be-read'>]><map>&x;</map>",
        )
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16)) for (document in documents) {
            val path = Files.createTempFile("p001-xml-security-", ".tmx")
            try {
                Files.write(path, document.toByteArray(charset))
                val rejected = assertIs<ContentResult.Failure>(ContentLoader.loadMap(path))
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, rejected.error.code)
                assertTrue(rejected.error.reason.contains("DOCTYPE"))
            } finally { Files.deleteIfExists(path) }
        }
        assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(mapPath()))
    }
}
