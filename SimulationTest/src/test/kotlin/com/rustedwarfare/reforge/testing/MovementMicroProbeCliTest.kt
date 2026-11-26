package com.rustedwarfare.reforge.testing

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovementMicroProbeCliTest {
    private fun repoRoot(): Path = Path.of(System.getProperty("repo.root", "..")).toAbsolutePath().normalize()

    @Test
    fun unified_scene_cli_runs_dev_scene_with_assertions_and_metadata_receipt() {
        val tmp = Files.createTempDirectory("reforge-cli-scene-")
        try {
            val code = MovementMicroProbe.executeCli(arrayOf(
                "--repo-root", repoRoot().toString(),
                "--scene", "narrow-bridge-yield",
                "--sheets", "false",
                "--assert", "true",
                "--output", tmp.toString(),
            ))
            assertEquals(0, code)
            val summary = Json.parseToJsonElement(Files.readString(tmp.resolve("probe-summary.json"))).jsonObject
            assertEquals(2, summary["schemaVersion"]!!.jsonPrimitive.int)
            assertTrue(summary["allAssertionsPassed"]!!.jsonPrimitive.boolean)
            assertEquals(64, summary["parameterHash"]!!.jsonPrimitive.content.length)
            val scene = summary["scenes"]!!.jsonArray.single().jsonObject
            assertEquals("narrow-bridge-yield", scene["sceneId"]!!.jsonPrimitive.content)
            assertEquals("bottleneck-handoff", scene["family"]!!.jsonPrimitive.content)
            assertEquals("A", scene["group"]!!.jsonPrimitive.content)
            assertEquals(2, scene["finalArrivedCount"]!!.jsonPrimitive.int)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun unified_scene_cli_loads_external_holdout_json_pack_without_polluting_dev_default() {
        val tmp = Files.createTempDirectory("reforge-cli-holdout-")
        try {
            val holdoutJson = tmp.resolve("holdout-sample.json")
            Files.writeString(holdoutJson, """
                {
                  "scenes": [
                    {
                      "id": "holdout-c-sample",
                      "displayName": "Holdout C Sample",
                      "family": "bottleneck-handoff",
                      "group": "C",
                      "seed": 9901,
                      "defaultSteps": 360,
                      "minArrived": 2,
                      "map": { "width": 24, "height": 14, "tileWidth": 20, "tileHeight": 20, "borderBlocked": true },
                      "units": [
                        { "unitId": "h-1", "ownerPlayerId": "p1", "typeId": "c_tank", "x": 100.0, "y": 140.0, "heading": 90.0 },
                        { "unitId": "h-2", "ownerPlayerId": "p1", "typeId": "c_tank", "x": 100.0, "y": 168.0, "heading": 90.0 }
                      ],
                      "orders": [
                        { "atStep": -1, "actionId": "ho-go", "ownerPlayerId": "p1", "unitIds": ["h-1", "h-2"], "targetX": 340.0, "targetY": 150.0, "groupMove": true }
                      ]
                    }
                  ]
                }
            """.trimIndent())
            val outDir = tmp.resolve("out")
            val code = MovementMicroProbe.executeCli(arrayOf(
                "--repo-root", repoRoot().toString(),
                "--holdout-file", holdoutJson.toString(),
                "--scene", "holdout",
                "--sheets", "false",
                "--assert", "true",
                "--output", outDir.toString(),
            ))
            assertEquals(0, code)
            val summary = Json.parseToJsonElement(Files.readString(outDir.resolve("probe-summary.json"))).jsonObject
            val scene = summary["scenes"]!!.jsonArray.single().jsonObject
            assertEquals("holdout-c-sample", scene["sceneId"]!!.jsonPrimitive.content)
            assertEquals("holdout", scene["category"]!!.jsonPrimitive.content)
            assertEquals("C", scene["group"]!!.jsonPrimitive.content)
            assertEquals(9901L, scene["seed"]!!.jsonPrimitive.long)
            assertEquals(2, scene["finalArrivedCount"]!!.jsonPrimitive.int)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun unified_scene_cli_assert_failure_returns_exit_code_2() {
        val tmp = Files.createTempDirectory("reforge-cli-assert-fail-")
        try {
            val code = MovementMicroProbe.executeCli(arrayOf(
                "--repo-root", repoRoot().toString(),
                "--scene", "narrow-bridge-yield",
                "--steps", "10",
                "--sheets", "false",
                "--assert", "true",
                "--output", tmp.toString(),
            ))
            assertEquals(2, code, "Failed assertions under --assert true must return exit code 2")
            val summary = Json.parseToJsonElement(Files.readString(tmp.resolve("probe-summary.json"))).jsonObject
            assertFalse(summary["allAssertionsPassed"]!!.jsonPrimitive.boolean)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun unified_scene_cli_list_outputs_schema_v2_and_dev_scenes() {
        val prevOut = System.out
        val buf = ByteArrayOutputStream()
        val code = try {
            System.setOut(PrintStream(buf, true, Charsets.UTF_8))
            MovementMicroProbe.executeCli(arrayOf("--list"))
        } finally {
            System.setOut(prevOut)
        }
        assertEquals(0, code)
        val payload = Json.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject
        assertEquals(2, payload["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals(64, payload["parameterHash"]!!.jsonPrimitive.content.length)
        val scenes = payload["scenes"]!!.jsonArray
        assertEquals(10, scenes.size)
        assertTrue(scenes.all { it.jsonObject["group"]!!.jsonPrimitive.content in setOf("A", "B") })
    }

    @Test
    fun unified_scene_cli_rejects_malformed_or_unsafe_holdout_packs_and_empty_scene_selection() {
        val tmp = Files.createTempDirectory("reforge-cli-security-")
        try {
            fun runWithHoldout(name: String, json: String, sceneArg: String = "holdout") {
                val file = tmp.resolve(name)
                Files.writeString(file, json)
                MovementMicroProbe.executeCli(arrayOf(
                    "--repo-root", repoRoot().toString(),
                    "--holdout-file", file.toString(),
                    "--scene", sceneArg,
                    "--sheets", "false",
                    "--output", tmp.resolve("out-$name").toString(),
                ))
            }

            // 1. Path traversal id
            assertFailsWith<IllegalArgumentException> {
                runWithHoldout("traversal.json", """
                    {"scenes":[{"id":"../escape","displayName":"Bad","family":"f","group":"C","seed":1,"defaultSteps":60,"minArrived":1,
                    "map":{"width":16,"height":12},"units":[{"unitId":"u1","ownerPlayerId":"p1","typeId":"c_tank","x":80,"y":80}],"orders":[]}]}
                """.trimIndent())
            }
            // 2. Shadowing built-in dev scene id
            assertFailsWith<IllegalArgumentException> {
                runWithHoldout("shadow.json", """
                    {"scenes":[{"id":"rocks-small-100","displayName":"Shadow","family":"f","group":"C","seed":1,"defaultSteps":60,"minArrived":1,
                    "map":{"width":16,"height":12},"units":[{"unitId":"u1","ownerPlayerId":"p1","typeId":"c_tank","x":80,"y":80}],"orders":[]}]}
                """.trimIndent())
            }
            // 3. Duplicate holdout id
            assertFailsWith<IllegalArgumentException> {
                runWithHoldout("dup.json", """
                    {"scenes":[
                      {"id":"holdout-dup","displayName":"D1","family":"f","group":"C","seed":1,"defaultSteps":60,"minArrived":1,
                       "map":{"width":16,"height":12},"units":[{"unitId":"u1","ownerPlayerId":"p1","typeId":"c_tank","x":80,"y":80}],"orders":[]},
                      {"id":"holdout-dup","displayName":"D2","family":"f","group":"D","seed":2,"defaultSteps":60,"minArrived":1,
                       "map":{"width":16,"height":12},"units":[{"unitId":"u1","ownerPlayerId":"p1","typeId":"c_tank","x":80,"y":80}],"orders":[]}
                    ]}
                """.trimIndent())
            }
            // 4. Spoofed group outside C..F
            assertFailsWith<IllegalArgumentException> {
                runWithHoldout("bad-group.json", """
                    {"scenes":[{"id":"holdout-bad-group","displayName":"BadGrp","family":"f","group":"A","seed":1,"defaultSteps":60,"minArrived":1,
                    "map":{"width":16,"height":12},"units":[{"unitId":"u1","ownerPlayerId":"p1","typeId":"c_tank","x":80,"y":80}],"orders":[]}]}
                """.trimIndent())
            }
            // 5. Rejected order unitId
            assertFailsWith<IllegalStateException> {
                runWithHoldout("bad-order.json", """
                    {"scenes":[{"id":"holdout-bad-order","displayName":"BadOrd","family":"f","group":"C","seed":1,"defaultSteps":60,"minArrived":1,
                    "map":{"width":16,"height":12},"units":[{"unitId":"u1","ownerPlayerId":"p1","typeId":"c_tank","x":80,"y":80}],
                    "orders":[{"atStep":-1,"actionId":"go","ownerPlayerId":"p1","unitIds":["nonexistent-unit"],"targetX":200,"targetY":80,"groupMove":false}]}]}
                """.trimIndent())
            }
            // 6. Empty --scene selection
            assertFailsWith<IllegalArgumentException> {
                MovementMicroProbe.executeCli(arrayOf("--scene", "", "--sheets", "false", "--output", tmp.resolve("empty1").toString()))
            }
            assertFailsWith<IllegalArgumentException> {
                MovementMicroProbe.executeCli(arrayOf("--scene", " , ", "--sheets", "false", "--output", tmp.resolve("empty2").toString()))
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun main_router_detects_scene_cli_in_any_flag_order_without_breaking_run_or_compare() {
        assertTrue(isSceneCliInvocation(arrayOf("scene", "--list")))
        assertTrue(isSceneCliInvocation(arrayOf("--scene", "narrow-bridge-yield")))
        assertTrue(isSceneCliInvocation(arrayOf("--output", "out", "--scene", "narrow-bridge-yield")))
        assertTrue(isSceneCliInvocation(arrayOf("--holdout-file", "h.json", "--list")))
        assertFalse(isSceneCliInvocation(arrayOf("run", "--scenario", "a.json", "--scene", "x")))
        assertFalse(isSceneCliInvocation(arrayOf("compare", "--actual", "a.zip", "--list")))
        assertFalse(isSceneCliInvocation(arrayOf("--help")))
    }
}
