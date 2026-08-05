package studio.koeda.norrklang.automotive

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.ui.settings.SettingsScreen
import studio.koeda.norrklang.ui.settings.SettingsViewModel
import studio.koeda.norrklang.ui.theme.NorrklangTheme

/** Reached via the car's settings entry for the app (APPLICATION_PREFERENCES). */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NorrklangTheme {
                val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()

                // Close once a signed-in session ends (sign-out from this
                // screen landing). Latching on Connected keeps settings open
                // when launched while already signed out.
                var wasConnected by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(sessionState) {
                    when {
                        sessionState is SessionManager.SessionState.Connected -> wasConnected = true
                        sessionState is SessionManager.SessionState.SignedOut && wasConnected ->
                            finish()
                    }
                }

                SettingsScreen(
                    viewModel = viewModel,
                    onBack = ::finish,
                    onSignIn = {
                        startActivity(Intent(this, SignInActivity::class.java))
                    },
                )
            }
        }
    }
}
