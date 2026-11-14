package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AuthoredTransportTest {
    @TempDir lateinit var temporary: Path

    @Test fun a_configured_water_transport_admits_only_eligible_cargo_and_unloads_through_normal_orders() =
        exerciseWaterTransport(deployed = false)

    @Test fun a_stationary_deployed_transport_unloads_without_needing_a_movement_update() =
        exerciseWaterTransport(deployed = true)

    @Test fun json_and_hjson_load_the_same_transport_and_reject_invalid_slot_capacity() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("hover-production/json/package/units/hoverTank")
        for (name in listOf("tank.png", "tank_dead.png", "shadow.png")) {
            Files.copy(fixtures.resolve(name), temporary.resolve(name))
        }
        val base = JsonValue.readJSON(Files.readString(fixtures.resolve("hover.json"))).asObject()
        base.get("core").asObject().set("name", "river_tender")
        base.set("abilities", JsonValue.readJSON("""[
            {"name":"waterBay","type":"transport","capacity":5,"passengerMovement":"LAND",
             "unloadTerrain":"either","unloadTicks":12}
        ]"""))
        val json = temporary.resolve("tender.json")
        val hjson = temporary.resolve("tender.hjson")
        fun writeInputs() {
            Files.writeString(json, base.toString())
            Files.writeString(hjson, "// Cargo capacity in slots\n" + base.toString().replace("\"capacity\"", "capacity"))
        }
        writeInputs()
        for (path in listOf(json, hjson)) {
            val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadHoverTankWithSources(path))
            assertEquals(TransportSettings(5, MovementType.LAND, TransportUnloadTerrain.EITHER, 12),
                loaded.value.definition.transportSettings)
        }
        base.get("abilities").asArray().get(0).asObject().set("capacity", 0)
        writeInputs()
        for (path in listOf(json, hjson)) {
            val rejected = assertIs<ContentResult.Failure>(ContentLoader.loadHoverTankWithSources(path))
            assertEquals("ability_waterBay", rejected.error.section)
            assertEquals("capacity", rejected.error.field)
        }
    }

    private fun exerciseWaterTransport(deployed: Boolean) =
        WaterTestScene.withMap { map ->
            val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
                .resolve("hover-production/json/package/units/hoverTank")
            val source = JsonValue.readJSON(Files.readString(fixtures.resolve("hover.json"))).asObject()
            source.get("core").asObject().set("name", "river_tender")
            val transport = """{"name":"waterBay","type":"transport","capacity":2,"passengerMovement":"HOVER",
                "unloadTerrain":"${if (deployed) "land" else "liquid"}","unloadTicks":6}"""
            val abilities = if (deployed) """[$transport,
                {"name":"blink","type":"blink","range":120,"chargeTicks":20,"cooldownTicks":60,"locked":true}]"""
                else "[$transport]"
            source.set("abilities", JsonValue.readJSON(abilities))
            if (deployed) source.set("movement", JsonValue.readJSON("""{"movementType":"NONE","moveSpeed":0,
                "moveAccelerationSpeed":0,"moveDecelerationSpeed":0,"maxTurnSpeed":0,"turnAcceleration":0}"""))
            for (name in listOf("tank.png", "tank_dead.png", "shadow.png")) {
                Files.copy(fixtures.resolve(name), temporary.resolve(name))
            }
            val file = temporary.resolve("tender.json")
            Files.writeString(file, source.toString())
            val loaded = ContentLoader.loadHoverTankWithSources(file)
            val carrier = assertIs<ContentResult.Success<LoadedUnitContent>>(loaded, loaded.toString()).value.definition
            val hover = WaterTestScene.unit(MovementType.HOVER)
            val landBody = WaterTestScene.unit(MovementType.LAND)
            val land = UnitDefinition("land", "CustomUnitMetadata", 6.0, 6.0, 1, landBody.imagePath, 1.0,
                landBody.movement, emptySet(), 15)
            val x = if (deployed) 212.0 else 412.0
            val base = WaterTestScene.scenario(map, MovementType.HOVER, Position(x, 160.0))
            val cargoUnit = UnitDefinition("cargo", "CustomUnitMetadata", 6.0, 6.0, 1, hover.imagePath, 1.0,
                hover.movement, emptySet(), 15, transportSlots = 2)
            val input = base.copy(unitDefinitions = listOf(carrier, cargoUnit, land).associateBy { it.id }, units = listOf(
                UnitSpawn("tender", "p1", carrier.id, Position(x, 160.0), 90.0),
                UnitSpawn("shore-tender", "p1", carrier.id, Position(350.0, 160.0), 90.0),
                UnitSpawn("parcel", "p1", cargoUnit.id, Position(x + 9, 160.0), 90.0),
                UnitSpawn("overflow", "p1", cargoUnit.id, Position(x + 10, 165.0), 90.0),
                UnitSpawn("shore-parcel", "p1", cargoUnit.id, Position(360.0, 160.0), 90.0),
                UnitSpawn("wrong-medium", "p1", land.id, Position(x - 20, 160.0), 90.0),
                UnitSpawn("enemy", "p2", cargoUnit.id, Position(x + 14, 160.0), 90.0)),
                combatContract = CombatContract(12345, -1,
                    mapOf("tender" to 8L, "parcel" to 9L, "overflow" to 10L, "wrong-medium" to 11L, "enemy" to 12L,
                        "shore-tender" to 13L, "shore-parcel" to 14L), 15L))
            val result = SimulationContext.initialize(input)
            val context = assertIs<InitResult.Accepted>(result, result.toString()).context
            try {
                fun cargo() = context.observePlayer(context.world, "p1").units
                    .single { it.unitId == "tender" }.transport!!
                fun load(unit: String) = context.submit(LoadAction("load-$unit", "p1", "tender", unit,
                    LoadDirection.UP, context.world))
                assertEquals(2, cargo().capacity)
                assertEquals("invalid-passenger", assertIs<ActionResult.Rejected>(load("wrong-medium")).error.code)
                assertIs<ActionResult.Rejected>(load("enemy"))
                assertIs<ActionResult.Accepted>(load("parcel"))
                assertEquals(2, cargo().occupiedSlots)
                assertEquals(listOf("parcel"), cargo().passengerIds)
                assertEquals("transport-full", assertIs<ActionResult.Rejected>(load("overflow")).error.code)
                assertNull(context.observePlayer(context.world, "p2").units.single { it.unitId == "tender" }.transport)
                assertIs<ActionResult.Accepted>(context.submit(
                    LoadAction("shore-load", "p1", "shore-parcel", "shore-tender", LoadDirection.INTO, context.world)))
                val shore = context.observe().units.single { it.unitId == "shore-tender" }.transport!!
                if (!deployed) {
                    val dryUnload = context.submit(UnloadAction("dry-unload", "p1", "shore-tender", true, context.world))
                    assertEquals("cannot-unload", assertIs<ActionResult.Rejected>(dryUnload).error.code)
                    assertFalse(shore.canUnload)
                }
                assertEquals(listOf("shore-parcel"), shore.passengerIds)
                assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "tender", true, context.world)))
                repeat(5) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertEquals(listOf("parcel"), cargo().passengerIds)
                val observation = context.advance(Step(5, 1.0, "sim-tick"))
                assertTrue(observation.events.any { it.kind == "unit-unloaded" && it.unitId == "parcel" })
                assertTrue(cargo().passengerIds.orEmpty().isEmpty())
                assertNull(context.observe().units.single { it.unitId == "parcel" }.carriedBy)
            } finally {
                context.close()
            }
        }
}
