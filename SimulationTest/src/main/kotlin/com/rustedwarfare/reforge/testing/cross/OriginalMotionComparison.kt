package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.math.*

/** O.v1 is separate from self and exact tool cross. No configurable default tolerances. */
object OriginalMotionComparison {
    const val STANDARD_SHA256 = "68ca40e6557a4056c03f8ad0f1fee03bd7064fcf9c79f071c43a5259067e0cca"
    internal const val STANDARD_ID = "p002.original-motion.O.v1"

    fun compare(contractPath: Path, actualPath: Path, expectedPath: Path): Judgment {
        val refs = mutableListOf<JsonElement>()
        var runtimePair=false
        fun result(code: Int, category: String, reason: String? = null, first: JsonElement = JsonNull,
                   details: JsonElement = JsonNull): Judgment = Judgment(code, obj(
            "kind" to s("original-motion-comparison"), "schemaVersion" to n(1), "standardId" to s(STANDARD_ID),
            "result" to s(when(code) {0 -> if(runtimePair)"compatibility-passed"else"completed";2 -> "compatibility-failed";3 -> "evidence-insufficient";else -> "execution-failed"}),
            "exitCode" to n(code), "category" to s(category), "reason" to reason?.let(::s),
            "originalCompatibility" to b(runtimePair && code==0), "firstDivergence" to first,
            "evidenceRefs" to array(refs), "details" to details))
        return try {
            val contract = read(contractPath).also { refs += identity(contractPath) }
            contract.header("motion-cross-contract")
            only(contract, setOf("kind","schemaVersion","scope","contractId","caseId","standard","actual","expected","runtimeVerifier"))
            require(contract.text("scope") == "original-motion-v1")
            contract.id("contractId")
            val standardRef = contract.objectAt("standard")
            only(standardRef,setOf("path","sha256"))
            require(standardRef.text("sha256") == STANDARD_SHA256) { "Unapproved standard identity" }
            val standardPath = Path.of(standardRef.text("path"))
            require(sha256(standardPath) == STANDARD_SHA256) { "Standard bytes drift" }
            refs += identity(standardPath)
            val standard = read(standardPath)
            standard.header("original-motion-standard")
            require(standard.text("standardId") == STANDARD_ID && standard.objectAt("approval").text("status") == "approved")
            val normative = standard.arrayAt("normativeFrozenSources").map { it.jsonObject }
            for (source in normative) {
                val file = Path.of(source.text("path"))
                require(sha256(file) == source.text("sha256")) { "Frozen O source drift: $file" }
                refs += identity(file)
            }
            val cases = read(Path.of(normative.single { it.text("path").contains("scenarios") }.text("path")))
            val case = cases.arrayAt("cases").map { it.jsonObject }.singleOrNull { it.text("id") == contract.text("caseId") }
                ?: throw InputError("Case is outside O.v1")
            val core = evidence(actualPath, "core", contract.objectAt("actual"), refs)
            val original = evidence(expectedPath, "original", contract.objectAt("expected"), refs)
            runtimePair=MotionSources.runtimePair(core,original,actualPath,expectedPath,contract,refs)
            val common = cases.objectAt("common")
            val initial = case["start"] as? JsonArray ?: common.arrayAt("start")
            for (trace in listOf(core, original)) {
                val first = trace.points.first()
                if(first.x != initial[0].jsonPrimitive.double || first.y != initial[1].jsonPrimitive.double ||
                    first.heading != case.number("coreHeading"))throw MotionFailure(4,"wrong-initial-state","Declared initial state differs from approved scene")
                if (case.text("category") == "stationary" && trace.points.last().q < case.number("durationQ")) throw MotionFailure(3,"missing-duration","Stationary duration is incomplete")
                for (point in trace.points.filter { case.text("category") == "stationary" }) {
                    if (hypot(point.x-first.x,point.y-first.y) > .001 || point.target != JsonNull) {
                        return result(2,"stationary-drift", first = point.difference("stationary", first.raw, point.raw))
                    }
                }
            }
            val comparison = MotionRules.compare(case,core,original)
            result(comparison.code,if(runtimePair && comparison.code==0)"approved-O-runtime-compatible"else comparison.category,first=comparison.first,details=comparison.details)
        } catch (failure: MotionFailure) {
            result(failure.code,failure.category,failure.message)
        } catch (failure: InputError) {
            result(64,"input-error",failure.message)
        } catch (failure: IllegalArgumentException) {
            result(64,"input-error",failure.message)
        } catch (failure: java.io.IOException) {
            result(4,"io-failed",failure.message)
        }
    }

