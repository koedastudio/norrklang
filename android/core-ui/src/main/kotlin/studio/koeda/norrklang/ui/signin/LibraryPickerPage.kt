package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.LibraryChecklist

/**
 * The sign-in step shown when the server has several music libraries: all
 * pre-selected, "Continue" while at least one is. Same scaffold as
 * [SignInScreen]; parked-only on AAOS like the rest of sign-in.
 */
@Composable
internal fun LibraryPickerPage(
    libraries: List<MusicLibrary>,
    selectedIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onContinue: () -> Unit,
    continuing: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormPage(modifier = modifier, onBack = onBack) {
        FormTitle(stringResource(R.string.signin_pick_libraries_title))
        FormSubtitle(stringResource(R.string.signin_pick_libraries_subtitle))
        LibraryChecklist(
            libraries = libraries,
            selectedIds = selectedIds,
            onToggle = onToggle,
        )
        FormButton(
            text = stringResource(R.string.signin_continue),
            onClick = onContinue,
            enabled = selectedIds.isNotEmpty(),
            busy = continuing,
        )
    }
}
