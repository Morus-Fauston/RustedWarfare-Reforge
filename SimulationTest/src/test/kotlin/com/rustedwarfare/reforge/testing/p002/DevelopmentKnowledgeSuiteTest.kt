package com.rustedwarfare.reforge.testing.p002

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*

class DevelopmentKnowledgeSuiteTest {
    @Test fun `legacy host contract retains its historical disc semantics`() {
        val result=P002KnowledgeSuite.run(Path.of(System.getProperty("repo.root")),
            "TestLab/suites/P002-knowledge-host.natural.v2.json",Files.createTempDirectory("eng017-host-legacy-"),listOf("legacy-semantics-test"))
        println("eng017-host-legacy-raw=${result.output}")
        assertEquals(2,result.exitCode)
        assertTrue(Files.readString(result.output.resolve("verdict.json")).contains("truth collision penetration"))
    }
    @Test fun `explicit automatic development suite verifies six full archives with fixed real coverage`() {
        val result=P002KnowledgeSuite.run(Path.of(System.getProperty("repo.root")),
            "TestLab/suites/P002-automatic-vision.development.center.v2.json",Files.createTempDirectory("eng017-automatic-development-"),listOf("automatic-development-test"))
        println("eng017-automatic-development-raw=${result.output}")
        assertEquals(0,result.exitCode,Files.readString(result.output.resolve("verdict.json")))
        assertEquals(6,result.caseCount);assertEquals(0,P002KnowledgeSuite.verify(result.output).exitCode)
    }
    @Test fun `historical and development protocols reject mixed or unknown identities`() {
        val root=Path.of(System.getProperty("repo.root"))
        val old=readObject(root.resolve("TestLab/suites/P002-knowledge-host.natural.v2.json"))
        val current=readObject(root.resolve("TestLab/suites/P002-knowledge-host.development.center.v2.json"))
        val contract=readObject(root.resolve("TestLab/contracts/P002-knowledge-host/R01.development.center.v2.json"))
        assertFalse(DevelopmentKnowledgeProtocol.selected(old));assertTrue(DevelopmentKnowledgeProtocol.selected(current))
        assertFailsWith<InputError> { DevelopmentKnowledgeProtocol.selected(JsonObject(old+DevelopmentKnowledgeProtocol.identity)) }
        assertFailsWith<InputError> { DevelopmentKnowledgeProtocol.selected(JsonObject(current+("motionRulesVersion" to s("unknown")))) }
        assertFailsWith<InputError> { DevelopmentKnowledgeProtocol.selected(JsonObject(current+("schemaVersion" to n(1)))) }
        assertFailsWith<InputError> { DevelopmentKnowledgeProtocol.contract(contract,false) }
        assertFailsWith<InputError> { DevelopmentKnowledgeProtocol.contract(readObject(root.resolve("TestLab/contracts/P002-knowledge-host/R01.natural.v2.json")),true) }
    }
    @Test fun `development host rejects semantic corruptions after both formats and inventory are refreshed`() {
        val source=P002KnowledgeSuite.run(Path.of(System.getProperty("repo.root")),"TestLab/suites/P002-knowledge-host.development.center.v2.json",
            Files.createTempDirectory("eng017-host-negative-source-"),listOf("development-host-negative"))
        assertEquals(0,source.exitCode)
        println("eng017-host-negative-source=${source.output}")
        fun corrupt(name:String,change:(JsonObject)->JsonObject) {
            val dest=Files.createTempDirectory("eng017-host-$name-")
            Files.walk(source.output).use { paths->paths.sorted().forEach { p->val q=dest.resolve(source.output.relativize(p));if(Files.isDirectory(p))Files.createDirectories(q)else Files.copy(p,q) } }
            for(format in listOf("raw","normalized")) {
                val file=dest.resolve("K09-collision/$format.jsonl")
                val rows=Files.readAllLines(file).map { change(json.parseToJsonElement(it).jsonObject) }
                Files.writeString(file,rows.joinToString("\n",postfix="\n"))
            }
            val manifest=readObject(dest.resolve("manifest.json"))
            val refs=Files.walk(dest).use { paths->paths.filter { Files.isRegularFile(it) && it.fileName.toString()!="manifest.json" }.sorted().map {
                obj("path" to s(dest.relativize(it).toString().replace('\\','/')),"sha256" to s(sha256(it)))
            }.toList() }
            Files.writeString(dest.resolve("manifest.json"),JsonObject(manifest+("files" to array(refs))).toString()+"\n")
            val verdict=P002KnowledgeSuite.verify(dest)
            println("eng017-host-mutation=$name exit=${verdict.exitCode} reason=${verdict.reason} path=$dest")
            assertEquals(2,verdict.exitCode,name+": "+verdict.reason)
        }
        fun unit(row:JsonObject,fields:Map<String,JsonElement>):JsonObject {
            val obs=row.objectAt("observation");val u=obs.arrayAt("units").single().jsonObject
            return JsonObject(row+("observation" to JsonObject(obs+("units" to array(listOf(JsonObject(u+fields)))))))
        }
        corrupt("dynamic-state") { row->if(row["label"]==s("blocked:40"))unit(row,mapOf("movementState" to s("IDLE")))else row }
        corrupt("center-wall") { row->if(row["label"]==s("blocked:40"))unit(row,mapOf("position" to obj("x" to n(50.0),"y" to n(50.0))))else row }
        corrupt("map-outside") { row->if(row["label"]==s("blocked:40"))unit(row,mapOf("position" to obj("x" to n(0.5),"y" to n(50.0))))else row }
        corrupt("missing-collision-witness") { row->if(row["operation"]==s("advance") && row.text("label").startsWith("blocked:")) {
            val obs=row.objectAt("observation");JsonObject(row+("observation" to JsonObject(obs+("events" to JsonArray(obs.arrayAt("events").filter { it.jsonObject["kind"]!=s("movement-blocked") })))))
        }else row }
        corrupt("wrong-request-replan") { row->if(row["label"]==s("resume:0"))JsonObject(row+("searches" to JsonArray(row.arrayAt("searches").map { JsonObject(it.jsonObject+("actionId" to s("alias"))) })))else row }
        corrupt("wrong-replan-goal") { row->if(row["label"]==s("resume:0")) {
            val changed=unit(row,mapOf("moveTarget" to obj("x" to n(71.0),"y" to n(50.0))))
            JsonObject(changed+("searches" to JsonArray(row.arrayAt("searches").map { JsonObject(it.jsonObject+("searchTarget" to obj("x" to n(71.0),"y" to n(50.0)))) })))
        }else row }
        corrupt("wrong-replan-origin") { row->if(row["label"]==s("resume:0"))JsonObject(row+("searches" to JsonArray(row.arrayAt("searches").map { JsonObject(it.jsonObject+("searchStart" to obj("x" to n(30.0),"y" to n(50.0)))) })))else row }
        corrupt("wrong-replan-outcome") { row->if(row["label"]==s("resume:0"))JsonObject(row+("searches" to JsonArray(row.arrayAt("searches").map { JsonObject(it.jsonObject+("outcome" to s("UNREACHABLE"))) })))else row }
        // Header is independently checked after the file inventory is honestly refreshed.
        val execution=source.output.resolve("execution.json")
        val before=readObject(execution)
        Files.writeString(execution,JsonObject(before+mapOf("kind" to s("other"),"schemaVersion" to n(99))).toString()+"\n")
        val manifest=readObject(source.output.resolve("manifest.json"))
        val refs=manifest.arrayAt("files").map { v->val r=v.jsonObject
            if(r["path"]==s("execution.json"))JsonObject(r+("sha256" to s(sha256(execution))))else r
        }
        Files.writeString(source.output.resolve("manifest.json"),JsonObject(manifest+("files" to JsonArray(refs))).toString()+"\n")
        assertEquals(64,P002KnowledgeSuite.verify(source.output).exitCode)
    }
    @Test fun `explicit development host suite verifies eleven actual cases without upgrading legacy semantics`() {
        val result=P002KnowledgeSuite.run(Path.of(System.getProperty("repo.root")),
            "TestLab/suites/P002-knowledge-host.development.center.v2.json",Files.createTempDirectory("eng017-host-development-"),listOf("development-host-test"))
        println("eng017-host-development-raw=${result.output}")
        assertEquals(0,result.exitCode,Files.readString(result.output.resolve("verdict.json")))
        assertEquals(11,result.caseCount)
        assertEquals(0,P002KnowledgeSuite.verify(result.output).exitCode)
    }
}
