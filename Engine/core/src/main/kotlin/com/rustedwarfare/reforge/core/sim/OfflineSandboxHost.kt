package com.rustedwarfare.reforge.core.sim

data class SandboxCommandRequest(val sequence: Long, val world: WorldHandle, val generation: Long, val text: String)
enum class SandboxCommandStatus { QUEUED, HELP, APPLIED, REJECTED }
data class SandboxCommandReceipt(val sequence: Long, val generation: Long, val worldId: String,
    val status: SandboxCommandStatus, val command: SandboxCommand? = null, val stepIndex: Long? = null,
    val unitId: String? = null, val code: String? = null, val errorPosition: Int? = null)

/** Local host capability, fixed before the match; ordinary player Actions never grant deployment. */
class OfflineSandboxHost(input: Scenario, val deploymentEnabled: Boolean = false, val generation: Long = 1L) : AutoCloseable {
    init { require(generation > 0L) }

    val context: SimulationContext = when (val initialized = SimulationContext.initialize(input)) {
        is InitResult.Accepted -> initialized.context
        is InitResult.Rejected -> error("${initialized.error.code}: ${initialized.error.message}")
    }
    private val world = context.world
    private var closed = false
    private val requests = linkedMapOf<Long, Pair<SandboxCommandRequest, SandboxCommandReceipt>>()
    private val pending = linkedMapOf<Long, Pair<SandboxCommandRequest, SandboxCommand.Spawn>>()

    fun submit(request: SandboxCommandRequest): SandboxCommandReceipt {
        fun rejected(code: String, position: Int? = null) = SandboxCommandReceipt(request.sequence, request.generation,
            request.world.id, SandboxCommandStatus.REJECTED, code = code, errorPosition = position)
        if (closed || !context.isCurrentSandboxWorld(world)) return rejected("closed")
        if (request.world !== world || request.generation != generation) return rejected("stale-world")
        if (!deploymentEnabled) return rejected("deployment-disabled")
        if (request.sequence <= 0L) return rejected("invalid-request-sequence")
        requests[request.sequence]?.let { return if (it.first == request) it.second else rejected("request-conflict") }
        if (requests.size >= 1024) return rejected("command-limit")
        fun retain(result: SandboxCommandReceipt): SandboxCommandReceipt {
            requests[request.sequence] = request to result
            return result
        }
        val parsed = SandboxCommands.parse(request.text)
        if (parsed is SandboxParseResult.Rejected) {
            val result = rejected(parsed.code, parsed.position)
            // Do not retain arbitrarily large caller strings in the bounded local request ledger.
            return if (request.text.length <= 512) retain(result) else result
        }
        val command = (parsed as SandboxParseResult.Parsed).command
        if (command is SandboxCommand.Spawn) context.validateSandboxSpawn(command, request.generation, request.sequence)?.let {
            return retain(rejected(it.code).copy(command = command))
        }
        val receipt = SandboxCommandReceipt(request.sequence, generation, world.id,
            if (command == SandboxCommand.Help) SandboxCommandStatus.HELP else SandboxCommandStatus.QUEUED, command)
        requests[request.sequence] = request to receipt
        if (command is SandboxCommand.Spawn) pending[request.sequence] = request to command
        return receipt
    }

    fun receipt(sequence: Long): SandboxCommandReceipt? = requests[sequence]?.second

    fun advance(step: Step): Observation {
        check(!closed && context.isCurrentSandboxWorld(world)) { "Local sandbox host is closed" }
        val (observation, applied) = context.advanceSandbox(step, pending.values.toList())
        // Publish deduplication results only after the entire real simulation step committed.
        for (result in applied) {
            val request = requests.getValue(result.sequence).first
            requests[result.sequence] = request to result
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
