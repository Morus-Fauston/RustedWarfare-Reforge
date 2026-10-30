package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.*
import kotlinx.serialization.json.*

class AutomaticVisionDevelopmentTest {
    @Test fun `one actual hidden discovery move normally clears and settles within original fixed budget`() {
        val directory=Files.createTempDirectory("eng017-avp-development-")
        println("eng017-avp-development-raw=$directory")
        val run=try { AutomaticVisionDevelopment.captureDevelopment(AutomaticVisionPerf.hiddenDiscoveryFixture(TerrainRetention.MEMORY)) }
        catch(error:AutomaticVisionPerfCaptureException) { Files.write(directory.resolve("partial-raw.jsonl"),error.rows.map { it.toString() });throw error }
        Files.write(directory.resolve("raw.jsonl"),run.rows.map { it.toString() })
        AutomaticVisionDevelopment.qualifyDevelopment(run)
        println("eng017-avp-completion=${run.rows.last()}")
        println("eng017-avp-repair-count="+run.rows.flatMap { it["samples"]?.jsonArray.orEmpty() }.count { it.jsonObject["reason"]==JsonPrimitive("ROUTE_REPAIR") })
        fun reject(name:String,changed:List<JsonObject>,reason:String?=null) {
            val patch=buildJsonObject {
                put("baseFile","raw.jsonl");put("baseRows",run.rows.size);put("resultRows",changed.size)
                putJsonArray("replacements") { changed.forEachIndexed { i,row->if(row!=run.rows.getOrNull(i))add(buildJsonObject { put("index",i);put("row",row) }) } }
            }
            Files.writeString(directory.resolve(name+"-patch.json"),patch.toString()+"\n")
            val error=assertFailsWith<IllegalArgumentException>(name) { AutomaticVisionDevelopment.qualifyDevelopment(run.copy(rows=changed)) }
            if(reason!=null)assertTrue(error.message.orEmpty().contains(reason),name+": "+error.message)
            println("eng017-avp-mutation=$name reason=${error.message}")
        }
        fun unit(row:JsonObject,fields:Map<String,JsonElement>):JsonObject {
            val obs=row.getValue("observation").jsonObject;val units=obs.getValue("units").jsonArray
            val changed=JsonArray(listOf(JsonObject(units.single().jsonObject+fields)))
            val players=row.getValue("players").jsonObject;val owner=players.getValue("p1").jsonObject;val po=owner.getValue("observation").jsonObject
            val changedPlayer=JsonObject(owner+("observation" to JsonObject(po+("units" to changed))))
            val changedPlayers=JsonObject(players+("p1" to changedPlayer))
            return JsonObject(row+mapOf("observation" to JsonObject(obs+("units" to changed)),"players" to changedPlayers))
        }
        fun changedStep(step:Int,fields:Map<String,JsonElement>)=run.rows.map { if(it["label"]==JsonPrimitive("advance:$step"))unit(it,fields)else it }
        fun syncObservation(step:Int,field:String,value:JsonElement)=run.rows.map { row->if(row["label"]!=JsonPrimitive("advance:$step"))row else {
            val obs=row.getValue("observation").jsonObject
            val players=row.getValue("players").jsonObject;val owner=players.getValue("p1").jsonObject;val po=owner.getValue("observation").jsonObject
            val changedOwner=JsonObject(owner+("observation" to JsonObject(po+(field to value))))
            JsonObject(row+mapOf("observation" to JsonObject(obs+(field to value)),"players" to JsonObject(players+("p1" to changedOwner))))
        } }
        val normal=run.rows.last().getValue("normalClearStep").jsonPrimitive.int
        val settle=run.rows.last().getValue("settleStep").jsonPrimitive.int
        reject("initial-position-synced",run.rows.mapIndexed { i,r->if(i==0)unit(r,mapOf("position" to buildJsonObject { put("x",51.0);put("y",70.0) }))else r },"fixed spawn")
        reject("queued-submit-motion",run.rows.mapIndexed { i,r->if(i==1)unit(r,mapOf("speed" to JsonPrimitive(0.1)))else r },"fixed spawn")
        for ((field,value) in listOf("constructionProgress" to JsonPrimitive(0.5),
            "constructionTargetId" to JsonPrimitive("unexpected-building"),
            "tasks" to JsonArray(listOf(buildJsonObject { put("kind","MOVE") })))) {
            reject("initial-$field",run.rows.mapIndexed { i,r->if(i==0)unit(r,mapOf(field to value))else r },"fixed spawn")
        }
        val normalEvents=run.rows[normal+2].getValue("observation").jsonObject.getValue("events").jsonArray
        reject("duplicate-normal-synced",syncObservation(normal,"events",JsonArray(normalEvents+normalEvents)),"Duplicate per-unit lifecycle")
        reject("late-started-synced",syncObservation(1,"events",JsonArray(listOf(buildJsonObject { put("kind","movement-started");put("unitId","u0") }))),"Late start")
        val result=run.rows[2].getValue("observation").jsonObject.getValue("actionResults").jsonArray.single().jsonObject
        reject("host-result-synced",syncObservation(0,"actionResults",JsonArray(listOf(JsonObject(result+("status" to JsonPrimitive("rejected")))))),"Host request results")
        reject("clear-heading-synced",changedStep(normal,mapOf("heading" to JsonPrimitive(90.0))),"heading or retained cg")
        reject("clear-cg-synced",changedStep(normal,mapOf("angularVelocity" to JsonPrimitive(0.0))),"heading or retained cg")
        reject("braking-speed-synced",changedStep(normal+1,mapOf("speed" to JsonPrimitive(0.0))),"proportional braking")
        reject("terminal-cg-synced",changedStep(settle+1,mapOf("angularVelocity" to JsonPrimitive(0.0))),"heading or retained cg")
        reject("missing-terminal-tail",run.rows.dropLast(2)+run.rows.last(),"Failed requirement")
        reject("wrong-settle-step",run.rows.dropLast(1)+JsonObject(run.rows.last()+("settleStep" to JsonPrimitive(settle-1))),"Failed requirement")
        reject("wrong-protocol",run.rows.map { JsonObject(it+("developmentOnly" to JsonPrimitive(false))) },"Historical or unknown")
        reject("actual-truth-sweep",changedStep(12,mapOf("position" to buildJsonObject { put("x",330.0);put("y",90.0) })),"Truth sweep collision")
        val falseNormal=run.rows.mapIndexed { i,row->when(i) {
            3 -> {
                val altered=unit(row,mapOf("moveTarget" to JsonNull,"pathPoints" to JsonArray(emptyList()),"speed" to JsonPrimitive(0.033),
                    "position" to buildJsonObject { put("x",50.088);put("y",70.0) },"movementState" to JsonPrimitive("DECELERATING")))
                val event=JsonArray(listOf(buildJsonObject { put("kind","movement-arrived");put("unitId","u0") }))
                val obs=altered.getValue("observation").jsonObject;val players=altered.getValue("players").jsonObject;val owner=players.getValue("p1").jsonObject
                val ownerObservation=JsonObject(owner.getValue("observation").jsonObject+("events" to event))
                val changedOwner=JsonObject(owner+("observation" to ownerObservation))
                val changedPlayers=JsonObject(players+("p1" to changedOwner))
                JsonObject(altered+mapOf("observation" to JsonObject(obs+("events" to event)),"players" to changedPlayers))
            }
            run.rows.lastIndex -> JsonObject(row+("normalClearStep" to JsonPrimitive(1)))
            else -> row
        } }
        reject("illegal-prestate-clear",falseNormal,"Illegal pre-state normal clear")
        reject("outside-path",changedStep(0,mapOf("pathPoints" to JsonArray(listOf(buildJsonObject { put("x",430.0);put("y",-20.0) })))),"Invalid path point")
        reject("wrong-goal",changedStep(0,mapOf("moveTarget" to buildJsonObject { put("x",431.0);put("y",70.0) })),"Path/target")
        reject("nonfinite-heading",changedStep(0,mapOf("heading" to JsonPrimitive("NaN"))),"Non-finite motion")
        reject("vanished-move",changedStep(12,mapOf("moveTarget" to JsonNull,"pathPoints" to JsonArray(emptyList()),"speed" to JsonPrimitive(0.0),"movementState" to JsonPrimitive("IDLE"))),"Move ended without")
        for(field in listOf("events","actionResults")) {
            val changed=run.rows.mapIndexed { i,row->if(i!=2)row else {
                val players=row.getValue("players").jsonObject;val hidden=players.getValue("p2").jsonObject;val obs=hidden.getValue("observation").jsonObject
                val altered=JsonObject(obs+(field to row.getValue("observation").jsonObject.getValue(field)))
                JsonObject(row+("players" to JsonObject(players+("p2" to JsonObject(hidden+("observation" to altered))))))
            } }
            reject("hidden-player-$field",changed,"Player p2 $field")
        }
        val knownIndex=(3 until run.rows.lastIndex).first { i->
            run.rows[i-1].getValue("players").jsonObject.getValue("p1").jsonObject.getValue("cells").jsonArray[134].jsonObject["lastKnownBlocked"]==JsonPrimitive(true)
        }
        val crossing=JsonArray(listOf(270.0 to 90.0,310.0 to 90.0,430.0 to 70.0).map { (x,y)->buildJsonObject { put("x",x);put("y",y) } })
        reject("known-wall-path",run.rows.mapIndexed { i,row->if(i==knownIndex)unit(row,mapOf("pathPoints" to crossing))else row },"Known-terrain remaining route")
        val searchIndex=run.rows.indexOfFirst { r->r["samples"]?.jsonArray.orEmpty().any { it.jsonObject["reason"]==JsonPrimitive("KNOWLEDGE_REPLAN") } }
        for((name,field,value)in listOf(Triple("wrong-action","actionId",JsonPrimitive("alias")),Triple("wrong-start","searchStart",buildJsonObject { put("x",0.0);put("y",0.0) }),
            Triple("wrong-outcome","outcome",JsonPrimitive("FAILED")),Triple("wrong-search-target","searchTarget",buildJsonObject { put("x",123.0);put("y",123.0) }),
            Triple("wrong-prefix","preservedPrefixPointCount",JsonPrimitive(99)),Triple("wrong-scheduled-step","scheduledStep",JsonPrimitive(1)),
            Triple("wrong-search-call-count","actualSearchCalls",JsonPrimitive(2)))) {
            val changed=run.rows.mapIndexed { i,row->if(i!=searchIndex)row else JsonObject(row+("samples" to JsonArray(row.getValue("samples").jsonArray.map { JsonObject(it.jsonObject+(field to value)) }))) }
            reject(name,changed)
        }
        val mandatoryIndex=(3 until run.rows.lastIndex).first { i->
            val previous=run.rows[i-1];val u=previous.getValue("observation").jsonObject.getValue("units").jsonArray.single().jsonObject
            val points=u.getValue("pathPoints").jsonArray
            if(points.isEmpty() || run.rows[i].getValue("samples").jsonArray.isEmpty())false else {
                val p=u.getValue("position").jsonObject;val first=points.first().jsonObject
                val cells=previous.getValue("players").jsonObject.getValue("p1").jsonObject.getValue("cells").jsonArray
                val walls=cells.mapIndexedNotNull { j,v->if(v.jsonObject["lastKnownBlocked"]==JsonPrimitive(true))intArrayOf(j%30,j/30)else null }
                !com.rustedwarfare.reforge.testing.MotionCenterGeometry.centerPath(listOf(
                    com.rustedwarfare.reforge.core.sim.Position(p.getValue("x").jsonPrimitive.double,p.getValue("y").jsonPrimitive.double),
                    com.rustedwarfare.reforge.core.sim.Position(first.getValue("x").jsonPrimitive.double,first.getValue("y").jsonPrimitive.double)),walls,30,20,20,20,11.0)
            }
        }
        reject("missing-publicly-required-search",run.rows.mapIndexed { i,row->if(i==mandatoryIndex)JsonObject(row+("samples" to JsonArray(emptyList())))else row },"Missing required route repair")
    }

