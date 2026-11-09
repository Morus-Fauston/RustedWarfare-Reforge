package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonObject
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Owned images; actual finite action fields, shared section inheritance and IGNORE. */
internal object AfterburnContentFixture {
    fun write(root: Path, format: String): Path {
        Files.createDirectories(root)
        for ((name, size) in linkedMapOf("body" to (33 to 52), "dead" to (33 to 52),
                "shadow" to (33 to 52), "turret" to (16 to 26), "beam" to (13 to 20),
                "beam_start" to (20 to 20), "beam_end" to (20 to 20), "trail" to (32 to 32))) {
            val image = java.awt.image.BufferedImage(size.first, size.second, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            image.setRGB(size.first / 2, size.second / 2, 0xff497f74.toInt())
            check(javax.imageio.ImageIO.write(image, "png", root.resolve("$name.png").toFile()))
        }
        val core = linkedMapOf("name" to "aaBeamGunship", "maxHp" to "2500", "radius" to "22",
            "price" to "6000", "buildSpeed" to "0.0004761904761904762", "image" to "body.png",
            "image_dead" to "dead.png", "image_shadow" to "shadow.png", "image_turret" to "turret.png",
            "beam_image" to "beam.png", "beam_image_start" to "beam_start.png", "beam_image_end" to "beam_end.png")
        core += mapOf("energyRegen" to "0.01", "energyRegenWhenRecharging" to "0.05",
            "energyNeedsToRechargeToFull" to "true")
        val base = linkedMapOf("core" to core, "action_afterBurn" to linkedMapOf(
            "buildSpeed" to "0", "convertTo" to "aaBeamGunship_afterburn", "resourceAmount" to "energy",
            "resourceAmount_setValue" to "6", "addActionCooldownTime" to "25s",
            "canPlayerCancel" to "false", "allowMultipleInQueue" to "false"))
        val burn = linkedMapOf("core" to linkedMapOf("name" to "aaBeamGunship_afterburn", "energyRegen" to "-0.02",
                "afterburn_trail_image" to "trail.png"),
            "movement" to linkedMapOf("moveSpeed" to "2.3", "moveAccelerationSpeed" to "0.13",
                "moveDecelerationSpeed" to "0.13", "maxTurnSpeed" to "1.5", "turnAcceleration" to "0.1"),
            "turret_1" to linkedMapOf("energyUsage" to "99", "idleSweepAngle" to "0"),
            "action_afterBurn" to linkedMapOf("resourceAmount" to "IGNORE", "resourceAmount_setValue" to "IGNORE"),
            "action_cancelBurn" to linkedMapOf("buildSpeed" to "0", "convertTo" to "aaBeamGunship",
                "resourceAmount" to "energy", "resourceAmount_setValue" to "6",
                "canPlayerCancel" to "false", "allowMultipleInQueue" to "false"),
            "hiddenAction_autoSwitchBack" to linkedMapOf("autoTrigger" to "if self.energy(empty=true)",
                "convertTo" to "aaBeamGunship", "resourceAmount" to "energy", "resourceAmount_setValue" to "6"))
        val numbers = setOf("maxHp", "radius", "price", "buildSpeed", "energyRegen", "energyRegenWhenRecharging",
            "resourceAmount_setValue", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
            "maxTurnSpeed", "turnAcceleration", "energyUsage", "idleSweepAngle")
        val booleans = setOf("canPlayerCancel", "allowMultipleInQueue", "energyNeedsToRechargeToFull")
        fun text(sections: Map<String, Map<String, String>>, parent: String?): String {
            if (format == "ini") return sections.entries.joinToString("\n") { (name, fields) ->
                "[$name]\n" + (if (name == "core" && parent != null) "copyFrom: $parent\n" else "") +
                    fields.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            } + "\n"
            val result = JsonObject().add("format", "rw-reforge.unit.v1")
            if (parent != null) result.add("copyFrom", org.hjson.JsonArray().add(parent))
            val actions = org.hjson.JsonArray()
            for ((name, fields) in sections) {
                val obj = JsonObject()
                if (name.startsWith("action_")) obj.add("name", name.removePrefix("action_"))
                for ((key, value) in fields) when {
                    key in numbers && value != "IGNORE" -> obj.add(key, JsonValue.readJSON(value))
                    key in booleans -> obj.add(key, value == "true")
                    else -> obj.add(key, value)
                }
                if (name.startsWith("action_")) actions.add(obj)
                else if (name.startsWith("turret_")) result.add("weapons", org.hjson.JsonArray().add(
                    obj.add("name", name.removePrefix("turret_"))))
                else result.add(name, obj)
            }
            if (actions.size() > 0) result.add("actions", actions)
            return result.toString() + "\n"
        }
        Files.writeString(root.resolve("aa.$format"), text(base, null))
        Files.writeString(root.resolve("afterburn.$format"), text(burn, "aa.$format"))
        Files.writeString(root.resolve("pack.json"), """{
            "format":"rw-reforge.content-pack.v1", "id":"owned-afterburn", "name":"Owned afterburn",
            "units":[{"type":"aa-beam-gunship","file":"aa.$format"},
                     {"type":"aa-beam-gunship","file":"afterburn.$format"}]
        }""")
        return root
    }
}

class AfterburnContentTest {
    @TempDir lateinit var temporary: Path

