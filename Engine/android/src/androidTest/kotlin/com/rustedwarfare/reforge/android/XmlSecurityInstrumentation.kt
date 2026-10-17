package com.rustedwarfare.reforge.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.os.SystemClock
import com.rustedwarfare.reforge.core.content.*
import java.io.File

/** Device-side checks through the same public loader used by the shared client. */
class XmlSecurityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            val fixtures = File(targetContext.cacheDir, "xml-security-fixtures")
            SandboxAssets.copy(targetContext.assets, fixtures)
            val root = File(fixtures, "TestLab/local/P001-移动沙盒输入")
            check(ContentLoader.loadMap(File(root, "maps/p001-controlled.tmx").toPath()) is ContentResult.Success)
            check(ContentLoader.loadUnitVariant(File(root, "units/weaponless_tank.ini").toPath()) is ContentResult.Success)
            var count = 2
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
            val activity = startActivitySync(Intent(targetContext, AndroidLauncher::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun await(condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (condition()) return
                    SystemClock.sleep(50)
                }
                error("Activity recreation evidence timed out")
            }
            fun trace(directory: File) = File(directory, "client-diagnostic.jsonl").let { if (it.exists()) it.readText() else "" }
            await { runs.listFiles().orEmpty().any { it !in before && trace(it).contains("\"load-ready\"") } }
            val original = runs.listFiles().orEmpty().single { it !in before }
            val monitor = addMonitor(AndroidLauncher::class.java.name, null, false)
            runOnMainSync { activity.recreate() }
            val replacement = checkNotNull(waitForMonitorWithTimeout(monitor, 15000))
            removeMonitor(monitor)
            await { trace(original).contains("\"evidence-completed\"") }
            val closed = trace(original)
            check(closed.contains("\"presentation-released\"") && closed.contains("\"closed\"") &&
                closed.contains("\"application-disposed\"") && closed.contains("\"changingConfigurations\":true"))
            await { runs.listFiles().orEmpty().any { it !in before && it != original && trace(it).contains("\"load-ready\"") } }
            runOnMainSync { replacement.finish() }
            val recreated = runs.listFiles().orEmpty().single { it !in before && it != original }
            await { trace(recreated).contains("\"evidence-completed\"") }
            result.putString("result", "PASS: $count checks; legal TMX/TSX/INI and UTF-8/UTF-16 DTD/entity rejection; PASS: Activity.recreate closes old host and creates new match")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("result", "FAIL: $failure")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
