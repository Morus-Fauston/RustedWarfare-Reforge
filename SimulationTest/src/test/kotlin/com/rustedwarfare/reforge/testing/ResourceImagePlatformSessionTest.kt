package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class ResourceImagePlatformSessionTest {
    private val input = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public/inputs/resource-image-packs/sandbox")

    @Test fun changed_builtin_content_layout_is_used_by_the_real_platform_preparation() {
        val temporary = Files.createTempDirectory("resource-platform-final-").toAbsolutePath().normalize()
        try {
            val snapshot = temporary.resolve("inputs")
            Files.walk(input).use { paths -> paths.forEach { path ->
                val destination = snapshot.resolve(input.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
            } }
            val unit = snapshot.resolve("package/units/scout.json")
            Files.writeString(unit, Files.readString(unit).replace("amber_scout", "c_tank")
                .replace("\"copyFrom\":", "\"graphics\": {\"total_frames\": 1}, \"copyFrom\":"))
            val manifest = snapshot.resolve("resources/amber/images.json")
            val document = json.parseToJsonElement(Files.readString(manifest)).jsonObject
            val images = document.objectAt("images").toMutableMap()
            images["reforge:units/c_tank/body"] = JsonObject(images.getValue("reforge:units/c_tank/body").jsonObject.toMutableMap().apply {
                put("frameWidth", JsonPrimitive(60)); put("totalFrames", JsonPrimitive(1))
            })
            Files.writeString(manifest, JsonObject(document.toMutableMap().apply { put("images", JsonObject(images)) }).toString())
            val plan = temporary.resolve("plan.json")
            Files.writeString(plan, """{"schemaVersion":1,"contentPack":{"directory":"package","unitId":"c_tank"},
                "resourcePacks":{"directories":["resources/amber"]},
                "intents":[{"intentId":"choose","kind":"select","position":{"x":110,"y":210}}]}""")
            val output = temporary.resolve("run")
            PlatformSessionRunner.run(snapshot, plan, output)
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val body = rows.single { it["kind"] == s("visual-assets-ready") }.objectAt("resourceStack")
                .objectAt("resources").objectAt("reforge:units/c_tank/body")
            assertEquals("owned:amber", body.text("providerId"))
            assertEquals(60L, body.integer("frameWidth")); assertEquals(1L, body.integer("totalFrames"))
            assertEquals(120L, body.integer("pixelWidth")); assertEquals(b(true), body["bound"])
            val observed = rows.first { it["kind"] == s("observation") }.objectAt("sample").arrayAt("units")
                .single { it.jsonObject["unitId"] == s("a") }.jsonObject
            assertEquals("c_tank", observed.text("typeId")); assertEquals(250.0, observed.number("maxHealth"))
            assertFalse(Files.exists(output.resolve("resource-cache/settings.properties")))
        } finally { removeOwned(temporary) }
    }

    @Test fun ordered_image_packs_use_the_real_pc_preparation_and_current_content_without_changing_gameplay() {
        val temporary = Files.createTempDirectory("resource-platform-").toAbsolutePath().normalize()
        try {
            for ((index, names) in listOf(listOf("amber", "blue"), listOf("blue", "amber")).withIndex()) {
                val plan = temporary.resolve("plan-$index.json")
                Files.writeString(plan, """{"schemaVersion":1,"contentPack":{"directory":"package","unitId":"amber_scout"},
                    "resourcePacks":{"directories":[${names.joinToString(",") { "\"resources/$it\"" }}]},
                    "intents":[{"intentId":"choose","kind":"select","position":{"x":110,"y":210}}]}""")
                val output = temporary.resolve("run-$index")
                PlatformSessionRunner.run(input, plan, output)
                val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                val stack = rows.single { it["kind"] == s("visual-assets-ready") }.objectAt("resourceStack")
                assertEquals(names.map { s("owned:$it") } + s("reforge:base"), stack.arrayAt("packOrder").toList())
                val images = stack.objectAt("resources")
                val body = images.objectAt("reforge:units/amber_scout/body")
                assertEquals("owned:${names.first()}", body.text("providerId"))
                assertEquals(20L, body.integer("frameWidth")); assertEquals(3L, body.integer("totalFrames"))
                assertEquals(120L, body.integer("pixelWidth")); assertEquals(b(true), body["bound"])
                assertEquals("owned:blue", images.objectAt("reforge:units/amber_scout/turret").text("providerId"))
                assertEquals("reforge:base", images.objectAt("reforge:units/amber_scout/dead").text("providerId"))
                val unit = rows.first { it["kind"] == s("observation") }.objectAt("sample").arrayAt("units")
                    .single { it.jsonObject["unitId"] == s("a") }.jsonObject
                assertEquals(250.0, unit.number("maxHealth")); assertEquals("amber_scout", unit.text("typeId"))
                assertFalse(Files.exists(output.resolve("resource-cache/settings.properties")), "Preparation must not save a UI selection")
            }
        } finally { removeOwned(temporary) }
    }

    @Test fun malformed_or_escaping_image_selections_publish_no_world() {
        val temporary = Files.createTempDirectory("resource-platform-negative-").toAbsolutePath().normalize()
        try {
            for ((index, selection) in listOf(
                """{"directories":["../escape"]}""", """{"directories":[]}""",
                """{"directories":["resources/amber","resources/amber"]}""",
                """{"directories":[null]}""", """{"directories":["resources/amber"],"unknown":true}"""
            ).withIndex()) {
                val plan = temporary.resolve("plan-$index.json")
                Files.writeString(plan, """{"schemaVersion":1,"resourcePacks":$selection,
                    "intents":[{"intentId":"choose","kind":"select","position":{"x":110,"y":210}}]}""")
                val output = temporary.resolve("run-$index")
                assertFails { PlatformSessionRunner.run(input, plan, output) }
                assertEquals(emptyList(), Files.readAllLines(output.resolve("client-diagnostic.jsonl")))
            }
        } finally { removeOwned(temporary) }
    }

    private fun removeOwned(root: Path) {
        require(root.isAbsolute && root.fileName.toString().startsWith("resource-platform-"))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { file ->
            require(file.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(file)
        } }
    }
}