    @Test fun all_formats_reject_unqualified_predicates_actions_cooldowns_and_trail_images_with_source_locations() {
        for (format in listOf("ini", "json", "hjson")) for (fault in listOf("predicate", "action", "cooldown", "target", "trail")) {
            val root = AfterburnContentFixture.write(temporary.resolve("$format-$fault"), format)
            val file = root.resolve(if (fault in listOf("cooldown", "target")) "aa.$format" else "afterburn.$format")
            val before = Files.readString(file)
            when (fault) {
                "predicate" -> Files.writeString(file, before.replace("empty=true", "lessThan=1"))
                "action" -> Files.writeString(file, before.replace("cancelBurn", "arbitraryScript"))
                "cooldown" -> Files.writeString(file, before.replace("25s", "24s"))
                "target" -> Files.writeString(file, before.replace("aaBeamGunship_afterburn", "missing-form"))
                "trail" -> check(javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", root.resolve("trail.png").toFile()))
            }
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root), "$format/$fault").error
            assertTrue(error.file.isNotBlank(), "$format/$fault must retain its source")
            assertTrue(error.reason.isNotBlank())
        }
    }

    @Test fun all_formats_load_both_real_forms_and_their_inherited_energy_and_movement() {
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(AfterburnContentFixture.write(temporary.resolve(format), format))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
            val base = pack.units.getValue("aaBeamGunship")
            val burn = pack.units.getValue("aaBeamGunship_afterburn")
            assertEquals(1.0, base.movement.moveSpeed)
            assertEquals(2.3, burn.movement.moveSpeed)
            assertEquals(.13, burn.movement.moveAccelerationSpeed)
            assertEquals(-.02f, burn.energy!!.regeneration)
            assertEquals(.05f, burn.energy!!.rechargingRegeneration)
            assertEquals(99f, burn.combat!!.namedWeapons!!.ordered.single().energyUsage)
            assertEquals(2500.0, burn.maximumHealth)
            val normalAction = base.customActions!!.actions.single()
            val inheritedAction = burn.customActions!!.actions.first()
            assertEquals("aaBeamGunship_afterburn_0", normalAction.actionId)
            assertEquals(normalAction.actionId, inheritedAction.actionId)
            assertEquals(25000, inheritedAction.cooldown)
            assertEquals(6f, normalAction.energySet)
            assertNull(inheritedAction.energySet)
            assertEquals(AutomaticActionCondition.ENERGY_EMPTY, burn.customActions!!.actions.last().automatic)
        }
    }

    @Test fun a_missing_conversion_form_is_rejected_in_the_final_enabled_content_set() {
        val root = AfterburnContentFixture.write(temporary, "json")
        val manifest = root.resolve("pack.json")
        val doc = JsonValue.readJSON(Files.readString(manifest)).asObject()
        doc.get("units").asArray().remove(1)
        Files.writeString(manifest, doc.toString())
        val result = ContentLoader.loadContentPacks(listOf(root))
        val failure = assertIs<ContentResult.Failure>(result)
        assertEquals(ContentErrorCode.BAD_REFERENCE, failure.error.code)
        assertEquals("aaBeamGunship_afterburn", failure.error.objectRef)
    }
}