    @Test fun `literal repair contracts retain required connectors near corner stalls and valid fallback`() {
        val start=com.rustedwarfare.reforge.core.sim.Position(270.0,130.0)
        val goal=com.rustedwarfare.reforge.core.sim.Position(430.0,70.0)
        val anchor=com.rustedwarfare.reforge.core.sim.Position(310.0,130.0)
        val oldPath=listOf(anchor,goal);val knowledge=List<Boolean?>(600) { if(it==194)true else null }
        fun point(p:com.rustedwarfare.reforge.core.sim.Position)=buildJsonObject { put("x",p.x);put("y",p.y) }
        val unit=buildJsonObject { put("moveTarget",point(goal)) }
        val observation=buildJsonObject { put("events",JsonArray(emptyList())) }
        fun sample(target:com.rustedwarfare.reforge.core.sim.Position=anchor,outcome:String="FOUND",source:com.rustedwarfare.reforge.core.sim.Position=start)=buildJsonObject {
            put("reason","ROUTE_REPAIR");put("searchStart",point(source));put("searchTarget",point(target));put("scheduledStep",0)
            put("preservedPrefixPointCount",0);put("outcome",outcome);put("pathPointCount",if(outcome=="FOUND")4 else 0)
        }
        fun check(values:List<JsonObject>,path:List<com.rustedwarfare.reforge.core.sim.Position> = oldPath)=
            AutomaticVisionDevelopment.qualifySearches(JsonArray(values),4,start,path,oldPath,knowledge,goal,unit,observation)
        check(listOf(sample()))
        check(listOf(sample(outcome="UNREACHABLE"),sample(target=goal)))
        assertFailsWith<IllegalArgumentException> { check(emptyList()) }
        assertFailsWith<IllegalArgumentException> { check(listOf(sample(),sample())) }
        assertFailsWith<IllegalArgumentException> { check(listOf(sample(target=com.rustedwarfare.reforge.core.sim.Position(123.0,123.0)))) }
        assertFailsWith<IllegalArgumentException> { check(listOf(sample(source=com.rustedwarfare.reforge.core.sim.Position(271.0,130.0)))) }
        assertFailsWith<IllegalArgumentException> { check(listOf(sample(outcome="FAILED"))) }
        assertFailsWith<IllegalArgumentException> { check(listOf(sample(outcome="UNREACHABLE"),sample())) }
        val near=listOf(com.rustedwarfare.reforge.core.sim.Position(271.0,130.0),anchor,goal)
        assertFailsWith<IllegalArgumentException> { check(emptyList(),near) }
        check(listOf(sample(target=goal)),near)
        assertFailsWith<IllegalArgumentException> { check(listOf(sample(target=near.first())),near) }
    }
}
