package com.rustedwarfare.reforge.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.rustedwarfare.reforge.client.BootstrapScreen

class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize(BootstrapScreen(), AndroidApplicationConfiguration())
    }
}
