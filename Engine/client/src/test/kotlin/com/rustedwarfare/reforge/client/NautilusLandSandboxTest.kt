package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class NautilusLandSandboxTest {
    @Test fun normal_command_start_builds_the_experimental_factory_and_pays_for_a_three_form_cargo_round_trip() {
        for (format in listOf("ini", "json", "hjson")) {
            val scene = NautilusSandboxScene.scenario(format)
            assertEquals("p009-nautilus-production", scene.scenarioId)
            SandboxSession(scene).use { s ->
                fun until(limit: Int, ready: () -> Boolean) {
                    var ticks = 0
                    while (!ready() && ticks++ < limit) s.tick()
                    assertTrue(ready(), "$format: ${s.feedback}; units=${s.observation.units.map { listOf(it.typeId, it.position, it.constructionProgress, it.tasks, it.constructionTargetId) }}")
                }
                s.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(s.produce("builder"))
                until(600) { s.observation.units.any { it.typeId == "builder" } }
                s.select(s.observation.units.single { it.typeId == "builder" }.position)
                val before = s.observation.balance!!
                assertIs<ActionResult.Accepted>(s.build("experimentalLandFactory", Position(210.0, 310.0)))
                until(7000) { s.observation.units.any { it.typeId == "experimentalLandFactory" && it.constructionProgress == 1f } }
                s.select(s.observation.units.single { it.typeId == "experimentalLandFactory" }.position)
                assertEquals("Experimental", s.actionMenu.selectedTitle)
                assertTrue(s.actionMenu.upgrades.isEmpty())
                assertEquals(listOf("nautilusSubmarineLand"), s.productionActions.map { it.typeId })
                val paid = s.observation.balance!!
                assertIs<ActionResult.Accepted>(s.produce("nautilusSubmarineLand"))
                assertEquals(paid - 18000L, s.observation.balance)
                until(3400) { s.observation.units.any { it.typeId == "nautilusSubmarineLand" } }
                val id = s.observation.units.single { it.typeId == "nautilusSubmarineLand" }.unitId
                fun mother() = s.observation.units.single { it.unitId == id }
                s.select(mother().position)
                assertIs<ActionResult.Accepted>(s.produce("hoverTank"))
                until(260) { mother().transport!!.passengerCount == 1 }
                assertIs<ActionResult.Accepted>(s.move(Position(500.0, 400.0)))
                until(2000) { mother().typeId == "nautilusSubmarineSurface" }
                assertEquals(1, mother().transport!!.passengerCount)
                assertIs<ActionResult.Accepted>(s.unitAction("dive"))
                until(140) { mother().typeId == "nautilusSubmarine" }
                assertEquals(1, mother().transport!!.passengerCount)
                assertIs<ActionResult.Accepted>(s.unitAction("surface"))
                until(2) { mother().typeId == "nautilusSubmarineSurface" }
                assertIs<ActionResult.Accepted>(s.unload(true))
                until(150) { mother().transport!!.passengerCount == 0 }
                assertTrue(s.observation.units.any { it.typeId == "hoverTank" })
                assertIs<ActionResult.Accepted>(s.move(Position(250.0, 400.0)))
                until(1800) { mother().typeId == "nautilusSubmarineLand" }
                assertEquals(id, mother().unitId)
                assertTrue(before > s.observation.balance!!)
            }
            SandboxSession(scene).use { reopened ->
                assertEquals(30000L, reopened.observation.balance)
                assertEquals(listOf("commandCenter"), reopened.observation.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            }
        }
    }
}
