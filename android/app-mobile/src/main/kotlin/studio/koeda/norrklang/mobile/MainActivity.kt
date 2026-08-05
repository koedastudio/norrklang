package studio.koeda.norrklang.mobile

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.media.NorrklangMediaLibraryService
import studio.koeda.norrklang.ui.settings.SettingsViewModel
import studio.koeda.norrklang.ui.signin.SignInScreen
import studio.koeda.norrklang.ui.signin.SignInViewModel
import studio.koeda.norrklang.ui.theme.NorrklangTheme

/**
 * Phone companion for Android Auto. Sign-in happens here (a projected head unit
 * cannot host the sign-in flow); the screen also shows connection status and a
 * small now-playing strip as proof the media session is alive.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val signInViewModel: SignInViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NorrklangTheme {
                val sessionState by settingsViewModel.sessionState.collectAsStateWithLifecycle()

                when (sessionState) {
                    is SessionManager.SessionState.Connected -> HomeWithController()
                    else -> SignInScreen(viewModel = signInViewModel, onSignedIn = {})
                }
            }
        }
    }

    @Composable
    private fun HomeWithController() {
        var controller by remember { mutableStateOf<MediaController?>(null) }

        DisposableEffect(Unit) {
            val token = SessionToken(
                this@MainActivity,
                ComponentName(this@MainActivity, NorrklangMediaLibraryService::class.java),
            )
            val future = MediaController.Builder(this@MainActivity, token).buildAsync()
            future.addListener({
                // Listeners fire on cancellation too (releaseFuture in
                // onDispose), where get() would throw.
                if (!future.isCancelled) controller = future.get()
            }, MoreExecutors.directExecutor())
            onDispose {
                MediaController.releaseFuture(future)
                controller = null
            }
        }

        HomeScreen(
            settingsViewModel = settingsViewModel,
            controller = controller,
        )
    }
}
