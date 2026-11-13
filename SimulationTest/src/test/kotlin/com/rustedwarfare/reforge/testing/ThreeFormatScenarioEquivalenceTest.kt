package com.rustedwarfare.reforge.testing

import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class ThreeFormatScenarioEquivalenceTest {
    private val publicRoot = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test fun selected_core_scenarios_load_equivalent_runtime_definitions_and_contracts() {
        val manifest = readObject(publicRoot.resolve("scenario-equivalence.json"))
        verify(manifest.arrayAt("families"))
    }

    @Test fun duplicate_format_and_missing_family_are_rejected() {
        val families = readObject(publicRoot.resolve("scenario-equivalence.json")).arrayAt("families")
        val first = families.first().jsonObject
        val members = first.arrayAt("members")
        val repeated = JsonObject(first + ("members" to JsonArray(listOf(members[0], members[1], members[0]))))
        kotlin.test.assertFailsWith<AssertionError> { verify(JsonArray(listOf(repeated) + families.drop(1))) }
        kotlin.test.assertFailsWith<AssertionError> { verify(JsonArray(families.drop(1))) }
    }

    private fun verify(families: JsonArray) {
        assertTrue(families.isNotEmpty())
        val suites = listOf("content-three-formats", "production-combat").associateWith { suite ->
            readObject(publicRoot.resolve("suites/$suite.json")).arrayAt("short").map { it.jsonObject }
                .associateBy { it.text("id") }
        }
        val inherited = suites.flatMap { (suite, scenarios) ->
            scenarios.values.filter { "equivalentTo" in it }.map { suite to it.text("id") }
        }
        val consumed = mutableListOf<Pair<String, String>>()
        for (family in families.map { it.jsonObject }) {
            val title = family.text("name")
            val suite = family.text("suite")
            val registered = suites.getValue(suite)
            val members = family.arrayAt("members").map { it.jsonObject }
            assertEquals(3, members.size, title)
            assertEquals(3, members.map { it.text("id") }.toSet().size, title)
            val representative = members.first().text("id")
            val loaded = members.mapIndexed { index, member ->
                val identity = member.text("id")
                val entry = registered.getValue(identity)
                assertEquals(member, entry - "equivalentTo", "$title/$identity registration")
                if (index == 0) assertTrue("equivalentTo" !in entry)
                else {
                    assertEquals(representative, entry.text("equivalentTo"), "$title/$identity representative")
                    consumed += suite to identity
                }
                val seed = publicFile(member.text("scenario"))
                val contract = publicFile(member.text("contract"))
                val input = ScenarioReader.load(seed, contract)
                val declarations = input.scenarioDocument.objectAt("content")
                val units = declarations["unitVariants"]?.jsonArray?.map { it.jsonObject }
                    ?: listOf(declarations.objectAt("unitVariant"))
                val extension = listOf("json", "ini", "hjson")[index]
                assertTrue(units.any { it.text("path").endsWith(".$extension") }, "$title/$identity missing format")
                input
            }
            assertEquals(3, loaded.size, title)
            val reference = semantics(loaded[0])
            for ((index, variant) in loaded.withIndex()) {
                assertEquals(reference, semantics(variant), "$title member $index")
                println("$title member $index: ${variant.scenarioIdentity.id}, ${variant.identities.size} verified content inputs")
            }
        }
        assertEquals(inherited.sortedBy { it.toString() }, consumed.sortedBy { it.toString() })
    }

    private fun publicFile(relative: String): Path {
        val resolved = publicRoot.resolve(relative).toRealPath()
        require(resolved.startsWith(publicRoot.toRealPath()))
        return resolved
    }

    private fun semantics(loaded: LoadedScenario): Map<String, Any?> {
        val core = loaded.core
        return linkedMapOf(
            "scenario" to (loaded.scenarioDocument - setOf("scenarioId", "inputs", "content")),
            "contract" to (loaded.contractDocument - setOf("contractId", "scenarioIds")),
            "loaderSlots" to loaded.scenarioDocument.objectAt("content").entries
                .filter { it.key != "dependencies" }.associate { (name, value) ->
                    name to when (value) {
                        is JsonArray -> value.map { it.jsonObject - setOf("path", "sha256") }
                        else -> value.jsonObject - setOf("path", "sha256")
                    }
                },
            "units" to core.unitDefinitions.mapValues { (_, definition) -> project(definition) },
            "map" to listOf(core.map.widthTiles, core.map.heightTiles, core.map.tileWidth, core.map.tileHeight,
                core.map.groundGids.toList(), core.map.tileSets.map(::project)),
            "players" to core.players,
            "spawns" to core.units,
            "steps" to loaded.steps,
            "actions" to loaded.actions,
        )
    }

    private fun project(value: Any?): Any? = when (value) {
        null, is Number, is Boolean, is String, is Enum<*> -> value
        is Path -> listOf(value.fileName.toString(), sha256(value))
        is Map<*, *> -> value.entries.associate { (key, item) -> project(key) to project(item) }
        is Set<*> -> value.map(::project).toSet()
        is Iterable<*> -> value.map(::project)
        is Lazy<*> -> project(value.value)
        is IntArray -> value.toList()
        is BooleanArray -> value.toList()
        else -> {
            require(value.javaClass.name.startsWith("com.rustedwarfare.reforge.core.")) {
                "Unknown runtime field projection: ${value.javaClass.name}"
            }
            value.javaClass.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .associate { field ->
                    check(field.trySetAccessible()) { "Cannot inspect runtime field ${field.name}" }
                    field.name to project(field.get(value))
                }
        }
    }
}
