package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Admission to declared inputs only. Does not reserve a slot, start a host or grant any source/behavior PASS. */
internal object MotionV2SourceAdmission {
    private fun checkIdentity(reference:JsonObject) {
        require(MotionV2CoreInputs.reference(Path.of(reference.text("path")))==reference) {
            "Source admission input changed: ${reference.text("path")}" }
    }
    class Admitted private constructor(val policy:MotionV2Standard.Policy,val plan:MotionV2Campaign.Plan,
                                      val request:MotionV2SourceReceipt.Request,val slot:JsonObject,
                                      val originalEntry:JsonObject,val caseDefinition:JsonObject,val identities:List<JsonObject>) {
        fun verifyStable() { for(reference in identities)checkIdentity(reference) }
        companion object {
            internal fun load(wrapperPath:Path,wrapperSha:String,campaignPath:Path,campaignSha:String,requestRef:JsonObject,
                              caseId:String,repeat:Int,role:String):Admitted {
                val wrapperRef=MotionV2CoreInputs.reference(wrapperPath)
                val campaignRef=MotionV2CoreInputs.reference(campaignPath)
                require(wrapperRef.text("sha256")==wrapperSha) { "Explicit approved wrapper identity differs" }
                require(campaignRef.text("sha256")==campaignSha) { "Explicit campaign identity differs" }
                checkIdentity(requestRef)
                val policy=MotionV2Standard.loadApproved(wrapperPath,wrapperSha)
                val plan=MotionV2Campaign.load(campaignPath,campaignSha,policy)
                val request=MotionV2SourceReceipt.loadRequest(requestRef,plan,caseId,repeat,role)
                val slot=plan.runs.single { it.text("caseId")==caseId && it.integer("repeat")==repeat.toLong() }
                val frozenRef=plan.document.objectAt("frozenOriginals");checkIdentity(frozenRef)
                // Campaign.load has already parsed and validated all FrozenOriginals semantics.
                // Read the same protected bytes, rather than choosing by historical capture.repeat.
                val frozen=OriginalMotionComparison.read(Path.of(frozenRef.text("path")))
                val entry=frozen.arrayAt("entries").map { it.jsonObject }.single {
                    it.required("caseId")==slot.required("caseId") && it.required("repeat")==slot.required("repeat") &&
                        it.required("originalRunId")==slot.required("originalRunId") }
                val case=policy.scenarios.arrayAt("cases").map { it.jsonObject }.single { it.text("id")==caseId }
                val identities=(listOf(wrapperRef,campaignRef,requestRef,frozenRef)+policy.identities+plan.identities+request.identities).distinct()
                return Admitted(policy,plan,request,slot,entry,case,identities).also { it.verifyStable() }
            }
        }
    }
    fun admit(wrapperPath:Path,wrapperSha:String,campaignPath:Path,campaignSha:String,requestRef:JsonObject,
              caseId:String,repeat:Int,role:String):Admitted =
        Admitted.load(wrapperPath,wrapperSha,campaignPath,campaignSha,requestRef,caseId,repeat,role)
}
