package com.rustedwarfare.reforge.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.rustedwarfare.reforge.client.BootstrapScreen
import java.io.File

class AndroidLauncher : AndroidApplication() {
    private var evidence: AndroidEvidence? = null
    private var sandbox: BootstrapScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val run = AndroidEvidence(File(getExternalFilesDir(null) ?: filesDir, "runs"))
        evidence = run
        try {
            val content = File(filesDir.canonicalFile, "p001")
            SandboxAssets.copy(assets, content)
            run.record("launch", mapOf("package" to packageName, "sdk" to android.os.Build.VERSION.SDK_INT,
                "abis" to android.os.Build.SUPPORTED_ABIS.toList(), "content" to content.absolutePath,
                "restoredActivity" to (savedInstanceState != null), "lifecyclePolicy" to "pause-no-catchup-destroy-new-match"))
            val screen = BootstrapScreen(File(content, "TestLab/local/P002-知识沙盒输入").toPath(), run, touchControls = true)
            sandbox = screen
            initialize(screen,
                AndroidApplicationConfiguration().apply { disableAudio = true; useImmersiveMode = true })
        } catch (failure: Throwable) {
            try { sandbox?.destroyPausedHost() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            sandbox = null
            try { run.record("launch-failed", mapOf("message" to failure.toString())) }
            finally { run.close(); evidence = null }
            throw failure
        }
    }

    override fun onDestroy() {
        try { super.onDestroy() }
        finally {
            try {
                sandbox?.destroyPausedHost()
                evidence?.record("activity-destroyed", mapOf("changingConfigurations" to isChangingConfigurations))
            } finally { evidence?.close() }
        }
    }
}
