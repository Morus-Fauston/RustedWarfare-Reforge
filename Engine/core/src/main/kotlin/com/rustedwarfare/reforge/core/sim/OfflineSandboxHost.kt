package com.rustedwarfare.reforge.core.sim

import java.util.Collections
import java.security.MessageDigest

data class SandboxCommandRequest(val sequence: Long, val world: WorldHandle, val generation: Long, val text: String,
    val batch: SandboxCommand.SpawnBatch? = null, val projectiles: SandboxCommand.ProjectileBatch? = null) {
    constructor(sequence: Long, world: WorldHandle, generation: Long, batch: SandboxCommand.SpawnBatch) :
        this(sequence, world, generation, "", batch)
    constructor(sequence: Long, world: WorldHandle, generation: Long, projectiles: SandboxCommand.ProjectileBatch) :
        this(sequence, world, generation, "", projectiles = projectiles)
}
enum class SandboxCommandStatus { QUEUED, HELP, APPLIED, REJECTED }
/** Exact ledger values as text, preserving integer and fractional balances in the same receipt. */
data class SandboxCreditChange(val playerId: String, val before: String, val after: String)
data class SandboxUnitChange(val unitId: String, val healthBefore: Double?, val healthAfter: Double?, val destroyed: Boolean)

data class SandboxCommandReceipt(val sequence: Long, val generation: Long, val worldId: String,
    val status: SandboxCommandStatus, val command: SandboxCommand? = null, val stepIndex: Long? = null,
    val unitId: String? = null, val code: String? = null, val errorPosition: Int? = null,
    val generated: Map<String, String> = emptyMap(), val failedEntry: Int? = null,
    val projectileIds: Map<String, Long> = emptyMap(), val credits: SandboxCreditChange? = null,
    val unitChange: SandboxUnitChange? = null)

