package com.rustedwarfare.reforge.core.knowledge

/** Knowledge retention only; these values do not define original fog geometry or update timing. */
enum class TerrainRetention { MEMORY, HARDCORE_FORGET }
enum class PathInformationSource { PLAYER_KNOWLEDGE, ORIGINAL_COMPATIBILITY }

data class KnowledgeRules(
    val retention: TerrainRetention,
    val pathSource: PathInformationSource,
    val shareAlliedVision: Boolean,
)
