package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.client.ContentPackChoice
import com.rustedwarfare.reforge.client.SandboxLoader
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.sim.Step
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class NamedWeaponCoreAdapterTest {
    @Test fun `host archive and authorized player archive retain both actual weapon identities`() {
        val repo = Path.of(System.getProperty("repo.root"))
        val fixture = repo.resolve("SimulationTest/public/scenarios/select-move-stop.json")
        val base = ScenarioReader.load(fixture, repo.resolve("SimulationTest/public/contracts/select-move-stop.json"))
        CoreAdapter(base).use { old -> assertFalse("combat" in old.initial().json()) }
        val root = repo.resolve("SimulationTest/public/inputs/named-weapons/json")
        val content = when (val result = ContentLoader.loadContentPacks(listOf(root.resolve("package")))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.toString())
        }
        val scene = SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(content, "named_tank"))
            val deadline = System.nanoTime() + 5_000_000_000L
            var loaded = loader.pollPrepared()
            while (loaded == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                loaded = loader.pollPrepared()
            }
            checkNotNull(loaded) { "Content preparation timed out" }.getOrThrow().scenario
        }
        val input = base.copy(core = scene,
            scenarioDocument = JsonObject(base.scenarioDocument + ("automaticVision" to
                obj("observers" to array(listOf(s("p1"), s("p2")))))))
        CoreAdapter(input).use { core ->
            core.initial()
            val names = mutableSetOf<String>()
            repeat(120) { index ->
                core.advance(Step(index.toLong(), 1.0, "sim-tick"))
                val host = core.observe().json()
                val weapons = host.objectAt("combat").arrayAt("weapons").map { it.jsonObject }
                    .filter { it["unitId"] == s("a") }
                assertEquals(listOf("near", "far"), weapons.map { it.text("weaponName") })
                for (event in host.arrayAt("projectileEvents").map { it.jsonObject }) {
                    val projectile = event.objectAt("projectile")
                    if (event["kind"] == s("born") && projectile["sourceUnitId"] == s("a"))
                        names += projectile.text("sourceWeaponName")
                }
                val views = checkNotNull(core.playerViews())
                for (player in listOf("p1", "p2")) {
                    val view = views.objectAt(player)
                    val publicWeapons = view.arrayAt("weapons").map { it.jsonObject }.filter { it["unitId"] == s("a") }
                    assertEquals(listOf("near", "far"), publicWeapons.map { it.text("weaponName") })
                    assertTrue(publicWeapons.all { "objectId" !in it && "randomCounter" !in it && "offset" !in it })
                    if (player == "p2") assertTrue(publicWeapons.all {
                        it["targetUnitId"] == JsonNull && it["warmup"] == JsonNull && it["cooldown"] == JsonNull })
                    else assertTrue(publicWeapons.all { it["cooldown"] is JsonPrimitive && it["cooldown"] != JsonNull })
                }
            }
            assertEquals(setOf("near", "far"), names)
        }
    }
}