/** Local host capability, fixed before the match; ordinary player Actions never grant deployment. */
class OfflineSandboxHost(input: Scenario, val deploymentEnabled: Boolean = false, val generation: Long = 1L,
    cameraCues: List<OfflineCameraCue> = emptyList()) : AutoCloseable {
    private data class SeenRequest(val identity: Any, val receipt: SandboxCommandReceipt)

    init {
        require(generation > 0L)
        val localPlayers = input.players.filter { it.control == PlayerControl.LOCAL }.map { it.playerId }.toSet()
        val width = input.map.widthTiles.toDouble() * input.map.tileWidth
        val height = input.map.heightTiles.toDouble() * input.map.tileHeight
        fun mapPoint(point: Position) = MapBounds.contains(width, height, point, 0.0)
        for (cue in cameraCues) {
            require(cue.stepIndex >= 0 && cue.playerId in localPlayers) { "Camera cue requires a step and local player" }
            val valid = when (val motion = cue.motion) {
                is OfflineCameraMotion.Locate -> mapPoint(motion.center)
                is OfflineCameraMotion.Pan -> mapPoint(motion.center) && motion.durationTicks > 0
                is OfflineCameraMotion.Follow -> motion.unitId.isNotBlank()
                OfflineCameraMotion.TargetUnavailable -> false
            }
            require(valid) { "Invalid camera cue at step ${cue.stepIndex}" }
        }
    }

    val context: SimulationContext = when (val initialized = SimulationContext.initialize(input)) {
        is InitResult.Accepted -> initialized.context
        is InitResult.Rejected -> error("${initialized.error.code}: ${initialized.error.message}")
    }
    private val world = context.world
    private var closed = false
    private val requests = linkedMapOf<Long, SeenRequest>()
    private val pending = linkedMapOf<Long, Pair<SandboxCommandRequest, SandboxCommand>>()

    private val cameraSchedule = cameraCues.groupBy { it.playerId }
        .mapValues { (_, cues) -> ArrayDeque(cues.sortedBy { it.stepIndex }) }
    private var cameraStep = -1L

    fun takeCameraMotions(playerId: String): List<OfflineCameraMotion> {
        if (closed || !context.isCurrentSandboxWorld(world)) return emptyList()
        val cues = cameraSchedule[playerId] ?: return emptyList()
        if (cues.firstOrNull()?.stepIndex?.let { it <= cameraStep } != true) return emptyList()
        val visible by lazy { context.observePlayer(world, playerId).units }
        return buildList {
            while (cues.firstOrNull()?.stepIndex?.let { it <= cameraStep } == true) {
                val motion = cues.removeFirst().motion
                add(if (motion is OfflineCameraMotion.Follow && visible.none { it.unitId == motion.unitId && it.alive })
                    OfflineCameraMotion.TargetUnavailable else motion)
            }
        }
    }

    fun submit(input: SandboxCommandRequest): SandboxCommandReceipt {
        fun rejected(code: String, position: Int? = null) = SandboxCommandReceipt(input.sequence, input.generation,
            input.world.id, SandboxCommandStatus.REJECTED, code = code, errorPosition = position)
        if (closed || !context.isCurrentSandboxWorld(world)) return rejected("closed")
        if (input.world !== world || input.generation != generation) return rejected("stale-world")
        if (!deploymentEnabled) return rejected("deployment-disabled")
        if (input.sequence <= 0L) return rejected("invalid-request-sequence")
        val oversized = (input.batch?.entries?.size ?: 0) > SandboxCommands.MAX_BATCH_SIZE ||
            (input.projectiles?.entries?.size ?: 0) > SandboxCommands.MAX_BATCH_SIZE
        val previous = requests[input.sequence]
        if (previous == null && requests.size >= 1024) return rejected("command-limit")
        if (oversized) {
            if (previous?.identity is SandboxCommandRequest) return rejected("request-conflict")
            val identity = oversizedFingerprint(input)
            if (previous != null) return if (previous.identity == identity) previous.receipt else rejected("request-conflict")
            return rejected("invalid-batch-size").also { requests[input.sequence] = SeenRequest(identity, it) }
        }
        if (previous != null) return if (previous.identity == input) previous.receipt else rejected("request-conflict")
        if (listOf(input.batch != null, input.projectiles != null, input.text.isNotEmpty()).count { it } > 1)
            return rejected("mixed-command-request")
        val request = input.copy(
            batch = input.batch?.let { SandboxCommand.SpawnBatch(immutable(it.entries)) },
            projectiles = input.projectiles?.let { SandboxCommand.ProjectileBatch(immutable(it.entries)) })
        fun retain(result: SandboxCommandReceipt): SandboxCommandReceipt {
            requests[request.sequence] = SeenRequest(request, result)
            return result
        }
        val parsed = (request.batch ?: request.projectiles)?.let { SandboxParseResult.Parsed(it) } ?: SandboxCommands.parse(request.text)
        if (parsed is SandboxParseResult.Rejected) {
            val result = rejected(parsed.code, parsed.position).copy(failedEntry = parsed.failedEntry)
            // Do not retain arbitrarily large caller strings in the bounded local request ledger.
            return if (request.text.length <= 512) retain(result) else result
        }
        val command = (parsed as SandboxParseResult.Parsed).command.let {
            if (it is SandboxCommand.SpawnBatch) SandboxCommand.SpawnBatch(Collections.unmodifiableList(it.entries)) else it
        }
        if (command is SandboxCommand.Spawn) context.validateSandboxSpawn(command, request.generation, request.sequence)?.let {
            return retain(rejected(it.code).copy(command = command))
        }
        val aliases = when (command) {
            is SandboxCommand.SpawnBatch -> command.entries.map { it.alias }
            is SandboxCommand.ProjectileBatch -> command.entries.map { it.alias }
            else -> null
        }
        if (aliases != null) {
            if (aliases.isEmpty() || aliases.size > SandboxCommands.MAX_BATCH_SIZE)
                return retain(rejected("invalid-batch-size"))
            val seen = mutableSetOf<String>()
            for ((index, alias) in aliases.withIndex()) {
                if (!SandboxCommands.validAlias(alias) || !seen.add(alias))
                    return retain(rejected("invalid-or-duplicate-alias").copy(failedEntry = index))
            }
        }
        val receipt = SandboxCommandReceipt(request.sequence, generation, world.id,
            if (command == SandboxCommand.Help) SandboxCommandStatus.HELP else SandboxCommandStatus.QUEUED, command)
        requests[request.sequence] = SeenRequest(request, receipt)
        if (command != SandboxCommand.Help) pending[request.sequence] = request to command
        return receipt
    }

    private fun oversizedFingerprint(request: SandboxCommandRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            digest.update(value.length.toString().encodeToByteArray())
            digest.update(':'.code.toByte())
            for (character in value) {
                digest.update((character.code ushr 8).toByte())
                digest.update(character.code.toByte())
            }
        }
        field(request.text)
        field(request.batch?.entries?.size?.toString().orEmpty())
        for ((alias, spawn) in request.batch?.entries.orEmpty()) {
            field(alias)
            field(spawn.typeId)
            field(spawn.ownerPlayerId)
            field(spawn.position.x.toBits().toString())
            field(spawn.position.y.toBits().toString())
            field(spawn.heading.toBits().toString())
            field(spawn.state.name)
        }
        field(request.projectiles?.entries?.size?.toString().orEmpty())
        for (entry in request.projectiles?.entries.orEmpty()) {
            field(entry.alias)
            field(entry.sourceUnitId)
            field(entry.targetUnitId)
            field(entry.weapon.orEmpty())
            field((entry.weapon != null).toString())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun receipt(sequence: Long): SandboxCommandReceipt? = requests[sequence]?.receipt

    fun projectileEvidence(ids: Set<Long>): List<SandboxProjectileEvidence> =
        if (closed || !deploymentEnabled || ids.isEmpty()) emptyList() else context.sandboxProjectileEvidence(ids)

    fun advance(step: Step, materializeObservation: Boolean = true): Observation {
        check(!closed && context.isCurrentSandboxWorld(world)) { "Local sandbox host is closed" }
        val (observation, applied) = context.advanceSandbox(step, pending.values.toList(), materializeObservation)
        // Publish deduplication results only after the entire real simulation step committed.
        for (result in applied) {
            requests[result.sequence] = requests.getValue(result.sequence).copy(receipt = result)
            pending.remove(result.sequence)
        }
        cameraStep = observation.stepIndex
        return observation
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        context.close()
    }
}
