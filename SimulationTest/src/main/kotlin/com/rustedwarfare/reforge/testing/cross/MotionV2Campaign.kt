package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Predeclared campaign format only. Runtime freshness and source qualification
 * are separate obligations, and loading never produces a compatibility result. */
internal object MotionV2Campaign {
    data class Plan(val document:JsonObject,val identity:JsonObject,val runs:List<JsonObject>,val sources:JsonObject,val identities:List<JsonObject>)
    fun load(path:Path,expectedSha256:String,policy:MotionV2Standard.Policy):Plan {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val file=path.toAbsolutePath().normalize();val consumed=mutableListOf<JsonObject>()
        fun keys(o:JsonObject,expected:Set<String>) { require(o.keys==expected) { "Unexpected campaign fields" } }
        fun bound(r:JsonObject,forbidden:Set<Path> = emptySet()):Path {
            keys(r,setOf("path","bytes","sha256"));val p=Path.of(r.text("path"))
            require(p.isAbsolute && p==p.normalize() && p !in forbidden)
            require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && r.integer("bytes")>=0 && Files.size(p)==r.integer("bytes") &&
                r.text("sha256").matches(Regex("[0-9a-f]{64}")) && sha256(p)==r.text("sha256")) { "Campaign source identity differs: $p" }
            consumed+=r;return p
        }
        require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && sha256(file)==expectedSha256)
        val identity=obj("path" to s(file.toString()),"bytes" to n(Files.size(file)),"sha256" to s(expectedSha256))
        val document=OriginalMotionComparison.read(bound(identity))
        keys(document,setOf("kind","schemaVersion","campaignId","createdUtc","standard","sources","runs","frozenOriginals","coreMotionMechanisms"))
        require(document.text("kind")=="motion-v2-final-campaign" && document.integer("schemaVersion")==3L && policy.standardId=="p002.original-motion.O.v2")
        fun uuid(value:String) { require(UUID.fromString(value).toString()==value) }
        uuid(document.text("campaignId"));val created=document.text("createdUtc");require(created.endsWith("Z"));Instant.parse(created)
        require(document.required("standard")==policy.wrapperRef)
        val standardPath=bound(policy.wrapperRef,setOf(file))
        for(r in policy.identities)bound(r,setOf(file)) // Do not keep using a Policy whose loaded files have drifted.
        val sources=document.objectAt("sources")
        keys(sources,setOf("nativeValidator","python","comparisonRuntime","coreRuntime","coreSources","coreBuildReceipt","nonAuthorReview"))
        for(name in listOf("nativeValidator","python","comparisonRuntime"))require(sources.objectAt(name) in policy.qualificationArtifacts) { "Tool absent from approved qualification artifacts: $name" }
        val sourcePaths=sources.mapValues { (_,value)->bound(value.jsonObject,setOf(file,standardPath)) }
        val review=OriginalMotionComparison.read(sourcePaths.getValue("nonAuthorReview"))
        keys(review,setOf("kind","schemaVersion","status","reviewer","author","coreRuntime","coreSources","coreBuildReceipt"))
        require(review.text("kind")=="motion-v2-core-snapshot-review" && review.integer("schemaVersion")==1L && review.text("status")=="accepted")
        require(review.text("reviewer").isNotBlank() && review.text("author").isNotBlank() && review.text("reviewer")!=review.text("author"))
        for(name in listOf("coreRuntime","coreSources","coreBuildReceipt"))require(review.required(name)==sources.required(name)) { "Review belongs to a different core snapshot: $name" }
        val cases=policy.scenarios.arrayAt("cases").map { it.jsonObject.text("id") }
        require(cases.size==12 && cases.toSet()==setOf("OM00","OM01E","OM01W","OM02S","OM02N","OM03SE","OM03NW","OM04","OM05","OM06","OM07","OM08"))
        val slots=cases.flatMap { id->(1..3).map { id to it.toLong() } }
        val runs=document.arrayAt("runs").map { it.jsonObject };require(runs.size==36)
        for((i,run)in runs.withIndex()) {
            keys(run,setOf("caseId","repeat","originalRunId","coreRunId"))
            require((run.text("caseId") to run.integer("repeat"))==slots[i])
            uuid(run.text("originalRunId"));uuid(run.text("coreRunId"))
        }
        val ids=runs.flatMap { listOf(it.text("originalRunId"),it.text("coreRunId")) };require(ids.distinct().size==72)
        bound(document.objectAt("frozenOriginals"),setOf(file,standardPath))
        bound(document.objectAt("coreMotionMechanisms"),setOf(file,standardPath))
        val originals=MotionV2FrozenOriginals.load(document.objectAt("frozenOriginals"),runs)
        val mechanisms=MotionV2CoreMechanisms.load(document.objectAt("coreMotionMechanisms"),sources)
        for(r in originals.identities+mechanisms.identities)bound(r,setOf(file,standardPath))
        // Recheck every consumed file, including the plan, after all parsing.
        for(r in consumed.toList()) {
            val p=Path.of(r.text("path"));require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && Files.size(p)==r.integer("bytes") && sha256(p)==r.text("sha256")) { "Campaign input drift during load" }
        }
        return Plan(document,identity,runs,sources,consumed.distinct())
    }
}
