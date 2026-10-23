package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.json.*

/** Loads an explicitly identified approved format. Loading never grants approval
 * or qualifies runtime evidence; the trusted execution plan supplies the hash. */
internal object MotionV2Standard {
    private const val ID="p002.original-motion.O.v2"
    private const val CONTRACT_BASE="a6bb1c858ab31dc8fe9994b563adf34d53c8f7928c1ddfb1d83ca6755772b1fc"
    private const val CATALOG_BASE="03ac1dc96774d529317c4fd4e75f8e620ccc9884d78f16c785fa02f0515f4361"
    private const val FINAL_VALIDATION="qualified frozen original fixed12x3 reused; collect only affected missing evidence or invalidated source slots; fresh current-core fixed12x3 after whole-version approval; preserve original UUIDs and slot mapping, no duplicate-run padding or best-batch selection (D-097)"
    data class Policy(val standardId: String, val contract: JsonObject, val scenarios: JsonObject,
                      val lengthEndpoint: String, val durationEndpoint: String, val identities: List<JsonObject>,
                      val wrapperRef: JsonObject, val qualificationArtifacts: List<JsonObject>)
    fun loadApproved(wrapperPath: Path, expectedWrapperSha256: String): Policy {
        require(Regex("[0-9a-f]{64}").matches(expectedWrapperSha256)) { "Explicit approved wrapper hash required" }
        val wrapperFile=wrapperPath.toAbsolutePath().normalize()
        val identities=mutableListOf<JsonObject>()
        fun exactKeys(o:JsonObject,keys:Set<String>) { require(o.keys==keys) { "Unsupported or missing fields: ${(o.keys-keys)+(keys-o.keys)}" } }
        fun bound(r:JsonObject,forbidden:Set<Path> = emptySet()):Path {
            exactKeys(r,setOf("path","bytes","sha256"))
            val p=Path.of(r.text("path"));require(p.isAbsolute)
            val file=p.normalize();require(file !in forbidden) { "Cyclic qualification reference" }
            require(Regex("[0-9a-f]{64}").matches(r.text("sha256")) && r.integer("bytes")>=0)
            require(Files.isRegularFile(file) && Files.size(file)==r.integer("bytes") && sha256(file)==r.text("sha256")) { "Frozen file identity differs: $file" }
            identities+=r;return file
        }
        fun read(r:JsonObject,forbidden:Set<Path> = emptySet())=OriginalMotionComparison.read(bound(r,forbidden))
        require(Files.isRegularFile(wrapperFile) && sha256(wrapperFile)==expectedWrapperSha256) { "Approved wrapper identity differs" }
        val wrapperRef=obj("path" to s(wrapperFile.toString()),"bytes" to n(Files.size(wrapperFile)),"sha256" to s(expectedWrapperSha256))
        val wrapper=read(wrapperRef)
        exactKeys(wrapper,setOf("kind","schemaVersion","standardId","approval","normative","toolQualification"))
        require(wrapper.text("kind")=="original-motion-standard" && wrapper.integer("schemaVersion")==2L && wrapper.text("standardId")==ID)
        val approval=wrapper.objectAt("approval")
        exactKeys(approval,setOf("status","source","date","threadId","text","scope"))
        require(approval.text("status")=="approved" && approval.text("source")=="maintainer-user-message") { "Whole O.v2 approval is required" }
        LocalDate.parse(approval.text("date"));approval.text("threadId");approval.text("text");approval.text("scope")
        val normative=wrapper.objectAt("normative")
        exactKeys(normative,setOf("specification","contract","scenarios"))
        val forbidden=setOf(wrapperFile)
        bound(normative.objectAt("specification"),forbidden)
        val contract=read(normative.objectAt("contract"),forbidden)
        val scenarios=read(normative.objectAt("scenarios"),forbidden)
        val contractBaseRef=contract.objectAt("basedOnCandidate");val catalogBaseRef=scenarios.objectAt("basedOnCandidate")
        require(contractBaseRef.text("sha256")==CONTRACT_BASE && catalogBaseRef.text("sha256")==CATALOG_BASE)
        val contractBase=read(contractBaseRef,forbidden);val catalogBase=read(catalogBaseRef,forbidden)
        require(contractBase.objectAt("scenarios").text("sha256")==CATALOG_BASE)
        val choices=contract.arrayAt("pendingFreezeChoices").map { it.jsonObject }
        val ids=setOf("OM07-length-endpoint","OM07-duration-endpoint")
        require(choices.size==2 && choices.map { it.text("id") }.toSet()==ids)
        val selected=choices.associate { it.text("id") to it.text("value") } // null never becomes a default.
        require(selected==mapOf("OM07-length-endpoint" to "declared-stable-tail-end","OM07-duration-endpoint" to "settle-start")) {
            "D-096 requires the declared stable tail length and earliest qualified settle duration"
        }
        val expectedChoices=contractBase.arrayAt("pendingFreezeChoices").map { raw ->
            val row=raw.jsonObject;val id=row.text("id");val value=selected.getValue(id)
            require(row.arrayAt("options").any { it.jsonPrimitive.isString && it.jsonPrimitive.content==value })
            JsonObject(row+("value" to s(value)))
        }
        val expectedContract=JsonObject(contractBase+mapOf(
            "kind" to s("motion-cross-contract-v2"),"schemaVersion" to n(2),"id" to s(ID),"approval" to approval,
            "executable" to b(true),"basedOnCandidate" to contractBaseRef,
            "specification" to normative.required("specification"),"scenarios" to normative.required("scenarios"),
            "pendingFreezeChoices" to array(expectedChoices),
            "provenance" to JsonObject(contractBase.objectAt("provenance")+("finalValidation" to s(FINAL_VALIDATION)))))
        val expectedCatalog=JsonObject(catalogBase+mapOf("kind" to s("motion-scenario-catalog-v2"),"schemaVersion" to n(2),
            "id" to s(ID),"approval" to approval,"executable" to b(true),"basedOnCandidate" to catalogBaseRef))
        // Full deep equality rejects unsupported nested semantics as well as
        // silently relaxed numbers, deleted applicable gates or replaced cases.
        require(contract==expectedContract) { "Unsupported V2 contract semantics or approval state" }
        require(scenarios==expectedCatalog) { "Unsupported V2 scenario semantics or approval state" }
        val qualificationRef=wrapper.objectAt("toolQualification")
        val qualificationPath=Path.of(qualificationRef.text("path")).toAbsolutePath().normalize()
        val qualification=read(qualificationRef,forbidden)
        exactKeys(qualification,setOf("kind","schemaVersion","status","caseIds","artifacts","evidenceRefs","nonAuthorReview"))
        require(qualification.text("kind")=="motion-v2-tool-qualification" && qualification.integer("schemaVersion")==1L && qualification.text("status")=="qualified")
        val caseIds=qualification.arrayAt("caseIds").map { require(it.jsonPrimitive.isString);it.jsonPrimitive.content }
        val expectedCases=scenarios.arrayAt("cases").map { it.jsonObject.text("id") }
        require(caseIds.size==12 && caseIds.toSet()==expectedCases.toSet())
        val noBackReference=forbidden+setOf(qualificationPath)
        for(key in listOf("artifacts","evidenceRefs")) {
            val refs=qualification.arrayAt(key);require(refs.isNotEmpty())
            refs.forEach { bound(it.jsonObject,noBackReference) }
        }
        bound(qualification.objectAt("nonAuthorReview"),noBackReference)
        require(identities.all { r->val p=Path.of(r.text("path"));Files.size(p)==r.integer("bytes") && sha256(p)==r.text("sha256") }) { "Identity drift during load" }
        return Policy(ID,contract,scenarios,selected.getValue("OM07-length-endpoint"),selected.getValue("OM07-duration-endpoint"),identities.distinct(),
            wrapperRef,qualification.arrayAt("artifacts").map { it.jsonObject })
    }
}
