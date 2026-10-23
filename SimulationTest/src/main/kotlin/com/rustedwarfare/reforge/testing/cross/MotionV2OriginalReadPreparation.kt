package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.json.*

/** Prepares a fresh original read from the current Started capability. Never runs a reader. */
internal object MotionV2OriginalReadPreparation {
    private fun checked(r:JsonObject):Path {
        val path=Path.of(r.text("path"))
        require(MotionV2CoreInputs.reference(path)==r) { "Original preparation input changed: $path" }
        return path
    }
    private fun directory(path:Path) {
        require(path.isAbsolute && path==path.normalize() && path.toRealPath()==path && Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) { "Canonical preparation directory required" }
        var cursor=checkNotNull(path.root)
        for(part in path) {
            cursor=cursor.resolve(part)
            require(Files.isDirectory(cursor,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(cursor) && cursor.toRealPath()==cursor) { "Redirected preparation directory" }
        }
    }
    class Prepared private constructor(val started:MotionV2ReservedSource.Started,val document:JsonObject,
                                       val reference:JsonObject,val slot:JsonObject,val graph:JsonObject,
                                       val graphRef:JsonObject,identities:List<JsonObject>) {
        val identities:List<JsonObject> = java.util.Collections.unmodifiableList(ArrayList(identities))
        fun verifyStable() {
            var primary:Exception?=null
            fun check(action:()->Unit) { try { action() } catch(e:Exception) { val first=primary;if(first==null)primary=e else first.addSuppressed(e) } }
            check { started.verifyStable() }
            for(r in identities)check { checked(r) }
            primary?.let { throw it }
        }
        companion object {
            internal fun create(started:MotionV2ReservedSource.Started,graphRef:JsonObject,output:Path):Prepared {
                directory(output);started.verifyStable()
                val admitted=started.reserved.admitted;val q=admitted.request.document;val entry=admitted.originalEntry
                require(q.text("role")=="original") { "Original preparation requires original role" }
                require(q.required("runId")==entry.required("originalRunId"))
                for(name in listOf("caseId","repeat"))require(q.required(name)==entry.required(name))
                require(graphRef in admitted.policy.qualificationArtifacts) { "Original source graph absent from approved qualification artifacts" }
                val identities=mutableListOf<JsonObject>()
                fun bound(r:JsonObject):Path=checked(r).also { identities+=r }
                val graph=readObject(bound(graphRef));graph.header("motion-v2-original-read-sources")
                require(graph.keys==setOf("kind","schemaVersion","sources","cli")) { "Original source graph fields differ" }
                val sources=graph.objectAt("sources")
                val metadata=listOf("python","assessment","deliveryManifest","assessmentReceipt","executionGraph","replayPlan","startupDependencies","terrainTransport")
                require(sources.keys==metadata.toSet()+setOf("pythonVersion","allowedCode")) { "Original sources fields differ" }
                require(sources.text("pythonVersion").isNotBlank())
                val code=sources.arrayAt("allowedCode").map { it.jsonObject }
                require(code.isNotEmpty() && code.distinct().size==code.size)
                val declared=code+metadata.map { sources.objectAt(it) }
                require(declared.groupBy { it.text("path") }.values.all { group->group.distinct().size==1 }) { "Conflicting original source identities" }
                for(r in declared)bound(r)
                val cli=graph.objectAt("cli");require(cli in code && Path.of(cli.text("path")).fileName.toString()=="original_source_read_cli_v1.py") { "Explicit original CLI missing from graph" }
                val dto=record("motion-v2-original-read-slot",*listOf("caseId","repeat","originalRunId","originalRequest","capture","rawRefs").map { it to entry.required(it) }.toTypedArray())
                val requestId=UUID.randomUUID().toString()
                require(requestId !in setOf(q.text("hostAttemptId"),q.text("runId")))
                val writes=mutableListOf<JsonObject>();var primary:Exception?=null;var result:Prepared?=null
                fun write(path:Path,value:JsonObject):JsonObject {
                    val text=value.toString()+"\n";val bytes=text.toByteArray(Charsets.UTF_8)
                    Files.writeString(path,text,StandardOpenOption.CREATE_NEW)
                    val ref=obj("path" to s(path.toString()),"bytes" to n(bytes.size),"sha256" to s(digest(bytes)))
                    writes+=ref;checked(ref);return ref
                }
                try {
                    val slot=write(output.resolve("read-slot.json"),dto)
                    val document=record("motion-v2-original-read-preparation","scope" to s("read-preparation-only"),
                        "campaign" to admitted.plan.identity,"runtimeRequest" to admitted.request.identity,"started" to started.reference,
                        "frozenOriginals" to admitted.plan.document.required("frozenOriginals"),"sourceGraph" to graphRef,
                        "slot" to slot,"hostAttemptId" to q.required("hostAttemptId"),"requestId" to s(requestId))
                    val reference=write(output.resolve("read-preparation.json"),document)
                    result=Prepared(started,document,reference,slot,graph,graphRef,(identities+writes).distinct())
                } catch(e:Exception) { primary=e }
                finally {
                    fun check(action:()->Unit) { try { action() } catch(e:Exception) { val first=primary;if(first==null)primary=e else first.addSuppressed(e) } }
                    check { started.verifyStable() }
                    for(r in identities+writes)check { checked(r) }
                }
                primary?.let { throw it };return checkNotNull(result).also { it.verifyStable() }
            }
        }
    }
    fun prepare(started:MotionV2ReservedSource.Started,sourceGraph:JsonObject,output:Path)=Prepared.create(started,sourceGraph,output)
}

/** File-only control entry. Cannot start Python or SimulationContext. */
internal object MotionV2OriginalReadPreparationMain {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2);val path=Path.of(args[0]);val identity=MotionV2CoreInputs.reference(path);require(identity.text("sha256")==args[1])
        val d=readObject(path);d.header("motion-v2-original-preparation-command")
        require(d.keys==setOf("kind","schemaVersion","wrapper","campaign","request","caseId","repeat","role","launch","ledgerRoot","knownCoreIndex","sourceGraph","output"))
        val w=d.objectAt("wrapper");val c=d.objectAt("campaign");val repeat=d.integer("repeat");require(repeat in 1L..3L)
        require(MotionV2CoreInputs.reference(Path.of(w.text("path")))==w && MotionV2CoreInputs.reference(Path.of(c.text("path")))==c)
        val admitted=MotionV2SourceAdmission.admit(Path.of(w.text("path")),w.text("sha256"),Path.of(c.text("path")),c.text("sha256"),d.objectAt("request"),d.text("caseId"),repeat.toInt(),d.text("role"))
        val started=MotionV2ReservedSource.load(admitted,d.objectAt("launch"),Path.of(d.text("ledgerRoot")),d.objectAt("knownCoreIndex")).begin()
        val prepared=MotionV2OriginalReadPreparation.prepare(started,d.objectAt("sourceGraph"),Path.of(d.text("output")))
        prepared.verifyStable();require(MotionV2CoreInputs.reference(path)==identity);println(prepared.reference)
    }
}
