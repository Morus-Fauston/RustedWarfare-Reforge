package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.TreeSet

/** Operates only on already parsed immutable declarations; no file or renderer access. */
internal object ContentPackComposer {
    fun compose(packs: List<LoadedContentPack>, errorPath: Path): LoadedContentSet {
        if (packs.isEmpty()) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, errorPath, field = "contentPacks", reason = "Select at least one package")
        val byId = linkedMapOf<String, LoadedContentPack>()
        for (pack in packs) {
            if (byId.putIfAbsent(pack.id, pack) != null)
                reject(ContentErrorCode.IDENTITY_CONFLICT, pack.manifest, field = "id", ref = pack.id, reason = "Duplicate package identity")
            for (dependency in pack.dependencies) if (packs.none { it.id == dependency })
                reject(ContentErrorCode.BAD_REFERENCE, pack.manifest, field = "dependencies", ref = dependency, reason = "Missing enabled local dependency")
        }
        for (pack in packs) for ((contentId, target) in pack.replacements) {
            val targetPack = byId[target.targetPackId]
            if (targetPack == null || target.targetContentId !in targetPack.units)
                reject(ContentErrorCode.BAD_REFERENCE, pack.manifest, field = "replacements", ref = contentId,
                    reason = "Target package ${target.targetPackId} does not declare ${target.targetContentId}")
        }
        val remaining = byId.mapValues { it.value.dependencies.size }.toMutableMap()
        val consumers = mutableMapOf<String, MutableList<String>>()
        for (pack in packs) for (dependency in pack.dependencies)
            consumers.getOrPut(dependency) { mutableListOf() }.add(pack.id)
        val ready = TreeSet(remaining.filterValues { it == 0 }.keys)
        val ordered = mutableListOf<LoadedContentPack>()
        while (ready.isNotEmpty()) {
            val id = ready.pollFirst()
            ordered += byId.getValue(id)
            for (consumer in consumers[id].orEmpty()) {
                val count = remaining.getValue(consumer) - 1
                remaining[consumer] = count
                if (count == 0) ready += consumer
            }
        }
        if (ordered.size != packs.size) {
            val cyclic = remaining.filterValues { it > 0 }.keys.sorted()
            reject(ContentErrorCode.BAD_REFERENCE, byId.getValue(cyclic.first()).manifest, field = "dependencies",
                ref = cyclic.joinToString(), reason = "Cyclic enabled package dependencies")
        }
        val units = linkedMapOf<String, UnitDefinition>()
        val provenance = linkedMapOf<String, ContentProvenance>()
        val sources = linkedMapOf<Path, ContentSourceIdentity>()
        for (pack in ordered) {
            for ((id, unit) in pack.units) {
                val previous = provenance[id]
                val target = pack.replacements[id]
                if (previous != null && (target == null || target.targetPackId != previous.packageId || target.targetContentId != id))
                    reject(ContentErrorCode.IDENTITY_CONFLICT, pack.manifest, field = "replacements", ref = id,
                        reason = "Duplicate unit must explicitly replace its current source package ${previous.packageId}")
                if (target != null && previous == null)
                    reject(ContentErrorCode.BAD_REFERENCE, pack.manifest, field = "replacements", ref = id, reason = "Replacement target is absent")
                units[id] = unit
                provenance[id] = ContentProvenance(pack.id, pack.unitFiles[id] ?: pack.manifest,
                    previous?.let { it.replacedPackageIds + it.packageId }.orEmpty())
            }
            for (source in pack.sources) {
                val previous = sources.putIfAbsent(source.path, source)
                if (previous != null && previous != source)
                    reject(ContentErrorCode.BAD_REFERENCE, source.path, reason = "Package source changed between declarations")
            }
        }
        for ((id, unit) in units) {
            fun bad(target: String, field: String, reason: String): Nothing = reject(ContentErrorCode.BAD_REFERENCE,
                provenance.getValue(id).entryPath, field = field, ref = target, reason = reason)
            unit.builder?.buildingTypeIds?.forEach { target ->
                if (units[target]?.construction == null) bad(target, "constructionTypes", "Builder target must exist with construction metadata in the final set")
            }
            unit.production?.let { production -> production.unitTypeIds.forEach { target ->
                val product = units[target]
                if (product?.build == null) bad(target, "productionUnits", "Production target must exist with build metadata in the final set")
                if (unit.className == "AuthoredBuilding" && (!AuthorFactoryRules.body(unit) ||
                        !AuthorFactoryRules.accepts(production.exitProfile, product)))
                    bad(target, "productionUnits", "Author factory foundation and product capabilities must match the selected existing exit")
                if (production.exitProfile == FactoryExitProfile.NATIVE_AIR &&
                    (product.movement.type != MovementType.AIR || product.rotor == null || product.combat == null))
                    bad(target, "productionUnits", "Air factory targets require an armed AIR unit with the finite rotor capability")
                if (production.exitProfile == FactoryExitProfile.CUSTOM_MECH &&
                    (target == "builder" && product.builder == null || target == "mechGun" && product.mech == null))
                    bad(target, "productionUnits", "Mech factory targets require the corresponding builder or two-legged mech capability")
            } }
        }
        return LoadedContentSet(ordered, units, provenance, sources.values.toList())
    }
}
