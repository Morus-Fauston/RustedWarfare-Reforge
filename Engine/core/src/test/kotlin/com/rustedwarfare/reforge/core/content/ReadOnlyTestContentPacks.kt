package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Process-local cache of deeply immutable [LoadedContentPack] and [MapDefinition] instances
 * for read-only test fixtures (ENG-P016-010B).
 *
 * Safety invariants:
 * 1. [loadStaticPack] refuses paths outside the authored `public/inputs` fixture root so mutable
 *    `@TempDir` error-injection tests can never alias or pollute cached entries.
 * 2. [LoadedContentPack], [UnitDefinition], and [MapDefinition] wrap all collections in
 *    `Collections.unmodifiable*` and defensively copy arrays on construction and access,
 *    preventing any cross-test state mutation.
 * 3. Every `SimulationContext` / `OfflineSandboxHost` is still freshly created per test.
 */
internal object ReadOnlyTestContentPacks {
    private val staticPacks = ConcurrentHashMap<Path, LoadedContentPack>()
    private val derivedPacks = ConcurrentHashMap<String, LoadedContentPack>()
    private val staticMaps = ConcurrentHashMap<String, MapDefinition>()

    fun publicFixturesRoot(): Path =
        System.getProperty("public.fixtures.dir")?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: Path.of("../../SimulationTest/public/inputs").toAbsolutePath().normalize()

    fun loadStaticPack(path: Path): LoadedContentPack {
        val normalized = path.toAbsolutePath().normalize()
        val fixturesRoot = publicFixturesRoot()
        assertTrue(
            normalized.startsWith(fixturesRoot),
            "ReadOnlyTestContentPacks.loadStaticPack requires a path inside $fixturesRoot, got: $normalized",
        )
        return staticPacks.computeIfAbsent(normalized) { key ->
            val result = ContentLoader.loadContentPack(key)
            assertIs<ContentResult.Success<LoadedContentPack>>(
                result,
                (result as? ContentResult.Failure)?.error?.describe() ?: result.toString(),
            ).value
        }
    }

    fun loadDerivedImmutablePack(cacheKey: String, populateDirectory: (Path) -> Unit): LoadedContentPack =
        derivedPacks.computeIfAbsent(cacheKey) {
            val tempRoot = Files.createTempDirectory("reforge-ro-derived-pack-")
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching {
                    Files.walk(tempRoot).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
            })
            val packDir = tempRoot.resolve("package")
            Files.createDirectories(packDir)
            populateDirectory(packDir)
            val result = ContentLoader.loadContentPack(packDir)
            assertIs<ContentResult.Success<LoadedContentPack>>(
                result,
                (result as? ContentResult.Failure)?.error?.describe() ?: result.toString(),
            ).value
        }

    fun loadCachedMap(cacheKey: String, buildMap: () -> MapDefinition): MapDefinition =
        staticMaps.computeIfAbsent(cacheKey) { buildMap() }
}
