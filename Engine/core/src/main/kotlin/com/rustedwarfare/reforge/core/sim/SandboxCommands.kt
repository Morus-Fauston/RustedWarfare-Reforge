package com.rustedwarfare.reforge.core.sim

enum class SandboxInitialState { READY, DAMAGED }

sealed interface SandboxCommand {
    data object Help : SandboxCommand
    data class Spawn(val typeId: String, val position: Position, val ownerPlayerId: String,
                     val heading: Double = 0.0, val state: SandboxInitialState = SandboxInitialState.READY) : SandboxCommand
}

sealed interface SandboxParseResult {
    data class Parsed(val command: SandboxCommand) : SandboxParseResult
    /** Character positions are one-based, including a missing argument just beyond the input. */
    data class Rejected(val code: String, val position: Int) : SandboxParseResult
}

/** Finite author commands shared by local UI, headless callers and command replay. */
object SandboxCommands {
    const val HELP = "/help\n/spawn <type> <x> <y> <owner> [heading=<angle>] [state=ready|damaged]"
    private val identifier = Regex("[A-Za-z0-9._:-]+")
    private val token = Regex("\\S+")

    fun parse(text: String): SandboxParseResult {
        fun reject(code: String, position: Int) = SandboxParseResult.Rejected(code, position)
        if (text.length > 512) return reject("input-too-long", 513)
        val lineBreak = text.indexOfFirst { it == '\n' || it == '\r' }
        if (lineBreak >= 0) return reject("single-line-required", lineBreak + 1)
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
}
