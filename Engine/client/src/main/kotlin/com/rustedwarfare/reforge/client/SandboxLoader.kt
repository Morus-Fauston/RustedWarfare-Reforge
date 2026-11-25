package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.sim.InitResult
import com.rustedwarfare.reforge.core.sim.SimulationContext
import com.rustedwarfare.reforge.core.content.ContentSourceIdentity
import com.rustedwarfare.reforge.core.content.ContentResult
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class SandboxPreparationTiming(val startedNanos: Long, val scenarioPreparedNanos: Long,
    val visualsPreparedNanos: Long, val completedNanos: Long)

data class LoadedSandbox(val scenario: Scenario, val visuals: PreparedVisualAssets?, val renderResources: SandboxRenderResources,
                         val contentSources: List<ContentSourceIdentity> = emptyList(), val inputRoot: Path? = null,
                         val contentPack: ContentPackChoice? = null, val preparationTiming: SandboxPreparationTiming? = null,
                         val startupNotices: List<String> = emptyList())

/** The render thread alone consumes results. Cancelled jobs never publish a world or own GL resources. */
class SandboxLoader internal constructor(private val load: ((Path) -> Scenario)?) : AutoCloseable {
    constructor() : this(null)
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "p001-content").apply { isDaemon = true } }
    private var pending: Future<Result<LoadedSandbox>>? = null

    fun request(root: Path, prepareVisuals: (() -> PreparedVisualAssets)? = null) {
        begin(root, null, prepareVisuals)
    }

    fun requestPack(root: Path, contentPack: ContentPackChoice, prepareVisuals: (() -> PreparedVisualAssets)? = null) {
        begin(root, contentPack, prepareVisuals)
    }

    fun requestTestScenario(choice: TestScenarioChoice, prepareVisuals: (() -> PreparedVisualAssets)? = null) {
        begin(choice.root, choice.content, prepareVisuals, choice)
    }

    fun requestCatalogScenario(
        entry: com.rustedwarfare.reforge.core.sim.ScenarioCatalogEntry,
        root: Path,
        contentPack: ContentPackChoice?,
        prepareVisuals: (() -> PreparedVisualAssets)? = null,
    ) {
        cancel()
        pending = executor.submit<Result<LoadedSandbox>> { runCatching {
            val startedNanos = System.nanoTime()
            val checked = contentPack?.revalidate()
            val autoPack = if (checked == null && java.nio.file.Files.isRegularFile(root.resolve("pack.json"))) {
                when (val loadedPack = com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(root)) {
                    is ContentResult.Success -> ContentPackChoice(loadedPack.value, loadedPack.value.units.keys.first())
                    is ContentResult.Failure -> null
                }
            } else null
            val effectivePack = checked ?: autoPack ?: fallbackCatalogPack(root)
            val baseDefs = effectivePack?.content?.units
                ?: ControlledScenario.prepare(root).scenario.unitDefinitions
            val definitions = com.rustedwarfare.reforge.core.sim.ScenarioCatalog.deriveBenchmarkDefinitions(baseDefs)
            val builtScene = entry.buildScenario(definitions)
            val usedTypes = builtScene.units.mapTo(linkedSetOf()) { it.typeId }
            val scene = builtScene.copy(
                unitDefinitions = builtScene.unitDefinitions.filterKeys { it in usedTypes },
            )
            val scenarioPreparedNanos = System.nanoTime()
            val visuals = prepareVisuals?.invoke()?.let { before -> effectivePack?.let { contentPackVisuals(before, it) } ?: before }
            val visualsPreparedNanos = System.nanoTime()
            val resources = SandboxRenderResources.from(scene)
            val catalogVisualAliases = mapOf(
                "c_tank" to listOf("c_tank", "tank"),
                "fast_scout" to listOf("c_tank", "tank"),
                "slow_heavy" to listOf("heavyTank", "megaTank", "mammothTank", "c_tank", "tank"),
                "helicopter" to listOf("helicopter"),
            )
            val renderResources = visuals?.let {
                resources.bindVisuals(it, useScenarioTerrain = false, unitVisualAliases = catalogVisualAliases)
            } ?: resources
            checked?.content?.verifyStable()?.let { stable ->
                if (stable is ContentResult.Failure) error(stable.error.describe())
            }
            visuals?.verifyForPublication()
            val notices = listOfNotNull(entry.knownLimitation)
            LoadedSandbox(
                scenario = scene,
                visuals = visuals,
                renderResources = renderResources,
                contentSources = effectivePack?.content?.sources ?: emptyList(),
                inputRoot = root.toAbsolutePath().normalize(),
                contentPack = effectivePack,
                preparationTiming = SandboxPreparationTiming(startedNanos, scenarioPreparedNanos, visualsPreparedNanos, System.nanoTime()),
                startupNotices = notices,
            )
        } }
    }

    private fun fallbackCatalogPack(root: Path): ContentPackChoice? {
        val candidates = listOf(
            root.resolve("package"),
            root.resolve("json/package"),
            root.resolve("../phase-six-a-sandbox/json/package"),
            root.resolve("../phase-seven-sandbox/json/package"),
            Path.of("test-scenarios/inputs/phase-six-a-sandbox/json/package"),
            Path.of("SimulationTest/public/inputs/phase-six-a-sandbox/json/package"),
        )
        for (candidate in candidates) {
            val normalized = candidate.toAbsolutePath().normalize()
            if (java.nio.file.Files.isRegularFile(normalized.resolve("pack.json"))) {
                val loaded = com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(normalized)
                if (loaded is ContentResult.Success && loaded.value.units.isNotEmpty()) {
                    return ContentPackChoice(loaded.value, loaded.value.units.keys.first())
                }
            }
        }
        return null
    }

    fun requestSkirmishMap(
        mapPath: Path,
        root: Path,
        contentPack: ContentPackChoice?,
        originalDirectory: Path? = null,
        displayName: String? = null,
        prepareVisuals: (() -> PreparedVisualAssets)? = null,
    ) {
        cancel()
        pending = executor.submit<Result<LoadedSandbox>> { runCatching {
            val startedNanos = System.nanoTime()
            val checked = contentPack?.revalidate()
            val autoPack = if (checked == null && java.nio.file.Files.isRegularFile(root.resolve("pack.json"))) {
                when (val loadedPack = com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(root)) {
                    is ContentResult.Success -> ContentPackChoice(loadedPack.value, loadedPack.value.units.keys.first())
                    is ContentResult.Failure -> null
                }
            } else null
            val effectivePack = checked ?: autoPack
            val definitions = effectivePack?.content?.units ?: ControlledScenario.prepare(root).scenario.unitDefinitions
            val built = TmxSkirmishScenarioBuilder.build(mapPath, definitions, originalDirectory, displayName)
            val scene = built.scenario
            val scenarioPreparedNanos = System.nanoTime()
            val visuals = prepareVisuals?.invoke()?.let { before -> effectivePack?.let { contentPackVisuals(before, it) } ?: before }
            val visualsPreparedNanos = System.nanoTime()
            val resources = SandboxRenderResources.from(scene)
            val originalTilesetRoot = TmxSkirmishScenarioBuilder.tilesetFallbackRoots(originalDirectory).firstOrNull()
            val renderResources = visuals?.let { resources.bindVisuals(it, useScenarioTerrain = false, originalTilesetRoot = originalTilesetRoot) }
                ?: resources.resolveTileAtlases(null, originalTilesetRoot)
            checked?.content?.verifyStable()?.let { stable ->
                if (stable is ContentResult.Failure) error(stable.error.describe())
            }
            visuals?.verifyForPublication()
            LoadedSandbox(
                scenario = scene,
                visuals = visuals,
                renderResources = renderResources,
                contentSources = effectivePack?.content?.sources ?: emptyList(),
                inputRoot = root.toAbsolutePath().normalize(),
                contentPack = effectivePack,
                preparationTiming = SandboxPreparationTiming(startedNanos, scenarioPreparedNanos, visualsPreparedNanos, System.nanoTime()),
                startupNotices = built.startupNotices,
            )
        } }
    }

    internal fun qualify(root: Path, contentPack: ContentPackChoice?) {
        begin(root, contentPack, null, qualify = true)
    }

    private fun begin(root: Path, contentPack: ContentPackChoice?, prepareVisuals: (() -> PreparedVisualAssets)?,
                      testScenario: TestScenarioChoice? = null, qualify: Boolean = false) {
        cancel()
        pending = executor.submit<Result<LoadedSandbox>> { runCatching {
            val startedNanos = System.nanoTime()
            testScenario?.verifyStable()
            val checked = contentPack?.revalidate()
            val prepared = if (testScenario != null) PreparedControlledScenario(testScenario.scenario, emptyList())
                else if (checked != null) PreparedControlledScenario(contentPackScenario(root, checked), emptyList())
                else load?.let { PreparedControlledScenario(it(root), emptyList()) } ?: ControlledScenario.prepare(root)
            val scene = prepared.scenario
            if (qualify) when (val initialization = SimulationContext.initialize(scene)) {
                is InitResult.Accepted -> initialization.context.close()
                is InitResult.Rejected -> error("${initialization.error.code}: ${initialization.error.message}")
            }
            val scenarioPreparedNanos = System.nanoTime()
            val visuals = prepareVisuals?.invoke()?.let { before -> checked?.let { contentPackVisuals(before, it) } ?: before }
            val visualsPreparedNanos = System.nanoTime()
            val resources = SandboxRenderResources.from(scene)
            val renderResources = visuals?.let {
                resources.bindVisuals(it, useScenarioTerrain = testScenario == null && checked?.content?.openingPack != null)
            } ?: resources
            prepared.verifyStable()
            checked?.content?.verifyStable()?.let { stable ->
                if (stable is ContentResult.Failure) error(stable.error.describe())
            }
            testScenario?.verifyStable()
            visuals?.verifyForPublication()
            LoadedSandbox(scene, visuals, renderResources, checked?.content?.sources ?: prepared.sources,
                root.toAbsolutePath().normalize(), checked,
                SandboxPreparationTiming(startedNanos, scenarioPreparedNanos, visualsPreparedNanos, System.nanoTime()))
        } }
    }
    fun poll(): Result<Scenario>? = pollPrepared()?.map { it.scenario }
    fun pollPrepared(): Result<LoadedSandbox>? {
        val job = pending ?: return null
        if (!job.isDone) return null
        pending = null
        return job.get()
    }
    fun cancel() { pending?.cancel(true); pending = null }
    override fun close() { cancel(); executor.shutdownNow() }
}
