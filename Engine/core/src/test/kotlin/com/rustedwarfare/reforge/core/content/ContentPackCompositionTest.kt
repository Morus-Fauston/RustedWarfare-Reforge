package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonArray
import org.hjson.JsonObject
import org.hjson.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContentPackCompositionTest {
    @Test
    fun dependency_and_replacement_metadata_rejects_bad_types_ids_members_and_local_targets() {
        withNamedPacks("base.scouts", "overlay.scouts") { packs ->
            val overlay = packs.getValue("overlay.scouts")
            val manifest = overlay.resolve("pack.json")
            val baseline = Files.readString(manifest)
            val valid = """{"contentId":"amber_scout","targetPackId":"base.scouts","targetContentId":"amber_scout"}"""
            data class Case(val extra: String, val code: ContentErrorCode, val field: String)
            val malformed = ContentErrorCode.MALFORMED_SYNTAX
            val reference = ContentErrorCode.BAD_REFERENCE
            val identity = ContentErrorCode.IDENTITY_CONFLICT
            val cases = listOf(
                Case(""""dependencies":null""", malformed, "dependencies"),
                Case(""""dependencies":"base.scouts"""", malformed, "dependencies"),
                Case(""""dependencies":[null]""", malformed, "dependencies[0]"),
                Case(""""dependencies":["Upper"]""", malformed, "dependencies[0]"),
                Case(""""dependencies":["base.scouts","base.scouts"]""", identity, "dependencies[1]"),
                Case(""""replacements":{}""", malformed, "replacements"),
                Case(""""replacements":null""", malformed, "replacements"),
                Case(""""replacements":[null]""", malformed, "replacements[0]"),
                Case(""""replacements":[{}]""", ContentErrorCode.MISSING_REQUIRED_FIELD, "replacements[0].contentId"),
                Case(""""replacements":[$valid]""", reference, "replacements[0].targetPackId"),
                Case(""""dependencies":["base.scouts"],"replacements":[$valid,$valid]""", identity, "replacements[1].contentId"),
                Case(""""dependencies":["base.scouts"],"replacements":[${valid.replace("base.scouts", "overlay.scouts")}]""", reference, "replacements[0].targetPackId"),
                Case(""""dependencies":["base.scouts"],"replacements":[${valid.replace("\"targetContentId\":\"amber_scout\"", "\"targetContentId\":\"other_scout\"")}]""", reference, "replacements[0].targetContentId"),
                Case(""""dependencies":["base.scouts"],"replacements":[${valid.replace("amber_scout", "missing_scout")}]""", reference, "replacements"),
                Case(""""dependencies":["base.scouts"],"replacements":[${valid.dropLast(1)},"priority":1}]""", ContentErrorCode.UNSUPPORTED_FIELD, "replacements[0].priority"),
                Case(""""dependencies":["base.scouts"],"replacements":[${valid.replace("\"contentId\":\"amber_scout\"", "\"contentId\":\"amber_scout\",\"contentId\":\"amber_scout\"")}]""", identity, "replacements[0].contentId"),
            )
            for (case in cases) {
                Files.writeString(manifest, baseline.trimEnd().dropLast(1) + "," + case.extra + "}")
                val failure = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(overlay), case.extra).error
                assertEquals(case.code, failure.code, case.extra)
                assertEquals(manifest.toString(), failure.file, case.extra)
                assertEquals(case.field, failure.field, case.extra)
            }
            Files.writeString(manifest, baseline)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(overlay))
        }
    }

    @Test
    fun missing_dependencies_cycles_duplicates_and_undeclared_conflicts_reject_the_whole_selection() {
        withNamedPacks("a.base", "b.overlay") { packs ->
            val base = packs.getValue("a.base"); val overlay = packs.getValue("b.overlay")
            fun failure(paths: List<Path>, code: ContentErrorCode, field: String) {
                val actual = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(paths)).error
                assertEquals(code, actual.code); assertEquals(field, actual.field)
            }
            failure(emptyList(), ContentErrorCode.MISSING_REQUIRED_FIELD, "contentPacks")
            failure(listOf(base, base), ContentErrorCode.IDENTITY_CONFLICT, "id")
            failure(listOf(base, overlay), ContentErrorCode.IDENTITY_CONFLICT, "replacements")
            declareReplacement(overlay, "a.base", 420)
            failure(listOf(overlay), ContentErrorCode.BAD_REFERENCE, "dependencies")
            editMetadata(base) { it.set("dependencies", JsonArray().add("b.overlay")) }
            failure(listOf(base, overlay), ContentErrorCode.BAD_REFERENCE, "dependencies")
            editMetadata(base) { it.set("dependencies", JsonArray().add("a.base")) }
            failure(listOf(base), ContentErrorCode.BAD_REFERENCE, "dependencies")
            editMetadata(base) { it.remove("dependencies") }
            assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(overlay, base)))
            editMetadata(overlay) { it.set("id", "a.base"); it.remove("dependencies"); it.remove("replacements") }
            failure(listOf(base, overlay), ContentErrorCode.IDENTITY_CONFLICT, "id")
        }
    }

    @Test
    fun replacement_forks_are_ambiguous_but_an_explicit_chain_has_the_same_final_source_in_every_order() {
        withNamedPacks("a.base", "b.middle", "c.final") { packs ->
            val base = packs.getValue("a.base"); val middle = packs.getValue("b.middle"); val final = packs.getValue("c.final")
            declareReplacement(middle, "a.base", 420); declareReplacement(final, "a.base", 600)
            for (paths in listOf(listOf(base, middle, final), listOf(final, middle, base)))
                assertEquals(ContentErrorCode.IDENTITY_CONFLICT, assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(paths)).error.code)
            declareReplacement(final, "b.middle", 600)
            for (paths in listOf(listOf(base, middle, final), listOf(final, middle, base), listOf(middle, base, final))) {
                val result = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(paths)).value
                assertEquals(600.0, result.units.getValue("amber_scout").maximumHealth)
                assertEquals("c.final", result.provenance.getValue("amber_scout").packageId)
                assertEquals(listOf("a.base", "b.middle"), result.provenance.getValue("amber_scout").replacedPackageIds)
            }
        }
    }

    @Test
    fun replaced_base_sources_stay_frozen_and_all_public_collection_views_are_immutable() {
        withNamedPacks("a.base", "b.overlay") { packs ->
            val base = packs.getValue("a.base"); val overlay = packs.getValue("b.overlay")
            declareReplacement(overlay, "a.base", 420)
            val result = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(base, overlay))).value
            assertTrue(result.sources.any { it.path == base.resolve("units/tank.png") })
            val path = base.resolve("units/tank.png"); val bytes = Files.readAllBytes(path)
            Files.write(path, bytes + byteArrayOf(32))
            assertIs<ContentResult.Failure>(result.verifyStable())
            assertEquals(420.0, result.units.getValue("amber_scout").maximumHealth)
            Files.write(path, bytes); assertIs<ContentResult.Success<Unit>>(result.verifyStable())
            assertFailsWith<UnsupportedOperationException> { (result.units as MutableMap).clear() }
            assertFailsWith<UnsupportedOperationException> { (result.packages as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (result.sources as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (result.provenance as MutableMap).clear() }
            assertFailsWith<UnsupportedOperationException> { (result.provenance.getValue("amber_scout").replacedPackageIds as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (result.packages.last().dependencies as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (result.packages.last().replacements as MutableMap).clear() }
            assertFailsWith<UnsupportedOperationException> { (result.packages.last().unitFiles as MutableMap).clear() }
        }
    }

    @Test
    fun a_replacement_must_target_a_unit_actually_declared_by_the_named_dependency() {
        withNamedPacks("base.scouts", "empty.scouts", "overlay.scouts") { packs ->
            val empty = packs.getValue("empty.scouts")
            val author = empty.resolve("units/scout.json")
            val unit = JsonValue.readJSON(Files.readString(author)).asObject()
            unit.get("core").asObject().set("name", "other_scout")
            Files.writeString(author, unit.toString())
            val overlay = packs.getValue("overlay.scouts")
            declareReplacement(overlay, "empty.scouts", 420)
            editMetadata(overlay) { it.set("dependencies", JsonArray().add("base.scouts").add("empty.scouts")) }
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(packs.values.toList())).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
            assertEquals(overlay.resolve("pack.json").toString(), error.file)
            assertEquals("replacements", error.field)
        }
    }

    @Test
    fun dependency_order_wins_over_both_package_names_and_the_order_of_selected_paths() {
        withNamedPacks("z.base", "a.overlay") { packs ->
            val base = packs.getValue("z.base")
            val overlay = packs.getValue("a.overlay")
            declareReplacement(overlay, "z.base", 420)
            for (paths in listOf(listOf(base, overlay), listOf(overlay, base))) {
                val attempt = ContentLoader.loadContentPacks(paths)
                val result = assertIs<ContentResult.Success<LoadedContentSet>>(attempt, attempt.toString()).value
                assertEquals(420.0, result.units.getValue("amber_scout").maximumHealth)
                assertEquals(listOf("z.base", "a.overlay"), result.packages.map { it.id })
                assertEquals("a.overlay", result.provenance.getValue("amber_scout").packageId)
            }
        }
    }

    @Test
    fun an_explicit_dependency_and_target_replace_the_unit_definition_with_a_traceable_source() {
        val temporary = Files.createTempDirectory("p008-pack-composition-").toAbsolutePath().normalize()
        try {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val source = fixtures.resolve("content-packs/amber-scouts-json")
            fun copy(name: String): Path {
                val root = temporary.resolve(name)
                Files.walk(source).use { paths -> paths.forEach { file ->
                    val target = root.resolve(source.relativize(file)).normalize()
                    require(target.startsWith(temporary))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
                return root
            }
            val base = copy("base")
            val overlay = copy("overlay")
            val baseManifest = JsonValue.readJSON(Files.readString(base.resolve("pack.json"))).asObject()
            baseManifest.set("id", "base.scouts")
            Files.writeString(base.resolve("pack.json"), baseManifest.toString())
            val replacement = JsonValue.readJSON(Files.readString(overlay.resolve("pack.json"))).asObject()
            replacement.set("id", "overlay.scouts")
            replacement.set("units", JsonArray().add(replacement.get("units").asArray().get(0)))
            replacement.set("dependencies", JsonArray().add("base.scouts"))
            replacement.set("replacements", JsonArray().add(JsonObject().add("contentId", "amber_scout")
                .add("targetPackId", "base.scouts").add("targetContentId", "amber_scout")))
            Files.writeString(overlay.resolve("pack.json"), replacement.toString())
            val author = overlay.resolve("units/scout.json")
            val unit = JsonValue.readJSON(Files.readString(author)).asObject()
            unit.get("core").asObject().set("maxHp", 420)
            Files.writeString(author, unit.toString())

            val attempt = ContentLoader.loadContentPacks(listOf(base, overlay))
            val loaded = assertIs<ContentResult.Success<LoadedContentSet>>(attempt, attempt.toString()).value
            assertEquals(setOf("amber_scout", "amber_guard"), loaded.units.keys)
            assertEquals(420.0, loaded.units.getValue("amber_scout").maximumHealth)
            assertEquals(320.0, loaded.units.getValue("amber_guard").maximumHealth)
            val origin = loaded.provenance.getValue("amber_scout")
            assertEquals("overlay.scouts", origin.packageId)
            assertEquals(author, origin.entryPath)
            assertEquals(listOf("base.scouts"), origin.replacedPackageIds)
            assertEquals("base.scouts", loaded.provenance.getValue("amber_guard").packageId)
            assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
            } }
        }
    }

    private fun withNamedPacks(vararg ids: String, check: (Map<String, Path>) -> Unit) {
        val temporary = Files.createTempDirectory("p008-dependency-packs-").toAbsolutePath().normalize()
        try {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val source = fixtures.resolve("content-packs/amber-scouts-json")
            val packs = ids.associateWith { id ->
                val root = temporary.resolve(id)
                Files.walk(source).use { paths -> paths.forEach { file ->
                    val target = root.resolve(source.relativize(file)).normalize()
                    require(target.startsWith(temporary))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
                editMetadata(root) { metadata ->
                    metadata.set("id", id)
                    metadata.set("units", JsonArray().add(metadata.get("units").asArray().get(0)))
                }
                root
            }
            check(packs)
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary)); Files.deleteIfExists(owned)
            } }
        }
    }

    private fun editMetadata(root: Path, edit: (JsonObject) -> Unit) {
        val path = root.resolve("pack.json")
        val value = JsonValue.readJSON(Files.readString(path)).asObject()
        edit(value); Files.writeString(path, value.toString())
    }

    private fun declareReplacement(root: Path, target: String, hp: Int) {
        editMetadata(root) { metadata ->
            metadata.set("dependencies", JsonArray().add(target))
            metadata.set("replacements", JsonArray().add(JsonObject().add("contentId", "amber_scout")
                .add("targetPackId", target).add("targetContentId", "amber_scout")))
        }
        val path = root.resolve("units/scout.json")
        val unit = JsonValue.readJSON(Files.readString(path)).asObject()
        unit.get("core").asObject().set("maxHp", hp)
        Files.writeString(path, unit.toString())
    }
}
