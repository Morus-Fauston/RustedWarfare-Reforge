package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AuthoredBlinkTest {
    @TempDir lateinit var temporary: Path

    @Test fun hover_form_unlocks_pays_charges_and_teleports_while_deployed_form_refuses() =
        WaterTestScene.withMap { map ->
            val sources = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
                .resolve("hover-production/json/package/units/hoverTank")
            for (name in listOf("tank.png", "tank_dead.png", "shadow.png"))
                Files.copy(sources.resolve(name), temporary.resolve(name))
            fun read(name: String, locked: Boolean): UnitDefinition {
                val raw = JsonValue.readJSON(Files.readString(sources.resolve("hover.json"))).asObject()
                raw.get("core").asObject().set("name", name)
                if (locked) raw.set("movement", JsonValue.readJSON("""{"movementType":"NONE","moveSpeed":0,
                    "moveAccelerationSpeed":0,"moveDecelerationSpeed":0,"maxTurnSpeed":0,"turnAcceleration":0}"""))
                val ability = JsonValue.readJSON("""[{"name":"jump","type":"blink","range":700,"chargeTicks":210,
                    "cooldownTicks":1500,"unlockCredits":10000,"unlockTicks":3000,"locked":$locked,"queueSpeed":3}]""")
                raw.set("abilities", ability)
                val path = temporary.resolve("$name.json")
                Files.writeString(path, raw.toString())
                val loaded = ContentLoader.loadHoverTankWithSources(path)
                return assertIs<ContentResult.Success<LoadedUnitContent>>(loaded, loaded.toString()).value.definition
            }
            val active = read("experimentalGunship", false)
            val deployed = read("experimentalGunshipLanded", true)
            val initial = Position(160.0, 160.0)
            val destination = Position(320.0, 180.0)
            val base = WaterTestScene.scenario(map, MovementType.HOVER, initial)
            val scene = base.copy(unitDefinitions = mapOf(active.id to active, deployed.id to deployed),
                units = listOf(UnitSpawn("ship", "p1", active.id, initial, 90.0),
                    UnitSpawn("deployed", "p1", deployed.id, Position(360.0, 160.0), 270.0)),
                economyContract = EconomyContract(mapOf("p1" to 22000L, "p2" to 0L)),
                combatContract = CombatContract(12345, -1, mapOf("ship" to 8L, "deployed" to 9L), 10L))
            val init = SimulationContext.initialize(scene)
            val context = assertIs<InitResult.Accepted>(init, init.toString()).context
            try {
                var step = 0L
                var id = 0
                fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
                fun view(unit: String, viewer: String = "p1") =
                    context.observePlayer(context.world, viewer).units.single { it.unitId == unit }
                fun ask(unit: String, action: String, target: Position? = null, cancel: Boolean = false) =
                    context.submit(UnitAction("test-${id++}", "p1", unit, action, context.world, cancel, target = target))
                fun rejected(action: ActionResult, code: String) =
                    assertEquals(code, assertIs<ActionResult.Rejected>(action).error.code)
                assertNull(view("ship", "p2").unitActions)
                assertEquals(listOf("addBlink"), view("ship").unitActions!!.actions.map { it.name })
                rejected(ask("ship", "blink", destination), "unit-action-locked")
                rejected(ask("deployed", "blink", destination), "unit-action-locked")
                assertIs<ActionResult.Accepted>(ask("ship", "addBlink"))
                assertEquals(12000L, context.observe().economy!!.balances.getValue("p1"))
                assertIs<ActionResult.Accepted>(ask("ship", "addBlink", cancel = true))
                assertEquals(22000L, context.observe().economy!!.balances.getValue("p1"))
                assertIs<ActionResult.Accepted>(ask("ship", "addBlink"))
                assertIs<ActionResult.Accepted>(ask("deployed", "addBlink"))
                assertEquals(2000L, context.observe().economy!!.balances.getValue("p1"))
                repeat(999) { tick() }
                assertEquals(listOf("addBlink"), view("ship").unitActions!!.actions.map { it.name })
                tick()
                assertEquals(listOf("blink"), view("ship").unitActions!!.actions.map { it.name })
                val locked = view("deployed").unitActions!!.actions.single()
                assertEquals("blink", locked.name)
                assertFalse(locked.available)
                rejected(ask("deployed", "blink", destination), "unit-action-locked")
                rejected(ask("ship", "blink", Position(-1.0, 180.0)), "action-target-impassable")
                rejected(ask("ship", "blink", Position(950.0, 180.0)), "action-target-out-of-range")
                assertIs<ActionResult.Accepted>(ask("ship", "blink", destination))
                repeat(5) { tick() }
                assertIs<ActionResult.Accepted>(ask("ship", "blink", cancel = true))
                assertEquals(initial, view("ship").position)
                assertEquals(0, view("ship").unitActions!!.actions.single().cooldownRemaining)
                assertIs<ActionResult.Accepted>(ask("ship", "blink", destination))
                repeat(69) { tick() }
                assertEquals(initial, view("ship").position)
                val finished = tick()
                assertTrue(finished.events.any { it.kind == "teleported" && it.unitId == "ship" })
                assertEquals(destination, view("ship").position)
                assertEquals(25000, view("ship").unitActions!!.actions.single().cooldownRemaining)
                rejected(ask("ship", "blink", initial), "unit-action-cooldown")
            } finally {
                context.close()
            }
        }

    @Test fun deployed_conversion_cancels_charging_and_death_refunds_only_pending_unlock() =
        WaterTestScene.withMap { map ->
            val profile = BlinkProfile(700.0, 210, 1500, 10000, 3000)
            val origin = Position(160.0, 160.0)
            val movement = WaterTestScene.unit(MovementType.HOVER).movement
            val image = WaterTestScene.unit(MovementType.HOVER).imagePath
            fun form(id: String, blink: BlinkProfile, actions: List<CustomActionDefinition>) =
                UnitDefinition(id, "CustomUnitMetadata", 7.0, 9.0, 1, image, 1.0,
                    movement, emptySet(), durability = DurabilityProfile(150.0, null),
                    customActions = CustomActionsProfile(blink.actions().actions + actions), blink = blink)
            val active = form("test-hover", profile, listOf(
                CustomActionDefinition("land", "land", "test-deployed", 1f, null, highPriority = true)))
            val deployed = form("test-deployed", profile.copy(locked = true), emptyList())
            val scene = WaterTestScene.scenario(map, MovementType.HOVER, origin).copy(
                unitDefinitions = mapOf(active.id to active, deployed.id to deployed))
            val unit = MutableUnit("ship", "p1", active.id, active, origin, 90.0)
            val world = WorldHandle("test-world")
            val step = StepState(units = linkedMapOf(unit.unitId to unit))
            val ledger = ProductionLedger.initial(mapOf("p1" to 12000L, "p2" to 0L), emptyMap())
            val unlock = CustomUnitActions.request(unit, UnitAction("unlock", "p1", unit.unitId, "addBlink", world),
                0, scene, map, ledger, 0.0)
            assertNull(unlock.error)
            assertEquals(2000L, unlock.ledger?.balances?.get("p1"))
            val paid = assertNotNull(unlock.ledger)
            assertEquals(12000L, CustomUnitActions.dropBlinkOnDeath(unit, paid)?.balances?.get("p1"))
            assertTrue(unit.customActionState.queue.isEmpty())

            unit.blinkUnlocked = true
            assertNull(CustomUnitActions.request(unit, UnitAction("land-order", "p1", unit.unitId, "land", world),
                1, scene, map, ledger, 1.0).error)
            val jump = UnitAction("jump", "p1", unit.unitId, "blink", world, target = Position(320.0, 180.0))
            assertNull(CustomUnitActions.request(unit, jump, 1, scene, map, ledger, 1.0).error)
            assertTrue(CustomUnitActions.advanceQueue(unit, scene.unitDefinitions, 1.0, 17, step) { _, _ ->
                error("Blink must not dispatch an ordinary ground projectile")
            })
            assertSame(deployed, unit.definition)
            assertSame(ledger, CustomUnitActions.reconcileBlinkConversion(unit, ledger))
            assertTrue(unit.blinkUnlocked)
            assertTrue(unit.customActionState.queue.isEmpty())
            assertEquals(origin, unit.position)
            assertTrue(step.pendingEvents.none { it.kind == "teleported" })
            assertFalse(CustomUnitActions.observe(unit, 17, scene, simulationTime = 2.0)!!.actions.single().available)
        }
}
