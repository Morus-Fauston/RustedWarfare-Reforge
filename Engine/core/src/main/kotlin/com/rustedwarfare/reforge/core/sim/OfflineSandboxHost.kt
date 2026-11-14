package com.rustedwarfare.reforge.core.sim

import java.util.Collections
import java.security.MessageDigest

data class SandboxCommandRequest(val sequence: Long, val world: WorldHandle, val generation: Long, val text: String,
    val batch: SandboxCommand.SpawnBatch? = null) {
    constructor(sequence: Long, world: WorldHandle, generation: Long, batch: SandboxCommand.SpawnBatch) :
        this(sequence, world, generation, "", batch)
}
enum class SandboxCommandStatus { QUEUED, HELP, APPLIED, REJECTED }
data class SandboxCommandReceipt(val sequence: Long, val generation: Long, val worldId: String,
    val status: SandboxCommandStatus, val command: SandboxCommand? = null, val stepIndex: Long? = null,
    val unitId: String? = null, val code: String? = null, val errorPosition: Int? = null,
    val generated: Map<String, String> = emptyMap(), val failedEntry: Int? = null)

/** Local host capability, fixed before the match; ordinary player Actions never grant deployment. */
class OfflineSandboxHost(input: Scenario, val deploymentEnabled: Boolean = false, val generation: Long = 1L) : AutoCloseable {
    private data class SeenRequest(val identity: Any, val receipt: SandboxCommandReceipt)

    init { require(generation > 0L) }

    val context: SimulationContext = when (val initialized = SimulationContext.initialize(input)) {
        is InitResult.Accepted -> initialized.context
        is InitResult.Rejected -> error("${initialized.error.code}: ${initialized.error.message}")
    }
    private val world = context.world
    private var closed = false
    private val requests = linkedMapOf<Long, SeenRequest>()
    private val pending = linkedMapOf<Long, Pair<SandboxCommandRequest, SandboxCommand>>()

    fun submit(input: SandboxCommandRequest): SandboxCommandReceipt {
        fun rejected(code: String, position: Int? = null) = SandboxCommandReceipt(input.sequence, input.generation,
            input.world.id, SandboxCommandStatus.REJECTED, code = code, errorPosition = position)
        if (closed || !context.isCurrentSandboxWorld(world)) return rejected("closed")
        if (input.world !== world || input.generation != generation) return rejected("stale-world")
        if (!deploymentEnabled) return rejected("deployment-disabled")
        if (input.sequence <= 0L) return rejected("invalid-request-sequence")
        val oversized = input.batch?.entries?.let { it.size > SandboxCommands.MAX_BATCH_SIZE } == true
        val previous = requests[input.sequence]
        if (previous == null && requests.size >= 1024) return rejected("command-limit")
        if (oversized) {
            if (previous?.identity is SandboxCommandRequest) return rejected("request-conflict")
            val identity = oversizedFingerprint(input)
            if (previous != null) return if (previous.identity == identity) previous.receipt else rejected("request-conflict")
            return rejected("invalid-batch-size").also { requests[input.sequence] = SeenRequest(identity, it) }
        }
        if (previous != null) return if (previous.identity == input) previous.receipt else rejected("request-conflict")
        if (input.batch != null && input.text.isNotEmpty()) return rejected("mixed-command-request")
        val request = if (input.batch == null) input else input.copy(
            batch = SandboxCommand.SpawnBatch(Collections.unmodifiableList(ArrayList(input.batch.entries))))
        fun retain(result: SandboxCommandReceipt): SandboxCommandReceipt {
            requests[request.sequence] = SeenRequest(request, result)
            return result
        }
        val parsed = request.batch?.let { SandboxParseResult.Parsed(it) } ?: SandboxCommands.parse(request.text)
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
        if (command is SandboxCommand.SpawnBatch) {
            if (command.entries.isEmpty() || command.entries.size > SandboxCommands.MAX_BATCH_SIZE)
                return retain(rejected("invalid-batch-size"))
            val aliases = mutableSetOf<String>()
            for ((index, entry) in command.entries.withIndex()) {
                if (!SandboxCommands.validAlias(entry.alias) || !aliases.add(entry.alias))
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
        for ((alias, spawn) in checkNotNull(request.batch).entries) {
            field(alias)
            field(spawn.typeId)
            field(spawn.ownerPlayerId)
            field(spawn.position.x.toBits().toString())
            field(spawn.position.y.toBits().toString())
            field(spawn.heading.toBits().toString())
            field(spawn.state.name)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun receipt(sequence: Long): SandboxCommandReceipt? = requests[sequence]?.receipt

    fun advance(step: Step): Observation {
        check(!closed && context.isCurrentSandboxWorld(world)) { "Local sandbox host is closed" }
        val (observation, applied) = context.advanceSandbox(step, pending.values.toList())
        // Publish deduplication results only after the entire real simulation step committed.
        for (result in applied) {
            requests[result.sequence] = requests.getValue(result.sequence).copy(receipt = result)
            pending.remove(result.sequence)
        }
        return observation
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        context.close()
    }
}
