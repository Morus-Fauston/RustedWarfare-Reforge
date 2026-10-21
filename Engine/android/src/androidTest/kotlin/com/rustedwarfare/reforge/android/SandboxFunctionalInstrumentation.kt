package com.rustedwarfare.reforge.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.badlogic.gdx.utils.JsonValue
import com.rustedwarfare.reforge.client.MapProjection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.util.Base64
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** One fixed exercise; recreate alone owns two Activity/run pairs. No simulation injection. */
class SandboxFunctionalInstrumentation : Instrumentation() {
    private var arguments: Bundle? = null
    private data class TouchGeometry(val x: Float, val y: Float, val width: Int, val height: Int,
        val windowWidth: Int, val windowHeight: Int, val originX: Int, val originY: Int)
    private class ForeignExpectation(val generation: Long, val initial: JsonValue, val id: String, var cursor: Int)
    private class Creations : android.app.Application.ActivityLifecycleCallbacks {
        val activities = CopyOnWriteArrayList<Activity>()
        val closing = AtomicBoolean()
        val finishFailure = AtomicReference<Throwable?>()
        private val main = Handler(Looper.getMainLooper())
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is AndroidLauncher) {
                activities.add(activity)
                // Defer until onCreate returns, even if attachment/identity validation fails.
                if (closing.get() && !main.post {
                    try { if (!activity.isDestroyed && !activity.isFinishing) activity.finish() }
                    catch (failure: Throwable) { finishFailure.compareAndSet(null, failure) }
                }) finishFailure.compareAndSet(null, IllegalStateException("Cannot schedule late Activity finish"))
            }
        }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments
        start()
    }

    private fun onMain(operation: () -> Unit) {
        SandboxUiCall.run({ body -> runOnMainSync(body) },{ operation() })
    }

    override fun onStart() {
        val result = Bundle()
        val errors = SandboxFunctionalOracle.Failures()
        val actions = JSONArray()
        var stream = SandboxFunctionalOracle.Stream()
        var activity: Activity? = null
        var monitor: Instrumentation.ActivityMonitor? = null
        var tail: Tail? = null
        var runName: String? = null
        var starts = 0
        var closed = false
        var generation = 0L
        var businessStart = 0L
        var retention: SandboxFunctionalOracle.Retention? = null
        var retentionCursor = 0
        val foreignExpectations = mutableListOf<ForeignExpectation>()
        var unreachable: SandboxFunctionalOracle.Unreachable? = null
        var unreachableCursor = 0
        var explore: SandboxExploreOracle? = null
        var exploreCursor = 0
        var visibility: SandboxVisibilityOracle? = null
        var visibilityCursor = 0
        var imageProbe: SandboxImageProbe? = null
        val images = JSONArray()
        var imageTotalBytes = 0
        var recoveryFixture: SandboxRecoveryFixture? = null
        var recoveryFirstGeneration = 0L
        var recoveryReady = false
        var pauseProbe: SandboxResumeProbe? = null
        var pauseFile: File? = null
        var pauseTarget: JsonValue? = null
        var pauseBusinessReady = false
        var finishSequence = -1L
        var creations: Creations? = null
        var observedApplication: android.app.Application? = null
        var pauseRuns: File? = null
        var pauseRunNames: Set<String>? = null
        var recreate = false
        var recreateRequested = false
        var recreateReady = false
        var priorStream: SandboxFunctionalOracle.Stream? = null
        var priorTail: Tail? = null
        var priorName: String? = null
        var priorFile: File? = null
        var priorGeneration = 0L
        var recreateTarget: JsonValue? = null
        var recreateInitial: JsonValue? = null
        var recreateBoundary = -1L
        var recreateRequestTime = 0L
        var learnedCell = -1
        var priorHash: String? = null
        var newStartBoundary = -1L
        var newStopBoundary = -1L
        var newFinishBoundary = -1L
        var captureNewStart = false
        var runsRoot: File? = null
        var runsBefore = emptySet<String>()
        var recreateMode: String? = null
        val closingFailures = mutableSetOf<String>()
        var repeatedClosingFailures = 0

        fun closingFailure(failure: Throwable) {
            if (!recreate || closingFailures.add(failure.stackTraceToString())) errors.cleanup(failure)
            else repeatedClosingFailures++
        }
        fun finishObserved() {
            val owned = if (recreate) (creations?.activities?.toList().orEmpty() + listOfNotNull(activity, monitor?.lastActivity)).distinct()
                else listOfNotNull(activity ?: monitor?.lastActivity)
            val failures = mutableListOf<Throwable>()
            onMain { owned.forEach {
                try { if (!it.isDestroyed && !it.isFinishing) it.finish() }
                catch (failure: Throwable) { failures += failure }
            } }
            failures.forEach(::closingFailure)
            creations?.finishFailure?.get()?.let(::closingFailure)
        }

        fun totalBytes() = stream.bytes + (priorStream?.takeIf { it !== stream }?.bytes ?: 0L)
        fun totalSteps() = stream.steps + (priorStream?.takeIf { it !== stream }?.steps ?: 0)
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun maybeAttachReplacement() {
            if (!recreateRequested) return
            val created = checkNotNull(creations).activities
            check(created.size in 1..2) { "Unexpected third Activity" }
            if (created.size == 2) {
                check(created[0] !== created[1])
                activity = created[1]
            }
            val root = checkNotNull(runsRoot)
            val added = root.listFiles().orEmpty().filter { it.name !in runsBefore && it.name != priorName }
            check(added.size <= 1) { "Unexpected third run" }
            if (added.size == 1 && stream === priorStream) {
                val directory = added.single()
                check(created.size == 2 && directory.isDirectory && directory.canonicalFile.parentFile == root.canonicalFile &&
                    directory.name.matches(Regex("run-[0-9]+-[0-9a-f]{8}")))
                stream = SandboxFunctionalOracle.Stream()
                runName = directory.name
                tail = Tail(File(directory, "client-diagnostic.jsonl"), stream)
                generation = 0L
            }
        }
        fun aggregate(closing: Boolean) {
            SandboxRecreateOracle.combined(priorStream?.takeIf { it !== stream }?.bytes ?: 0L,
                stream.bytes, priorStream?.takeIf { it !== stream }?.steps ?: 0, stream.steps, closing)
        }

        fun completePrefix(file: File): ByteArray {
            RandomAccessFile(file, "r").use { reader ->
                val length = reader.length()
                check(length in 1..16L * 1024 * 1024) { "Invalid finite pause log size" }
                val bytes = ByteArray(length.toInt())
                reader.readFully(bytes)
                val end = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
                check(end > 0)
                return bytes.copyOf(end)
            }
        }
        fun pauseIdentity() {
            creations?.let {
                check(it.activities.size == 1 && it.activities.single() === activity) { "Pause created another Activity" }
                check(pauseRuns?.list()?.toSet() == pauseRunNames) { "Pause changed app-owned run inventory" }
            }
        }
        fun verifyPause(bytes: ByteArray): Int {
            val probe = checkNotNull(pauseProbe)
            probe.failure.get()?.let { throw it }
            val a = checkNotNull(probe.first.get())
            val b = checkNotNull(probe.second.get())
            check(probe.pauses.get() == 1 && probe.resumes.get() == 1 && a.nanos <= b.nanos)
            check(probe.permission.state() == 3 &&
                probe.permission.requestNanos() - probe.permission.pauseNanos() >= 1_000_000_000L &&
                probe.permission.resumeNanos() >= probe.permission.requestNanos() && a.nanos >= probe.permission.resumeNanos())
            return SandboxPauseOracle.verify(bytes, generation, checkNotNull(pauseTarget),
                a.offset, b.offset, a.frame, b.frame, a.delta)
        }

        fun rows() = stream.records.toList()
        fun matches(row: JsonValue, kind: String, gen: Long? = null): Boolean =
            row.getString("kind") == kind && (gen == null || row.getLong("generation", -1) == gen)
        fun phase(row: JsonValue, value: String) = row.getString("phase", "") == value
        fun drainRetention() {
            val checker = retention ?: return
            while (retentionCursor < stream.records.size) {
                val row = stream.records[retentionCursor++]
                try { checker.accept(row) }
                catch (failure: Throwable) { errors.business(failure) }
            }
        }
        fun drainForeign() {
            for (expected in foreignExpectations) while (expected.cursor < stream.records.size) {
                val row = stream.records[expected.cursor++]
                if (matches(row, "observation", expected.generation)) {
                    try { SandboxFunctionalOracle.foreignUnchanged(expected.initial, row.get("sample"), expected.id) }
                    catch (failure: Throwable) { errors.business(failure) }
                }
            }
        }
        fun drainUnreachable() {
            val checker = unreachable ?: return
            while (unreachableCursor < stream.records.size) {
                val row = stream.records[unreachableCursor++]
                try { checker.accept(row) }
                catch (failure: Throwable) { errors.business(failure) }
            }
        }
        fun drainExplore() {
            val checker = explore ?: return
            exploreCursor = checker.drain(stream, errors, exploreCursor)
        }
        fun drainVisibility() {
            visibility?.let { visibilityCursor=it.drain(stream,errors,visibilityCursor) }
        }
        fun businessPoll(input: Boolean = false) {
            check(businessStart > 0) { "No Activity attempt" }
            val elapsed = SystemClock.elapsedRealtime() - businessStart
            maybeAttachReplacement()
            stream.feed(byteArrayOf(), elapsed, false)
            tail?.read(businessStart, false)
            if (priorStream !== stream) {
                priorTail?.read(businessStart, false)
                priorStream?.applicationErrors(errors)
            }
            if (recreate) aggregate(false)
            drainRetention()
            drainForeign()
            drainUnreachable()
            drainExplore()
            drainVisibility()
            pauseProbe?.failure?.get()?.let { errors.business(it) }
            if (!stream.longTrace) check(totalSteps() < 480) { "Approaching cumulative step limit; finish before further business actions" }
            stream.applicationErrors(errors)
            errors.primary?.let { throw it }
            if (input && stream.longTrace) stream.checkInput(SystemClock.elapsedRealtime() - businessStart)
        }
        fun await(label: String, predicate: () -> Boolean) {
            while (true) {
                businessPoll()
                val satisfied = predicate()
                if (stream.longTrace) stream.checkAwait(satisfied, SystemClock.elapsedRealtime() - businessStart)
                if (satisfied) return
                // Delay polls only; all completion decisions come from actual public records.
                SystemClock.sleep(10)
            }
        }
        fun geometry(uiX: Float, uiY: Float): TouchGeometry {
            businessPoll(input = true)
            val current=checkNotNull(activity)
            val origin=IntArray(2)
            var capturedResize: JsonValue?=null
            await("actual focused Activity and current viewport before pointer") {
                val resize=rows().lastOrNull { matches(it,"resize") }
                var ready=false
                onMain {
                    check(!current.isDestroyed && !current.isFinishing) { "Activity ended before pointer" }
                    val content=current.findViewById<ViewGroup>(android.R.id.content)
                    check(content.childCount==1) { "Unexpected content view" }
                    val view=content.getChildAt(0)
                    ready=resize!=null && current.hasWindowFocus() && view.visibility==View.VISIBLE &&
                        view.isShown && view.hasWindowFocus() && view.width==resize.getInt("width") && view.height==resize.getInt("height")
                    if(ready) { capturedResize=resize;view.getLocationOnScreen(origin) }
                }
                ready
            }
            val resize=checkNotNull(capturedResize)
            val x = origin[0] + resize.getInt("screenX") + uiX / 960f * resize.getInt("screenWidth")
            val y = origin[1] + resize.getInt("height") - (resize.getInt("screenY") + uiY / 640f * resize.getInt("screenHeight"))
            actions.put(JSONObject().put("kind", "geometry").put("generation", generation)
                .put("originX", origin[0]).put("originY", origin[1]).put("width", resize.getInt("width"))
                .put("height", resize.getInt("height")).put("screenX", resize.getInt("screenX"))
                .put("screenY", resize.getInt("screenY")).put("screenWidth", resize.getInt("screenWidth"))
                .put("screenHeight", resize.getInt("screenHeight")).put("uiX", uiX).put("uiY", uiY).put("x", x).put("y", y))
            return TouchGeometry(x, y, resize.getInt("screenWidth"), resize.getInt("screenHeight"),
                resize.getInt("width"),resize.getInt("height"),origin[0],origin[1])
        }
        fun tap(uiX: Float, uiY: Float, label: String, beforeDown: (() -> Unit)? = null): TouchGeometry {
            val point = geometry(uiX, uiY)
            val (x, y) = point
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                businessPoll(input = true)
                if (action == MotionEvent.ACTION_DOWN) beforeDown?.invoke()
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { sendPointerSync(event) } finally { event.recycle() }
            }
            actions.put(JSONObject().put("kind", "tap").put("label", label).put("generation", generation)
                .put("x", x).put("y", y))
            return point
        }
        fun key(code: Int) {
            businessPoll(input = true)
            sendKeyDownUpSync(code)
            actions.put(JSONObject().put("kind", "key").put("keyCode", code).put("generation", generation))
        }
        fun inputFrames(label: String) {
            val probe=SandboxImageProbe(activity as AndroidLauncher,File(checkNotNull(runsRoot),"${checkNotNull(runName)}/client-diagnostic.jsonl"))
            val (a,b)=probe.pair { businessPoll() }
            actions.put(JSONObject().put("kind","setup-input-processed-fences").put("label",label)
                .put("frameA",a.frame).put("frameB",b.frame).put("offsetA",a.offset).put("offsetB",b.offset))
        }
        fun setupState(probe: SandboxSetupProbe, label: String, mode: Int, popup: Boolean, shared: Boolean) {
            var previous: Triple<Int,Boolean,Boolean>?=null
            await(label) {
                val state=probe.poll()
                if(state==null) false else {
                    val observed=Triple(state.mode,state.popupAttached,state.shared)
                    if(observed!=previous) {
                        actions.put(JSONObject().put("kind","setup-ui-state").put("label",label)
                            .put("frame",state.frame).put("modeIndex",state.mode).put("popupAttached",state.popupAttached)
                            .put("shared",state.shared).put("paneX",state.paneX).put("paneY",state.paneY)
                            .put("paneWidth",state.paneWidth).put("paneHeight",state.paneHeight))
                        previous=observed
                    }
                    observed==Triple(mode,popup,shared)
                }
            }
        }
        fun configureSetup(mode: String, shared: Boolean, label: String) {
            tap(790f,176f,"$label mode selector")
            inputFrames("$label-selector-open")
            val probe=SandboxSetupProbe(activity as AndroidLauncher)
            setupState(probe,"$label-selector-open",0,true,false)
            val modeIndex=when(mode) { "NO_FOG" -> 0; "MEMORY" -> 1; else -> 2 }
            repeat(modeIndex) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_ENTER)
            inputFrames("$label-selector-confirm")
            // Enter starts a fade; wait for actual pane removal, then the actual checkbox value.
            setupState(probe,"$label-selector-removed",modeIndex,false,false)
            if(shared) tap(662f,128f,"$label shared on")
            setupState(probe,"$label-shared-option-ready",modeIndex,false,shared)
        }
        fun worldTap(x: Double, y: Double, label: String): TouchGeometry {
            val projection = MapProjection(600.0, 400.0)
            return tap(projection.x(x), projection.y(y), label)
        }
        fun load(mode: String, shared: Boolean) {
            val mark = rows().size
            tap(790f, 80f, "Start/Reopen", if (captureNewStart) ({
                check(SandboxRecreateOracle.setup(rows())) { "Replacement auto-loaded before actual Start input" }
                newStartBoundary = stream.records.size.toLong()
            }) else null)
            await("load-request") { rows().drop(mark).any { matches(it, "load-request") } }
            val request = rows().drop(mark).single { matches(it, "load-request") }
            SandboxFunctionalOracle.configuration(request, mode, shared)
            generation = SandboxFunctionalOracle.newGeneration(request, generation)
            await("initial+ready") {
                rows().any { matches(it, "observation", generation) && phase(it, "initial") } &&
                    rows().any { matches(it, "load-ready", generation) }
            }
            val initial = rows().first { matches(it, "observation", generation) && phase(it, "initial") }.get("sample")
            SandboxFunctionalOracle.initial(initial, mode, shared)
            val ready = rows().first { matches(it, "load-ready", generation) }
            check(ready.getInt("mapWidth") == 30 && ready.getInt("mapHeight") == 20 && ready.getInt("blockedCount") == 33)
            actions.put(JSONObject().put("kind", "load-confirmed").put("generation", generation).put("mode", mode).put("shared", shared))
        }
        fun actionSince(mark: Int, type: String, foreignId: String? = null): JsonValue {
            await(type) { rows().drop(mark).any { matches(it, "action", generation) && it.getString("type") == type } }
            return rows().drop(mark).first { matches(it, "action", generation) && it.getString("type") == type }
                .also {
                    if (foreignId == null) SandboxFunctionalOracle.accepted(it, type)
                    else SandboxFunctionalOracle.foreignAction(it, type, foreignId)
                    actions.put(JSONObject().put("kind", "action-confirmed").put("type", type)
                        .put("generation", generation).put("sequence", it.getLong("sequence")))
                }
        }
        fun captureImage(name: String, hidden: Boolean) {
            val checker=checkNotNull(visibility)
            check(if(hidden) checker.hiddenReady() else checker.visibleSelected())
            val probe=checkNotNull(imageProbe)
            var cursor=stream.records.size
            // The last complete observation already qualifies before the asynchronous interval begins.
            checker.imageStable(stream.records.last { it.getString("kind")=="observation" },hidden)
            val start=cursor
            fun pollImage() {
                businessPoll()
                while(cursor<stream.records.size) checker.imageStable(stream.records[cursor++],hidden)
            }
            val captured=probe.capture(name,{ operation -> onMain { operation() } },::pollImage)
            await("image callback in-flight record completed") {
                pollImage()
                probe.completedPrefixAtLeast(captured.c.offset)
            }
            pollImage()
            imageTotalBytes+=captured.bytes.size
            check(imageTotalBytes<=4*1024*1024)
            val root=targetContext.cacheDir.toPath().resolve("s04-images")
            check(!Files.isSymbolicLink(root))
            if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root)
            check(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
            val directory=root.resolve(checkNotNull(runName))
            if(images.length()==0) Files.createDirectory(directory)
            check(!Files.isSymbolicLink(directory) && Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
            val path=directory.resolve("$name.png")
            Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use {
                it.write(captured.bytes)
            }
            val sha=digest(captured.bytes)
            val identity=JSONObject().put("runName",runName).put("name",name).put("bytes",captured.bytes.size)
                .put("schema","s04-surface-image-v1").put("generation",generation).put("pixelCopyCode",0)
                .put("sha256",sha).put("width",captured.width).put("height",captured.height)
                .put("frameA",captured.a.frame).put("frameB",captured.b.frame).put("frameC",captured.c.frame)
                .put("offsetA",captured.a.offset).put("offsetB",captured.b.offset).put("offsetC",captured.c.offset)
                .put("nanosA",captured.a.nanos).put("nanosB",captured.b.nanos).put("nanosC",captured.c.nanos)
                .put("requestNanos",captured.requestNanos).put("callbackNanos",captured.callbackNanos)
                .put("baselineSequence",start-1).put("stableFromSequence",start).put("stableThroughSequence",cursor-1)
            var offset=0
            while(offset<captured.bytes.size) {
                pollImage()
                val count=minOf(32768,captured.bytes.size-offset)
                val chunk=JSONObject().put("schema","s04-image-chunk-v1").put("runName",runName).put("name",name)
                    .put("sha256",sha).put("totalBytes",captured.bytes.size).put("offset",offset).put("byteCount",count)
                    .put("base64",Base64.encodeToString(captured.bytes,offset,count,Base64.NO_WRAP))
                sendStatus(0,Bundle().apply { putString("imageChunk",chunk.toString()) })
                offset+=count
            }
            pollImage()
            images.put(identity)
            actions.put(JSONObject().put("kind","original-surface-image").put("identity",identity))
        }

        try {
            SandboxUiCall.verify { body -> runOnMainSync(body) }
            result.putBoolean("uiFailureReturnVerified",true)
            val parameters = linkedMapOf<String, String>()
            arguments?.keySet()?.forEach { name ->
                val value = arguments?.get(name)
                require(value is String) { "Only string parameters are accepted" }
                parameters[name] = value
            }
            val config = SandboxFunctionalOracle.parameters(parameters)
            if (config.exercise in setOf("explore","visibility")) stream = SandboxFunctionalOracle.Stream.forLongTrace()
            recreate = config.exercise == "recreate"
            recreateMode = config.mode
            result.putString("mode", config.mode)
            result.putBoolean("shared", config.shared)
            result.putString("exercise", config.exercise)
            val runs = File(targetContext.getExternalFilesDir(null) ?: targetContext.filesDir, "runs")
            val before = runs.list()?.toSet().orEmpty()
            runsRoot = runs
            runsBefore = before
            if (config.exercise == "pause" || recreate) {
                observedApplication = targetContext.applicationContext as android.app.Application
                creations = Creations()
                onMain { checkNotNull(observedApplication).registerActivityLifecycleCallbacks(checkNotNull(creations)) }
            }
            monitor = addMonitor(AndroidLauncher::class.java.name, null, false)
            starts = 1
            businessStart = SystemClock.elapsedRealtime()
            activity = startActivitySync(Intent(targetContext, AndroidLauncher::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("one fresh run") {
                val added = runs.listFiles().orEmpty().filter { it.name !in before }
                check(added.size <= 1) { "More than one new run" }
                if (added.size == 1 && tail == null) {
                    val directory = added.single()
                    check(directory.name.matches(Regex("run-[0-9]+-[0-9a-f]{8}")) && directory.isDirectory)
                    check(directory.canonicalFile.parentFile == runs.canonicalFile)
                    runName = directory.name
                    tail = Tail(File(directory, "client-diagnostic.jsonl"), stream)
                }
                tail != null
            }
            await("resize") { rows().any { matches(it, "resize") } }
            check(rows().none { matches(it, "load-request") || matches(it, "load-ready") || matches(it, "observation") }) { "Activity loaded before Start" }
            configureSetup(config.mode,config.shared,"initial")
            if(config.exercise=="recovery") {
                val fixture=SandboxRecoveryFixture(targetContext,checkNotNull(runName))
                recoveryFixture=fixture
                fixture.hide()
                actions.put(JSONObject().put("kind","required-derived-reference-moved")
                    .put("relative","TestLab/local/P002-知识沙盒输入/tilesets/p001-ground.tsx")
                    .put("sha256",fixture.sha256).put("bytes",fixture.byteCount))
                stream.expectLoadFailure(1)
                val failedMark=rows().size
                tap(790f,80f,"Start with required derived reference missing")
                await("failed load request") { rows().drop(failedMark).any { matches(it,"load-request") } }
                val request=rows().drop(failedMark).single { matches(it,"load-request") }
                SandboxFunctionalOracle.configuration(request,config.mode,config.shared)
                generation=SandboxFunctionalOracle.newGeneration(request,generation)
                recoveryFirstGeneration=generation
                await("one exact expected required-reference failure, no partial world") {
                    SandboxRecoveryOracle.failed(rows(),generation)
                }
                check(stream.expectedLoadFailureObserved())
                fixture.restore()
                check(fixture.restored)
                actions.put(JSONObject().put("kind","required-derived-reference-restored")
                    .put("sha256",fixture.sha256).put("bytes",fixture.byteCount).put("failedGeneration",generation))
            }
            load(config.mode, config.shared)
            if(config.exercise=="visibility") {
                val initial=stream.records.indexOfFirst { matches(it,"observation",generation) && phase(it,"initial") }
                val resize=rows().last { matches(it,"resize") }
                val map=MapProjection(600.0,400.0)
                visibility=SandboxVisibilityOracle(stream.records[initial],config.mode,
                    960.0/resize.getInt("screenWidth")/map.scale,640.0/resize.getInt("screenHeight")/map.scale)
                visibilityCursor=initial+1
                imageProbe=SandboxImageProbe(activity as AndroidLauncher,File(runs,"${checkNotNull(runName)}/client-diagnostic.jsonl"))
            }
            if (config.exercise == "retention") {
                val initialIndex = stream.records.indexOfFirst {
                    matches(it, "observation", generation) && phase(it, "initial")
                }
                check(initialIndex >= 0)
                val initial = stream.records[initialIndex]
                retention = SandboxFunctionalOracle.Retention(initial, config.mode)
                retentionCursor = initialIndex + 1
                val cell = SandboxKnowledgeView.sample(initial.get("sample")).get(155)
                actions.put(JSONObject().put("kind", "retention-initial").put("generation", generation)
                    .put("sequence", initial.getLong("sequence")).put("cell", 155)
                    .put("gid", cell.getLong("gid")).put("blocked", cell.getBoolean("blocked")))
            }
            var mark = rows().size
            worldTap(110.0, 110.0, "select u1")
            actionSince(mark, "SelectAction")
            await("selection u1") { rows().drop(mark).any { matches(it, "selection", generation) && it.getString("selectedId", "") == "u1" } }
            mark = rows().size
            val goalX = when(config.exercise) { "explore" -> 350.0; "visibility" -> 530.0; else -> 110.0 }
            val goalY = when (config.exercise) { "controls", "recovery" -> 150.0; "visibility" -> 270.0; "retention", "unreachable", "pause", "recreate" -> 290.0; else -> 110.0 }
            val moveTouch = worldTap(goalX, goalY, "single MoveAction")
            val move = actionSince(mark, "MoveAction")
            val projection = MapProjection(600.0, 400.0)
            SandboxFunctionalOracle.target(move, goalX, goalY,
                960.0 / moveTouch.width / projection.scale, 640.0 / moveTouch.height / projection.scale)
            if(config.exercise=="visibility") {
                val checker=checkNotNull(visibility)
                await("visible u3 with own approach stopped") { checker.approachReady() }
                mark=rows().size
                worldTap(550.0,310.0,"select visible u3 baseline")
                actionSince(mark,"SelectAction","u3")
                await("visible foreign selection baseline") { checker.visibleSelected() }
                captureImage("visible",false)
                val own=SandboxFunctionalOracle.unit(rows().last { matches(it,"observation",generation) }.get("sample"),"u1").get("position")
                mark=rows().size
                worldTap(own.getDouble("x"),own.getDouble("y"),"reselect own visible unit")
                actionSince(mark,"SelectAction")
                mark=rows().size
                worldTap(110.0,290.0,"leave visible enemy")
                actionSince(mark,"MoveAction")
                mark=rows().size
                worldTap(550.0,310.0,"reselect u3 before automatic visibility loss")
                actionSince(mark,"SelectAction","u3")
                await("three later hidden moving steps") { checker.hiddenReady() }
                captureImage("hidden",true)
                val hiddenPoint=geometry(projection.x(550.0),projection.y(310.0))
                tap(projection.x(550.0),projection.y(310.0),"hidden original unit position") {
                    checker.armHiddenHit(checker.nextSequence(),hiddenPoint.windowWidth,hiddenPoint.windowHeight,
                        hiddenPoint.x.toInt()-hiddenPoint.originX,hiddenPoint.y.toInt()-hiddenPoint.originY)
                }
                await("actual hidden pointer receipt") { checker.pointerReceived() }
                val fences=checkNotNull(imageProbe).pair { businessPoll() }
                await("processed frame partial row completed") { checkNotNull(imageProbe).completedPrefixAtLeast(fences.second.offset) }
                businessPoll()
                checker.confirmProcessedFrame(checker.nextSequence(),fences.first.frame,fences.second.frame)
                actions.put(JSONObject().put("kind","hidden-pointer-processed-frame").put("frameA",fences.first.frame)
                    .put("frameB",fences.second.frame).put("nextSequence",checker.nextSequence()))
                await("three new steps after hidden pointer processing") { checker.ready() }
                check(images.length()==2)
            } else if (recreate) {
                val originalActivity = checkNotNull(activity)
                val initial = rows().single { matches(it, "observation", generation) && phase(it, "initial") }.get("sample")
                recreateInitial = initial
                recreateTarget = move.get("target")
                val launch = rows().single { matches(it, "launch") }
                check(launch.get("restoredActivity").isBoolean && !launch.getBoolean("restoredActivity"))
                await("old active movement and newly explored terrain") {
                    val latest = rows().lastOrNull { matches(it, "observation", generation) && phase(it, "step") }
                    if (latest == null) false else {
                        learnedCell = SandboxRecreateOracle.learned(initial, latest.get("sample"), recreateTarget)
                        learnedCell >= 0
                    }
                }
                check(rows().last { matches(it, "selection", generation) }.getString("selectedId") == "u1")
                check(checkNotNull(creations).activities.single() === originalActivity)
                priorStream = stream
                priorTail = tail
                priorName = runName
                priorFile = File(runs, "${checkNotNull(priorName)}/client-diagnostic.jsonl")
                priorGeneration = generation
                // Flush the complete prefix immediately before queuing the real recreation request.
                businessPoll()
                val requestPrefix = completePrefix(checkNotNull(priorFile))
                recreateBoundary = requestPrefix.count { it == '\n'.code.toByte() }.toLong()
                recreateRequestTime = SystemClock.elapsedRealtime()
                recreateRequested = true
                actions.put(JSONObject().put("kind", "recreate-request").put("oldRunName", priorName)
                    .put("generation", priorGeneration).put("nextSequence", recreateBoundary)
                    .put("offset", requestPrefix.size).put("learnedCell", learnedCell))
                onMain {
                    check(!originalActivity.isDestroyed && !originalActivity.isFinishing && originalActivity.hasWindowFocus())
                    originalActivity.recreate()
                }
                await("old run normal closure within recreation deadline") {
                    check(SystemClock.elapsedRealtime() - recreateRequestTime <= 3000) { "Recreated old host did not close within 3 seconds" }
                    SandboxRecreateOracle.closed(checkNotNull(priorStream).records.toList(), priorGeneration,
                        recreateBoundary, true, recreateTarget)
                }
                checkNotNull(priorStream).complete()
                val oldRaw = completePrefix(checkNotNull(priorFile))
                check(oldRaw.size.toLong() == checkNotNull(priorStream).bytes)
                priorHash = digest(oldRaw)
                actions.put(JSONObject().put("kind", "recreate-old-closed").put("runName", priorName)
                    .put("bytes", oldRaw.size).put("sha256", priorHash))
                await("one replacement Activity and run at setup") {
                    maybeAttachReplacement()
                    stream !== priorStream && checkNotNull(creations).activities.size == 2 && SandboxRecreateOracle.setup(rows())
                }
                check(activity !== originalActivity)
                // Re-select the same knowledge mode explicitly; a new Activity starts at setup defaults.
                configureSetup(config.mode,false,"replacement")
                captureNewStart = true
                load(config.mode, false)
                captureNewStart = false
                tap(715f, 312f, "new match Stop with no selection")
                businessPoll()
                val afterTouch = completePrefix(File(runs, "${checkNotNull(runName)}/client-diagnostic.jsonl"))
                // One in-flight partial row may predate the touch; exclude it from the three-step witness.
                newStopBoundary = afterTouch.count { it == '\n'.code.toByte() }.toLong() + 1
                await("three post-touch clean new-world steps") {
                    SandboxRecreateOracle.fresh(rows(), initial, config.mode, newStartBoundary, newStopBoundary, learnedCell) >= 3
                }
                recreateReady = true
                actions.put(JSONObject().put("kind", "recreate-new-clean").put("runName", runName)
                    .put("generation", generation).put("startBoundary", newStartBoundary).put("stopBoundary", newStopBoundary))
            } else if (config.exercise == "pause") {
                await("actual active movement before pause") {
                    rows().drop(mark).any {
                        matches(it, "observation", generation) && phase(it, "step") &&
                            SandboxFunctionalOracle.moved(SandboxFunctionalOracle.unit(it.get("sample"), "u1"), 110.0, 110.0)
                    }
                }
                val current = activity as AndroidLauncher
                val file = File(runs, "${checkNotNull(runName)}/client-diagnostic.jsonl")
                pauseFile = file
                pauseTarget = move.get("target")
                pauseRuns = runs
                pauseRunNames = before + checkNotNull(runName)
                pauseIdentity()
                val probe = SandboxResumeProbe(current, file)
                pauseProbe = probe
                probe.register()
                await("lifecycle observer registered") { probe.registered.get() }
                onMain {
                    check(!current.isFinishing && !current.isDestroyed && current.hasWindowFocus())
                    check(current.moveTaskToBack(true)) { "Own Activity task did not move to background" }
                }
                await("actual pause") { rows().any { matches(it, "pause") } }
                val pausedIndex = rows().indexOfFirst { matches(it, "pause") }
                val heldFrom = SystemClock.elapsedRealtime()
                do {
                    businessPoll()
                    pauseIdentity()
                    check(rows().drop(pausedIndex + 1).none {
                        it.getString("kind") in setOf("observation", "resume", "load-request", "load-ready",
                            "match-exit", "application-disposed", "activity-destroyed", "closed")
                    }) { "Background advanced, resumed or closed unexpectedly" }
                    SystemClock.sleep(10)
                } while (SystemClock.elapsedRealtime() - heldFrom < 1000)
                val heldMillis = SystemClock.elapsedRealtime() - heldFrom
                actions.put(JSONObject().put("kind", "pause-held").put("millis", heldMillis)
                    .put("pauseSequence", rows()[pausedIndex].getLong("sequence")))
                // Linearizes the last-poll-to-request gap: an earlier callback is permanently invalid.
                probe.armResume()
                targetContext.startActivity(Intent(targetContext, AndroidLauncher::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                await("original Activity focused and first resume render fenced") {
                    pauseIdentity()
                    var focused = false
                    onMain { focused = !current.isDestroyed && !current.isFinishing && current.hasWindowFocus() }
                    focused && probe.second.get() != null
                }
                await("three continuous resumed movement steps") {
                    pauseIdentity()
                    verifyPause(completePrefix(file)) >= 3
                }
                pauseBusinessReady = true
                val a = checkNotNull(probe.first.get())
                val b = checkNotNull(probe.second.get())
                actions.put(JSONObject().put("kind", "resume-fences").put("runName", runName)
                    .put("offsetA", a.offset).put("offsetB", b.offset).put("frameA", a.frame).put("frameB", b.frame)
                    .put("deltaA", a.delta).put("nanosA", a.nanos).put("nanosB", b.nanos))
            } else if (config.exercise == "unreachable") {
                await("actual movement before unreachable command") {
                    rows().drop(mark).any {
                        matches(it, "observation", generation) && phase(it, "step") &&
                            SandboxFunctionalOracle.moved(SandboxFunctionalOracle.unit(it.get("sample"), "u1"), 110.0, 110.0)
                    }
                }
                mark = rows().size
                val blockedTouch = worldTap(450.0, 110.0, "Move into original enclosed target")
                val cancelled = actionSince(mark, "MoveAction")
                SandboxFunctionalOracle.target(cancelled, 450.0, 110.0,
                    960.0 / blockedTouch.width / projection.scale, 640.0 / blockedTouch.height / projection.scale)
                val actionIndex = stream.records.indexOf(cancelled)
                check(actionIndex > 0)
                // The exact preceding record must be this command's before-action.
                unreachable = SandboxFunctionalOracle.Unreachable(move, stream.records[actionIndex - 1], cancelled)
                unreachableCursor = actionIndex + 1
                await("unreachable cancellation and three stable steps") { checkNotNull(unreachable).ready() }
                actions.put(JSONObject().put("kind", "unreachable-command").put("generation", generation)
                    .put("startedSequence", move.getLong("sequence")).put("cancelledSequence", cancelled.getLong("sequence")))
            } else if (config.exercise == "controls" || config.exercise == "recovery") {
                await("actual movement") {
                    rows().drop(mark).any { matches(it, "observation", generation) && phase(it, "step") &&
                        SandboxFunctionalOracle.moved(SandboxFunctionalOracle.unit(it.get("sample"), "u1"), 110.0, 110.0) }
                }
                mark = rows().size
                tap(715f, 312f, "Stop")
                actionSince(mark, "StopAction")
                await("Stop clears target/path and remains still") {
                    val steps = rows().drop(mark).filter { matches(it, "observation", generation) && phase(it, "step") }.takeLast(3)
                    if (steps.size < 3) false else {
                        val units = steps.map { SandboxFunctionalOracle.unit(it.get("sample"), "u1") }
                        units.all { SandboxFunctionalOracle.stopped(it) } && units.all {
                            !SandboxFunctionalOracle.moved(it, units.first().get("position").getDouble("x"), units.first().get("position").getDouble("y"))
                        }
                    }
                }
                if (config.exercise=="controls" && (config.mode == "NO_FOG" || config.shared)) {
                    val initial = rows().first {
                        matches(it, "observation", generation) && phase(it, "initial")
                    }.get("sample")
                    for ((id, x) in listOf("u2" to 510.0, "u3" to 550.0)) {
                        mark = rows().size
                        foreignExpectations += ForeignExpectation(generation, initial, id, mark)
                        worldTap(x, 310.0, "select visible $id")
                        val select = actionSince(mark, "SelectAction", id)
                        await("foreign selection and its after-action") {
                            rows().drop(mark).any { matches(it, "selection", generation) && it.getString("selectedId", "") == id } &&
                                SandboxFunctionalOracle.foreignAfterAction(rows().drop(mark), select, initial, id)
                        }
                        mark = rows().size
                        val foreignTouch = worldTap(510.0, 270.0, "foreign Move rejected")
                        val rejectedMove = actionSince(mark, "MoveAction", id)
                        SandboxFunctionalOracle.target(rejectedMove, 510.0, 270.0,
                            960.0 / foreignTouch.width / projection.scale, 640.0 / foreignTouch.height / projection.scale)
                        await("foreign Move after-action") {
                            SandboxFunctionalOracle.foreignAfterAction(rows().drop(mark), rejectedMove, initial, id)
                        }
                        mark = rows().size
                        tap(715f, 312f, "foreign Stop rejected")
                        val rejectedStop = actionSince(mark, "StopAction", id)
                        await("foreign Stop after-action and subsequent observations") {
                            SandboxFunctionalOracle.foreignAfterAction(rows().drop(mark), rejectedStop, initial, id) &&
                                rows().drop(mark).count {
                                    matches(it, "observation", generation) && phase(it, "step") &&
                                        it.getLong("sequence") > rejectedStop.getLong("sequence")
                                } >= 3
                        }
                        actions.put(JSONObject().put("kind", "foreign-controls-rejected").put("generation", generation)
                            .put("unitId", id).put("selectSequence", select.getLong("sequence"))
                            .put("moveSequence", rejectedMove.getLong("sequence")).put("stopSequence", rejectedStop.getLong("sequence")))
                    }
                }
                if(config.exercise=="recovery") {
                    check(SandboxRecoveryOracle.failed(rows(),recoveryFirstGeneration) && generation>recoveryFirstGeneration &&
                        stream.expectedLoadFailureObserved() && recoveryFixture?.restored==true)
                    recoveryReady=true
                    actions.put(JSONObject().put("kind","recovered-match-move-stop")
                        .put("failedGeneration",recoveryFirstGeneration).put("newGeneration",generation))
                } else {
                    tap(790f, 176f, "disabled mode during match")
                    tap(662f, 128f, "disabled shared during match")
                    load(config.mode, config.shared)
                    mark = rows().size
                    tap(865f, 312f, "Exit match to setup")
                    await("Exit closes current match") {
                        rows().drop(mark).any { matches(it, "match-exit", generation) } &&
                            rows().drop(mark).any { matches(it, "closed", generation) }
                    }
                    tap(662f, 128f, "editable shared toggled")
                    load(config.mode, !config.shared)
                }
            } else if (config.exercise == "retention") {
                // businessPoll consumes every step since initial, including the Move wait.
                // A ready candidate only requests close; the close tail is checked below.
                await("three consecutive lost terrain observations") { checkNotNull(retention).ready() }
            } else {
                val pixelHeight = 640.0 / moveTouch.height / projection.scale
                await("adjacent after-Move observation") {
                    rows().any { matches(it, "observation", generation) && phase(it, "after-action") && it.getLong("sequence") == move.getLong("sequence") + 1 }
                }
                val commanded = rows().single {
                    matches(it, "observation", generation) && phase(it, "after-action") && it.getLong("sequence") == move.getLong("sequence") + 1
                }
                explore = SandboxExploreOracle(move, commanded, config.mode, pixelHeight)
                exploreCursor = stream.records.indexOf(commanded) + 1
                await("SC07 discovery/reroute/first eligible arrival") { checkNotNull(explore).ready() }
                val checker = checkNotNull(explore)
                actions.put(JSONObject().put("kind", "explore-observed").put("generation", generation)
                    .put("discoveryStep", checker.discoveryStep()).put("rerouted", checker.rerouted())
                    .put("arrivalStep", checker.terminal().get("sample").getLong("stepIndex"))
                    .put("terminalSequence", stream.terminalSequence()).put("businessEndSteps", stream.businessEndSteps()))
            }
        } catch (failure: Throwable) {
            stream.failBusiness(failure)
            if (failure !== errors.primary) errors.business(failure)
        } finally {
            val closeStart = SystemClock.elapsedRealtime()
            if (recreate) creations?.closing?.set(true)
            try {
                if (recreateReady) {
                    val prefix = completePrefix(File(checkNotNull(runsRoot), "${checkNotNull(runName)}/client-diagnostic.jsonl"))
                    newFinishBoundary = prefix.count { it == '\n'.code.toByte() }.toLong()
                }
            } catch (failure: Throwable) { errors.cleanup(failure) }
            try {
                pauseProbe?.close()
                pauseProbe?.failure?.get()?.let { throw it }
                if (pauseBusinessReady) {
                    pauseIdentity()
                    val prefix = completePrefix(checkNotNull(pauseFile))
                    check(verifyPause(prefix) >= 3)
                    finishSequence = prefix.count { it == '\n'.code.toByte() }.toLong()
                    actions.put(JSONObject().put("kind", "pause-finish-boundary")
                        .put("offset", prefix.size).put("nextSequence", finishSequence))
                }
            } catch (failure: Throwable) { errors.cleanup(failure) }
            try {
                if (stream.longTrace && errors.primary == null) stream.checkAwait(true, SystemClock.elapsedRealtime() - businessStart)
            } catch (failure: Throwable) { stream.failBusiness(failure); errors.cleanup(failure) }
            // A time/qualification failure must never skip the actual Activity finish request.
            try { finishObserved() } catch (failure: Throwable) { errors.cleanup(failure) }
            if (starts == 1) {
                try {
                    while (SystemClock.elapsedRealtime() - closeStart <= 3000) {
                        if (recreate) SandboxRecreateCleanup.poll(::finishObserved, ::maybeAttachReplacement, {
                            tail?.read(closeStart, true)
                            if (priorStream !== stream) priorTail?.read(closeStart, true)
                            aggregate(true)
                        }, ::closingFailure)
                        else try { tail?.read(closeStart, true) }
                        catch (failure: Throwable) { errors.cleanup(failure) }
                        // A normal disposal chain cannot turn a late application failure into PASS.
                        stream.applicationErrors(errors)
                        priorStream?.takeIf { it !== stream }?.applicationErrors(errors)
                        drainRetention()
                        drainForeign()
                        drainUnreachable()
                        drainExplore()
                        drainVisibility()
                        val captured = rows()
                        val closingRows = if (pauseBusinessReady) captured.filter {
                            finishSequence >= 0 && it.getLong("sequence") >= finishSequence
                        } else captured
                        closed = closingRows.any { matches(it, "presentation-released") } &&
                            closingRows.any { matches(it, "application-disposed") } &&
                            closingRows.any { matches(it, "activity-destroyed") } &&
                            (generation == 0L || closingRows.any { matches(it, "closed", generation) }) &&
                            closingRows.any { matches(it, "evidence-completed") && it.getString("status", "") == "normal" }
                        if (recreateRequested) {
                            var oldReleased = false
                            try { oldReleased = SandboxRecreateOracle.released(checkNotNull(priorStream).records.toList(),
                                priorGeneration, recreateBoundary, true) }
                            catch (failure: Throwable) { closingFailure(failure) }
                            closed = SandboxRecreateCleanup.resolved(true, creations?.activities?.size ?: 0,
                                stream !== priorStream, oldReleased, closed)
                        }
                        if (closed) break
                        if (errors.secondary.size > 8 && !recreateRequested) break
                        SystemClock.sleep(10)
                    }
                    check(closed) { "Exercise Activity runs did not produce full normal release chains within 3 seconds" }
                    stream.complete()
                    priorStream?.takeIf { it !== stream }?.complete()
                    if (recreateReady) {
                        check(SandboxRecreateOracle.closed(checkNotNull(priorStream).records.toList(), priorGeneration,
                            recreateBoundary, true, recreateTarget))
                        check(SandboxRecreateOracle.closed(rows(), generation, newFinishBoundary, false, null))
                        check(checkNotNull(creations).activities.size == 2 && stream !== priorStream)
                        check(checkNotNull(runsRoot).list()?.toSet() == runsBefore + setOf(checkNotNull(priorName), checkNotNull(runName)))
                        val oldRaw = completePrefix(checkNotNull(priorFile))
                        check(digest(oldRaw) == priorHash && oldRaw.size.toLong() == checkNotNull(priorStream).bytes) { "Closed old run changed" }
                        check(SandboxRecreateOracle.fresh(rows(), checkNotNull(recreateInitial), checkNotNull(recreateMode),
                            newStartBoundary, newStopBoundary, learnedCell) >= 3)
                        val newRaw = completePrefix(File(checkNotNull(runsRoot), "${checkNotNull(runName)}/client-diagnostic.jsonl"))
                        check(newRaw.size.toLong() == stream.bytes)
                        actions.put(JSONObject().put("kind", "recreate-two-runs-closed").put("oldRunName", priorName)
                            .put("newRunName", runName).put("oldSha256", priorHash).put("newSha256", digest(newRaw))
                            .put("oldBytes", oldRaw.size).put("newBytes", newRaw.size))
                    }
                    retention?.let { check(it.ready()) { "Full close stream did not retain a valid retention result" } }
                    unreachable?.let { check(it.ready()) { "Full close stream did not retain a valid unreachable result" } }
                    explore?.let { check(it.ready()) { "Full close stream did not retain a valid exploration result" } }
                    visibility?.let { check(it.ready()) { "Full close stream did not retain valid visibility qualification" } }
                    recoveryFixture?.let {
                        check(recoveryReady && it.restored && stream.expectedLoadFailureObserved() &&
                            SandboxRecoveryOracle.failed(rows(),recoveryFirstGeneration)) { "Required-reference failure/recovery not fully qualified" }
                    }
                    if (pauseBusinessReady) {
                        pauseIdentity()
                        val raw = completePrefix(checkNotNull(pauseFile))
                        check(raw.size.toLong() == stream.bytes) { "Closed pause raw bytes differ from captured stream" }
                        val released = rows().first {
                            matches(it, "presentation-released") && it.getLong("sequence") >= finishSequence
                        }.getLong("sequence")
                        var sequence = 0L
                        var cut = 0
                        while (sequence < released) {
                            check(cut < raw.size)
                            if (raw[cut++] == '\n'.code.toByte()) sequence++
                        }
                        check(verifyPause(raw.copyOf(cut)) >= 3) { "Late business prefix failed" }
                        actions.put(JSONObject().put("kind", "pause-full-log")
                            .put("bytes", raw.size).put("businessEndOffset", cut).put("releaseSequence", released)
                            .put("sha256", MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }))
                    }
                    check(totalSteps() <= (if (stream.longTrace) 3000 else 500) && totalBytes() <= 16L*1024*1024) { "Final exercise exceeds its explicit evidence limits" }
                } catch (failure: Throwable) { errors.cleanup(failure) }
            }
            try { tail?.close() } catch (failure: Throwable) { errors.cleanup(failure) }
            try { if (priorTail !== tail) priorTail?.close() } catch (failure: Throwable) { errors.cleanup(failure) }
            try { monitor?.let { removeMonitor(it) } } catch (failure: Throwable) { errors.cleanup(failure) }
            try { recoveryFixture?.close() } catch (failure: Throwable) { errors.cleanup(failure) }
            pauseProbe?.permission?.let {
                actions.put(JSONObject().put("kind", "pause-resume-permission").put("state", it.state())
                    .put("pauseNanos", it.pauseNanos()).put("requestNanos", it.requestNanos()).put("resumeNanos", it.resumeNanos()))
            }
            try {
                creations?.let { observer ->
                    onMain { checkNotNull(observedApplication).unregisterActivityLifecycleCallbacks(observer) }
                    result.putInt("observedActivityCreations", observer.activities.size)
                    if (recreateRequested) {
                        closed = closed && observer.activities.size == 2 && stream !== priorStream && observer.finishFailure.get() == null
                        check(closed) { "Recreation cleanup unresolved; preserve resource window and do not read inventory" }
                    }
                    observer.finishFailure.get()?.let { throw it }
                }
                pauseProbe?.failure?.get()?.let { throw it }
            } catch (failure: Throwable) { errors.cleanup(failure) }
            retention?.let {
                actions.put(JSONObject().put("kind", "retention-closed").put("generation", generation)
                    .put("firstLossStep", it.firstLossStep()).put("lossSequences", JSONArray(it.lossSequences()))
                    .put("lossSteps", JSONArray(it.lossSteps())).put("valid", it.ready()))
            }
            unreachable?.let {
                actions.put(JSONObject().put("kind", "unreachable-closed").put("generation", generation)
                    .put("lastSequence", it.lastSequence()).put("lastStep", it.lastStep()).put("valid", it.ready()))
            }
            result.putString("schema", "e9-sandbox-functional-result-v1")
            result.putInt("applicationStartAttempts", starts)
            result.putString("runName", runName)
            result.putString("priorRunName", priorName)
            result.putInt("repeatedClosingFailures", repeatedClosingFailures)
            if (recreateRequested) {
                result.putBoolean("recreateCleanupResolved", closed)
                result.putString("resourceDisposition", if (closed) "Normal release chains observed; host PID protection checks still required"
                    else "HELD: recreation replacement/closure unresolved; preserve scene, no inventory or automatic retry")
            }
            result.putLong("readBytes", totalBytes())
            result.putInt("cumulativePhaseSteps", totalSteps())
            result.putString("capturePolicy", if (stream.longTrace) "long-2500-business-500-tail-3000-total-60s" else "standard-500-total-15s")
            if (stream.longTrace) {
                result.putInt("businessEndSteps", stream.businessEndSteps())
                result.putLong("terminalSequence", stream.terminalSequence())
            }
            result.putBoolean("normalReleaseObserved", closed)
            result.putString("actions", actions.toString())
            result.putString("images",images.toString())
            result.putBoolean("imageCallbackUnresolved",imageProbe?.unresolved()==true)
            if(imageProbe?.unresolved()==true) errors.cleanup(IllegalStateException("HELD: unresolved PixelCopy callback; preserve scene, no inventory"))
            result.putBoolean("recoveryReferenceUnresolved",recoveryFixture?.restored==false)
            if(recoveryFixture?.restored==false) errors.cleanup(IllegalStateException("HELD: required reference recovery unresolved; no inventory"))
            result.putString("primaryError", errors.primary?.stackTraceToString())
            result.putString("secondaryErrors", JSONArray(errors.secondary.map { it.stackTraceToString() }).toString())
            val passed = errors.primary == null && errors.secondary.isEmpty() && closed
            result.putString("result", if (passed) "PASS: this fixed UI exercise; host closure and independent full-log review still required" else "FAIL: preserve raw result and complete new run; no automatic retry")
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }

    private class Tail(private val file: File, private val stream: SandboxFunctionalOracle.Stream) : AutoCloseable {
        private var offset = 0L
        private var reader: RandomAccessFile? = null
        fun read(phaseStart: Long, closing: Boolean) {
            stream.feed(byteArrayOf(), SystemClock.elapsedRealtime() - phaseStart, closing)
            if (!file.exists()) return
            if (reader == null) {
                check(file.canonicalFile.parentFile == file.parentFile.canonicalFile && file.isFile)
                reader = RandomAccessFile(file, "r")
            }
            val current = checkNotNull(reader)
            check(current.length() >= offset) { "New diagnostic file was truncated" }
            val buffer = ByteArray(8192)
            while (offset < current.length()) {
                val elapsed = SystemClock.elapsedRealtime() - phaseStart
                stream.feed(byteArrayOf(), elapsed, closing)
                val count = current.read(buffer, 0, minOf(buffer.size.toLong(), current.length()-offset).toInt())
                check(count > 0)
                offset += count
                stream.feed(buffer.copyOf(count), elapsed, closing)
            }
        }
        override fun close() { reader?.close() }
    }
}
