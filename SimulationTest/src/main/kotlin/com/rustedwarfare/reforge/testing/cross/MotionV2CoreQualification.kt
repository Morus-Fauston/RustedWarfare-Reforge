package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.*
import java.nio.file.Path
import java.util.UUID
import kotlin.math.ceil
import kotlinx.serialization.json.*

/** Explicit nonformal execution context. Cannot create or substitute a SourceAdmission.Admitted. */
internal object MotionV2CoreQualification {
    private fun checked(r:JsonObject):Path {
        val p=Path.of(r.text("path"))
        require(MotionV2CoreInputs.reference(p)==r) { "Qualification input changed: $p" }
        return p
    }
    class Context private constructor(val reference:JsonObject,val document:JsonObject,val inputObservation:JsonObject,
                                      val prepared:MotionV2CoreExecution.Prepared,val identities:List<JsonObject>,
                                      private val loaded:MotionV2CoreInputs.Loaded) {
        fun verifyStable() {
            for(r in identities)checked(r)
            val errors=loaded.verifyStable()
            require(errors.isEmpty()) { "Qualification inputs changed: ${errors.joinToString()}" }
        }
        companion object {
            internal fun load(reference:JsonObject):Context {
                val d=readObject(checked(reference))
                require(d.keys==setOf("kind","schemaVersion","purpose","formalCompatibility","caseId","runId", "inputRequest","inputManifest",
                    "start","heading","horizon","inputs"))
                d.header("motion-v2-core-qualification-context")
                require(d.text("purpose")=="execution-qualification-only" && d.required("formalCompatibility")==b(false))
                require(UUID.fromString(d.text("runId")).toString()==d.text("runId"))
                val request=d.objectAt("inputRequest");val manifest=d.objectAt("inputManifest")
                val q=readObject(checked(request));checked(manifest)
                val loaded=MotionV2CoreInputs.load(Path.of(manifest.text("path")),Path.of(request.text("path")))
                require(loaded.evidence.required("caseId")==d.required("caseId") && loaded.evidence.required("runId")==d.required("runId"))
                val start=d.objectAt("start");require(start.keys==setOf("x","y"));val initial=position(start)
                val heading=d.number("heading");val horizon=d.integer("horizon");require(horizon in 18L..604L)
                val commands=d.arrayAt("inputs").mapIndexed { i,value ->
                    val row=value.jsonObject;require(row.keys==setOf("actorPlayerId","unitId","original"))
                    val actor=row.id("actorPlayerId");val unit=row.id("unitId");val original=row.objectAt("original")
                    require(original.integer("sequence")==i.toLong())
                    val originalQ=original.number("q");val entry=ceil(originalQ)
                    require(originalQ>=0 && entry<=horizon.toDouble())
                    val kind=original.text("action");require(kind in setOf("move","stop"))
                    val target=if(kind=="move")position(original.objectAt("target"))else {
                        require(original.required("target")==JsonNull);null }
                    MotionV2CoreExecution.Scheduled(i,entry.toInt(),original) { world ->
                        if(target!=null)MoveAction("qual.$i",actor,unit,target,world)else StopAction("qual.$i",actor,unit,world) }
                }
                val scenario=Scenario("eng017.execution-qualification.${d.text("caseId")}.${d.text("runId")}",loaded.map,
                    mapOf("tank" to loaded.unit),listOf(Player("p1",PlayerControl.LOCAL)),listOf(UnitSpawn("u1","p1","tank",initial,heading)),
                    CoordinateModel("p001.coordinate.v1","2d","x-right","y-down","top-left","world-unit","double","ieee754-binary64"),
                    TimeModel("p001.time.v1","sim-tick","ieee754-binary64","fixed-explicit-delta"),MovementContract(16.0,4.0))
                val refs=(listOf(reference,request,manifest)+listOf("preparedInputs","catalog","original").map { q.objectAt(it) }+
                    loaded.evidence.arrayAt("files").map { it.jsonObject }).distinct()
                return Context(reference,d,loaded.evidence,MotionV2CoreExecution.Prepared(scenario,horizon.toInt(),commands),refs,loaded)
                    .also { it.verifyStable() }
            }
        }
    }
    fun load(reference:JsonObject):Context=Context.load(reference)
}
