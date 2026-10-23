package com.rustedwarfare.reforge.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.path.PathPlanner
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.sim.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.math.abs

/** Finite ART mechanism check. No Activity, simulation injection, or explicit file writes. */
class MotionMechanismInstrumentation : Instrumentation() {
    private val cases = JSONArray()
    private var rows = 0
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val output = JSONObject().put("kind", "eng017-art-motion-development-v1")
            .put("formalCompatibility", false).put("realPhone", false).put("cases", cases)
        var code = Activity.RESULT_CANCELED
        try {
            output.put("identity", identity())
            adaptive(.55, 1.5, .005)
            adaptive(2.2, 4.1, .25)
            for (speed in listOf(60.0, 145.0)) for (left in listOf(false, true))
                for (wall in listOf(false, true)) corridor(speed, left, wall)
            steeringAndStop()
            capturedKnowledgeRoutes()
            output.put("success", true)
            code = Activity.RESULT_OK
        } catch (failure: Throwable) {
            output.put("success", false).put("error", failure.stackTraceToString().take(12000))
        }
        var encoded = output.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > 128 * 1024) {
            code = Activity.RESULT_CANCELED
            encoded = JSONObject().put("kind", "eng017-art-motion-development-v1")
                .put("success", false).put("error", "Finite output byte budget exceeded").toString()
        }
        finish(code, Bundle().apply { putString("motionResult", encoded) })
    }

    private fun identity(): JSONObject {
        val targetLoader = targetContext.classLoader
        val classes = listOf(SimulationContext::class.java, MovementProfile::class.java, MotionStepSample::class.java,
            PathPlanner::class.java, PlanningTerrain::class.java, Position::class.java)
        val loaded = JSONArray()
        for (type in classes) {
            check(Class.forName(type.name, false, targetLoader) === type) { "Not target MAIN class: ${type.name}" }
            check(type.classLoader === AndroidLauncher::class.java.classLoader) { "Different MAIN classloader" }
            loaded.put(JSONObject().put("name", type.name).put("loader", checkNotNull(type.classLoader).toString()))
        }
        fun apk(file: File): JSONObject {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val block = ByteArray(65536)
                while (true) { val size = stream.read(block); if (size < 0) break; digest.update(block, 0, size) }
            }
            return JSONObject().put("path", file.absolutePath).put("bytes", file.length())
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
        }
        return JSONObject().put("classes", loaded).put("main", apk(File(targetContext.applicationInfo.sourceDir)))
            .put("test", apk(File(context.applicationInfo.sourceDir)))
            .put("pid", android.os.Process.myPid()).put("uid", android.os.Process.myUid())
            .put("sdk", android.os.Build.VERSION.SDK_INT)
    }

    private fun open(speed: Double, turn: Double, acceleration: Double, walls: Set<Pair<Int, Int>> = emptySet(),
                     heading: Double = 90.0, deceleration: Double = .02): SimulationContext {
        val movement = MovementProfile(MovementType.LAND, speed, if (speed > 10) 1.0 else .05,
            deceleration, turn, acceleration, false, false)
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 11.0, 11.0, 1,
            Paths.get("unused-test-image"), 1.0, movement, emptySet())
        val scenario = Scenario("art-development", MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600),
            BlockingGrid(30, 20, BooleanArray(600) { it % 30 to it / 30 in walls })),
            mapOf("tank" to unit), listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(if (speed > 10) 300.0 else 50.0,
                if (speed > 10) 200.0 else 70.0), heading)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
        val initialized = SimulationContext.initialize(scenario)
        check(initialized is InitResult.Accepted) { initialized.toString() }
        return initialized.context
    }

    private fun row(q: Long, unit: UnitObservation, trace: MotionStepSample? = null, events: List<Event> = emptyList()): JSONObject {
        check(++rows <= 128) { "Finite row budget exceeded" }
        val result = JSONObject().put("step", q).put("x", unit.position.x).put("y", unit.position.y)
            .put("heading", unit.heading).put("speed", unit.speed).put("cg", unit.angularVelocity)
            .put("target", unit.moveTarget?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL)
            .put("path", JSONArray(unit.pathPoints.map { JSONArray(listOf(it.x, it.y)) }))
            .put("state", unit.movementState.name)
            .put("events", JSONArray(events.map { JSONObject().put("kind", it.kind).put("unitId", it.unitId ?: JSONObject.NULL) }))
        if (trace != null) result.put("terrain", trace.terrainDisposition.name)
            .put("candidate", JSONArray(listOf(trace.candidate.x, trace.candidate.y)))
            .put("aim", trace.steeringAim?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL)
            .put("normalCompletion", trace.normalCompletion)
            .put("originMoveId", trace.originMoveId ?: JSONObject.NULL)
            .put("positionBefore", JSONArray(listOf(trace.positionBefore.x, trace.positionBefore.y)))
            .put("speedBefore", trace.speedBefore).put("headingBefore", trace.headingBefore).put("cgBefore", trace.angularVelocityBefore)
            .put("targetBefore", trace.commandTargetBefore?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL)
            .put("terrainResolved", trace.terrainResolved?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL)
            .put("correctionBefore", trace.correctionTimeBefore).put("correctionAfter", trace.correctionTimeAfter)
        return result
    }

    private fun finite(unit: UnitObservation) {
        check(listOf(unit.position.x, unit.position.y, unit.heading, unit.speed, unit.angularVelocity).all { it.isFinite() })
        check(unit.position.x in 11.0..589.0 && unit.position.y in 11.0..389.0)
    }

    private fun adaptive(speed: Double, turn: Double, acceleration: Double) {
        val result = JSONObject().put("case", "adaptive-$speed-$turn-$acceleration")
        val saved = JSONArray(); result.put("samples", saved); cases.put(result)
        val simulation = open(speed, turn, acceleration)
        try {
            simulation.enableMotionDiagnostics(simulation.world)
            simulation.submit(MoveAction("go", "p1", "u1", Position(450.0, 250.0), simulation.world))
            var previous = simulation.observe().units.single()
            var normal: Long? = null; var settle: Long? = null; var stable = 0; var advances = 0
            var previousAim: Position? = null; var changedAim = false
            var stableAnchor: UnitObservation? = null
            for (q in 0L until 2500L) {
                val observation = simulation.advance(Step(q, 1.0, "sim-tick"))
                advances++; val now = observation.units.single(); finite(now)
                val trace = simulation.drainMotionDiagnostics(simulation.world).single()
                val aim = trace.steeringAim
                var saveAim = false
                if (q == 0L) {
                    val distance = if (speed == .55) 165.0 else 72.16
                    val expected = Position(50.0 + 400.0 * distance / kotlin.math.hypot(400.0, 180.0),
                        70.0 + 180.0 * distance / kotlin.math.hypot(400.0, 180.0))
                    check(aim != null && abs(aim.x - expected.x) < 1e-10 && abs(aim.y - expected.y) < 1e-10)
                }
                if (aim != null && previousAim != null && aim != previousAim && !changedAim) {
                    val expected = if (speed == .55) Position(450.0, 250.0) else Position(
                        50.0 + 400.0 * 108.24 / kotlin.math.hypot(400.0, 180.0),
                        70.0 + 180.0 * 108.24 / kotlin.math.hypot(400.0, 180.0))
                    check(abs(aim.x - expected.x) < 1e-10 && abs(aim.y - expected.y) < 1e-10)
                    changedAim = true; saveAim = true
                    result.put("aimBeforeFirstChange", JSONArray(listOf(previousAim.x, previousAim.y)))
                }
                if (aim != null) previousAim = aim
                check(observation.events.none { it.kind in setOf("path-unreachable", "movement-blocked", "movement-boundary-blocked") })
                val arrived = observation.events.count { it.kind == "movement-arrived" }
                check(trace.normalCompletion == (arrived == 1))
                check(arrived <= 1 && (arrived == 0 || normal == null))
                if (arrived == 1) normal = q
                if (normal != null) {
                    check(now.moveTarget == null && now.pathPoints.isEmpty())
                    check(now.heading == previous.heading && now.angularVelocity == previous.angularVelocity)
                    check(abs(now.speed - maxOf(0.0, previous.speed - .02 * speed)) <= 1e-12)
                    stable = if (now.speed == 0.0 && now.position == previous.position) stable + 1 else 0
                    if (now.speed == 0.0) {
                        if (settle == null) { settle = q; stableAnchor = now }
                        check(now.movementState == MovementState.IDLE && now == stableAnchor)
                    }
                }
                if (q == 0L || arrived == 1 || saveAim || now.speed == 0.0 && normal != null) saved.put(row(q, now, trace, observation.events))
                previous = now
                if (stable >= 19) break
            }
            check(normal != null && settle != null && stable >= 19) { "No unique normal completion and 18q tail within 2500 steps" }
            check(changedAim) { "No adaptive aim was consumed" }
            result.put("normalStep", normal).put("settleStep", settle).put("advances", advances)
                .put("stablePoints", stable).put("allStepsChecked", true).put("fullTrajectorySaved", false).put("success", true)
        } finally { simulation.close() }
    }

    private fun corridor(speed: Double, left: Boolean, wall: Boolean) {
        val walls = if (!wall) emptySet() else (9..11).map { (if (left) 13 else 17) to it }.toSet()
        val result = JSONObject().put("case", "corridor-$speed-$left-$wall")
        val saved = JSONArray(); result.put("samples", saved); cases.put(result)
        val measured = open(speed, 4.1, .25, walls, if (left) 270.0 else 90.0)
        val plain = open(speed, 4.1, .25, walls, if (left) 270.0 else 90.0)
        try {
            for (simulation in listOf(measured, plain)) {
                simulation.enableKnowledge(simulation.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
                simulation.submit(MoveAction("go", "p1", "u1", Position(if (left) 50.0 else 550.0, 200.0), simulation.world))
            }
            measured.enableMotionDiagnostics(measured.world)
            val known = measured.knowledgeSnapshot(measured.world, "p1").cells
            val path = measured.observe().units.single().pathPoints
            val a = measured.advance(Step(0, 1.0, "sim-tick")); val b = plain.advance(Step(0, 1.0, "sim-tick"))
            check(a.units == b.units && a.events == b.events && a.actionResults == b.actionResults)
            val unit = a.units.single(); finite(unit)
            if (wall) check(unit.position == Position(300.0, 200.0))
            else {
                check(abs(unit.position.x - (300.0 + if (left) -speed else speed)) < 1e-10)
                check(abs(unit.position.y - 200.0) < 1e-10)
            }
            check(unit.speed == speed && unit.moveTarget == Position(if (left) 50.0 else 550.0, 200.0) && unit.pathPoints == path)
            check(measured.knowledgeSnapshot(measured.world, "p1").cells == known)
            val trace = measured.drainMotionDiagnostics(measured.world).single()
            check(trace.finalPosition == unit.position && (wall || trace.candidate == unit.position))
            check((kotlin.math.floor(trace.candidate.x / 20.0).toInt() to
                kotlin.math.floor(trace.candidate.y / 20.0).toInt()) !in walls) { "Candidate itself is in a wall" }
            check(trace.terrainDisposition == if (wall) MotionTerrainDisposition.CORRECTED else MotionTerrainDisposition.DIRECT)
            check(a.events.map { it.kind } == if (wall) listOf("movement-started", "movement-blocked") else listOf("movement-started"))
            saved.put(row(0, unit, trace, a.events)); result.put("success", true)
        } finally { measured.close(); plain.close() }
    }

    private fun steeringAndStop() {
        val result = JSONObject().put("case", "guard-nonzero-steering-stop")
        val saved = JSONArray(); result.put("samples", saved); cases.put(result)
        val simulation = open(60.0, 4.1, .25, (9..11).map { 17 to it }.toSet(), 89.5, .5)
        try {
            simulation.enableKnowledge(simulation.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            simulation.enableMotionDiagnostics(simulation.world)
            val known = simulation.knowledgeSnapshot(simulation.world, "p1").cells
            simulation.submit(MoveAction("go", "p1", "u1", Position(550.0, 200.0), simulation.world))
            val hitObservation = simulation.advance(Step(0, 1.0, "sim-tick"))
            val hit = hitObservation.units.single()
            check(hit.position == Position(300.0, 200.0) && hit.speed == 60.0 && hit.heading == 89.75 && hit.angularVelocity == .25)
            val first = simulation.drainMotionDiagnostics(simulation.world).single()
            check(first.terrainDisposition == MotionTerrainDisposition.CORRECTED && first.correctionTimeAfter == 1.0)
            saved.put(row(0, hit, first, hitObservation.events))
            simulation.submit(StopAction("stop", "p1", "u1", simulation.world))
            for (q in 1L..3L) {
                val observation = simulation.advance(Step(q, 1.0, "sim-tick"))
                val now = observation.units.single(); finite(now)
                check(now.moveTarget == null && now.pathPoints.isEmpty())
                check(now.heading == hit.heading && now.angularVelocity == hit.angularVelocity && now.speed == if (q == 1L) 30.0 else 0.0)
                check(simulation.knowledgeSnapshot(simulation.world, "p1").cells == known)
                val trace = simulation.drainMotionDiagnostics(simulation.world).single()
                check(trace.terrainDisposition == MotionTerrainDisposition.DIRECT && trace.correctionTimeAfter == if (q < 3L) 1.0 else 0.0)
                check(trace.candidate == trace.finalPosition)
                saved.put(row(q, now, trace, observation.events))
            }
            result.put("success", true)
        } finally { simulation.close() }
    }

    private fun capturedKnowledgeRoutes() {
        val result = JSONObject().put("case", "captured-knowledge-reachability")
        val checked = JSONArray(); result.put("checks", checked); cases.put(result)
        val known = setOf(44,74,104,110,111,112,113,114,134,140,144,164,170,174,194,200,204,224,230,231,254,374)
        val minimal = setOf(113,140,174,204,231)
        val start = Position(526.1723287351451,86.49821124585615)
        val targets = listOf(
            "10308" to Position(437.0927429199219,101.6015625),
            "10337" to Position(452.9504089355469,115.02386474609375),
            "10362" to Position(446.851318359375,91.22979736328125),
            "10528" to Position(453.5603332519531,108.92279052734375),
            "10563" to Position(453.5603332519531,108.92279052734375),
            "10582" to Position(453.5603332519531,108.92279052734375),
            "minimal-five" to Position(453.5603332519531,108.92279052734375),
        )
        for ((sequence,target) in targets) {
            val blocked = if (sequence == "minimal-five") minimal else known
            val terrain = PlanningTerrain(30,20,20,20,BooleanArray(600) { it in blocked })
            val item = JSONObject().put("input", sequence).put("blockedIndices", JSONArray(blocked.sorted()))
                .put("widthTiles",30).put("heightTiles",20).put("tileWidth",20).put("tileHeight",20)
                .put("radius",11.0).put("start",JSONArray(listOf(start.x,start.y)))
                .put("target",JSONArray(listOf(target.x,target.y)))
            checked.put(item)
            val route = checkNotNull(PathPlanner.findPath(terrain,start,target,11.0)) { "Captured $sequence rejected" }
            item.put("route",JSONArray(route.map { JSONArray(listOf(it.x,it.y)) }))
            check(route.isNotEmpty() && route.last() == target) { "Captured $sequence target changed" }
            var previous = start
            for (point in route) {
                // Public direct-path branch verifies each emitted leg; retained
                // coordinates also support independent host geometry checking.
                check(PathPlanner.findPath(terrain,previous,point,11.0) == listOf(point)) { "Captured $sequence has a non-direct leg" }
                previous = point
            }
            item.put("allLegsDirect",true).put("success",true)
        }
        check(checked.length() == 7)
        result.put("success",true)
    }
}
