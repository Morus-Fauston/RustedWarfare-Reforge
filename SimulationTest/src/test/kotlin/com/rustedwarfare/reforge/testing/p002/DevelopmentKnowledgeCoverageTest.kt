package com.rustedwarfare.reforge.testing.p002

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Fixed development probe: preserves the original operations and adds 120 advances. */
class DevelopmentKnowledgeCoverageTest {
    @Test fun `fixed extended automatic fixture exercises real wall loss before qualification`() {
        val root=Path.of(System.getProperty("repo.root"))
        val fixture=readObject(root.resolve("TestLab/scenarios/P002-automatic-vision/R01.v1.json"))
        val directory=Files.createTempDirectory("eng017-automatic-coverage-")
        println("eng017-automatic-coverage-raw=$directory")
        for(case in fixture.arrayAt("cases").map { it.jsonObject }) {
            val operations=case.arrayAt("operations").flatMap { value->
                val op=value.jsonObject
                if(op.text("kind")=="advance")listOf(value,obj("label" to s("development-extension"),"kind" to s("advance"),"count" to n(120)))else listOf(value)
            }
            val extended=JsonObject(case+("operations" to JsonArray(operations)))
            val rows=AutomaticKnowledgeCases.capture(fixture.objectAt("model"),extended)
            Files.writeString(directory.resolve(case.text("caseId")+".jsonl"),rows.joinToString("\n",postfix="\n"))
            assertEquals(emptyList(),DevelopmentAutomaticKnowledgeOracle.validate(rows,fixture.objectAt("model"),extended))
            fun replace(value:JsonElement,path:List<String>,replacement:JsonElement):JsonElement {
                if(path.isEmpty())return replacement
                val key=path.first()
                return when(value) {
                    is JsonObject->JsonObject(value+(key to replace(value.getValue(key),path.drop(1),replacement)))
                    is JsonArray->JsonArray(value.mapIndexed { i,v->if(i==key.toInt())replace(v,path.drop(1),replacement)else v })
                    else->error("Invalid mutation path")
                }
            }
            fun at(label:String,path:String,value:JsonElement)=rows.map { row->if(row["label"]==s(label))replace(row,path.split('/'),value).jsonObject else row }
            fun reject(name:String,changed:List<JsonObject>) {
                val issues=DevelopmentAutomaticKnowledgeOracle.validate(changed,fixture.objectAt("model"),extended)
                Files.writeString(directory.resolve(case.text("caseId")+"-"+name+".jsonl"),changed.joinToString("\n",postfix="\n"))
                Files.writeString(directory.resolve(case.text("caseId")+"-"+name+"-issues.json"),JsonArray(issues.map(::s)).toString()+"\n")
                assertTrue(issues.isNotEmpty(),name+" corruption passed")
                println("eng017-automatic-mutation=${case.text("caseId")}/$name issues=${issues.size}")
            }
            fun syncUnit(label:String,field:String,value:JsonElement)=rows.map { row->if(row["label"]!=s(label))row else {
                var changed=replace(row,listOf("observation","units","0",field),value).jsonObject
                for(player in listOf("p1","p2"))if(row.objectAt("players").objectAt(player).objectAt("observation").arrayAt("units").isNotEmpty())
                    changed=replace(changed,listOf("players",player,"observation","units","0",field),value).jsonObject
                changed
            } }
            fun syncObservation(label:String,field:String,value:JsonElement)=rows.map { row->if(row["label"]!=s(label))row else {
                var changed=replace(row,listOf("observation",field),value).jsonObject
                for(player in listOf("p1","p2"))if(row.objectAt("players").objectAt(player).objectAt("observation").arrayAt(field).isNotEmpty() || player=="p1")
                    changed=replace(changed,listOf("players",player,"observation",field),value).jsonObject
                changed
            } }
            if(case.text("caseId")=="A01-memory") {
                val corrupt=rows.map { row->if(row["label"]!=s("initial"))row else {
                    val players=row.objectAt("players");val p=players.objectAt("p1");val vision=p.objectAt("vision")
                    JsonObject(row+("players" to JsonObject(players+("p1" to JsonObject(p+("vision" to JsonObject(vision+("effectiveCells" to array(emptyList())))))))))
                } }
                assertTrue(DevelopmentAutomaticKnowledgeOracle.validate(corrupt,fixture.objectAt("model"),extended).isNotEmpty(),"independent oracle accepted missing visible cells")
                reject("missing-vision",corrupt)
                reject("initial-position",at("initial","observation/units/0/position",obj("x" to n(31.0),"y" to n(50.0))))
                reject("center-in-wall",at("advance:0","observation/units/0/position",obj("x" to n(30.0),"y" to n(30.0))))
                reject("outside-map",at("advance:0","observation/units/0/position",obj("x" to n(0.5),"y" to n(50.0))))
                reject("dynamic-state",at("advance:0","observation/units/0/movementState",s("IDLE")))
                reject("epoch",at("initial","players/p1/vision/epoch",n(1)))
                reject("revision",at("initial","players/p1/knowledge/revision",n(2)))
                reject("owner-dto",at("advance:0","players/p1/observation/units/0/speed",n(0.0)))
                reject("nonadvance-motion",at("move","observation/units/0/position",obj("x" to n(31.0),"y" to n(50.0))))
                reject("frozen-trajectory",rows.map { row->if(row["kind"]!=s("p002-sample"))row else replace(row,listOf("observation","units","0","position"),fixture.objectAt("model").required("start")).jsonObject })
                val forgotten=obj("visible" to b(false),"lastKnownBlocked" to JsonNull,"lastKnownGroundGid" to JsonNull)
                val changed=at("advance:0","players/p1/knowledge/cells/6",forgotten).map { row->if(row["label"]==s("advance:0"))replace(row,listOf("knowledge","p1","cells","6"),forgotten).jsonObject else row }
                reject("memory-forgets",changed)
                val leak=obj("visible" to b(false),"lastKnownBlocked" to b(true),"lastKnownGroundGid" to n(0))
                val leaked=at("initial","players/p1/knowledge/cells/8",leak).map { row->if(row["label"]==s("initial"))replace(row,listOf("knowledge","p1","cells","8"),leak).jsonObject else row }
                reject("unseen-terrain-leak",leaked)
                reject("clear-heading-synced",syncUnit("advance:2","heading",n(91.0)))
                reject("clear-cg-synced",syncUnit("advance:2","angularVelocity",n(0.25)))
                reject("idle-cg-synced",syncUnit("development-extension:0","angularVelocity",n(0.25)))
                reject("late-started-synced",syncObservation("advance:1","events",array(listOf(obj("kind" to s("movement-started"),"unitId" to s("u1"))))))
                val result=rows.single { it["label"]==s("move") }.objectAt("observation").arrayAt("actionResults").single().jsonObject
                reject("request-queue-synced",syncObservation("move","actionResults",array(listOf(JsonObject(result+("queuedForStep" to n(99)))))))
                reject("request-status-synced",syncObservation("move","actionResults",array(listOf(JsonObject(result+("status" to s("rejected")))))))
                val active=rows.single { it["label"]==s("advance:1") }.objectAt("observation").arrayAt("units").single()
                val neverCleared=rows.map { row->if(row["operation"]!=s("advance") || row.text("label") in setOf("advance:0","advance:1"))row else {
                    var changed=replace(row,listOf("observation","units"),array(listOf(active))).jsonObject
                    changed=replace(changed,listOf("observation","events"),array(emptyList())).jsonObject
                    for(player in listOf("p1","p2")) {
                        val entry=row.objectAt("players").objectAt(player).objectAt("observation")
                        if(entry.arrayAt("units").isNotEmpty())changed=replace(changed,listOf("players",player,"observation","units"),array(listOf(active))).jsonObject
                        changed=replace(changed,listOf("players",player,"observation","events"),array(emptyList())).jsonObject
                    }
                    changed
                } }
                val neverIssues=DevelopmentAutomaticKnowledgeOracle.validate(neverCleared,fixture.objectAt("model"),extended)
                assertTrue(neverIssues.any { it.contains("missing unique normal completion") })
                // Preserve visibility/knowledge and owner DTO equality: this negative is
                // rejected for the missing required completion, not a copy mismatch.
                assertFalse(neverIssues.any { it.contains("DTO") || it.contains("knowledge") || it.contains("visibility") })
                reject("never-cleared-synced",neverCleared)
                val shortTail=rows.filter { row->row["kind"]!=s("p002-sample") || row["operation"]!=s("advance") ||
                    row.text("label").startsWith("advance:") || row["label"]==s("development-extension:0") }
                assertTrue(DevelopmentAutomaticKnowledgeOracle.validate(shortTail,fixture.objectAt("model"),extended).any { it.contains("18q stable") })
                reject("short-tail",shortTail)
            }
            if(case.text("caseId")=="A02-hardcore") {
                val retained=obj("visible" to b(false),"lastKnownBlocked" to b(true),"lastKnownGroundGid" to n(0))
                val changed=at("advance:0","players/p1/knowledge/cells/6",retained).map { row->if(row["label"]==s("advance:0"))replace(row,listOf("knowledge","p1","cells","6"),retained).jsonObject else row }
                reject("hardcore-retains",changed)
            }
            if(case.text("caseId")=="A03-sharing-off")reject("sharing-off-leak",at("initial","players/p2/observation/units",rows.first().objectAt("observation").arrayAt("units")))
            if(case.text("caseId")=="A04-sharing-on") {
                val move=rows.single { it["label"]==s("move") }
                reject("nonowner-command-leak",at("move","players/p2/observation/units",move.objectAt("observation").arrayAt("units")))
                reject("rejected-command-change",at("steal","observation/units/0/moveTarget",obj("x" to n(71.0),"y" to n(50.0))))
            }
            if(case.text("caseId")=="A06-lifecycle") {
                reject("stale-accepted",at("stale","operationResult",s("accepted")))
                reject("manual-accepted",at("manual","operationResult",s("accepted")))
                reject("reopen-state",at("reopen","observation/units/0/speed",n(1.0)))
            }
            if(case.text("caseId") !in setOf("A01-memory","A02-hardcore"))continue
            val cells=rows.filter { it["operation"]!=s("close") && it["kind"]==s("p002-sample") }
                .map { it.objectAt("players").objectAt("p1").objectAt("knowledge").arrayAt("cells")[6].jsonObject }
            val first=cells.indexOfFirst { it.required("visible")==b(true) }
            assertTrue(first>=0,"wall never seen")
            val lost=cells.drop(first+1).firstOrNull { it.required("visible")==b(false) }
            assertNotNull(lost,"wall never actually left vision")
            assertEquals(if(case.text("retention")=="MEMORY")b(true)else JsonNull,lost.required("lastKnownBlocked"))
        }
    }
}