    private fun evidence(path: Path, role: String, binding: JsonObject, refs: MutableList<JsonElement>): MotionTrace {
        only(binding,setOf("sha256","runId"))
        if (sha256(path) != binding.text("sha256")) throw MotionFailure(4,"identity-mismatch","$role evidence bytes differ")
        refs += identity(path)
        val document = try { read(path) } catch (e: java.nio.charset.CharacterCodingException) { throw MotionFailure(3,"malformed-evidence",e.message.orEmpty()) }
            catch (e: IllegalArgumentException) { throw MotionFailure(3,"malformed-evidence",e.message.orEmpty()) }
        try {
            document.header("motion-evidence")
            if (document.integer("exitCode") != 0L) throw MotionFailure(4,"capture-failed","$role process/capture failed")
            if (document["complete"] != b(true)) throw MotionFailure(3,"incomplete-evidence","$role has no complete capture")
            if (document.text("role") != role || document.text("runId") != binding.text("runId")) {
                throw MotionFailure(4,"identity-mismatch","$role role/run mismatch")
            }
            val kind=document.text("sourceKind")
            require(kind=="tool-fixture" || role=="core" && kind=="core-runtime" || role=="original" && kind=="native-scene-runtime") { "Unsupported runtime role/source" }
            val rows = document.arrayAt("samples").map { it.jsonObject }
            if (rows.isEmpty() || document.integer("recordCount") != rows.size.toLong()) throw MotionFailure(3,"incomplete-evidence","Record count mismatch")
            val points = rows.mapIndexed { index,row ->
                if (row.integer("index") != index.toLong()) throw MotionFailure(3,"missing-step","$role sample index is not contiguous")
                val state = row.objectAt("state")
                if(state.text("id") != "u1" || state.text("owner") != "p1" || state.text("definition") != "tank")throw MotionFailure(4,"object-identity-mismatch","Object mapping mismatch")
                MotionPoint(index,row.number("q"),row.integer("phase"),row.integer("inputSequence"),
                    state.number("x"),state.number("y"),state.number("heading"),state.required("target"),row)
            }
            if (points.first().q != 0.0) throw MotionFailure(3,"missing-initial","Initial q must be zero")
            for ((a,z) in points.zipWithNext()) {
                if (z.q < a.q || z.q==a.q && kind=="tool-fixture" || kind!="native-scene-runtime" && z.q-a.q > if(role=="original")1.2 else 1.0) throw MotionFailure(3,"missing-step","$role invalid step interval")
            }
            val inputs = document.arrayAt("inputs").map { it.jsonObject }
            for ((index,input) in inputs.withIndex()) {
                if(input.integer("sequence") != index.toLong() || input.number("q") < 0 ||
                    input.text("action") !in setOf("move","stop")) throw MotionFailure(3,"invalid-input-evidence","Input sequence/action is invalid")
                if(index>0 && input.number("q") <= inputs[index-1].number("q")) throw MotionFailure(3,"invalid-input-evidence","Input times are not increasing")
                input.objectAt("raw")
            }
            return MotionTrace(role, points, document)
        } catch (failure: InputError) { throw MotionFailure(3,"missing-field",failure.message.orEmpty()) }
    }

    internal fun read(path: Path): JsonObject {
        val bytes = Files.readAllBytes(path)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return CrossComparison.strictObject(decoder.decode(ByteBuffer.wrap(bytes)).toString())
    }
    internal fun only(value: JsonObject, allowed: Set<String>) {
        require(value.keys.all { it in allowed }) { "Unsupported O policy/field: ${value.keys-allowed}" }
    }
    private fun identity(path: Path) = obj("path" to s(path.toAbsolutePath().normalize().toString()),"sha256" to s(sha256(path)))
}

internal data class MotionPoint(val index: Int,val q: Double,val phase: Long,val input: Long,
    val x: Double,val y: Double,val heading: Double,val target: JsonElement,val raw: JsonObject) {
    fun difference(field: String, expected: JsonElement, actual: JsonElement) = obj("field" to s(field),
        "q" to n(q),"sampleIndex" to n(index),"expected" to expected,"actual" to actual,"raw" to raw)
}
internal data class MotionTrace(val role: String,val points: List<MotionPoint>,val document: JsonObject)
internal class MotionFailure(val code: Int,val category: String,message: String): RuntimeException(message)
