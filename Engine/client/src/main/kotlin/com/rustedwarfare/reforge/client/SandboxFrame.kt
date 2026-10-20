package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import java.util.Collections

enum class SandboxMode { NO_FOG, MEMORY, HARDCORE }

data class SandboxOptions(val mode: SandboxMode = SandboxMode.NO_FOG, val shareAlliedVision: Boolean = false) {
    internal fun knowledgeRules(): KnowledgeRules? = when (mode) {
        SandboxMode.NO_FOG -> null
        SandboxMode.MEMORY -> KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, shareAlliedVision)
        SandboxMode.HARDCORE -> KnowledgeRules(TerrainRetention.HARDCORE_FORGET, PathInformationSource.PLAYER_KNOWLEDGE, shareAlliedVision)
    }
}

data class TerrainFrameCell(val column: Int, val row: Int, val gid: Int?, val visible: Boolean) {
    val known: Boolean get() = gid != null
}

class SandboxFrame internal constructor(val observation: PlayerObservation, terrain: List<TerrainFrameCell>) {
    val terrain: List<TerrainFrameCell> = Collections.unmodifiableList(ArrayList(terrain))
}
