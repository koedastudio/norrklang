package studio.koeda.norrklang.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import studio.koeda.norrklang.data.diagnostics.Diagnostics

@HiltAndroidApp
class NorrklangMobileApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Same on-device crash capture as the automotive app: the settings
        // screen renders what it records (see Diagnostics).
        Diagnostics.install(this)
    }
}
