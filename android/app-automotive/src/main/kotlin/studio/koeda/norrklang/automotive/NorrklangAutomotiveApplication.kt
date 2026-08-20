package studio.koeda.norrklang.automotive

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import studio.koeda.norrklang.data.diagnostics.Diagnostics

@HiltAndroidApp
class NorrklangAutomotiveApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Cars give end users no adb/logcat and rarely report Play vitals —
        // capture crashes on-device so the settings screen can show them.
        Diagnostics.install(this)
    }
}
