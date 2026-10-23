package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Receipt format only; loading never grants raw/runtime qualification. */
internal object MotionV2SourceReceipt {
    data class Parsed(val document:JsonObject,val request:JsonObject,val raw:Path?,val identities:List<JsonObject>)
    data class Request(val document:JsonObject,val identity:JsonObject,val identities:List<JsonObject>)
    private class Inputs(plan:MotionV2Campaign.Plan) {
        val consumed=mutableListOf<JsonObject>()
        fun bound(r:JsonObject):Path {
            require(r.keys==setOf("path","bytes","sha256"))
            val p=Path.of(r.text("path"))
            require(p.isAbsolute && p==p.normalize() && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
            require(r.integer("bytes")>=0 && Files.size(p)==r.integer("bytes") && r.text("sha256").matches(Regex("[0-9a-f]{64}")) && sha256(p)==r.text("sha256"))
            consumed+=r;return p
        }
        init {
            require(plan.document.integer("schemaVersion")==3L)
            for(r in plan.identities)bound(r)
            bound(plan.identity);bound(plan.document.objectAt("standard"))
            for(r in plan.sources.values)bound(r.jsonObject)
        }
        fun stable() { for(r in consumed.toList())bound(r) }
    }
    fun loadRequest(reference:JsonObject,plan:MotionV2Campaign.Plan,caseId:String,repeat:Int,role:String):Request {
        val inputs=Inputs(plan)
        val request=OriginalMotionComparison.read(inputs.bound(reference))
        require(request.keys==setOf("kind","schemaVersion","campaign","standard","caseId","repeat","role","runId","hostAttemptId","sources"))
        require(request.text("kind")=="motion-v2-runtime-request" && request.integer("schemaVersion")==3L)
        require(role in setOf("original","core") && repeat in 1..3)
        val slot=plan.runs.single { it.text("caseId")==caseId && it.integer("repeat")==repeat.toLong() }
        val expected=obj("campaign" to plan.identity,"standard" to plan.document.required("standard"),
            "caseId" to s(caseId),"repeat" to n(repeat),"role" to s(role),"runId" to slot.required(if(role=="original")"originalRunId"else"coreRunId"))
        for((key,value)in expected)require(request.required(key)==value) { "Request belongs to another slot or source: $key" }
        val attempt=request.text("hostAttemptId")
        require(UUID.fromString(attempt).toString()==attempt && attempt!=request.text("runId"))
        require(request.required("sources")==plan.sources)
        inputs.stable()
        return Request(request,reference,inputs.consumed.distinct())
    }
    fun load(reference:JsonObject,plan:MotionV2Campaign.Plan,caseId:String,repeat:Int,role:String):Parsed {
        val inputs=Inputs(plan)
        val document=OriginalMotionComparison.read(inputs.bound(reference))
        require(document.keys==setOf("kind","schemaVersion","campaign","standard","caseId","repeat","role","runId","hostAttemptId",
            "hostStartUtc","hostFinishUtc","request","raw","status","error"))
        require(document.text("kind")=="motion-v2-runtime-receipt" && document.integer("schemaVersion")==3L)
        val parsed=loadRequest(document.objectAt("request"),plan,caseId,repeat,role)
        val request=parsed.document
        for(r in parsed.identities)inputs.bound(r)
        for(key in listOf("campaign","standard","caseId","repeat","role","runId","hostAttemptId"))
            require(document.required(key)==request.required(key)) { "Receipt/request belongs to another slot or source: $key" }
        fun utc(value:JsonElement):Instant {
            val text=value.jsonPrimitive;require(text.isString && text.content.endsWith("Z"))
            return Instant.parse(text.content)
        }
        val start=utc(document.required("hostStartUtc"));val finish=utc(document.required("hostFinishUtc"))
        require(start>=utc(plan.document.required("createdUtc")) && finish>=start)
        when(document.text("status")) {
            "completed"->require(document.required("raw")!=JsonNull && document.required("error")==JsonNull)
            "failed"->{ val e=document.required("error").jsonPrimitive;require(e.isString && e.content.isNotBlank()) }
            else->error("Unsupported execution status")
        }
        val raw=if(document.required("raw")==JsonNull)null else inputs.bound(document.objectAt("raw"))
        inputs.stable()
        return Parsed(document,request,raw,inputs.consumed.distinct())
    }
}
