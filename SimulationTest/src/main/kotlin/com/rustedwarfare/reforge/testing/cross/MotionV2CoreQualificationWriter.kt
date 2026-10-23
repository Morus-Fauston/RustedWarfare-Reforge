package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.SimulationContext
import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.json.*

/** Qualification only, including failures. Never writes a formal result or changes diagnostic evidence. */
internal object MotionV2CoreQualificationWriter {
    data class Captured(val document:JsonObject,val reference:JsonObject,val trace:MotionV2CoreExecution.Trace)
    private fun runtimeObservations()=array(listOf(MotionV2CoreQualificationWriter::class.java,MotionV2CoreQualification::class.java,
        MotionV2CoreExecution::class.java,MotionV2CoreTraceJson::class.java,SimulationContext::class.java,Json::class.java).map { type ->
        val resource="/"+type.name.replace('.','/')+".class"
        val bytes=checkNotNull(type.getResourceAsStream(resource)).use { it.readAllBytes() }
        obj("class" to s(type.name),"codeSource" to s(type.protectionDomain.codeSource.location.toString()),
            "classResource" to s(resource),"classResourceBytes" to n(bytes.size),"classResourceSha256" to s(digest(bytes))) })
    fun capture(context:MotionV2CoreQualification.Context,output:Path,runtimeGuard:(String)->Unit,
                qualificationObserver:((String)->Unit)?=null):Captured {
        var primary:Exception?=null;var trace:MotionV2CoreExecution.Trace?=null;var document:JsonObject?=null
        var rawRef:JsonObject?=null;var start:JsonObject?=null;var rawWritten=false;var outputDirectoryQualified=false
        val postInputs=mutableListOf<Exception>();val postRuntime=mutableListOf<Exception>()
        fun failed(e:Exception) { val first=primary;if(first==null)primary=e else if(first!==e)first.addSuppressed(e) }
        try {
            require(output.isAbsolute && output==output.normalize() && output.parent.toRealPath()==output.parent &&
                Files.isDirectory(output.parent,LinkOption.NOFOLLOW_LINKS)) { "Expected canonical existing output directory" }
            outputDirectoryQualified=true
            require(!Files.exists(output,LinkOption.NOFOLLOW_LINKS)) { "Qualification raw already exists" }
            val intent=record("motion-v2-core-qualification-start","formalCompatibility" to b(false),"context" to context.reference,
                "runId" to context.document.required("runId"),"caseId" to context.document.required("caseId"),
                "outputPath" to s(output.toString()),"startedUtc" to s(Instant.now().toString()),"pid" to n(ProcessHandle.current().pid()),
                "qualificationObserverUsed" to b(qualificationObserver!=null))
            val startPath=output.resolveSibling(output.fileName.toString()+".start.json")
            Files.writeString(startPath,intent.toString()+"\n",StandardOpenOption.CREATE_NEW)
            start=MotionV2CoreInputs.reference(startPath)
            context.verifyStable();runtimeGuard("before-execution")
            trace=MotionV2CoreExecution.run(context.prepared,inputGuard={ stage->qualificationObserver?.invoke(stage);context.verifyStable() },
                runtimeGuard=runtimeGuard)
            primary=trace.firstError?.error
            document=record("motion-v2-core-execution-qualification","formalCompatibility" to b(false),"role" to s("core"),
                "context" to context.reference,"runId" to context.document.required("runId"),"caseId" to context.document.required("caseId"),
                "complete" to b(trace.completed),"exitCode" to n(if(trace.completed)0 else 4),"start" to start,
                "inputObservation" to context.inputObservation,"inputIdentities" to array(context.identities),
                "qualificationObserverUsed" to b(qualificationObserver!=null),"runtimeObservations" to runtimeObservations(),
                "javaVersion" to s(System.getProperty("java.version")),"javaHome" to s(System.getProperty("java.home")),
                "javaCommand" to ProcessHandle.current().info().command().orElse(null)?.let(::s),"pid" to n(ProcessHandle.current().pid()),
                "trace" to MotionV2CoreTraceJson.trace(trace))
            val serialized=document.toString()+"\n"
            Files.writeString(output,serialized,StandardOpenOption.CREATE_NEW)
            val written=MotionV2CoreInputs.reference(output);val bytes=serialized.toByteArray(Charsets.UTF_8)
            require(written.integer("bytes")==bytes.size.toLong() && written.text("sha256")==digest(bytes)) { "Qualification written raw identity differs" }
            rawRef=written;rawWritten=true
            qualificationObserver?.invoke("after-write")
        } catch(e:Exception) { failed(e) }
        finally {
            try { context.verifyStable() } catch(e:Exception) { postInputs+=e;failed(e) }
            try { runtimeGuard("writer-finally") } catch(e:Exception) { postRuntime+=e;failed(e) }
        }
        val error=primary
        if(error!=null) {
            try {
                require(outputDirectoryQualified) { "Failure receipt refused: output directory was not qualified" }
                val failure=record("motion-v2-core-qualification-failure","formalCompatibility" to b(false),"context" to context.reference,
                    "runId" to context.document.required("runId"),"caseId" to context.document.required("caseId"),"start" to start,
                    "rawPath" to s(output.toString()),"raw" to rawRef,"rawWritten" to b(rawWritten),"partialRaw" to if(rawWritten)JsonNull else document,
                    "trace" to if(document==null)trace?.let(MotionV2CoreTraceJson::trace)else JsonNull,
                    "qualificationObserverUsed" to b(qualificationObserver!=null),"error" to MotionV2CoreTraceJson.error(error),
                    "inputPostErrors" to array(postInputs.map(MotionV2CoreTraceJson::error)),"runtimePostErrors" to array(postRuntime.map(MotionV2CoreTraceJson::error)))
                Files.writeString(output.resolveSibling(output.fileName.toString()+".failure.json"),failure.toString()+"\n",StandardOpenOption.CREATE_NEW)
            } catch(secondary:Exception) { if(secondary!==error)error.addSuppressed(secondary) }
            throw error
        }
        return Captured(checkNotNull(document),checkNotNull(rawRef),checkNotNull(trace))
    }
}
