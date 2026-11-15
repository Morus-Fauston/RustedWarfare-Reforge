package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.SandboxCommand
import com.rustedwarfare.reforge.core.sim.SandboxCommands
import com.rustedwarfare.reforge.core.sim.SandboxParseResult
import java.util.Locale

internal sealed interface SandboxPick {
    data class Spawn(val setup: SandboxCommand.Spawn, val repeat: Boolean = false) : SandboxPick
    data object Destroy : SandboxPick
    data class Health(val amount: Double) : SandboxPick

    val action: String get() = when (this) {
        is Spawn -> "spawn"
        Destroy -> "destroy"
        is Health -> "health"
    }

    fun command(point: Position, unitId: String?): String? = when (this) {
        is Spawn -> "/spawn ${setup.typeId} ${point.x} ${point.y} ${setup.ownerPlayerId} " +
            "heading=${setup.heading} state=${setup.state.name.lowercase(Locale.ROOT)}"
        Destroy -> unitId?.let { "/destroy $it" }
        is Health -> unitId?.let {
            val value = amount.toString()
            "/health $it ${if (value.startsWith('-')) value else "+$value"}"
        }
    }

    companion object {
        fun parseSpawn(text: String, repeat: Boolean): Spawn? {
            if (text.any { it == '\n' || it == '\r' }) return null
            val words = parameters(text).split(Regex("\\s+"), limit = 3)
            if (words.size < 2) return null
            val options = words.getOrNull(2).orEmpty()
            val spawn = parsed("/spawn ${words[0]} 0 0 ${words[1]} $options") as? SandboxCommand.Spawn ?: return null
            return Spawn(spawn, repeat)
        }

        fun parseHealth(text: String): Health? =
            (parsed("/health point ${parameters(text)}") as? SandboxCommand.Health)?.let { Health(it.amount) }

        // The desktop slash that opens the text console can also arrive as a typed character.
        private fun parameters(text: String) = text.trim().removePrefix("/").trimStart()

        private fun parsed(text: String): SandboxCommand? = (SandboxCommands.parse(text) as? SandboxParseResult.Parsed)?.command
    }
}

internal data class SandboxPickPreview(val cursor: Position, val targetId: String?, val mode: SandboxPick)
