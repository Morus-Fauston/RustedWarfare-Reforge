package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/** Actual admission export for the host's reservation call. Never reserves or runs a simulation. */
internal object MotionV2SourceLaunch {
    class Export internal constructor(val document:JsonObject,val reference:JsonObject,
                                      private val admitted:MotionV2SourceAdmission.Admitted) {
        fun verifyStable() {
            var primary:Exception?=null
            try { require(MotionV2CoreInputs.reference(Path.of(reference.text("path")))==reference) { "Admission export changed" } }
            catch(e:Exception) { primary=e }
            try { admitted.verifyStable() } catch(e:Exception) { val first=primary;if(first==null)primary=e else first.addSuppressed(e) }
            primary?.let { throw it }
        }
    }
    fun export(wrapper:Path,wrapperSha:String,campaign:Path,campaignSha:String,requestRef:JsonObject,
               caseId:String,repeat:Int,role:String,output:Path):Export {
        require(output.isAbsolute && output==output.normalize() && output.parent.toRealPath()==output.parent &&
            Files.isDirectory(output.parent,LinkOption.NOFOLLOW_LINKS)) { "Expected canonical existing output directory" }
        require(!Files.exists(output,LinkOption.NOFOLLOW_LINKS)) { "Admission export already exists" }
        val admitted=MotionV2SourceAdmission.admit(wrapper,wrapperSha,campaign,campaignSha,requestRef,caseId,repeat,role)
        var primary:Exception?=null;var result:Export?=null
        try {
            admitted.verifyStable()
            val d=record("motion-v2-source-admission-export","formalCompatibility" to b(false),
                "requestRef" to admitted.request.identity,"request" to admitted.request.document,
                "wrapper" to admitted.policy.wrapperRef,"campaign" to admitted.plan.identity,
                "slot" to admitted.slot,"originalEntry" to admitted.originalEntry,"identities" to array(admitted.identities))
            val text=d.toString()+"\n";Files.writeString(output,text,StandardOpenOption.CREATE_NEW)
            val reference=MotionV2CoreInputs.reference(output);val bytes=text.toByteArray(Charsets.UTF_8)
            require(reference.integer("bytes")==bytes.size.toLong() && reference.text("sha256")==digest(bytes)) { "Written admission export identity differs" }
            result=Export(d,reference,admitted)
        } catch(e:Exception) { primary=e }
        finally {
            try { admitted.verifyStable() } catch(e:Exception) { val first=primary;if(first==null)primary=e else first.addSuppressed(e) }
        }
        primary?.let { throw it }
        return checkNotNull(result)
    }
}

/** Explicit finite file-only entry. A host must verify exit and identities before using its output. */
internal object MotionV2SourceLaunchMain {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==11) { "wrapper wrapperSHA campaign campaignSHA request requestBytes requestSHA case repeat role output" }
        val request=obj("path" to s(args[4]),"bytes" to n(args[5].toLong()),"sha256" to s(args[6]))
        val result=MotionV2SourceLaunch.export(Path.of(args[0]),args[1],Path.of(args[2]),args[3],request,args[7],args[8].toInt(),args[9],Path.of(args[10]))
        result.verifyStable()
        println(result.reference)
    }
}
