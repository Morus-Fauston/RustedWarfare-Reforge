package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.json.*

/** Consumes actual reserved bytes. Does not grant source/behavior PASS or run a simulation. */
internal object MotionV2ReservedSource {
    private fun checked(r:JsonObject):Path {
        val p=Path.of(r.text("path"));require(MotionV2CoreInputs.reference(p)==r) { "Reserved source changed: $p" };return p
    }
    private fun directory(p:Path) {
        require(p.isAbsolute && p==p.normalize() && p.toRealPath()==p && Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)) { "Canonical ledger directory required" }
        var cursor=checkNotNull(p.root)
        for(part in p) {
            cursor=cursor.resolve(part)
            require(Files.isDirectory(cursor,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(cursor) && cursor.toRealPath()==cursor) { "Redirected ledger component" }
        }
    }
    class Reserved private constructor(val admitted:MotionV2SourceAdmission.Admitted,val reference:JsonObject,
                                       val root:Path,val history:JsonObject,claims:List<JsonObject>,identities:List<JsonObject>) {
        val claims:List<JsonObject> = java.util.Collections.unmodifiableList(ArrayList(claims))
        val identities:List<JsonObject> = java.util.Collections.unmodifiableList(ArrayList(identities))
        fun verifyStable() {
            var primary:Exception?=null
            fun check(operation:()->Unit) { try { operation() } catch(e:Exception) { val first=primary;if(first==null)primary=e else first.addSuppressed(e) } }
            check { directory(root) };check { admitted.verifyStable() }
            for(r in identities)check { checked(r) }
            primary?.let { throw it }
        }
        fun begin():Started=Started.create(this)
        companion object {
            internal fun load(admitted:MotionV2SourceAdmission.Admitted,reference:JsonObject,root:Path,history:JsonObject):Reserved {
                directory(root);admitted.verifyStable();val consumed=mutableListOf<JsonObject>()
                fun bound(r:JsonObject):Path=checked(r).also { consumed+=r }
                fun read(r:JsonObject)=readObject(bound(r))
                val d=read(reference);val q=admitted.request.document
                require(d.keys==setOf("kind","schemaVersion","formalCompatibility","stage","status","request","wrapper","campaign","ledgerRoot","knownCoreIndex",
                    "primaryError","reservation","command","boundBefore","boundAfter","afterErrors","admissionExport","identitiesStable","finishedUtc","receiptWriteError")) { "Unsupported reserved launch fields" }
                d.header("motion-v2-source-launch-attempt")
                require(d.text("status")=="RESERVED_NO_HOST_STARTED" && d.text("stage")=="reserved" && d.required("formalCompatibility")==b(false))
                require(d.required("primaryError")==JsonNull && d.required("receiptWriteError")==JsonNull && d.required("identitiesStable")==b(true) && d.arrayAt("afterErrors").isEmpty())
                require(d.required("request")==admitted.request.identity && d.required("wrapper")==admitted.policy.wrapperRef && d.required("campaign")==admitted.plan.identity)
                require(d.text("ledgerRoot")==root.toString()) { "Reservation root differs" }
                require(d.required("knownCoreIndex")==history) { "Reservation history differs" };bound(history)
                val before=d.arrayAt("boundBefore");require(before==d.required("boundAfter") && before.isNotEmpty())
                for(r in before)bound(r.jsonObject)
                require(before.toSet().containsAll(admitted.identities)) { "Launch omitted admitted identities" }
                val command=d.objectAt("command");require(command.integer("exitCode")==0L && command.arrayAt("outputErrors").isEmpty())
                require(command.keys==setOf("argv","timeoutSeconds","startedUtc","exitCode","start","outputErrors","finishedUtc","stdout","stderr"))
                val start=read(command.objectAt("start"));require(start.required("argv")==command.required("argv") && start.required("exitCode")==JsonNull)
                bound(command.objectAt("stderr"));val stdout=Files.readString(bound(command.objectAt("stdout")))
                val exportRef=d.objectAt("admissionExport");require(json.parseToJsonElement(stdout)==exportRef)
                val argv=command.arrayAt("argv").map { it.jsonPrimitive.content }
                require(argv.size==15 && argv[1]=="-cp" && argv.drop(3)==listOf("com.rustedwarfare.reforge.testing.cross.MotionV2SourceLaunchMain",
                    admitted.policy.wrapperRef.text("path"),admitted.policy.wrapperRef.text("sha256"),admitted.plan.identity.text("path"),admitted.plan.identity.text("sha256"),
                    admitted.request.identity.text("path"),admitted.request.identity.integer("bytes").toString(),admitted.request.identity.text("sha256"),
                    q.text("caseId"),q.integer("repeat").toString(),q.text("role"),exportRef.text("path"))) { "Admission child command differs" }
                val runtimePaths=argv[2].split(java.io.File.pathSeparator)
                require(runtimePaths.size==6 && runtimePaths.distinct().size==6 && (runtimePaths+argv[0]).all { p->before.any { it.jsonObject.text("path")==p } })
                val exported=read(exportRef);exported.header("motion-v2-source-admission-export")
                require(exported.keys==setOf("kind","schemaVersion","formalCompatibility","requestRef","request","wrapper","campaign","slot","originalEntry","identities"))
                require(exported.required("formalCompatibility")==b(false) && exported.required("requestRef")==admitted.request.identity && exported.required("request")==q)
                require(exported.required("wrapper")==admitted.policy.wrapperRef && exported.required("campaign")==admitted.plan.identity &&
                    exported.required("slot")==admitted.slot && exported.required("originalEntry")==admitted.originalEntry)
                require(exported.arrayAt("identities").toSet()==admitted.identities.toSet())
                val r=d.objectAt("reservation")
                require(r.keys==setOf("kind","schemaVersion","status","stage","request","expectedRequest","knownCoreIndex","root","claims","inputs"))
                r.header("motion-v2-attempt-reservation")
                require(r.text("status")=="RESERVED" && r.text("stage")=="reserved" && r.required("request")==admitted.request.identity && r.required("expectedRequest")==q)
                require(r.text("root")==root.toString() && r.required("knownCoreIndex")==history)
                val inputs=r.arrayAt("inputs");require(history in inputs && admitted.request.identity in inputs)
                val index=read(history)
                val expectedInputs=listOf(admitted.request.identity,q.objectAt("campaign"),q.objectAt("standard"))+
                    q.objectAt("sources").values.map { it.jsonObject }+history+index.arrayAt("entries").map { it.jsonObject.objectAt("evidence") }
                require(inputs==array(expectedInputs)) { "Reservation input closure differs" }
                for(input in inputs)bound(input.jsonObject)
                val kinds=if(q.text("role")=="core")listOf("slot","host","core")else listOf("slot","host")
                val paths=listOf(root.resolve("slots").resolve(admitted.plan.identity.text("sha256")).resolve("${q.text("caseId")}-${q.integer("repeat")}-${q.text("role")}.json"),
                    root.resolve("hosts").resolve(q.text("hostAttemptId")+".json"),root.resolve("cores").resolve(q.text("runId")+".json"))
                val claims=r.arrayAt("claims").map { it.jsonObject };require(claims.size==kinds.size)
                for((i,claim)in claims.withIndex()) {
                    require(claim.keys==setOf("claimKind","claimPath","reference") && claim.text("claimKind")==kinds[i] && claim.text("claimPath")==paths[i].toString()) { "Reservation claim path differs" }
                    val cr=claim.objectAt("reference");require(cr.text("path")==paths[i].toString());val c=read(cr)
                    require(c.keys==setOf("kind","schemaVersion","claimKind","stage","createdUtc","request","expectedRequest","knownCoreIndex"))
                    c.header("motion-v2-reservation-claim")
                    require(c.text("claimKind")==kinds[i] && c.text("stage")==kinds[i] && c.required("request")==admitted.request.identity && c.required("expectedRequest")==q && c.required("knownCoreIndex")==history)
                    Instant.parse(c.text("createdUtc"))
                }
                return Reserved(admitted,reference,root,history,claims,consumed.distinct()).also { it.verifyStable() }
            }
        }
    }
    class Started private constructor(val reserved:Reserved,val reference:JsonObject,val processId:Long) {
        fun verifyStable() {
            require(processId==ProcessHandle.current().pid());var first:Exception?=null
            try { reserved.verifyStable() } catch(e:Exception) { first=e }
            try { checked(reference) } catch(e:Exception) { val primary=first;if(primary==null)first=e else primary.addSuppressed(e) }
            first?.let { throw it }
        }
        companion object {
            internal fun create(reserved:Reserved):Started {
                reserved.verifyStable();val q=reserved.admitted.request.document
                var first:Exception?=null;var result:Started?=null
                try {
                    var folder=reserved.root
                    for(part in listOf("executions",reserved.admitted.plan.identity.text("sha256"))) {
                        directory(folder);folder=folder.resolve(part)
                        try { Files.createDirectory(folder) } catch(_:FileAlreadyExistsException) { /* Validate the existing component below. */ }
                        directory(folder)
                    }
                    val target=folder.resolve("${q.text("caseId")}-${q.integer("repeat")}-${q.text("role")}.json")
                    val pid=ProcessHandle.current().pid()
                    val d=record("motion-v2-execution-intent","formalCompatibility" to b(false),"launch" to reserved.reference,
                        "requestRef" to reserved.admitted.request.identity,"request" to q,"claims" to array(reserved.claims),
                        "createdUtc" to s(Instant.now().toString()),"pid" to n(pid))
                    val text=d.toString()+"\n";Files.writeString(target,text,StandardOpenOption.CREATE_NEW)
                    val r=MotionV2CoreInputs.reference(target);val bytes=text.toByteArray(Charsets.UTF_8)
                    require(r.integer("bytes")==bytes.size.toLong() && r.text("sha256")==digest(bytes)) { "Execution intent written identity differs" }
                    result=Started(reserved,r,pid)
                } catch(e:Exception) { first=e }
                finally {
                    try { reserved.verifyStable() } catch(e:Exception) { val primary=first;if(primary==null)first=e else primary.addSuppressed(e) }
                    try { result?.let { checked(it.reference) } } catch(e:Exception) { val primary=first;if(primary==null)first=e else primary.addSuppressed(e) }
                }
                first?.let { throw it };return checkNotNull(result)
            }
        }
    }
    fun load(admitted:MotionV2SourceAdmission.Admitted,launchRef:JsonObject,expectedRoot:Path,expectedHistory:JsonObject)=Reserved.load(admitted,launchRef,expectedRoot,expectedHistory)
}

