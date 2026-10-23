package com.rustedwarfare.reforge.testing.p002

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.math.floor

/** Independent development oracle; no production vision/knowledge implementation is called. */
internal object DevelopmentAutomaticKnowledgeOracle {
    fun validate(rows:List<JsonObject>,model:JsonObject,fixture:JsonObject):List<String> {
        val problems=mutableListOf<String>()
        fun expect(ok:Boolean,reason:String) { if(!ok)problems+=reason }
        val width=model.integer("width").toInt();val height=model.integer("height").toInt()
        val tw=model.integer("tileWidth").toInt();val th=model.integer("tileHeight").toInt()
        val walls=fixture.arrayAt("blocked").map { it.jsonArray.let { xy->intArrayOf(xy[0].jsonPrimitive.int,xy[1].jsonPrimitive.int) } }
        val wallIds=walls.map { it[1]*width+it[0] }.toSet()
        val noFog=fixture.required("noFog")==b(true);val sharing=fixture.required("sharing")==b(true)
        val memory=fixture.text("retention")=="MEMORY"
        val operations=fixture.arrayAt("operations").map { it.jsonObject }.associateBy { it.text("label") }
        val actorByAction=operations.values.filter { it.text("kind")=="move" }.associate { it.text("label") to it.text("actor") }
        val initialUnit=obj("unitId" to s("u1"),"ownerPlayerId" to s("p1"),"typeId" to s("tank"),"position" to model.required("start"),
            "heading" to model.required("heading"),"speed" to n(0.0),"angularVelocity" to n(0.0),"moveTarget" to JsonNull,"pathPoints" to array(emptyList()),"movementState" to s("IDLE"))
        var previous:JsonObject?=null;var committed:JsonObject?=null;var advances=0L;var declaredTarget:JsonElement=JsonNull
        var pendingEvents:List<JsonElement> = emptyList();var pendingResults:List<JsonElement> = emptyList()
        var acceptedMoves=0;var normalClears=0;var clearQ:Double?=null
        fun finishGeneration() {
            if(acceptedMoves>0) {
                expect(normalClears==acceptedMoves,"accepted Move missing unique normal completion within fixed observation budget")
                expect(clearQ!=null && advances-clearQ!!>=18.0,"normal completion missing full 18q stable no-command tail")
            }
        }
        var retained=Array(2) { arrayOfNulls<Boolean>(width*height) }
        val seenWalls=mutableSetOf<Int>();var lostWall=false;var moved=false;var generation=-1L
        for(row in rows.filter { it["kind"]==s("p002-sample") })try {
            val label=row.text("label");val operation=row.text("operation")
            fun check(ok:Boolean,message:String)=expect(ok,"$label: $message")
            if(operation=="close") {
                finishGeneration()
                check(listOf("observation","players","knowledge").all { row.required(it)==JsonNull },"closed state exposed")
                previous=null;committed=null;continue
            }
            if(operation in setOf("initialize","reopen")) {
                check(row.integer("generation")==generation+1,"invalid generation reset")
                generation=row.integer("generation");advances=0;retained=Array(2) { arrayOfNulls<Boolean>(width*height) };committed=null;declaredTarget=JsonNull
                pendingEvents=emptyList();pendingResults=emptyList()
                acceptedMoves=0;normalClears=0;clearQ=null
            }else check(row.integer("generation")==generation,"unexpected generation")
            if(operation=="advance")advances++
            val host=row.objectAt("observation");val u=host.arrayAt("units").single().jsonObject
            val position=u.objectAt("position");val x=position.number("x");val y=position.number("y")
            check(u.keys==initialUnit.keys && u.text("unitId")=="u1" && u.text("ownerPlayerId")=="p1" && u.text("typeId")=="tank","unit identity/shape differs")
            val heading=u.number("heading");val speed=u.number("speed");u.number("angularVelocity")
            check(heading>=0 && heading<360 && speed>=0 && speed<=model.objectAt("movement").number("speed"),"invalid heading/speed")
            val path=u.arrayAt("pathPoints").map { it.jsonObject.let { p->Position(p.number("x"),p.number("y")) } }
            val target=u.required("moveTarget")
            if(operation=="move" && operations.getValue(label).text("actor")=="p1")declaredTarget=operations.getValue(label).required("target")
            if(target!=JsonNull)check(target==declaredTarget,"active target differs from last accepted Move")
            if(target!=JsonNull)target.jsonObject.let { it.number("x");it.number("y") }
            check(MotionCenterGeometry.centerPath(listOf(Position(x,y)),walls,width,height,tw,th,model.number("radius")),"actual center/map containment invalid")
            val expectedState=if(target!=JsonNull)"MOVING"else if(speed!=0.0)"DECELERATING"else "IDLE"
            check(u.text("movementState")==expectedState,"movement state differs")
            check((target==JsonNull)==path.isEmpty(),"path/target lifecycle differs")
            if(target!=JsonNull)check(path.lastOrNull()==target.jsonObject.let { Position(it.number("x"),it.number("y")) },"path goal differs from command")
            if(operation in setOf("initialize","reopen"))check(u==initialUnit,"initial/reopened unit differs")
            val prior=previous?.objectAt("observation")?.arrayAt("units")?.single()?.jsonObject
            if(prior!=null) {
                if(prior["position"]!=u["position"])moved=true
                if(operation!="advance")check(listOf("position","heading","speed","angularVelocity").all { prior[it]==u[it] },"non-advance changed motion")
                if(operation in setOf("stale-world","project-rejected") || operation=="move" && row.text("operationResult")!="accepted") {
                    check(u==prior,"rejected operation changed unit/command")
                    check(previous.required("knowledge")==row.required("knowledge"),"rejected operation changed knowledge")
                }
            }
            val events=host.arrayAt("events").map { it.jsonObject }
            check(events.all { it["unitId"]==s("u1") && it.text("kind") in setOf("movement-started","movement-arrived") },"unexpected lifecycle event")
            check(events.map { it.text("kind") }.distinct().size==events.size,"duplicate lifecycle event")
            if(operation=="advance" && prior!=null) {
                val cleared=prior.required("moveTarget")!=JsonNull && target==JsonNull
                check(events.any { it["kind"]==s("movement-arrived") }==cleared,"normal clear/arrival mismatch")
                if(cleared) {
                    normalClears++;clearQ=host.number("simulationTime")
                    val goal=prior.objectAt("moveTarget");val old=prior.objectAt("position")
                    val dx=old.number("x")-goal.number("x");val dy=old.number("y")-goal.number("y")
                    check(dx*dx+dy*dy<49.0,"arrival failed the strict initial normal radius")
                }
                if(prior.required("moveTarget")==JsonNull)check(target==JsonNull,"command resurrected")
                if(cleared || prior.required("moveTarget")==JsonNull) {
                    // This fixed fixture has C*V=400, greater than its maximum speed20:
                    // every no-command step stops before displacement. Do not generalize
                    // this finite check into a terrain-candidate qualification.
                    check(model.objectAt("movement").number("deceleration")*model.objectAt("movement").number("speed")>=model.objectAt("movement").number("speed"),"fixture does not provide immediate braking")
                    check(u.number("speed")==0.0 && u["position"]==prior["position"] &&
                        u["heading"]==prior["heading"] && u["angularVelocity"]==prior["angularVelocity"],"clear or idle tail changed motion")
                }
            }
            fun event(kind:String)=obj("kind" to s(kind),"unitId" to s("u1"))
            val expectedEvents:List<JsonElement>;val expectedResults:List<JsonElement>
            when(operation) {
                "initialize","reopen"->{expectedEvents=emptyList();expectedResults=emptyList()}
                "move"->{
                    if(operations.getValue(label).text("actor")=="p1") {
                        acceptedMoves++
                        expectedEvents=listOf(event("movement-started"))
                        expectedResults=listOf(obj("actionId" to s(label),"status" to s("accepted"),"queuedForStep" to JsonNull,"events" to array(expectedEvents)))
                        pendingEvents=expectedEvents;pendingResults=expectedResults
                    }else {
                        expectedEvents=emptyList()
                        expectedResults=listOf(obj("actionId" to s(label),"status" to s("rejected"),"error" to obj("code" to s("not-owner"),"message" to s("Player 'p2' does not own 'u1'"))))
                    }
                }
                "advance"->{
                    val cleared=prior?.required("moveTarget")!=null && prior.required("moveTarget")!=JsonNull && target==JsonNull
                    expectedEvents=pendingEvents+if(cleared)listOf(event("movement-arrived"))else emptyList()
                    expectedResults=pendingResults;pendingEvents=emptyList();pendingResults=emptyList()
                }
                else->{expectedEvents=previous?.objectAt("observation")?.arrayAt("events").orEmpty();expectedResults=previous?.objectAt("observation")?.arrayAt("actionResults").orEmpty()}
            }
            check(host.arrayAt("events")==array(expectedEvents),"host events differ from fixed operation lifecycle")
            check(host.arrayAt("actionResults")==array(expectedResults),"host request results differ from fixed operation")
            val searches=row.arrayAt("searches")
            if(operation=="move") {
                val op=operations.getValue(label);val accepted=op.text("actor")=="p1"
                check(row.text("operationResult")==if(accepted)"accepted"else "not-owner","Move result/ownership differs")
                if(accepted) {
                    check(target==op.required("target"),"Move target differs")
                    val search=searches.singleOrNull()?.jsonObject
                    check(search!=null && search["actionId"]==s(label) && search["unitId"]==s("u1") && search["reason"]==s("USER_MOVE") &&
                        search["outcome"]==s("FOUND") && search["usedPlayerKnowledge"]==b(!noFog) && search["actualSearchCalls"]==n(1) &&
                        search["scheduledStep"]==JsonNull && search["executionStep"]==JsonNull && search["searchStart"]==prior?.required("position") &&
                        search["searchTarget"]==target && search["preservedPrefixPointCount"]==n(0) && search["pathPointCount"]==n(path.size),"Move search provenance differs")
                }else check(searches.isEmpty(),"rejected Move searched")
            }else check(searches.isEmpty(),"unexpected search in fixed open route fixture")
            check(row.integer("searchCount")==searches.size.toLong(),"search count differs")
            if(operation=="stale-world")check(row.text("operationResult")=="stale-world","stale handle accepted")
            if(operation=="project-rejected")check(row.text("operationResult")=="automatic-vision","manual projection accepted")
            // Enumerate all cells using world-space cell centers, independently of the
            // production bounding-box scan and its normalized coordinate implementation.
            val basic=(0 until width*height).filter { cell->
                val dx=(cell%width+0.5)*tw-x;val dy=(cell/width+0.5)*th-y
                dx*dx/(tw.toDouble()*tw)+dy*dy/(th.toDouble()*th)<=model.integer("sight").toDouble().let { it*it }
            }.toSet()
            val players=row.objectAt("players")
            if(operation=="advance")committed=host
            val committedExtra=committed?.takeIf { it["sampleId"]!=host["sampleId"] }
            val visibleEvents=(committedExtra?.arrayAt("events").orEmpty()+host.arrayAt("events"))
            val requestResults=(committedExtra?.arrayAt("actionResults").orEmpty()+host.arrayAt("actionResults"))
            for((playerIndex,player)in listOf("p1","p2").withIndex()) {
                val entry=players.objectAt(player);val observed=entry.objectAt("observation")
                val ownBasic=if(playerIndex==0)basic else emptySet()
                val effective=if(playerIndex==0 || sharing)basic else emptySet()
                val col=floor(x/tw).toInt().coerceAtMost(width-1);val r=floor(y/th).toInt().coerceAtMost(height-1)
                val visible=noFog || r*width+col in effective
                val visibleUnit=if(playerIndex==0)u else JsonObject(u+mapOf("moveTarget" to JsonNull,"pathPoints" to array(emptyList())))
                check(observed.arrayAt("units")==if(visible)array(listOf(visibleUnit))else array(emptyList()),"$player visibility/DTO/permission differs")
                check(observed.arrayAt("events")==array(if(visible)visibleEvents else emptyList()),"$player filtered events differ")
                check(observed.arrayAt("actionResults")==array(requestResults.filter { actorByAction[it.jsonObject.text("actionId")]==player }),"$player action privacy differs")
                if(noFog) {
                    check(entry.required("vision")==JsonNull && entry.required("knowledge")==JsonNull && row.required("knowledge")==JsonNull,"NoFog exposes automatic state")
                }else {
                    val vision=entry.objectAt("vision");val store=entry.objectAt("knowledge")
                    check(vision.arrayAt("basicCells")==array(ownBasic.sorted().map(::n)) && vision.arrayAt("effectiveCells")==array(effective.sorted().map(::n)),"$player independently computed visibility differs")
                    check(vision.integer("epoch")==advances && store.integer("revision")==advances+1,"$player epoch/revision differs")
                    val expected=(0 until width*height).map { cell->
                        val seen=cell in effective
                        if(seen)retained[playerIndex][cell]=cell in wallIds
                        else if(!memory)retained[playerIndex][cell]=null
                        obj("visible" to b(seen),"lastKnownBlocked" to retained[playerIndex][cell]?.let(::b),
                            "lastKnownGroundGid" to if(retained[playerIndex][cell]!=null)n(0)else JsonNull)
                    }
                    check(store.arrayAt("cells")==array(expected),"$player independent full knowledge cells differ")
                    check(store==row.objectAt("knowledge").objectAt(player),"$player host/player knowledge differs")
                    if(playerIndex==0)for(wall in wallIds) {
                        if(wall in effective)seenWalls+=wall
                        else if(wall in seenWalls)lostWall=true
                    }
                }
            }
            if(operation=="advance")committed=host
            previous=row
        }catch(error:Exception) { problems+="${row["label"]}: malformed development sample: ${error.message}" }
        finishGeneration()
        expect(moved,"missing real movement coverage")
        if(!noFog)expect(seenWalls.isNotEmpty() && lostWall,"missing visible-to-hidden wall coverage")
        return problems
    }
}
