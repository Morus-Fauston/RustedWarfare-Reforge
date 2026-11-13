package com.rustedwarfare.reforge.client

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SubmarinePairedSceneTest {
    @Test fun three_format_paired_scenes_accept_only_the_same_strict_depth_boolean_as_the_formal_core_action() {
        val public = Path.of(System.getProperty("public.fixtures.dir")).parent
        for (format in listOf("ini","json","hjson")) {
            val source = public.resolve("scenarios/submarine-$format-short.json")
            val scene = TestScenarioChoice.open(source)
            scene.verifyStable()
            assertEquals("p009-submarine-production",scene.scenario.scenarioId)
            assertNotNull(scene.scenario.unitDefinitions.getValue("attackSubmarine").submarine)
            val original = Files.readString(source)
            val start = original.indexOf("\"intents\"")
            assertTrue(start>0)
            for (replacement in listOf("false", "\"true\"", "1")) {
                val malformed=original.substring(0,start)+original.substring(start).replaceFirst("\"surface\": true", "\"surface\": $replacement")
                val temporary=Files.createTempFile("submarine-invalid-",".json").toAbsolutePath().normalize()
                try {
                    Files.writeString(temporary,malformed)
                    val error=assertFailsWith<IllegalArgumentException> { TestScenarioChoice.open(temporary,scene.root) }
                    assertTrue(error.message.orEmpty().contains("TEST_SCENE_INTENT_MISMATCH"),error.toString())
                } finally { require(temporary.fileName.toString().startsWith("submarine-invalid-"));Files.deleteIfExists(temporary) }
            }
        }
    }
}