/** File-only control seam: actual re-admission plus claim verification and one intent. No simulation. */
internal object MotionV2ReservedSourceMain {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2);val path=Path.of(args[0]);val commandRef=MotionV2CoreInputs.reference(path);require(commandRef.text("sha256")==args[1]);val d=readObject(path)
        require(d.keys==setOf("kind","schemaVersion","wrapper","campaign","request","caseId","repeat","role","launch","ledgerRoot","knownCoreIndex"))
        d.header("motion-v2-reserved-source-command")
        val w=d.objectAt("wrapper");val c=d.objectAt("campaign")
        require(MotionV2CoreInputs.reference(Path.of(w.text("path")))==w && MotionV2CoreInputs.reference(Path.of(c.text("path")))==c)
        val repeat=d.integer("repeat");require(repeat in 1L..3L)
        val admitted=MotionV2SourceAdmission.admit(Path.of(w.text("path")),w.text("sha256"),Path.of(c.text("path")),c.text("sha256"),d.objectAt("request"),d.text("caseId"),repeat.toInt(),d.text("role"))
        val started=MotionV2ReservedSource.load(admitted,d.objectAt("launch"),Path.of(d.text("ledgerRoot")),d.objectAt("knownCoreIndex")).begin()
        started.verifyStable();require(MotionV2CoreInputs.reference(path)==commandRef);println(started.reference)
    }
}
