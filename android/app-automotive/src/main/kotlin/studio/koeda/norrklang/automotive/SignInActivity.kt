package studio.koeda.norrklang.automotive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import studio.koeda.norrklang.ui.signin.SignInScreen
import studio.koeda.norrklang.ui.signin.SignInViewModel
import studio.koeda.norrklang.ui.theme.NorrklangTheme

/**
 * Car-screen sign-in. Launched by the OS from the media UI's error-resolution
 * "Sign in" affordance while parked.
 *
 * Deliberately has NO launcher intent-filter and NO distractionOptimized
 * meta-data — both are Play car-review rejection triggers for media apps.
 */
@AndroidEntryPoint
class SignInActivity : ComponentActivity() {

    private val viewModel: SignInViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NorrklangTheme {
                SignInScreen(viewModel = viewModel, onSignedIn = ::finish, onBack = ::finish)
            }
        }
    }
}
