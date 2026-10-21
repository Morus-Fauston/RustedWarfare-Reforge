package com.rustedwarfare.reforge.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import com.rustedwarfare.reforge.client.ControlledScenario
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.Position
import org.json.JSONObject
import java.io.File

/** Device-side checks through the same public loader used by the shared client. */
class XmlSecurityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            val fixtures = File(targetContext.cacheDir.canonicalFile, "xml-security-fixtures")
            SandboxAssets.copy(targetContext.assets, fixtures)
            val root = File(fixtures, "TestLab/local/P001-移动沙盒输入")
            check(ContentLoader.loadMap(File(root, "maps/p001-controlled.tmx").toPath()) is ContentResult.Success)
            check(ContentLoader.loadUnitVariant(File(root, "units/weaponless_tank.ini").toPath()) is ContentResult.Success)
            var count = 2
            val knowledge = ControlledScenario.load(File(fixtures, "TestLab/local/P002-知识沙盒输入").toPath())
            check(knowledge.scenarioId == "p002-knowledge" && knowledge.players.map { it.playerId } == listOf("p1", "p2", "p3") &&
                knowledge.players.map { it.teamId } == listOf("blue", "blue", "red") &&
                knowledge.unitDefinitions.getValue("tank").fogOfWarSightRange == 3 &&
                knowledge.units.map { it.position } == listOf(Position(110.0, 110.0), Position(510.0, 310.0), Position(550.0, 310.0)))
            count++
            val documents = listOf(
                "<!DOCTYPE map [<!ENTITY x 'expanded'>]><map>&x;</map>",
                "<!DOCTYPE map SYSTEM 'file:///must-not-be-read'><map/>",
                "<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///must-not-be-read'>]><map>&x;</map>",
            )
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16)) for (document in documents) {
                val file = File(targetContext.cacheDir, "p001-xml-security.tmx")
                try {
                    file.writeBytes(document.toByteArray(charset))
                    val rejected = ContentLoader.loadMap(file.toPath())
                    check(rejected is ContentResult.Failure && rejected.error.code == ContentErrorCode.MALFORMED_SYNTAX &&
                        rejected.error.reason.contains("DOCTYPE")) { rejected.toString() }
                    count++
                } finally { check(file.delete()) }
            }
            val runs = File(targetContext.getExternalFilesDir(null) ?: targetContext.filesDir, "runs")
            val before = runs.listFiles()?.toSet().orEmpty()
            fun await(condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (condition()) return
                    SystemClock.sleep(50)
                }
                error("Activity recreation evidence timed out")
            }
            fun trace(directory: File) = File(directory, "client-diagnostic.jsonl").let { if (it.exists()) it.readText() else "" }
            fun records(directory: File): List<JSONObject> {
                val text = trace(directory)
                return text.substring(0, text.lastIndexOf('\n') + 1).lineSequence().filter { it.isNotBlank() }
                    .map { JSONObject(it) }.toList()
            }
            fun startFromSetup(activity: Activity, directory: File) {
                await { records(directory).any { it.getString("kind") == "resize" } }
                val setup = records(directory)
                check(setup.none { it.getString("kind") in listOf("load-request", "load-ready", "observation") }) {
                    "A new Activity must wait for Start"
                }
                val size = setup.last { it.getString("kind") == "resize" }
                val origin = IntArray(2)
                runOnMainSync {
                    val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    check(view.width == size.getInt("width") && view.height == size.getInt("height"))
                    view.getLocationOnScreen(origin)
                }
                val x = origin[0] + size.getInt("screenX") + 790f / 960f * size.getInt("screenWidth")
                val y = origin[1] + size.getInt("height") - (size.getInt("screenY") + 80f / 640f * size.getInt("screenHeight"))
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { sendPointerSync(event) } finally { event.recycle() }
                }
                await { records(directory).any { it.getString("kind") == "load-ready" } }
                check(records(directory).any { it.getString("kind") == "load-request" &&
                    it.getString("mode") == "NO_FOG" && !it.getBoolean("shareAlliedVision") })
                check(records(directory).any { it.getString("kind") == "observation" && it.getString("phase") == "initial" &&
                    it.getJSONObject("sample").getString("scenarioId") == "p002-knowledge" &&
                    it.getJSONObject("sample").getJSONArray("units").length() == 3 })
            }
            val activities = mutableListOf<Activity>()
            try {
                val activity = startActivitySync(Intent(targetContext, AndroidLauncher::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    .also { activities += it }
                await { runs.listFiles().orEmpty().any { it !in before && trace(it).contains("\"resize\"") } }
                val original = runs.listFiles().orEmpty().single { it !in before }
                startFromSetup(activity, original)
                val monitor = addMonitor(AndroidLauncher::class.java.name, null, false)
                val replacement = try {
                    runOnMainSync { activity.recreate() }
                    checkNotNull(waitForMonitorWithTimeout(monitor, 15000)).also { activities += it }
                } finally { removeMonitor(monitor) }
                await { trace(original).contains("\"evidence-completed\"") }
                val closed = trace(original)
                check(closed.contains("\"presentation-released\"") && closed.contains("\"closed\"") &&
                    closed.contains("\"application-disposed\"") && closed.contains("\"changingConfigurations\":true"))
                await { runs.listFiles().orEmpty().any { it !in before && it != original && trace(it).contains("\"resize\"") } }
                val recreated = runs.listFiles().orEmpty().single { it !in before && it != original }
                startFromSetup(replacement, recreated)
                runOnMainSync { replacement.finish() }
                await { trace(recreated).contains("\"evidence-completed\"") }
            } finally { runOnMainSync { activities.filterNot { it.isDestroyed }.forEach { it.finish() } } }
            result.putString("result", "PASS: $count content checks; legal P001 XML, P002 teams/sight/spawns, UTF-8/UTF-16 DTD/entity rejection; PASS: real Start touch and Activity.recreate returning to setup before a new P002 match")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("result", "FAIL: $failure")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
