package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.path.PathSearchReason
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class ReplanPerfTest {
    @Test fun `development lifecycle rejects wrong active goals path tails and aliased completion events`() {
        val batch=ReplanPerf.measure(unit(),10,TerrainRetention.MEMORY,true)
        ReplanPerf.validate(batch)
        fun changed(stepIndex:Int, transform:(JsonObject)->JsonObject):ReplanBatch=batch.copy(steps=batch.steps.mapIndexed { i,step->
            if(i!=stepIndex)step else step.copy(gameplay=JsonObject(step.gameplay+("observation" to transform(step.gameplay.getValue("observation").jsonObject))))
        })
        fun firstUnit(obs:JsonObject,transform:(JsonObject)->JsonObject):JsonObject {
            val units=obs.getValue("units").jsonArray.toMutableList();units[0]=transform(units[0].jsonObject)
            return JsonObject(obs+("units" to JsonArray(units)))
        }
        val goal=buildJsonObject { put("x",551.0);put("y",150.0) }
        val badGoal=changed(1) { obs->firstUnit(obs) { JsonObject(it+("moveTarget" to goal)) } }
        val badTail=changed(1) { obs->firstUnit(obs) { u->
            val points=u.getValue("pathPoints").jsonArray.toMutableList();points[points.lastIndex]=goal
            JsonObject(u+("pathPoints" to JsonArray(points)))
        } }
        val alias=changed(6) { obs->
            val events=obs.getValue("events").jsonArray.map { v->val e=v.jsonObject
                if(e["unitId"]==JsonPrimitive("rp-0"))JsonObject(e+("unitId" to JsonPrimitive("rp-1")))else e
            };JsonObject(obs+("events" to JsonArray(events)))
        }
        val arrived=changed(1) { obs->JsonObject(obs+("events" to JsonArray(obs.getValue("events").jsonArray+
            buildJsonObject { put("kind","movement-arrived");put("unitId","rp-0") }))) }
        val wrongRequest=changed(0) { obs->
            val results=obs.getValue("actionResults").jsonArray.toMutableList()
            results[0]=JsonObject(results[0].jsonObject+("actionId" to JsonPrimitive("other")))
            JsonObject(obs+("actionResults" to JsonArray(results)))
        }
        for((name,value,message)in listOf(Triple("goal",badGoal,"Wrong active request goal"),Triple("tail",badTail,"Wrong active request goal"),
            Triple("event alias",alias,"Wrong per-unit request lifecycle events"),Triple("false arrived",arrived,"Wrong per-unit request lifecycle events"),
            Triple("request",wrongRequest,"Wrong original request outcomes"))) {
            val error=assertFailsWith<IllegalStateException>(name) { ReplanPerf.validate(value) }
            assertTrue(error.message.orEmpty().contains(message),name+": "+error.message)
        }
    }
    @Test fun `development center geometry accepts near wall centers and rejects true known path crossings`() {
        val batch=ReplanPerf.measure(unit(),1,TerrainRetention.MEMORY,true)
        ReplanPerf.validate(batch)
        fun change(stepIndex:Int,field:String,value:JsonElement):ReplanBatch {
            val step=batch.steps[stepIndex];val obs=step.gameplay.getValue("observation").jsonObject
            val unit=obs.getValue("units").jsonArray.single().jsonObject
            val changed=JsonObject(obs+("units" to JsonArray(listOf(JsonObject(unit+(field to value))))))
            return batch.copy(steps=batch.steps.mapIndexed { index,s->if(index==stepIndex)s.copy(gameplay=JsonObject(s.gameplay+("observation" to changed)))else s })
        }
        // This check qualifies the recorded final center, not the physical candidate that produced it.
        val near=change(1,"position",buildJsonObject { put("x",299.5);put("y",150.0) })
        // Later search origins must remain bound to their actual previous snapshot: restrict
        // the synthetic geometric positive to a non-search-adjacent step.
        val nearQuiet=change(3,"position",buildJsonObject { put("x",299.5);put("y",90.0) })
        ReplanPerf.validate(nearQuiet)
        assertFailsWith<IllegalStateException> { ReplanPerf.validate(near) }
        val changedPath=change(2,"pathPoints",buildJsonArray { add(buildJsonObject { put("x",550.0);put("y",150.0) }) })
        val illegal=changedPath.copy(steps=changedPath.steps.mapIndexed { i,s->if(i==2)s.copy(samples=s.samples.map { it.copy(pathPointCount=1) })else s })
        val error=assertFailsWith<IllegalStateException> { ReplanPerf.validate(illegal) }
        assertTrue(error.message.orEmpty().contains("Illegal known-terrain path"))
    }
    @Test fun `development replanning retains all actual search input identities`() {
        val batch=ReplanPerf.measure(unit(),1,TerrainRetention.MEMORY,true)
        val step=batch.steps[2];val s=step.samples.single()
        for(changed in listOf(s.copy(actionId="other"),s.copy(scheduledStep=2),s.copy(searchStart=null),
            s.copy(searchStart=com.rustedwarfare.reforge.core.sim.Position(50.0,150.0)),s.copy(searchTarget=null),
            s.copy(preservedPrefixPointCount=1))) {
            val steps=batch.steps.mapIndexed { i,value->if(i==2)value.copy(samples=listOf(changed))else value }
            val error=assertFailsWith<IllegalStateException> { ReplanPerf.validate(batch.copy(steps=steps)) }
            assertTrue(error.message.orEmpty().contains("search input identity"))
        }
    }
    @Test fun `development protocol requires its fixed hash before any run output or core execution`() {
        val root=Path.of(System.getProperty("repo.root"))
        val protocol=root.resolve("TestLab/contracts/P002-perf/RP.development.center.v2.json")
        ReplanPerfMain.requireDevelopmentProtocol(protocol,ReplanPerf.DEVELOPMENT_CONTRACT_SHA256)
        assertFailsWith<IllegalArgumentException> { ReplanPerfMain.requireDevelopmentProtocol(protocol,"0".repeat(64)) }
        val old=root.resolve("TestLab/contracts/P002-perf/s08-v2.json")
        val oldSha=java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(old)).joinToString("") { "%02x".format(it) }
        assertFailsWith<IllegalArgumentException> { ReplanPerfMain.requireDevelopmentProtocol(old,oldSha) }
        assertFailsWith<IllegalArgumentException> { ReplanPerfMain.requireDevelopmentProtocol(old,ReplanPerf.DEVELOPMENT_CONTRACT_SHA256) }
        val logs=java.nio.file.Files.createTempDirectory("eng017-rp-old-cli-")
        val unit=root.resolve("TestLab/local/P001-移动沙盒输入/units/weaponless_tank.ini")
        val unitSha=java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(unit)).joinToString("") { "%02x".format(it) }
        val cp=listOf(ReplanPerfMain::class.java,com.rustedwarfare.reforge.core.sim.SimulationContext::class.java,
            Json::class.java,kotlinx.serialization.KSerializer::class.java,Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        val argv=listOf(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-cp",cp,
            ReplanPerfMain::class.java.name,"--repo-root",root.toString(),"--unit",unit.toString(),"--unit-sha",unitSha,
            "--protocol",old.toString(),"--protocol-sha",oldSha,"--output",logs.toString())
        java.nio.file.Files.writeString(logs.resolve("argv.json"),JsonArray(argv.map(::JsonPrimitive)).toString(),java.nio.file.StandardOpenOption.CREATE_NEW)
        println("eng017-rp-old-cli=$logs")
        val process=ProcessBuilder(argv).redirectOutput(logs.resolve("stdout.log").toFile()).redirectError(logs.resolve("stderr.log").toFile()).start()
        val finished=process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)
        if(!finished)process.destroyForcibly()
        assertTrue(finished,"Old-protocol CLI did not terminate")
        assertEquals(64,process.exitValue())
        assertTrue(java.nio.file.Files.readString(logs.resolve("stderr.log")).contains("Explicit development replanning contract identity required"))
        assertTrue(java.nio.file.Files.list(logs).use { stream->stream.noneMatch { java.nio.file.Files.isDirectory(it) } })
    }
    @Test fun `development replanning refuses old unknown and missing version identities`() {
        val batch=ReplanPerf.measure(unit(),1,TerrainRetention.MEMORY,true)
        for(version in listOf("","rp-v1","unknown")) {
            val invalid=batch.copy(geometryVersion=version)
            assertFailsWith<IllegalStateException> { ReplanPerf.validate(invalid) }
            assertFailsWith<IllegalStateException> { ReplanPerf.sameGameplay(batch,invalid) }
        }
    }
    @Test fun `development replanning raw identifies its limited center scope`() {
        val batch=ReplanPerf.measure(unit(),1,TerrainRetention.MEMORY,true)
        val raw=ReplanPerf.batchJson(batch)
        assertEquals(PathPerfBatch.DEVELOPMENT_GEOMETRY,raw["geometryVersion"]?.jsonPrimitive?.content)
        assertEquals(true,raw["developmentOnly"]?.jsonPrimitive?.boolean)
        assertEquals("development-replanning-only",raw["qualificationScope"]?.jsonPrimitive?.content)
        assertEquals(false,raw["fullTerrainWritebackQualified"]?.jsonPrimitive?.boolean)
    }
    private fun unit() = assertIs<ContentResult.Success<com.rustedwarfare.reforge.core.content.UnitDefinition>>(
        ContentLoader.loadUnitVariant(Path.of(System.getProperty("repo.root"),
            "TestLab/local/P001-移动沙盒输入/units/weaponless_tank.ini")),
    ).value

    @Test
    fun `real discovery replans once while unrelated and repeated knowledge does not`() {
        val batch = ReplanPerf.measure(unit(), 1, TerrainRetention.MEMORY, true)
        assertEquals(listOf(1, 0, 1, 0, 0, 1, 1, 0), batch.steps.map { it.samples.size })
        assertEquals(listOf(PathSearchReason.USER_MOVE, PathSearchReason.KNOWLEDGE_REPLAN,
            PathSearchReason.USER_MOVE, PathSearchReason.KNOWLEDGE_REPLAN),
            batch.steps.flatMap { it.samples }.map { it.reason })
        for (sample in batch.steps.flatMap { it.samples }) {
            val raw = ReplanPerf.sampleJson(sample)
            assertEquals(550.0, raw.getValue("searchTarget").jsonObject.getValue("x").jsonPrimitive.double)
            assertEquals(150.0, raw.getValue("searchTarget").jsonObject.getValue("y").jsonPrimitive.double)
        }
    }

    @Test
    fun `validation rejects altered identity and illegal actual positions`() {
        val batch = ReplanPerf.measure(unit(), 1, TerrainRetention.MEMORY, true)
        fun altered(fields: Map<String, JsonElement>): ReplanBatch {
            val step = batch.steps.first()
            val observation = step.gameplay.getValue("observation").jsonObject
            val original = observation.getValue("units").jsonArray.single().jsonObject
            val changed = JsonObject(observation + ("units" to JsonArray(listOf(JsonObject(original + fields)))))
            return batch.copy(steps = listOf(step.copy(gameplay = JsonObject(step.gameplay + ("observation" to changed)))) + batch.steps.drop(1))
        }
        for ((name, value) in listOf("unitId" to "alias", "ownerPlayerId" to "p2", "typeId" to "other")) {
            assertFailsWith<IllegalStateException>(name) { ReplanPerf.validate(altered(mapOf(name to JsonPrimitive(value)))) }
        }
        for ((x, y) in listOf(-10000.0 to 150.0, 50.0 to 10000.0, 310.0 to 150.0, 30.0 to 30.0, Double.NaN to 150.0)) {
            val position = buildJsonObject { put("x", x); put("y", y) }
            assertFailsWith<IllegalStateException>("position $x,$y") { ReplanPerf.validate(altered(mapOf("position" to position))) }
        }
        val steps = batch.steps.toMutableList()
        steps[0] = steps[0].copy(samples = steps[0].samples.map { it.copy(unitId = "alias") })
        assertFailsWith<IllegalStateException> { ReplanPerf.validate(batch.copy(steps = steps)) }
    }

    @Test
    fun `fifty real units execute all searches with diagnostic equivalence and bounded state`() {
        val rawDirectory=java.nio.file.Files.createTempDirectory("eng017-replan-pre-validation-")
        println("eng017-replan-raw=$rawDirectory")
        for (retention in TerrainRetention.entries) {
            val measured = ReplanPerf.measure(unit(), 50, retention, true)
            val control = ReplanPerf.measure(unit(), 50, retention, false)
            for((side,batch)in listOf("on" to measured,"off" to control))
                java.nio.file.Files.writeString(rawDirectory.resolve("${retention.name}-$side.json"),ReplanPerf.batchJson(batch).toString()+"\n",java.nio.file.StandardOpenOption.CREATE_NEW)
            ReplanPerf.validate(measured)
            ReplanPerf.validate(control)
            assertTrue(ReplanPerf.sameGameplay(measured, control))
            assertEquals(200, measured.steps.sumOf { it.samples.size })
            assertEquals(100, measured.steps.flatMap { it.samples }.count { it.reason == PathSearchReason.KNOWLEDGE_REPLAN })
            assertFailsWith<IllegalStateException> { ReplanPerf.validate(measured.copy(steps = measured.steps.dropLast(1))) }
            val wrong = measured.steps.toMutableList()
            wrong[2] = wrong[2].copy(samples = emptyList())
            assertFailsWith<IllegalStateException> { ReplanPerf.validate(measured.copy(steps = wrong)) }
        }
    }
}
