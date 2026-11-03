package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WeightedSoundPackCompositionTest {
    @Test fun append_chains_keep_duplicate_uri_weights_and_distinguish_event_layers_from_final_file_providers() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val backend = fixture.backend()
            val bottom = pack(fixture, backend, "bottom", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/bottom-a","weight":9}]},
                "owned:events/retained":{"candidates":[{"sound":"owned:sounds/bottom-b"}]}
            """.trimIndent())
            val middle = pack(fixture, backend, "middle", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/middle-a","weight":2}]}
            """.trimIndent())
            val top = pack(fixture, backend, "top", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/middle-a","weight":3}],"append":true},
                "owned:events/new":{"candidates":[{"sound":"owned:sounds/top-b"}],"append":true}
            """.trimIndent(), """"owned:sounds/middle-a":{"file":"clip.wav"},"owned:sounds/top-b":{"file":"clip.wav"}""")
            val selection = backend.initialSelection.copy(packs = ResourcePackSelection(
                listOf(bottom.catalogEntry, middle.catalogEntry, top.catalogEntry),
                listOf(top.metadata.id.value, middle.metadata.id.value, bottom.metadata.id.value)))
            val sounds = assertNotNull(backend.prepare(selection).resolvedSounds)
            val event = ResourceId.parse("reforge:events/tank-fire")
            assertEquals(listOf("owned:sounds/middle-a", "owned:sounds/middle-a"), sounds.events.getValue(event).candidates.map { it.sound.value })
            assertEquals(listOf(3, 2), sounds.events.getValue(event).candidates.map { it.weight })
            assertEquals(5L, sounds.events.getValue(event).totalWeight)
            assertEquals(listOf("owned:top", "owned:middle"), sounds.candidateProviders[event])
            assertEquals("owned:top", sounds.soundProviders[ResourceId.parse("owned:sounds/middle-a")])
            assertEquals(top.sounds.getValue(ResourceId.parse("owned:sounds/middle-a")), sounds.sounds.getValue(ResourceId.parse("owned:sounds/middle-a")))
            assertEquals("owned:sounds/bottom-b", sounds.events.getValue(ResourceId.parse("owned:events/retained")).sound.value)
            assertEquals("owned:sounds/top-b", sounds.events.getValue(ResourceId.parse("owned:events/new")).sound.value)
            assertFailsWith<UnsupportedOperationException> { (sounds.candidateProviders.getValue(event) as MutableList<String>).clear() }
        }
    }

    @Test fun a_hidden_bad_weighted_candidate_blocks_apply_without_overwriting_good_settings_and_disabling_repairs_it() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val backend = fixture.backend()
            val good = pack(fixture, backend, "good", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/good-a","weight":2},{"sound":"owned:sounds/good-b"}]}
            """.trimIndent())
            val bad = pack(fixture, backend, "bad", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/bad-a"},{"sound":"owned:sounds/missing"}]}
            """.trimIndent())
            val goodSelection = backend.initialSelection.copy(packs = ResourcePackSelection(
                listOf(good.catalogEntry, bad.catalogEntry), listOf(good.metadata.id.value)))
            VisualAssetSettingsSession(backend).use { session ->
                fun apply(selection: VisualAssetSelection) {
                    session.edit(selection); session.apply()
                    val end = System.nanoTime() + 10_000_000_000L
                    while (!session.poll()) { check(System.nanoTime() < end); Thread.sleep(2) }
                }
                apply(goodSelection)
                assertTrue(session.canStart, session.error)
                val saved = Files.readAllBytes(fixture.settings)
                apply(goodSelection.copy(packs = ResourcePackSelection(goodSelection.packs.catalog,
                    listOf(good.metadata.id.value, bad.metadata.id.value))))
                assertFalse(session.canStart)
                for (part in listOf("owned:bad", "sounds.json", "candidates[1]", "owned:sounds/missing"))
                    assertTrue(session.error.orEmpty().contains(part), session.error)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                apply(goodSelection)
                assertTrue(session.canStart, session.error)
                assertEquals(goodSelection, fixture.store.load())
                assertEquals(listOf(2, 1), assertNotNull(backend.prepare(session.selection).resolvedSounds)
                    .events.getValue(ResourceId.parse("reforge:events/tank-fire")).candidates.map { it.weight })
            }
        }
    }

    @Test fun actual_prepared_weights_use_exact_long_ticket_boundaries_without_flattening_multi_candidate_events() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = pack(fixture, backend, "wide", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/wide-a","weight":2147483647},{"sound":"owned:sounds/wide-b","weight":2147483647}]}
            """.trimIndent())
            val prepared = backend.prepare(backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value))))
            val sounds = assertNotNull(prepared.resolvedSounds)
            val event = ResourceId.parse("reforge:events/tank-fire")
            assertEquals(4294967294L, sounds.events.getValue(event).totalWeight)
            // Independently worked intervals: [0,2147483647) and [2147483647,4294967294).
            for ((ticket, expected) in listOf(0L to "wide-a", 2147483646L to "wide-a",
                    2147483647L to "wide-b", 4294967293L to "wide-b")) {
                assertEquals("owned:sounds/$expected", sounds.choose(event, ticket)?.sound?.value)
            }
            for (invalid in listOf(-1L, 4294967294L, Long.MAX_VALUE))
                assertFailsWith<IllegalArgumentException> { sounds.choose(event, invalid) }
            assertNull(sounds.choose(ResourceId.parse("owned:events/absent"), 0))
            assertTrue(assertFailsWith<IllegalStateException> { sounds.resolve(event) }.message.orEmpty().contains("RESOURCE_SOUND_SELECTION_REQUIRED"))
        }
    }

    private fun pack(fixture: ResourceImagePackApplicationFixture, backend: PcVisualAssetSettings, name: String,
        events: String, sounds: String? = null): PreparedImagePack {
        val first = fixture.create("owned:$name", listOf(OwnPackImage("owned:icons/$name", frames = 1, width = 16, height = 16)))
        val root = first.manifest.parent
        Files.copy(Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs/wav/clip.wav"), root.resolve("clip.wav"))
        val metadata = root.resolve("pack.json")
        Files.writeString(metadata, Files.readString(metadata).replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":3"))
        Files.writeString(root.resolve("sounds.json"), """{"formatVersion":2,
            "sounds":{${sounds ?: "\"owned:sounds/$name-a\":{\"file\":\"clip.wav\"},\"owned:sounds/$name-b\":{\"file\":\"clip.wav\"}"}},
            "events":{$events}}""")
        return backend.checkImagePack(root)
    }

    @Test fun actual_pc_stack_explicit_append_prepends_weighted_candidates_to_the_lower_event_table() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val backend = fixture.backend()
            val lower = pack(fixture, backend, "lower", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/lower-a","weight":2},{"sound":"owned:sounds/lower-b"}]}
            """.trimIndent())
            val upper = pack(fixture, backend, "upper", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/upper-a","weight":3}],"append":true}
            """.trimIndent())
            val prepared = backend.prepare(backend.initialSelection.copy(packs = ResourcePackSelection(listOf(lower.catalogEntry, upper.catalogEntry),
                listOf(upper.metadata.id.value, lower.metadata.id.value))))
            var ready: Map<String, Any?> = emptyMap()
            ClientEvidence { _, values -> ready = values }.recordVisualAssetsReady(prepared)
            val events = assertIs<Map<*, *>>(assertIs<Map<*, *>>(ready["soundStack"])["events"])
            val event = assertIs<Map<*, *>>(events["reforge:events/tank-fire"])
            val candidates = assertIs<List<*>>(event["candidates"]).map { assertIs<Map<*, *>>(it) }
            assertEquals(listOf("owned:sounds/upper-a", "owned:sounds/lower-a", "owned:sounds/lower-b"), candidates.map { it["sound"] })
            assertEquals(listOf(3, 2, 1), candidates.map { it["weight"] })
            assertEquals(listOf("owned:upper", "owned:lower", "owned:lower"), candidates.map { it["providerId"] })
            assertEquals(6L, assertIs<Number>(event["totalWeight"]).toLong())
            assertEquals("owned:upper", event["providerId"])
            prepared.verifyForPublication()
        }
    }

    @Test fun actual_pc_stack_replaces_only_the_declared_event_and_reports_the_whole_weighted_table() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val backend = fixture.backend()
            val lower = pack(fixture, backend, "lower", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/lower-a","weight":2},{"sound":"owned:sounds/lower-b"}]},
                "owned:events/other":{"candidates":[{"sound":"owned:sounds/lower-b"}]}
            """.trimIndent())
            val upper = pack(fixture, backend, "upper", """
                "reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/upper-a","weight":3},{"sound":"owned:sounds/upper-b"}]}
            """.trimIndent())
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(lower.catalogEntry, upper.catalogEntry),
                listOf(upper.metadata.id.value, lower.metadata.id.value)))
            val prepared = backend.prepare(selected)
            var ready: Map<String, Any?> = emptyMap()
            ClientEvidence { kind, values -> assertEquals("visual-assets-ready", kind); ready = values }.recordVisualAssetsReady(prepared)
            val stack = assertIs<Map<*, *>>(ready["soundStack"])
            assertEquals(listOf("owned:upper", "owned:lower", "reforge:base"), stack["packOrder"])
            val events = assertIs<Map<*, *>>(stack["events"])
            val tank = assertIs<Map<*, *>>(events["reforge:events/tank-fire"])
            val candidates = assertIs<List<*>>(tank["candidates"]).map { assertIs<Map<*, *>>(it) }
            assertEquals(listOf("owned:sounds/upper-a", "owned:sounds/upper-b"), candidates.map { it["sound"] })
            assertEquals(listOf(3, 1), candidates.map { it["weight"] })
            assertEquals(listOf("owned:upper", "owned:upper"), candidates.map { it["providerId"] })
            assertEquals(4L, assertIs<Number>(tank["totalWeight"]).toLong())
            val other = assertIs<Map<*, *>>(events["owned:events/other"])
            assertEquals("owned:sounds/lower-b", other["sound"])
            assertEquals("owned:lower", other["providerId"])
            prepared.verifyForPublication()
        }
    }
}
