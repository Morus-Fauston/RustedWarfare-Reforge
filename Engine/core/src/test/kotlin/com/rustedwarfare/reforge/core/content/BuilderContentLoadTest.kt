package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class BuilderContentLoadTest {
    private fun file(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/start/units/builder.properties") } ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-001/20261027-intake-01/builder-input-01/units/builder.properties"))

    @Test fun loaded_native_builder_is_paid_born_selected_and_moved_through_the_real_world() {
        val builder = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadBuilder(file())).value
        assertEquals("builder", builder.id)
        assertEquals(170.0, builder.maximumHealth)
        assertEquals(10.0, builder.radius)
        assertEquals(12.0, builder.displayRadius)
        assertEquals(1, builder.totalFrames)
        assertEquals(MovementProfile(MovementType.LAND, .8, .04, .1, 3.8, .35, false, false), builder.movement)
        assertNull(builder.combat)
        val base = BuildingTestContent.scenario()
        val factory = base.unitDefinitions.getValue("landFactory")
        val producer = UnitDefinition(factory.id, factory.className, factory.radius, factory.displayRadius,
            factory.totalFrames, factory.imagePath, factory.imageScale, factory.movement, emptySet(),
            durability = factory.durability, building = factory.building, production = ProductionProfile("builder"),
            directHitTargetKind = factory.directHitTargetKind, income = IncomeProfile(10f, 4.5f))
        val input = base.copy(unitDefinitions = mapOf(producer.id to producer, builder.id to builder),
            units = base.units.filter { it.unitId == "f" }, combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val nonce = "0".repeat(32)
            val id = "produced:p1:$nonce"
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", "builder", nonce, c.world)))
            assertEquals(1250.0, c.observePlayer(c.world, "p1").credits)
            repeat(500) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(1, c.observe().units.size)
            c.advance(Step(500, 1.0, "sim-tick"))
            val born = c.observe().units.single { it.unitId == id }
            assertEquals("builder", born.typeId)
            assertEquals(170.0, born.health)
            assertEquals(Position(310.0, 215.0), born.position)
            assertEquals(1475.0, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(SelectAction("select", "p1", id, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("move", "p1", id, Position(430.0, 330.0), c.world)))
            for (step in 501L..800L) c.advance(Step(step, 1.0, "sim-tick"))
            val moved = c.observePlayer(c.world, "p1").units.single { it.unitId == id }
            assertTrue(moved.position.x > 400.0 && moved.position.y > 300.0)
            assertTrue(c.observe().combat!!.weapons.none { it.unitId == id })
        } finally { c.close() }
    }

    @Test fun rejected_builder_input_keeps_precise_field_errors_and_never_falls_back_to_a_tank() {
        val source = file()
        val text = Files.readString(source).replace(Regex("(?m)^(image|image_dead)=(.*)$")) {
            "${it.groupValues[1]}=${source.parent.resolve(it.groupValues[2].trim()).toAbsolutePath()}"
        }
        val path = Files.createTempFile("p006-builder-", ".properties")
        val badPng = Files.createTempFile("p006-builder-", ".png")
        try {
            Files.write(badPng, byteArrayOf(1, 2, 3))
            val cases = listOf(
                Triple(text.replace("maxHp=170", ""), "maxHp", ContentErrorCode.MISSING_REQUIRED_FIELD),
                Triple(text + "\nunknown=1", "unknown", ContentErrorCode.UNSUPPORTED_FIELD),
                Triple(text + "\nprice=500", "price", ContentErrorCode.MALFORMED_SYNTAX),
                Triple(text.replace("moveSpeed=0.8", "moveSpeed=NaN"), "moveSpeed", ContentErrorCode.MALFORMED_SYNTAX),
                Triple(text.replace("buildSpeed=0.002", "buildSpeed=0.003"), "buildSpeed", ContentErrorCode.UNSUPPORTED_FEATURE),
                Triple(text.replace("name=builder", "name=mega_builder"), "name", ContentErrorCode.UNSUPPORTED_FEATURE),
                Triple(text.replace(Regex("(?m)^image=.*$")) { "image=$badPng" }, "image", ContentErrorCode.BAD_REFERENCE),
                Triple(text.replace(Regex("(?m)^image_dead=.*$"), "image_dead=missing-builder-dead.png"), "image_dead", ContentErrorCode.BAD_REFERENCE))
            for ((input, field, code) in cases) {
                Files.writeString(path, input)
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadBuilder(path)).error
                assertEquals(code, error.code)
                assertEquals(field, error.field)
                assertEquals(path.toAbsolutePath().normalize().toString(), error.file)
            }
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(badPng) }
    }
}
