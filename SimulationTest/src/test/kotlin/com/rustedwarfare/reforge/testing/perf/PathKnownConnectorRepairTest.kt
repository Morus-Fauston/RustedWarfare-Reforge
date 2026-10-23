package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.path.*
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.MotionCenterGeometry
import com.rustedwarfare.reforge.testing.json
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** D-084: a post-movement connector may need repairing before the next movement. */
class PathKnownConnectorRepairTest {
    @Test fun `fifty known gap routes repair invalid connectors before subsequent movement`() {
        val walls=(0 until 24).filter { it !in 10..14 }.map { intArrayOf(16,it) }
        val unit=UnitDefinition("tank","tank",11.0,11.0,1,Path.of("assets/units/tanks/tank.png"),1.0,
            MovementProfile(MovementType.LAND,1.1,0.05,0.02,3.0,0.01,false,false),emptySet())
        val map=MapDefinition(32,24,20,20,emptyList(),IntArray(768),
            BlockingGrid(32,24,BooleanArray(768) { it%32==16 && it/32 !in 10..14 }))
        val scenario=Scenario("known-connector-repair",map,mapOf(unit.id to unit),listOf(Player("p1",PlayerControl.LOCAL)),
            (0 until 50).map { UnitSpawn(id(it),"p1",unit.id,Position(50.0+20*(it%10),50.0+20*(it/10)),90.0) },
            CoordinateModel("p001.coordinate.v1","2d","x-right","y-down","top-left","world-unit","double","ieee754-binary64"),
            TimeModel("p001.time.v1","sim-tick","ieee754-binary64","fixed-explicit-delta"),MovementContract(16.0,4.0))
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        val directory=Files.createTempDirectory("eng017-known-connector-")
        println("eng017-known-connector-raw=$directory")
        val records=mutableListOf<JsonObject>()
        try {
            context.enableKnowledge(context.world,KnowledgeRules(TerrainRetention.MEMORY,PathInformationSource.PLAYER_KNOWLEDGE,false))
            context.enablePathDiagnostics(context.world)
            val cells=(0 until 768).map { VisibleTerrainCell(it%32,it/32,map.blocking.isBlocked(it%32,it/32)) }
            context.acceptVisibleTerrain(context.world,"p1",cells)
            val initial=context.knowledgeSnapshot(context.world,"p1")
            records+=buildJsonObject {
                put("kind","development-known-connector-repair");put("formalCompatibility",false)
                put("developmentOnly",true);put("steps",10);put("unitCount",50)
                put("knowledgeRevision",initial.revision)
                putJsonArray("cells") { cells.forEach { c->add(buildJsonObject { put("column",c.column);put("row",c.row);put("blocked",c.blocked) }) } }
            }
            for(i in 0 until 50)assertIs<ActionResult.Accepted>(context.submit(MoveAction("perf-move%03d".format(i),"p1",id(i),goal(i),context.world,0)))
            var previous:Observation?=null
            var exercised=0
            for(step in 0..9) {
                val before=previous?.units?.associateBy { it.unitId }.orEmpty()
                val invalid=before.values.filter { it.pathPoints.isNotEmpty() && !clear(listOf(it.position,it.pathPoints.first()),walls) }
                if(step==1)assertEquals(setOf(6,17,28,39,49).map(::id).toSet(),invalid.map { it.unitId }.toSet())
                val observation=context.advance(Step(step.toLong(),1.0,"sim-tick"))
                val searches=context.drainPathDiagnostics(context.world)
                records+=buildJsonObject {
                    put("kind","step");put("step",step)
                    put("observation",observation.json())
                    putJsonArray("searches") { searches.forEach { add(ReplanPerf.sampleJson(it)) } }
                    putJsonArray("invalidBeforeMovement") { invalid.forEach { add(it.unitId) } }
                }
                assertEquals(initial.cells,context.knowledgeSnapshot(context.world,"p1").cells)
                assertEquals(initial.revision,context.knowledgeSnapshot(context.world,"p1").revision)
                for(u in observation.units) {
                    val start=before[u.unitId]?.position ?: scenario.units.single { it.unitId==u.unitId }.position
                    assertTrue(clear(listOf(start,u.position),walls),"physical center crosses known wall: step=$step ${u.unitId}")
                }
                for(old in invalid) {
                    exercised++
                    val sample=searches.single { it.unitId==old.unitId }
                    assertEquals(PathSearchReason.ROUTE_REPAIR,sample.reason)
                    assertEquals("perf-move"+old.unitId.removePrefix("perf-u"),sample.actionId)
                    assertEquals(0L,sample.scheduledStep);assertEquals(step.toLong(),sample.executionStep)
                    assertEquals(old.position,sample.searchStart);assertEquals(0,sample.preservedPrefixPointCount)
                    assertEquals(1L,sample.actualSearchCalls);assertTrue(sample.usedPlayerKnowledge)
                    assertEquals(PathSearchOutcome.FOUND,sample.outcome)
                    assertTrue(sample.searchTarget==old.pathPoints.first() || sample.searchTarget==old.moveTarget)
                    val current=observation.units.single { it.unitId==old.unitId }
                    assertTrue(clear(listOf(old.position)+current.pathPoints,walls),"repaired global path invalid: step=$step ${old.unitId}")
                }
                previous=observation
            }
            assertTrue(exercised>=5,"required post-movement connector cases were not exercised")
        } finally {
            Files.write(directory.resolve("raw.jsonl"),records.map { it.toString() })
            context.close()
        }
    }
    private fun clear(points:List<Position>,walls:List<IntArray>)=MotionCenterGeometry.centerPath(points,walls,32,24,20,20,11.0)
    private fun id(i:Int)="perf-u%03d".format(i)
    private fun goal(i:Int)=Position(410.0+20*(i%10),330.0+20*(i/10))
}
