package com.rustedwarfare.reforge.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Surface
import android.view.SurfaceHolder
import com.badlogic.gdx.Application
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** TEST-only current app surface capture. Does not pause, drive, or inspect the simulation. */
internal class SandboxImageProbe(private val activity: AndroidLauncher, private val file: File) {
    data class Fence(val frame: Long, val offset: Long, val nanos: Long)
    data class Capture(val bytes: ByteArray, val width: Int, val height: Int,
        val a: Fence, val b: Fence, val c: Fence, val requestNanos: Long, val callbackNanos: Long)
    private val app: Application = activity
    private val graphics = app.graphics
    private val handler = Handler(Looper.getMainLooper())
    private val failure = AtomicReference<Throwable?>()
    private val held = AtomicBoolean()
    private val expired = AtomicBoolean()
    private val names = mutableSetOf<String>()
    private fun fence(): Fence {
        check(app.graphics === graphics && file.isFile && file.length() in 1..16L*1024*1024)
        return Fence(graphics.frameId, file.length(), System.nanoTime())
    }
    private fun post(body: () -> Unit) {
        app.postRunnable { try { if (!expired.get()) body() } catch (problem: Throwable) { failure.compareAndSet(null, problem) } }
    }
    private fun waitUntil(poll: () -> Unit, deadline: Long, predicate: () -> Boolean) {
        while (true) {
            poll()
            failure.get()?.let { throw it }
            if (SystemClock.elapsedRealtime() > deadline) {
                expired.set(true)
                error("Image/frame deadline exceeded; callbackPending=${held.get()}")
            }
            if (predicate()) return
            SystemClock.sleep(10)
        }
    }
    fun pair(poll: () -> Unit): Pair<Fence,Fence> {
        val a = AtomicReference<Fence?>(); val b = AtomicReference<Fence?>()
        val deadline = SystemClock.elapsedRealtime()+3000
        post { a.set(fence()); post { b.set(fence()) } }
        waitUntil(poll,deadline) { b.get()!=null }
        val first=checkNotNull(a.get()); val second=checkNotNull(b.get())
        check(second.frame==first.frame+1 && second.offset>=first.offset && second.nanos>=first.nanos)
        return first to second
    }
    fun capture(name: String, main: (() -> Unit) -> Unit, poll: () -> Unit): Capture {
        var view: SurfaceView?=null
        var surfaceIdentity: Surface?=null
        val surfaceEvents=object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { failure.compareAndSet(null,IllegalStateException("Surface created during image interval")) }
            override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { failure.compareAndSet(null,IllegalStateException("Surface changed during image interval")) }
            override fun surfaceDestroyed(holder: SurfaceHolder) { failure.compareAndSet(null,IllegalStateException("Surface destroyed during image interval")) }
        }
        val focus=ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if(!focused) failure.compareAndSet(null,IllegalStateException("Window lost focus during image capture"))
        }
        main {
            check(!activity.isDestroyed && !activity.isFinishing && activity.hasWindowFocus())
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            check(content.childCount==1)
            val observed=content.getChildAt(0) as? SurfaceView ?: error("Expected own SurfaceView")
            check(observed.isShown && observed.hasWindowFocus() && observed.holder.surface.isValid)
            view=observed;surfaceIdentity=observed.holder.surface
            observed.viewTreeObserver.addOnWindowFocusChangeListener(focus)
            observed.holder.addCallback(surfaceEvents)
        }
        fun witness() {
            val observed=checkNotNull(view)
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            check(!activity.isDestroyed && !activity.isFinishing && activity.hasWindowFocus() &&
                content.childCount==1 && content.getChildAt(0)===observed && observed.isShown &&
                observed.hasWindowFocus() && observed.holder.surface.isValid &&
                observed.holder.surface===surfaceIdentity && app.graphics===graphics)
            failure.get()?.let { throw it }
        }
        var result: Capture?=null
        var primary: Throwable?=null
        try {
            result=captureObserved(name,{ operation -> main { witness();operation();witness() } },{
                poll();main { witness() }
            })
        } catch(problem: Throwable) { primary=problem }
        finally {
            try { main {
                checkNotNull(view).viewTreeObserver.removeOnWindowFocusChangeListener(focus)
                checkNotNull(view).holder.removeCallback(surfaceEvents)
            } }
            catch(problem: Throwable) { if(primary==null) primary=problem else primary.addSuppressed(problem) }
        }
        primary?.let { throw it }
        return checkNotNull(result)
    }
    private fun captureObserved(name: String, main: (() -> Unit) -> Unit, poll: () -> Unit): Capture {
        check(name in setOf("visible","hidden") && names.add(name) && !expired.get() && !held.get())
        val deadline=SystemClock.elapsedRealtime()+3000
        val a=AtomicReference<Fence?>(); val b=AtomicReference<Fence?>(); val c=AtomicReference<Fence?>()
        post { a.set(fence()); post { b.set(fence()) } }
        waitUntil(poll,deadline) { b.get()!=null }
        val first=checkNotNull(a.get()); val second=checkNotNull(b.get())
        check(second.frame==first.frame+1 && second.offset>=first.offset && second.nanos>=first.nanos)
        var surface: SurfaceView?=null
        var bitmap: Bitmap?=null
        var width=0; var height=0; var request=0L
        val bytes=AtomicReference<ByteArray?>()
        val callback=AtomicReference<Long?>()
        main {
            check(!activity.isDestroyed && !activity.isFinishing && activity.hasWindowFocus())
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            check(content.childCount==1)
            surface=content.getChildAt(0) as? SurfaceView ?: error("Expected own single SurfaceView")
            val view=checkNotNull(surface)
            check(view.isShown && view.hasWindowFocus() && view.holder.surface.isValid)
            width=view.width; height=view.height
            check(width in 1..4096 && height in 1..4096 && width.toLong()*height<=4L*1024*1024)
            val target=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            bitmap=target
            check(target.allocationByteCount<=16*1024*1024)
            request=System.nanoTime()
            held.set(true)
            try {
                PixelCopy.request(view,target,{ code ->
                    try {
                        callback.set(System.nanoTime())
                        if (!expired.get()) {
                            check(code==PixelCopy.SUCCESS) { "PixelCopy returned $code" }
                            check(!activity.isDestroyed && !activity.isFinishing && activity.hasWindowFocus())
                            check(content.childCount==1 && content.getChildAt(0)===view && view.isShown &&
                                view.hasWindowFocus() && view.holder.surface.isValid && view.width==width && view.height==height)
                            val output=ByteArrayOutputStream()
                            check(target.compress(Bitmap.CompressFormat.PNG,100,output))
                            val raw=output.toByteArray()
                            check(raw.size in 1..2*1024*1024) { "Original full-resolution PNG exceeds capture bound" }
                            bytes.set(raw)
                            post { c.set(fence()) }
                        }
                    } catch (problem: Throwable) { failure.compareAndSet(null,problem) }
                    finally { target.recycle(); held.set(false) }
                },handler)
            } catch (problem: Throwable) {
                held.set(false); target.recycle(); throw problem
            }
        }
        try {
            waitUntil(poll,deadline) { c.get()!=null && !held.get() }
            val third=checkNotNull(c.get())
            check(third.frame>second.frame && third.offset>=second.offset && request>=second.nanos &&
                checkNotNull(callback.get())>=request && third.nanos>=checkNotNull(callback.get()))
            main {
                val content=activity.findViewById<ViewGroup>(android.R.id.content)
                val view=checkNotNull(surface)
                check(!activity.isDestroyed && !activity.isFinishing && activity.hasWindowFocus() &&
                    content.childCount==1 && content.getChildAt(0)===view && view.width==width && view.height==height &&
                    view.isShown && view.hasWindowFocus() && view.holder.surface.isValid && app.graphics===graphics)
            }
            return Capture(checkNotNull(bytes.get()),width,height,first,second,third,request,checkNotNull(callback.get()))
        } finally {
            // Native copy owns target until callback. A timeout never recycles an in-flight bitmap.
            if (!held.get()) bitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }
    fun unresolved(): Boolean = held.get()
    fun completedPrefixAtLeast(offset: Long): Boolean {
        RandomAccessFile(file,"r").use { input ->
            if (input.length()<offset || offset<=0) return false
            input.seek(offset-1)
            if (input.read()=='\n'.code) return true
            // Include the one row in flight at fence C; never accept a truncated qualification window.
            while (input.filePointer<input.length()) if (input.read()=='\n'.code) return true
            return false
        }
    }
}
