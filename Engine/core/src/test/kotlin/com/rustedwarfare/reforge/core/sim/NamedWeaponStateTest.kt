package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.TurretMount
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.WeaponRecoil
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedUnitContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NamedWeaponStateTest {
    @Test
    fun loaded_weapon_defaults_do_not_inherit_another_weapons_barrel_or_odd_offset() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("named-weapons/json/package/units")
        for (format in listOf("ini", "json", "hjson")) for (case in listOf("omitted", "zero", "template", "inserted")) {
            val root = Files.createTempDirectory("named-barrel-defaults-").toAbsolutePath().normalize()
            try {
                for (name in listOf("tank.png", "tank_dead.png", "tank_turret.png"))
                    Files.copy(source.resolve(name), root.resolve(name))
                var baseText = Files.readString(source.resolve("base.ini")).lineSequence()
                    .filterNot { it.trim().startsWith("dont_load:") }.joinToString("\n")
                if (case == "template") baseText = baseText.replace("recoilReturnTime: 7",
                    "recoilReturnTime: 7\nbarrelX: 4\nbarrelOffsetX_onOddShots: 6")
                Files.writeString(root.resolve("base.ini"), baseText)
                val names = if (case == "inserted") listOf("inserted", "first", "second") else listOf("first", "second")
                fun extra(name: String): String = when (name) {
                    "first" -> "barrelX:-3,barrelOffsetX_onOddShots:-2"
                    "inserted" -> "barrelX:7,barrelOffsetX_onOddShots:8"
                    else -> if (case == "zero") "barrelX:0,barrelOffsetX_onOddShots:0" else ""
                }
                val text = if (format == "ini") {
                    "[core]\ncopyFrom:base.ini\nname:named_tank\n" + names.joinToString("\n") { name ->
                        "[turret_$name]\n@copyFromSection:template_gun\nx:0\ny:0\ndelay:1\nrecoilOffset:0\nturnSpeed:0\nturnSpeedAcceleration:0\n" +
                            extra(name).replace(',', '\n')
                    }
                } else {
                    val weapons = names.joinToString(",") { name ->
                        val fields = extra(name).split(',').filter { it.isNotEmpty() }.joinToString(",") {
                            val (key, value) = it.split(':'); "\"$key\":$value"
                        }
                        "{\"name\":\"$name\",\"copyFromSections\":[\"template_gun\"],\"x\":0,\"y\":0,\"delay\":1,\"recoilOffset\":0,\"turnSpeed\":0,\"turnSpeedAcceleration\":0" +
                            (if (fields.isEmpty()) "" else ",$fields") + "}"
                    }
                    "{\"format\":\"rw-reforge.unit.v2\",\"copyFrom\":[\"base.ini\"],\"core\":{\"name\":\"named_tank\"},\"weapons\":[$weapons]}"
                }
                val file = root.resolve("named.$format"); Files.writeString(file, text)
                val loaded = ContentLoader.loadDeclaredTankWithSources(file)
                val definition = assertIs<ContentResult.Success<LoadedUnitContent>>(loaded, "$format/$case: $loaded").value.definition
                val scene = CombatTestContent.scenario()
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene.copy(
                    unitDefinitions = mapOf(definition.id to definition), units = scene.units.map {
                        it.copy(typeId = definition.id, position = if (it.unitId == "b") Position(220.0, 210.0) else it.position)
                    }))).context
                try {
                    val y0 = if (case == "template") 214.0 else 210.0
                    val y1 = if (case == "template") 220.0 else 210.0
                    val label = "$format/$case"
                    assertEquals(Position(130.0, y0), assertNotNull(context.observe().combat).weapons.single {
                        it.unitId == "a" && it.weaponName == "second" }.muzzle, "$label initial")
                    for ((step, y) in listOf(y0, y1).withIndex()) {
                        val result = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                        val birth = result.projectileEvents.single { it.kind == "born" &&
                            it.projectile.sourceUnitId == "a" && it.projectile.sourceWeaponName == "second" }
                        assertEquals(Position(130.0, y), birth.projectile.position, "$label born $step")
                    }
                } finally { context.close() }
            } finally {
                require(root.isAbsolute && root.fileName.toString().startsWith("named-barrel-defaults-"))
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                    require(it.toAbsolutePath().normalize().startsWith(root)); Files.delete(it)
                } }
            }
        }
    }

    @Test
    fun each_real_muzzle_uses_its_own_barrel_odd_shot_offset_and_recoil_clock() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = WeaponCatalog(listOf(
                weapon.copy(name = "left", barrelX = -3.0, oddShotOffset = -2.0, recoil = WeaponRecoil(-4.0, 2.0, 4.0)),
                weapon.copy(name = "right", barrelX = 3.0, oddShotOffset = 2.0, recoil = WeaponRecoil(0.0, 4.0, 7.0))))))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
                if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
            }))).context
        try {
            fun states(observation: Observation) = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }
            assertEquals(listOf(Position(130.0, 207.0), Position(130.0, 213.0)), states(context.observe()).map { it.muzzle })
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            val births = first.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
            assertEquals(listOf(Position(130.0, 207.0), Position(130.0, 213.0)), births.map { it.projectile.position })
            assertEquals(listOf(Position(108.0, 210.0), Position(110.0, 210.0)), states(first).map { it.pivot })
            assertEquals(listOf(Position(128.0, 205.0), Position(130.0, 215.0)), states(first).map { it.muzzle })
            assertTrue(states(first).all { it.oddShot })
        } finally { context.close() }
    }

    @Test
    fun close_reopen_recreates_all_named_state_and_does_not_accept_the_previous_world_handle() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = WeaponCatalog(listOf(
                weapon.copy(name = "ordinary"), weapon.copy(name = "continuous", warmup = 2.0, warmupNoReset = true)))))
        val input = base.copy(unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
            if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
        })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val initial = context.observe()
            val oldWorld = context.world
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            val retained = assertNotNull(first.combat).weapons
            assertTrue(retained.all { it.cooldown > 0.0 && it.targetUnitId != null })
            assertTrue(retained.any { it.warmup > 0.0 })
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(input))
            assertEquals(initial.combat, context.observe().combat)
            assertTrue(context.observe().projectiles.isEmpty())
            assertIs<ActionResult.Rejected>(context.submit(StopAction("stale", "p1", "a", oldWorld)))
            val repeated = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(first.combat, repeated.combat)
            assertEquals(first.projectiles, repeated.projectiles)
            assertTrue(retained.all { it.cooldown > 0.0 && it.targetUnitId != null })
        } finally { context.close() }
    }

    @Test
    fun a_second_weapon_allocation_failure_rolls_back_every_named_clock_warmup_target_and_random_state() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single().copy(turnSpeed = 0.0, turnAcceleration = 0.0,
            warmupCallDownRate = 0.0)
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = WeaponCatalog(listOf(
                weapon.copy(name = "first"), weapon.copy(name = "second", warmup = 2.0, warmupNoReset = true)))))
        val witness = UnitDefinition("witness", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, durability = DurabilityProfile(210.0, combat.deadImagePath))
        val input = base.copy(unitDefinitions = mapOf("c_tank" to tank, "witness" to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) } + Player("p3", PlayerControl.NONE, "three"),
            units = base.units.map { if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it } +
                UnitSpawn("c", "p3", "witness", Position(170.0, 270.0), 0.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), Long.MAX_VALUE - 1))
        val failed = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val before = failed.observe()
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            for (context in listOf(failed, control)) {
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("a-correct", "p1", "a", "c", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("b-correct", "p2", "b", "c", context.world)))
            }
            val actual = failed.advance(Step(0, 1.0, "sim-tick"))
            val expected = control.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(expected.units, actual.units)
            assertEquals(expected.combat, actual.combat)
            assertEquals(expected.projectiles, actual.projectiles)
            assertEquals(expected.projectileEvents, actual.projectileEvents)
            assertTrue(actual.projectiles.isEmpty())
            assertTrue(assertNotNull(actual.combat).weapons.all { it.warmup == 0.0 && it.cooldown == 0.0 && !it.oddShot })
        } finally { failed.close(); control.close() }
    }

    @Test
    fun death_clears_the_entire_dead_units_weapon_targets_in_the_published_impact_frame() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(maxHp = 25.0,
                namedWeapons = WeaponCatalog(listOf(weapon.copy(name = "one"), weapon.copy(name = "two")))))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
                if (it.unitId == "b") it.copy(position = Position(135.5, 210.0)) else it
            }))).context
        try {
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(observation.units.all { !it.alive })
            val states = assertNotNull(observation.combat).weapons
            assertEquals(4, states.size)
            assertTrue(states.all { it.targetUnitId == null }, "No dead unit may retain a secondary weapon target")
        } finally { context.close() }
    }

    @Test
    fun a_nonfinite_secondary_weapon_cannot_bypass_world_admission() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val catalog = WeaponCatalog(listOf(weapon.copy(name = "valid"),
            weapon.copy(name = "invalid", mount = TurretMount(Double.NaN, 0.0))))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
        val result = SimulationContext.initialize(base.copy(unitDefinitions = mapOf("c_tank" to tank)))
        try {
            assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result).error.code)
        } finally { if (result is InitResult.Accepted) result.context.close() }
    }

    @Test
    fun both_real_weapons_fire_from_separate_muzzles_and_retain_their_own_cooldown() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val catalog = WeaponCatalog(listOf(
            weapon.copy(name = "left", mount = TurretMount(-12.0, 0.0)),
            weapon.copy(name = "right", mount = TurretMount(12.0, 0.0))))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
        val input = base.copy(unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
            if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
        })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val births = mutableListOf<ProjectileObservation>()
            var observation = context.observe()
            for (step in 0L..5L) {
                observation = context.advance(Step(step, 1.0, "sim-tick"))
                births += observation.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
                    .map { it.projectile }
            }
            assertEquals(2, births.size, "Two actual weapons must each create a projectile within the six-step window")
            assertEquals(2, births.map { it.objectId }.toSet().size)
            assertEquals(listOf("b", "b"), births.map { it.targetUnitId })
            assertTrue(kotlin.math.abs(births[0].position.y - births[1].position.y) > 10.0,
                "Opposite twelve-unit mounts must not emit both shots from the old central muzzle")
            val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }
            assertEquals(listOf("left", "right"), states.map { it.weaponName })
            assertTrue(states.all { it.cooldown > 0.0 && it.targetUnitId == "b" })
        } finally { context.close() }
    }

    @Test
    fun a_host_observes_each_declared_weapon_at_its_own_pivot_and_muzzle() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val catalog = WeaponCatalog(listOf(
            weapon.copy(name = "left", mount = TurretMount(-12.0, 0.0)),
            weapon.copy(name = "right", mount = TurretMount(12.0, 0.0))))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            base.copy(unitDefinitions = mapOf("c_tank" to tank)))).context
        try {
            val states = assertNotNull(context.observe().combat).weapons.filter { it.unitId == "a" }
            assertEquals(2, states.size, "One unit must expose both actual weapon states")
            assertEquals(listOf("left", "right"), states.map { it.weaponName })
            assertEquals(listOf(Position(110.0, 198.0), Position(110.0, 222.0)), states.map { it.pivot })
            assertEquals(listOf(Position(130.0, 198.0), Position(130.0, 222.0)), states.map { it.muzzle })
        } finally { context.close() }
    }
}
