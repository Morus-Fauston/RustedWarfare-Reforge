package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*

/** Strict historical diagnostic protocol; no raw/runtime qualification is granted. */
internal object MotionV2DiagnosticAdapter {
    enum class Cause { NORMAL, EXTERNAL_STOP, UNREACHABLE, LOCAL_PATH_END, UNKNOWN }
    data class Clear(val cause: Cause, val input: Long, val event: MotionV2Events.Event)
    data class InputConsumption(val sequence: Long, val action: String, val quantizationEntryQ: Double,
                                val event: MotionV2Events.Event, val raw: JsonObject)
    data class Facts(val points: List<MotionV2Sampling.Point>, val calls: List<MotionV2Events.Call>,
                     val clears: List<Clear>, val inputs: List<Double>, val consumptions: List<InputConsumption>)
    fun core(document: JsonObject): Facts {
        document.header("motion-diagnostic-evidence")
        require(document.text("role")=="core" && document.text("sourceKind")=="core-diagnostic-runtime" &&
            document.required("formalCompatibility")==b(false) && document.integer("motionInputSchemaVersion")==1L)
        return MotionV2RecordNormalizer.core(document,"diag.")
    }
    fun original(document: JsonObject): Facts {
        document.header("motion-evidence")
        val localSource=document.text("sourceKind")=="native-local-clear-diagnostic-scene-runtime"
        require(document.text("role")=="original" && (localSource || document.text("sourceKind")=="native-diagnostic-scene-runtime"))
        return MotionV2RecordNormalizer.original(document,if(localSource)2 else 1)
    }
}
