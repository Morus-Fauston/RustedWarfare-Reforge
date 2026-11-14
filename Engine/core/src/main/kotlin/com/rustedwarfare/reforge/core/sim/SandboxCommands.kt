package com.rustedwarfare.reforge.core.sim

enum class SandboxInitialState { READY, DAMAGED }

sealed interface SandboxCommand {
    data object Help : SandboxCommand
    data class Spawn(val typeId: String, val position: Position, val ownerPlayerId: String,
                     val heading: Double = 0.0, val state: SandboxInitialState = SandboxInitialState.READY) : SandboxCommand
    data class Deployment(val alias: String, val spawn: Spawn)
    data class SpawnBatch(val entries: List<Deployment>) : SandboxCommand
}

sealed interface SandboxParseResult {
    data class Parsed(val command: SandboxCommand) : SandboxParseResult
    /** Character positions are one-based, including a missing argument just beyond the input. */
    data class Rejected(val code: String, val position: Int, val failedEntry: Int? = null) : SandboxParseResult
}

/** Finite author commands shared by local UI, headless callers and command replay. */
object SandboxCommands {
    const val HELP = "/help\n/spawn <type> <x> <y> <owner> [heading=<angle>] [state=ready|damaged]\n" +
        "/spawn-batch <alias> <type> <x> <y> <owner> [options] | <alias> …"
    internal const val MAX_BATCH_SIZE = 32
    private val identifier = Regex("[A-Za-z0-9._:-]+")
    private val token = Regex("\\S+")

    fun parse(text: String): SandboxParseResult {
        fun reject(code: String, position: Int, entry: Int? = null) = SandboxParseResult.Rejected(code, position, entry)
        if (text.length > 512) return reject("input-too-long", 513)
        val lineBreak = text.indexOfFirst { it == '\n' || it == '\r' }
        if (lineBreak >= 0) return reject("single-line-required", lineBreak + 1)
        val head = token.find(text)
        if (head?.value == "/spawn-batch") {
            val entries = mutableListOf<SandboxCommand.Deployment>()
            var offset = head.range.last + 1
            for ((index, part) in text.substring(offset).split('|').withIndex()) {
                val alias = token.find(part) ?: return reject("missing-deployment-entry", offset + 1, index)
                if (!validAlias(alias.value)) return reject("invalid-alias", offset + alias.range.first + 1, index)
                val args = part.substring(alias.range.last + 1)
                val spawn = parse("/spawn$args")
                if (spawn is SandboxParseResult.Rejected)
                    return reject(spawn.code, offset + alias.range.last + spawn.position - 5, index)
                val parsed = (spawn as SandboxParseResult.Parsed).command as SandboxCommand.Spawn
                entries += SandboxCommand.Deployment(alias.value, parsed)
                offset += part.length + 1
            }
            return SandboxParseResult.Parsed(SandboxCommand.SpawnBatch(entries))
        }
        val words = token.findAll(text).toList()
        if (words.isEmpty()) return reject("expected-command", 1)
        fun at(index: Int) = words[index].range.first + 1
        if (words[0].value == "/help") return if (words.size == 1)
            SandboxParseResult.Parsed(SandboxCommand.Help) else reject("unexpected-argument", at(1))
        if (words[0].value != "/spawn") return reject("unknown-command", at(0))
        if (words.size < 5) return reject("missing-argument", text.length + 1)
        if (!identifier.matches(words[1].value)) return reject("invalid-type-id", at(1))
        if (!identifier.matches(words[4].value)) return reject("invalid-owner-id", at(4))
        fun number(value: String) = value.toDoubleOrNull()?.takeIf { it.isFinite() }
        val x = number(words[2].value) ?: return reject("invalid-number", at(2))
        val y = number(words[3].value) ?: return reject("invalid-number", at(3))
        var heading = 0.0
        var state = SandboxInitialState.READY
        val seen = mutableSetOf<String>()
        for (index in 5 until words.size) {
            val option = words[index].value
            val key = option.substringBefore('=')
            if (key !in setOf("heading", "state")) return reject("unknown-field", at(index))
            if (!seen.add(key)) return reject("duplicate-field", at(index))
            val value = option.substringAfter('=', "")
            when (key) {
                "heading" -> heading = number(value) ?: return reject("invalid-number", at(index))
                "state" -> state = when (value) {
                    "ready" -> SandboxInitialState.READY
                    "damaged" -> SandboxInitialState.DAMAGED
                    else -> return reject("invalid-state", at(index))
                }
            }
        }
        return SandboxParseResult.Parsed(SandboxCommand.Spawn(words[1].value, Position(x, y), words[4].value, heading, state))
    }

    internal fun validAlias(value: String): Boolean = value.length <= 64 && identifier.matches(value)
}
