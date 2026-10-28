package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Fixed economic families; ordinary upgrades require explicit activation. */
internal object EconomyContentReader {
    private val actionFields = setOf("convertTo", "price", "text", "description", "displayType", "buildSpeed", "iconExtraImage", "iconExtraColor")
    private val fields = mapOf(
        "core" to setOf("name", "class", "copyFrom", "dont_load", "price", "maxHp", "mass", "displayLocaleKey",
            "overrideAndReplace", "generation_resources", "footprint", "constructionFootprint", "techLevel",
            "buildSpeed", "radius", "displayRadius", "isBio", "isBuilding", "placeOnlyOnResPool",
            "buildingSelectionOffset", "numBitsOnDeath", "hideScorchMark", "effectOnDeath", "explodeTypeOnDeath",
            "fireTurretXAtSelfOnDeath"),
        "graphics" to setOf("total_frames", "image", "image_back", "image_back_always_use_full_image", "image_wreak"),
        "attack" to setOf("canAttack", "canAttackFlyingUnits", "canAttackLandUnits", "canAttackUnderwaterUnits", "maxAttackRange"),
        "movement" to setOf("movementType", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration"),
        "animation_idle" to setOf("onActions", "body_0s", "body_0.4s", "body_0.6s", "body_0.8s", "body_1.0s", "body_1.3s", "pingPong",
            "body_0.2s", "body_0.3s", "body_0.5s", "body_0.7s", "KeyframeTimeScale", "@copyFrom_skipThisSection"),
        "action_upgradeT2" to actionFields + "@copyFrom_skipThisSection",
        "action_upgradeT3" to actionFields,
        "action_overclock" to actionFields,
        "action_reinforce" to actionFields,
        "ai" to setOf("buildPriority", "recommendedInEachBasePriorityIfUnmet", "recommendedInEachBaseNum",
            "noneInBaseExtraPriority", "noneGlobalExtraPriority", "maxEachBase", "maxGlobal", "upgradedFrom"),
        "turret_explode" to setOf("x", "y", "canShoot", "projectile"),
        "projectile_explode" to setOf("life", "instant", "speed", "largeHitEffect", "frame", "shadowFrame", "drawType",
            "targetGround", "areaDamage", "areaRadius", "areaExpandTime", "deflectionPower", "lightColor", "lightSize", "friendlyFire"),
    )

    fun read(path: Path): UnitDefinition = read(path, false)

    fun read(path: Path, enableUpgrades: Boolean): UnitDefinition {
        ordinary(path, path)
        val child = parse(path)
        val id = child["core"]?.get("name")
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "core", "name", reason = "Missing economic identity")
        val ids = if (enableUpgrades) setOf("extractorT1", "extractorT2", "extractorT3", "fabricatorT1", "fabricatorT2", "fabricatorT3")
            else setOf("extractorT1", "fabricatorT1")
        if (id !in ids) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name", id,
            "Unsupported economic type")
        val extractor = id.startsWith("extractor")
        val tier = id.last().digitToInt()
        val family = if (extractor) "extractor" else "fabricator"
        val parentName = if (extractor) "extractor_common.ini" else if (tier > 1) "fabricatorT1.ini" else null
        if ((extractor && child["core"]?.get("copyFrom") != "extractor_common.ini") ||
            (!extractor && child["core"]?.get("copyFrom") != parentName) || child["core"]?.get("dont_load") == "true")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "copyFrom", reason = "Expected the fixed extractor template")
        val parent = parentName?.let { parse(reference(path, it)) } ?: emptyMap()
        if (extractor && (parent["core"]?.get("dont_load") != "true" || parent["core"]?.containsKey("copyFrom") == true))
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "copyFrom", reason = "Expected one template layer")
        if (!extractor && tier > 1 && (parent["core"]?.get("name") != "fabricatorT1" ||
                parent["core"]?.containsKey("copyFrom") == true || parent["core"]?.containsKey("dont_load") == true))
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "copyFrom", reason = "Expected the fixed T1 parent")
        val values = linkedMapOf<String, MutableMap<String, String>>()
        for (source in listOf(parent, child)) for ((section, entries) in source) {
            if ("@copyFrom_skipThisSection" in entries) {
                if (extractor || tier == 1 || source !== child || entries["@copyFrom_skipThisSection"] != "true")
                    reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, reason = "Unexpected section inheritance override")
                values.remove(section)
            }
            val effective = entries - "@copyFrom_skipThisSection"
            if (effective.isNotEmpty()) values.getOrPut(section) { linkedMapOf() }.putAll(effective)
        }
        fun required(section: String, key: String) = values[section]?.get(key)?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, key, reason = "Missing economic field")
        fun fixed(section: String, key: String, expected: String) {
            if (required(section, key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, key,
                reason = "Expected fixed value $expected")
        }
        fun number(section: String, key: String, expected: Double): Double {
            val value = required(section, key).toDoubleOrNull()
            if (value == null || !value.isFinite()) reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key,
                reason = "Expected finite number")
            if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, key,
                reason = "Expected fixed value $expected")
            return value
        }
        fun exactKeys(section: String, expected: Set<String>) {
            if (values[section]?.keys != expected) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section,
                reason = "Expected the fixed fields for $id")
        }
        val extractorOnly = setOf("buildingSelectionOffset", "numBitsOnDeath", "hideScorchMark", "effectOnDeath",
            "explodeTypeOnDeath", "dont_load")
        if (!extractor && values["core"]!!.keys.any { it in extractorOnly })
            reject(ContentErrorCode.UNSUPPORTED_FIELD, path, "core", reason = "Extractor-only economic field")
        if (child["core"]?.containsKey("dont_load") == true)
            reject(ContentErrorCode.UNSUPPORTED_FIELD, path, "core", "dont_load", reason = "Only the template may set dont_load")
        exactKeys("graphics", if (extractor) setOf("total_frames", "image", "image_back", "image_back_always_use_full_image")
            else setOf("total_frames", "image", "image_wreak"))
        fixed("core", "displayLocaleKey", if (extractor) "extractor" else "fabricator")
        val animation = if (extractor) mapOf("onActions" to "idle", "body_0s" to "{frame:0}",
            "body_0.8s" to "{frame:3}", "body_1.0s" to "{frame:3}", "pingPong" to "true") +
            (if (tier == 1) emptyMap() else mapOf("KeyframeTimeScale" to if (tier == 2) "0.5" else "0.33"))
        else when (tier) {
            1 -> mapOf("onActions" to "idle", "body_0s" to "{frame:2}", "body_0.4s" to "{frame:2}",
                "body_0.6s" to "{frame:0}", "body_0.8s" to "{frame:0}", "body_1.3s" to "{frame:2}")
            2 -> mapOf("onActions" to "idle", "body_0s" to "{frame:2}", "body_0.3s" to "{frame:2}",
                "body_0.5s" to "{frame:0}", "body_0.6s" to "{frame:0}", "body_1.0s" to "{frame:2}")
            else -> mapOf("onActions" to "idle", "body_0s" to "{frame:2}", "body_0.2s" to "{frame:2}",
                "body_0.4s" to "{frame:0}", "body_0.5s" to "{frame:0}", "body_0.7s" to "{frame:2}")
        }
        exactKeys("animation_idle", animation.keys)
        for ((key, value) in animation) fixed("animation_idle", key, value)
        val ai = if (extractor) mapOf("buildPriority" to .4, "recommendedInEachBasePriorityIfUnmet" to .55,
            "recommendedInEachBaseNum" to 2.0, "noneInBaseExtraPriority" to .43, "maxEachBase" to 99.0)
        else mapOf("buildPriority" to .03, "noneInBaseExtraPriority" to .04, "noneGlobalExtraPriority" to .1,
            "maxEachBase" to 1.0, "maxGlobal" to 5.0)
        exactKeys("ai", ai.keys + if (extractor || tier > 1) setOf("upgradedFrom") else emptySet())
        for ((key, value) in ai) number("ai", key, value)
        if (extractor || tier > 1) fixed("ai", "upgradedFrom", "${family}T1")
        val expectedActions = when {
            tier < 3 -> setOf("action_upgradeT${tier + 1}")
            extractor -> setOf("action_overclock", "action_reinforce")
            else -> emptySet()
        }
        if (values.keys.filter { it.startsWith("action_") }.toSet() != expectedActions)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, reason = "Unexpected economic action set")
        fun checkAction(section: String, target: String, price: Double, speed: String, text: String, description: String) {
            exactKeys(section, actionFields)
            fixed(section, "convertTo", target); number(section, "price", price)
            if (speed.endsWith('s')) fixed(section, "buildSpeed", speed) else number(section, "buildSpeed", speed.toDouble())
            fixed(section, "text", text); fixed(section, "description", description)
            fixed(section, "displayType", "upgrade")
            fixed(section, "iconExtraImage", "SHARED:icon_upgrade_top.png"); fixed(section, "iconExtraColor", "#AAFFFFFF")
        }
        val upgradePrice = if (extractor) if (tier == 1) 1400L else 4000L else if (tier == 1) 4400L else 7500L
        val upgradeRate = if (extractor) if (tier == 1) .0006f else .0003f else 1f / ((if (tier == 1) 65.6f else 93.3f) * 60f)
        if (tier < 3) checkAction("action_upgradeT${tier + 1}", "${family}T${tier + 1}", upgradePrice.toDouble(),
            if (extractor) if (tier == 1) "0.0006" else "0.0003" else if (tier == 1) "65.6s" else "93.3s",
            "i:gui.actions.upgradeT${tier + 1}", "i:units.$family.upgrade.description" + if (tier == 2) "T3" else "")
        else if (extractor) {
            checkAction("action_overclock", "extractorT3_overclocked", 8000.0, "0.0002",
                "i:units.extractor.upgrade_overclock.text", "i:units.extractor.upgrade_overclock.description")
            checkAction("action_reinforce", "extractorT3_reinforced", 3000.0, "0.0007",
                "i:units.extractor.upgrade_reinforce.text", "i:units.extractor.upgrade_reinforce.description")
        }
        if (tier == 1 || !extractor) fixed("core", "overrideAndReplace", if (tier == 1) family else "NONE")
        else if (values["core"]?.containsKey("overrideAndReplace") == true)
            reject(ContentErrorCode.UNSUPPORTED_FIELD, path, "core", "overrideAndReplace", reason = "Only T1 overrides the native extractor")
        val income = (if (extractor) listOf(8, 12, 20) else listOf(2, 7, 14))[tier - 1]
        fixed("core", "generation_resources", "credits=$income")
        val shape = if (extractor) "0,0,0,0" else "-1,-1,1,1"
        fixed("core", "footprint", shape)
        fixed("core", "constructionFootprint", shape)
        fixed("core", "isBuilding", "true")
        if (extractor) fixed("core", "placeOnlyOnResPool", "true")
        else if (values["core"]?.containsKey("placeOnlyOnResPool") == true) fixed("core", "placeOnlyOnResPool", "false")
        if (values["core"]?.containsKey("isBio") == true) fixed("core", "isBio", "false")
        if (values["core"]?.containsKey("class") == true) fixed("core", "class", "CustomUnitMetadata")
        number("core", "mass", 9000.0); number("core", "techLevel", if (extractor) tier.toDouble() else minOf(tier, 2).toDouble())
        if (extractor) {
            number("core", "buildingSelectionOffset", 4.0); number("core", "numBitsOnDeath", 16.0)
            fixed("core", "hideScorchMark", "true")
            fixed("core", "effectOnDeath", "resourcePoolSmoke")
            fixed("core", "explodeTypeOnDeath", "buildingNoShockwaveOrSmoke")
            fixed("graphics", "image_back_always_use_full_image", "true")
        }
        fixed("movement", "movementType", "NONE")
        number("movement", "moveSpeed", 0.0); number("movement", "maxTurnSpeed", 0.0)
        number("movement", "moveAccelerationSpeed", .01); number("movement", "moveDecelerationSpeed", .01)
        number("movement", "turnAcceleration", .1)
        for (key in listOf("canAttack", "canAttackFlyingUnits", "canAttackLandUnits", "canAttackUnderwaterUnits"))
            fixed("attack", key, "false")
        number("attack", "maxAttackRange", 0.0)
        if (extractor && (values.keys.any { it == "turret_explode" || it == "projectile_explode" } ||
            values["core"]?.containsKey("fireTurretXAtSelfOnDeath") == true || values["graphics"]?.containsKey("image_wreak") == true))
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, reason = "Extractor has no configured death turret or corpse")
        if (!extractor) {
            fixed("core", "fireTurretXAtSelfOnDeath", "explode")
            number("turret_explode", "x", 0.0); number("turret_explode", "y", 0.0)
            fixed("turret_explode", "canShoot", "false"); fixed("turret_explode", "projectile", "explode")
            for ((key, expected) in mapOf("life" to 99.0, "speed" to 1.0, "frame" to 0.0, "shadowFrame" to 1.0,
                    "drawType" to 1.0, "areaDamage" to listOf(350.0, 450.0, 850.0)[tier - 1], "areaRadius" to 90.0, "areaExpandTime" to 35.0,
                    "deflectionPower" to -1.0, "lightSize" to 1.0)) number("projectile_explode", key, expected)
            for (key in listOf("instant", "largeHitEffect", "targetGround", "friendlyFire")) fixed("projectile_explode", key, "true")
            fixed("projectile_explode", "lightColor", "#FFEEEE00")
        }
        fun image(key: String, width: Int, height: Int): Path {
            val file = reference(path, required("graphics", key))
            try {
                TurretContentReader.validatePng(file)
                java.io.DataInputStream(Files.newInputStream(file)).use {
                    it.readLong(); it.readInt(); it.readInt()
                    require(it.readInt() == width && it.readInt() == height) { "Economic PNG dimensions" }
                }
            } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return file
        }
        val retained = values.flatMap { (s, entries) -> entries.keys.map { "$s.$it" } }.filter {
            (it.startsWith("action_") || it.startsWith("animation_") || it.startsWith("ai.")) &&
                !(enableUpgrades && tier < 3 && it in setOf("action_upgradeT${tier + 1}.convertTo",
                    "action_upgradeT${tier + 1}.price", "action_upgradeT${tier + 1}.buildSpeed")) }
        val footprint = if (extractor) BuildingFootprint(0, 0, 0, 0) else BuildingFootprint(-1, -1, 1, 1)
        val displayRadius = if (extractor || values["core"]?.containsKey("displayRadius") == true) number("core", "displayRadius", 20.0) else 20.0
        return UnitDefinition(id, "CustomEconomicBuilding", number("core", "radius", if (extractor) 18.0 else 20.0),
            displayRadius, number("graphics", "total_frames", if (extractor) 4.0 else 3.0).toInt(),
            image("image", if (extractor) 148 else 141, if (extractor) 59 else 60), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), retained.toSet(),
            durability = DurabilityProfile(number("core", "maxHp", (if (extractor) listOf(800.0, 1000.0, 2000.0) else listOf(500.0, 1000.0, 2200.0))[tier - 1]),
                if (extractor) null else image("image_wreak", 47, 60)),
            building = BuildingProfile(footprint, if (extractor) image("image_back", 51, 44) else null,
                FootprintAnchor(10f, 10f), requiresResourcePool = extractor),
            construction = ConstructionProfile(number("core", "price", (if (extractor) listOf(700.0, 2100.0, 6100.0) else listOf(2200.0, 6600.0, 14100.0))[tier - 1]).toLong(),
                number("core", "buildSpeed", if (extractor) .001 else listOf(.0005, .0004, .0003)[tier - 1]).toFloat()),
            income = IncomeProfile(40f, income.toFloat()),
            upgrades = if (enableUpgrades && tier < 3) UpgradeProfile(listOf(UpgradeActionProfile("upgrade_t${tier + 1}", "${family}T${tier + 1}", upgradePrice, upgradeRate))) else null,
            deathExplosion = if (extractor) null else DeathExplosionProfile(listOf(350f, 450f, 850f)[tier - 1], 90f, 35f))
    }

    private fun ordinary(file: Path, source: Path) {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || !Files.isReadable(file) || file.toRealPath() != file ||
            file.parent.toRealPath() != file.parent)
            reject(ContentErrorCode.BAD_REFERENCE, source, reason = "Expected ordinary economic content file")
    }
    private fun reference(source: Path, value: String): Path {
        val relative = try { Path.of(value) } catch (_: java.nio.file.InvalidPathException) {
            reject(ContentErrorCode.BAD_REFERENCE, source, reason = "Invalid economic reference")
        }
        if (relative.root != null || relative.any { it.toString() == ".." })
            reject(ContentErrorCode.BAD_REFERENCE, source, reason = "Economic reference must remain in content directory")
        val resolved = source.parent.resolve(relative).normalize()
        if (!resolved.startsWith(source.parent))
            reject(ContentErrorCode.BAD_REFERENCE, source, reason = "Economic reference must remain in content directory")
        return resolved.also { ordinary(it, source) }
    }
    private fun parse(path: Path): Map<String, Map<String, String>> {
        val values = linkedMapOf<String, MutableMap<String, String>>()
        var section: String? = null
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line.startsWith('[')) {
                if (!line.endsWith(']') || line.count { it == '[' } != 1 || line.count { it == ']' } != 1)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, reason = "Invalid section at line ${index + 1}")
                val name = line.substring(1, line.length - 1)
                if (name !in fields) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, name, reason = "Unsupported economic section")
                if (values.putIfAbsent(name, linkedMapOf()) != null)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, name, reason = "Duplicate section")
                section = name
            } else {
                val current = section; val split = line.indexOf(':')
                if (current == null || split <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path,
                    reason = "Invalid field at line ${index + 1}")
                val key = line.substring(0, split).trim()
                if (key !in fields.getValue(current)) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, current, key,
                    reason = "Unsupported economic field")
                if (values.getValue(current).putIfAbsent(key, line.substring(split + 1).trim()) != null)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, current, key, reason = "Duplicate field")
            }
        }
        return values
    }
}
