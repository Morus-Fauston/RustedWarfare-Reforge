package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Runtime eligibility requires revalidating the pinned raw adapter and the core DTO provenance. */
internal object MotionSources {
    const val ADAPTER_SHA="48b174174d452dbcc71acbb6053b1526573b94a30c0850e3a777742151ec19f4"
    const val PYTHON_SHA="ef8f51028ac5329641985112f8efb1c2d4c47c86b8011ddf7e6fae21e2b4e5a1"
    private fun bound(value: JsonObject,refs: MutableList<JsonElement>): Path {
        val path=Path.of(value.text("path"))
        if(!path.isAbsolute || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || sha256(path)!=value.text("sha256"))throw MotionFailure(4,"source-identity-mismatch","Runtime source bytes differ: $path")
        refs+=obj("path" to s(path.toString()),"sha256" to s(sha256(path)));return path
    }
    internal fun bindPrepared(request: JsonObject,original: JsonObject,refs: MutableList<JsonElement>): List<JsonObject> {
        val preparedRef=request.objectAt("preparedInputs")
        val approved=original.objectAt("provenance").objectAt("preparedInputs")
        if(preparedRef.text("sha256")!=approved.text("sha256") || Path.of(preparedRef.text("path")).normalize()!=Path.of(approved.text("path")).normalize())throw MotionFailure(4,"core-prepared-identity","Core content manifest differs from native preparation")
        val prepared=OriginalMotionComparison.read(bound(preparedRef,refs))
        val map=prepared.arrayAt("cases").map{it.jsonObject}.single{it.text("caseId")==request.text("caseId")}.objectAt("core")
        return listOf(map,prepared.objectAt("coreUnit")).also{for(ref in it)bound(ref,refs)}
    }
    fun runtimePair(core: MotionTrace,original: MotionTrace,corePath: Path,originalPath: Path,contract: JsonObject,refs: MutableList<JsonElement>): Boolean {
        val kinds=listOf(core.document.text("sourceKind"),original.document.text("sourceKind"))
        if(kinds==listOf("tool-fixture","tool-fixture"))return false
        if(kinds!=listOf("core-runtime","native-scene-runtime"))throw MotionFailure(3,"runtime-pair-required","Both approved runtime sources are required")
        val originalProvenance=original.document.objectAt("provenance")
        if(core.document.text("caseId")!=contract.text("caseId") || original.document.text("caseId")!=contract.text("caseId"))throw MotionFailure(4,"runtime-case-mismatch","Runtime evidence case differs from contract")
        val adapterRef=originalProvenance.objectAt("validator")
        if(adapterRef.text("sha256")!=ADAPTER_SHA)throw MotionFailure(3,"unreviewed-native-adapter","Native adapter version is outside this comparison implementation")
        val adapter=bound(adapterRef,refs);val capture=bound(originalProvenance.objectAt("capture"),refs)
        val verifierRef=contract["runtimeVerifier"] as? JsonObject ?: throw MotionFailure(3,"missing-runtime-verifier","Explicit pinned interpreter is required to recheck the raw capture")
        OriginalMotionComparison.only(verifierRef,setOf("path","sha256"))
        if(verifierRef.text("sha256")!=PYTHON_SHA)throw MotionFailure(3,"unreviewed-runtime-verifier","Interpreter identity is outside this tool build")
        val python=bound(verifierRef,refs)
        val script="""
            import hashlib,importlib.util,json,pathlib,sys
            p=pathlib.Path(sys.argv[1]);s=importlib.util.spec_from_file_location('cross_pinned_scene',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
            value=m.validate(pathlib.Path(sys.argv[2]));raw=pathlib.Path(sys.argv[3]).read_bytes();assert json.loads(raw)==value,'normalized evidence differs from raw revalidation';print(hashlib.sha256(raw).hexdigest())
        """.trimIndent()
        val process=ProcessBuilder(python.toString(),"-E","-B","-c",script,adapter.toString(),capture.toString(),originalPath.toAbsolutePath().toString()).redirectErrorStream(true).start()
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw MotionFailure(4,"native-revalidation-timeout","Pinned raw validator exceeded 30 seconds")}
        val output=process.inputStream.use{it.readNBytes(16385)}.toString(Charsets.UTF_8)
        if(process.exitValue()!=0 || output.trim()!=sha256(originalPath))throw MotionFailure(3,"native-revalidation-failed","Raw revalidation does not reproduce this evidence: ${output.take(1500)}")
        val coreProvenance=core.document.objectAt("provenance")
        val request=OriginalMotionComparison.read(bound(coreProvenance.objectAt("request"),refs))
        val originalBinding=request.objectAt("original")
        if(Path.of(originalBinding.text("path")).toAbsolutePath().normalize()!=originalPath.toAbsolutePath().normalize() || originalBinding.text("sha256")!=sha256(originalPath) || request.text("runId")!=core.document.text("runId") || request.text("caseId")!=original.document.text("caseId"))throw MotionFailure(4,"core-replay-binding","Core replay is bound to different original evidence/case")
        val contentRefs=bindPrepared(request,original.document,refs)
        for(required in contentRefs)if(coreProvenance.arrayAt("inputs").none{value->val v=value.jsonObject;v.text("path")==required.text("path") && v.text("sha256")==required.text("sha256")})throw MotionFailure(4,"core-content-origin","Loaded map/unit is absent from core content provenance")
        for(value in coreProvenance.arrayAt("inputs"))bound(value.jsonObject,refs)
        val artifacts=coreProvenance.arrayAt("runtimeArtifacts").map{it.jsonObject}
        if(artifacts.map{it.text("class")}.toSet()!=setOf(MotionCoreCapture::class.java.name,"com.rustedwarfare.reforge.core.sim.SimulationContext","kotlinx.serialization.json.Json","kotlin.KotlinVersion"))throw MotionFailure(3,"missing-runtime-identity","Incomplete core runtime artifact set")
        for(value in artifacts) {
            val type=Class.forName(value.text("class"))
            val executing=Path.of(type.protectionDomain.codeSource.location.toURI())
            if(Files.isRegularFile(executing)) {
                if(value["sha256"]!=s(sha256(executing)))throw MotionFailure(4,"runtime-tool-identity","Replay and executing comparator artifact bytes differ")
            } else {
                val bytes=type.getResourceAsStream("/${type.name.replace('.','/')}.class")!!.use{it.readBytes()}
                if(value["classSha256"]!=s(digest(bytes)))throw MotionFailure(4,"runtime-tool-identity","Replay and executing comparator class bytes differ")
            }
            if("sha256" in value)bound(value,refs)
            else {
                val path=Path.of(value.text("path")).resolve(value.text("class").replace('.','/')+".class")
                if(sha256(path)!=value.text("classSha256"))throw MotionFailure(4,"core-class-identity","Core runtime class bytes changed")
                refs+=obj("path" to s(path.toString()),"sha256" to s(sha256(path)))
            }
        }
        var lastAdvanceQ=0.0
        val inputPosts=mutableSetOf<Long>()
        for(point in core.points) {
            val raw=point.raw.objectAt("raw");val dto=raw.objectAt("observation");val units=dto.arrayAt("units")
            if(units.size!=1 || dto.number("simulationTime")!=point.q)throw MotionFailure(3,"core-raw-normalization","Core DTO time/unit count differs")
            val unit=units.single().jsonObject
            if(unit.text("unitId")!="u1" || unit.text("ownerPlayerId")!="p1" || unit.text("typeId")!="tank" || unit.objectAt("position").number("x")!=point.x || unit.objectAt("position").number("y")!=point.y || unit.number("heading")!=point.heading || unit.required("moveTarget")!=point.target)throw MotionFailure(4,"core-raw-normalization","Normalized core state differs from raw DTO")
            when(raw.text("kind")) {
                "initial"->if(point.index!=0 || point.q!=0.0)throw MotionFailure(3,"core-initial-order","Initial DTO must occur first")
                "input-post"->if(point.q!=lastAdvanceQ || !inputPosts.add(point.input) || core.inputs.none{it.number("q")==point.q && it.integer("sequence")==point.input})throw MotionFailure(3,"core-input-boundary","Unbound or duplicate core input boundary")
                "advance"->{if(point.q!=lastAdvanceQ+1 || dto.integer("stepIndex")!=point.q.toLong()-1)throw MotionFailure(3,"core-step-order","Raw core step sequence differs from fixed q");lastAdvanceQ=point.q}
                else->throw MotionFailure(3,"core-raw-kind","Unsupported raw core record")
            }
        }
        if(inputPosts.size!=core.inputs.size)throw MotionFailure(3,"core-missing-input-post","Missing accepted-input post DTO")
        for((c,o)in core.inputs.zip(original.inputs))if(c.objectAt("raw").required("originalInput")!=o || c.objectAt("raw").number("submittedAtQ")!=c.number("q"))throw MotionFailure(4,"core-input-origin","Core command is not bound to the original input record")
        return true
    }
}
