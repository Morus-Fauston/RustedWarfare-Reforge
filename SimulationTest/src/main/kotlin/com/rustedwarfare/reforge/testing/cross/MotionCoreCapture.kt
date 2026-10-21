package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.math.*

/** Separate O replay adapter; fixed public core steps, no changes to the self runner or gameplay. */
object MotionCoreCapture {
    fun capture(requestPath: Path,output: Path): JsonObject {
        val request=OriginalMotionComparison.read(requestPath)
        request.header("motion-core-request")
        OriginalMotionComparison.only(request,setOf("kind","schemaVersion","caseId","runId","standard","preparedInputs","original"))
        val runId=request.text("runId");require(UUID.fromString(runId).toString()==runId)
        val identities=mutableListOf<JsonElement>()
        fun bound(ref: JsonObject): Path {
            val path=Path.of(ref.text("path"));require(path.isAbsolute() && sha256(path)==ref.text("sha256"))
            identities+=obj("path" to s(path.toString()),"sha256" to s(sha256(path)));return path
        }
        val standardPath=bound(request.objectAt("standard"));require(sha256(standardPath)==OriginalMotionComparison.STANDARD_SHA256)
        val standard=OriginalMotionComparison.read(standardPath)
        val sources=standard.arrayAt("normativeFrozenSources").map { it.jsonObject }
        for(ref in sources)bound(ref)
        val catalog=OriginalMotionComparison.read(Path.of(sources.single{it.text("path").contains("scenarios")}.text("path")))
        val case=catalog.arrayAt("cases").map{it.jsonObject}.single{it.text("id")==request.text("caseId")}
        val originalPath=bound(request.objectAt("original"));val original=OriginalMotionComparison.read(originalPath)
        original.header("motion-evidence");require(original.text("role")=="original" && original["complete"]==b(true) && original.integer("exitCode")==0L)
        if(original.text("sourceKind")=="native-scene-runtime")MotionSources.bindPrepared(request,original,identities)
        val prepared=OriginalMotionComparison.read(bound(request.objectAt("preparedInputs")))
        val mapRef=prepared.arrayAt("cases").map{it.jsonObject}.single{it.text("caseId")==case.text("id")}.objectAt("core")
        for(ref in prepared.arrayAt("inputs"))bound(ref.jsonObject)
        val map=content(ContentLoader.loadMap(bound(mapRef)))
        val unit=content(ContentLoader.loadUnitVariant(bound(prepared.objectAt("coreUnit"))))
        val horizon=ceil(original.arrayAt("samples").last().jsonObject.number("q")).toInt();require(horizon in 18..604)
        val commands=original.arrayAt("inputs").map{it.jsonObject}
        val initial=case["start"] as? JsonArray ?: catalog.objectAt("common").arrayAt("start")
        val scenario=Scenario("s07.${case.text("id")}.$runId",map,mapOf("tank" to unit),listOf(Player("p1",PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1","p1","tank",Position(initial[0].jsonPrimitive.double,initial[1].jsonPrimitive.double),case.number("coreHeading"))),
            CoordinateModel("p001.coordinate.v1","2d","x-right","y-down","top-left","world-unit","double","ieee754-binary64"),
            TimeModel("p001.time.v1","sim-tick","ieee754-binary64","fixed-explicit-delta"),MovementContract(16.0,4.0))
        val context=(SimulationContext.initialize(scenario) as? InitResult.Accepted)?.context ?: throw CoreFailure("O replay initialization rejected")
        val samples=mutableListOf<JsonElement>();val inputs=mutableListOf<JsonElement>();var sequence=-1
        var captureFailure: Exception?=null
        fun record(q: Int,observation: Observation,kind: String="advance") {
            require(observation.simulationTime==q.toDouble() && observation.units.size==1)
            val u=observation.units.single();require(u.unitId=="u1" && u.ownerPlayerId=="p1" && u.typeId=="tank")
            samples+=obj("index" to n(samples.size),"q" to n(q),"phase" to n(0),"inputSequence" to n(sequence),
                "state" to obj("id" to s(u.unitId),"owner" to s(u.ownerPlayerId),"definition" to s(u.typeId),
                    "x" to n(u.position.x),"y" to n(u.position.y),"heading" to n(u.heading),"target" to u.moveTarget?.json()),
                "raw" to obj("kind" to s(kind),"observation" to observation.json()))
        }
        try {
            record(0,context.observe(),"initial")
            for(q in 0..horizon) {
                if(q>0)record(q,context.advance(Step(q.toLong()-1,1.0,"sim-tick")))
                for((i,c)in commands.withIndex().filter{ceil(it.value.number("q")).toInt()==q}) {
                    require(i==sequence+1)
                    val before=context.observe().json();val id="O.$i"
                    val action=when(c.text("action")){
                        "move"->MoveAction(id,"p1","u1",position(c.objectAt("target")),context.world)
                        "stop"->StopAction(id,"p1","u1",context.world)
                        else->throw InputError("Unsupported O command")
                    }
                    val submitted=context.submit(action);if(submitted !is ActionResult.Accepted)throw CoreFailure("O replay command rejected: $submitted")
                    sequence=i
                    inputs+=obj("sequence" to n(i),"q" to n(q),"action" to c.required("action"),"target" to c.required("target"),
                        "raw" to obj("submittedAtQ" to n(q),"before" to before,"after" to context.observe().json(),"submitResult" to submitted.json(),"originalInput" to c))
                    record(q,context.observe(),"input-post")
                }
            }
            require(inputs.size==commands.size)
        } catch(error: Exception){captureFailure=error} finally { context.close() }
        val artifacts=listOf(MotionCoreCapture::class.java,SimulationContext::class.java,Json::class.java,kotlin.KotlinVersion::class.java).map { type->
            val path=Path.of(type.protectionDomain.codeSource.location.toURI())
            if(Files.isRegularFile(path))obj("class" to s(type.name),"path" to s(path.toString()),"sha256" to s(sha256(path)))
            else obj("class" to s(type.name),"path" to s(path.toString()),"classSha256" to s(digest(type.getResourceAsStream("/${type.name.replace('.','/')}.class")!!.use{it.readBytes()})))
        }
        val document=obj("kind" to s("motion-evidence"),"schemaVersion" to n(1),"role" to s("core"),"runId" to s(runId),
            "caseId" to case.required("id"),"sourceKind" to s("core-runtime"),"complete" to b(captureFailure==null),"exitCode" to n(if(captureFailure==null)0 else 4),
            "captureError" to captureFailure?.let{obj("type" to s(it.javaClass.name),"message" to it.message?.let(::s))},
            "samples" to array(samples),"inputs" to array(inputs),"recordCount" to n(samples.size),
            "provenance" to obj("request" to obj("path" to s(requestPath.toAbsolutePath().toString()),"sha256" to s(sha256(requestPath))),
                "inputs" to array(identities),"runtimeArtifacts" to array(artifacts),"javaVersion" to s(System.getProperty("java.version")),"pid" to n(ProcessHandle.current().pid())))
        Files.writeString(output,document.toString()+"\n",StandardOpenOption.CREATE_NEW)
        if(captureFailure!=null)throw CoreFailure("Core replay failed; partial raw DTOs retained at $output: ${captureFailure.javaClass.name}: ${captureFailure.message}")
        return document
    }
    private fun <T> content(result: ContentResult<T>): T=when(result){
        is ContentResult.Success->result.value
        is ContentResult.Failure->throw CoreFailure("O content load failed: ${result.error}")
    }
}
