package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import com.rustedwarfare.reforge.client.ResourcePackCatalogEntry
import com.rustedwarfare.reforge.client.ResourcePackSelection
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourcePackStackSettingsTest {
    @Test
    fun real_pc_settings_restores_the_saved_order_and_cli_changes_only_the_builtin_layer_temporarily() {
        val root = Files.createTempDirectory("resource-stack-application-").toAbsolutePath().normalize()
        try {
            val amber = ResourcePackCatalogEntry("owned:amber", root.resolve("amber/pack.json"), "a".repeat(64))
            val blue = ResourcePackCatalogEntry("owned:blue", root.resolve("blue/pack.hjson"), "b".repeat(64))
            val saved = VisualAssetSelection(ResourcePackSelection(listOf(amber, blue),
                listOf(blue.id, VisualAssetSource.ORIGINAL.resourcePackId, amber.id)), root.resolve("original"))
            val file = root.resolve("settings.properties")
            val store = VisualAssetSettingsStore(file)
            store.save(saved)
            val before = Files.readAllBytes(file)
            val plain = PcVisualAssetSettings(store, root.resolve("cache"), root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), root))
            assertEquals(saved, plain.initialSelection, "The real startup adapter must not rebuild a single-source selection and drop users")
            val temporary = PcVisualAssetSettings(store, root.resolve("cache"), root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo", "--visuals", "placeholder"), root))
            assertEquals(saved.copy(source = VisualAssetSource.PLACEHOLDER, temporary = true), temporary.initialSelection)
            assertContentEquals(before, Files.readAllBytes(file))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun ordered_user_catalog_and_original_layer_round_trip_without_a_second_source_truth() {
        val root = Files.createTempDirectory("resource-stack-roundtrip-").toAbsolutePath().normalize()
        try {
            val amber = ResourcePackCatalogEntry("owned:amber", root.resolve("amber/pack.json"), "a".repeat(64))
            val blue = ResourcePackCatalogEntry("owned:blue", root.resolve("blue/pack.hjson"), "b".repeat(64))
            val mutableCatalog = mutableListOf(amber, blue)
            val mutableOrder = mutableListOf(blue.id, VisualAssetSource.ORIGINAL.resourcePackId, amber.id)
            val packs = ResourcePackSelection(mutableCatalog, mutableOrder)
            val selected = VisualAssetSelection(packs, root.resolve("original"))
            mutableCatalog.clear(); mutableOrder.clear()
            assertEquals(listOf(blue.id, VisualAssetSource.ORIGINAL.resourcePackId, amber.id), selected.packs.enabled)
            assertEquals(VisualAssetSource.ORIGINAL, selected.source)
            assertFailsWith<UnsupportedOperationException> { (selected.packs.enabled as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (selected.packs.catalog as MutableList).clear() }
            val file = root.resolve("settings.properties")
            val store = VisualAssetSettingsStore(file)
            store.save(selected)
            val before = Files.readAllBytes(file)
            assertEquals(selected, VisualAssetSettingsStore(file).load())
            assertContentEquals(before, Files.readAllBytes(file))
            store.save(selected.copy(temporary = true))
            assertContentEquals(before, Files.readAllBytes(file))
            val baseOverride = selected.copy(source = VisualAssetSource.PLACEHOLDER)
            assertEquals(listOf(blue.id, amber.id), baseOverride.packs.enabled)
            assertEquals(packs.catalog, baseOverride.packs.catalog)
            assertEquals(VisualAssetSource.PLACEHOLDER, baseOverride.source)
            assertEquals(listOf(blue.id, amber.id, VisualAssetSource.ORIGINAL.resourcePackId),
                baseOverride.copy(source = VisualAssetSource.ORIGINAL).packs.enabled)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun malformed_v3_arrays_entries_paths_and_ids_never_rewrite_saved_bytes() {
        val root = Files.createTempDirectory("resource-stack-invalid-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("settings.properties")
            val ordinary = """[{"id":"owned:one","manifest":"${root.resolve("one/pack.json").toString().replace('\\', '/')}","identity":"${"a".repeat(64)}"}]"""
            for ((catalog, enabled) in listOf(
                "[]" to "{}", "[]" to "[1]", "[]" to "[\"owned:missing\"]",
                "[]" to "[\"reforge:base\"]", "[]" to "[\"reforge:original-android-1.15-176\",\"reforge:original-android-1.15-176\"]",
                ordinary.replace("owned:one", "reforge:base") to "[]",
                ordinary.replace("a".repeat(64), "NOT-A-SHA") to "[]",
                ordinary.replace(root.toString().replace('\\', '/') + "/one/pack.json", "relative/pack.json") to "[]",
                ordinary.replace("\"id\":", "\"extra\":1,\"id\":") to "[]",
                ordinary.replace("\"id\":", "\"id\":\"owned:duplicate\",\"id\":") to "[]",
                ("[" + ordinary.drop(1).dropLast(1) + "," + ordinary.drop(1).dropLast(1) + "]") to "[]",
                ordinary.replace("identity", "missingIdentity") to "[]",
            )) {
                val values = Properties().apply {
                    setProperty("schemaVersion", "3"); setProperty("originalDirectory", "")
                    setProperty("catalog", catalog); setProperty("enabled", enabled)
                }
                Files.newBufferedWriter(file).use { values.store(it, "Owned malformed stack") }
                val before = Files.readAllBytes(file)
                assertTrue(assertFailsWith<IllegalArgumentException> { VisualAssetSettingsStore(file).load() }
                    .message.orEmpty().contains("VISUAL_SETTINGS_INVALID"))
                assertContentEquals(before, Files.readAllBytes(file))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun successful_explicit_save_publishes_v3_order_while_v2_load_remains_read_only() {
        val root = Files.createTempDirectory("resource-stack-settings-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("settings.properties")
            val directory = root.resolve("original").toString().replace('\\', '/')
            Files.writeString(file, "schemaVersion=2\nresourcePackId=reforge:original-android-1.15-176\noriginalDirectory=$directory\n")
            val before = Files.readAllBytes(file)
            val store = VisualAssetSettingsStore(file)
            val selected = store.load()
            assertEquals(VisualAssetSource.ORIGINAL, selected.source)
            assertEquals(root.resolve("original"), selected.originalDirectory)
            assertContentEquals(before, Files.readAllBytes(file), "Loading the old choice cannot publish a new configuration")

            store.save(selected)
            val saved = Properties().also { values -> Files.newBufferedReader(file).use(values::load) }
            assertEquals("3", saved.getProperty("schemaVersion"), "Successful saves must publish the ordered-stack format")
            assertEquals(setOf("schemaVersion", "originalDirectory", "catalog", "enabled"), saved.stringPropertyNames())
            assertEquals("[]", saved.getProperty("catalog"))
            assertEquals("[\"reforge:original-android-1.15-176\"]", saved.getProperty("enabled"))
            assertEquals(selected, VisualAssetSettingsStore(file).load())

            store.save(VisualAssetSelection())
            assertEquals(VisualAssetSelection(), store.load())
            val base = Properties().also { values -> Files.newBufferedReader(file).use(values::load) }
            assertEquals("[]", base.getProperty("enabled"), "The base layer is implicit and always last")
        } finally {
            require(root.isAbsolute && root.fileName.toString().startsWith("resource-stack-settings-"))
            root.toFile().deleteRecursively()
        }
    }
}
