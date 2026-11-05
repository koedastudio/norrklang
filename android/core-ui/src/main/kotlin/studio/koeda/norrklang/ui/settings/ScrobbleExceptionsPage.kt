package studio.koeda.norrklang.ui.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings
import studio.koeda.norrklang.ui.R

/**
 * Hub for the play-reporting exclusions, one row per kind (see
 * [ScrobbleExclusionScreen]). Kept off the main page: most users never
 * touch these, and three rows there read as three more settings.
 */
@Composable
internal fun ScrobbleExceptionsPage(
    scrobble: ScrobbleSettings,
    onArtists: () -> Unit,
    onPlaylists: () -> Unit,
    onLibraries: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.settings_scrobble_exceptions),
        onBack = onBack,
        modifier = modifier,
        hint = stringResource(R.string.settings_scrobble_exceptions_page_hint),
    ) {
        ExclusionRow(
            title = stringResource(R.string.settings_scrobble_artists),
            subtitle = stringResource(R.string.settings_scrobble_artists_hint),
            excludedCount = scrobble.excludedArtistIds.size,
            onClick = onArtists,
        )
        HorizontalDivider()
        ExclusionRow(
            title = stringResource(R.string.settings_scrobble_playlists),
            subtitle = stringResource(R.string.settings_scrobble_playlists_hint),
            excludedCount = scrobble.excludedPlaylistIds.size,
            onClick = onPlaylists,
        )
        HorizontalDivider()
        ExclusionRow(
            title = stringResource(R.string.settings_scrobble_libraries),
            subtitle = stringResource(R.string.settings_scrobble_libraries_hint),
            excludedCount = scrobble.excludedLibraryIds.size,
            onClick = onLibraries,
        )
        HorizontalDivider()
    }
}
