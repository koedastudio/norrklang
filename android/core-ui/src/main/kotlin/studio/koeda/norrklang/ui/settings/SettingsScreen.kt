package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.settings.SettingsViewModel.PickerState
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * Which page of the settings UI is showing. Sub-pages return to [Main],
 * except the three exclusion pickers, which return to [ScrobbleExceptions].
 */
private enum class SettingsPage {
    Main, Libraries, Quality, NowPlaying, ScrobbleExceptions, ScrobbleArtists, ScrobblePlaylists, ScrobbleLibraries,
    SavedSongs, Diagnostics, About,
}

/**
 * Server info + sign-out, reached via the car's APPLICATION_PREFERENCES entry.
 *
 * Sign-out completion is not signalled here — hosts that must close on
 * sign-out observe [SettingsViewModel.sessionState] instead.
 *
 * [onBack] renders an explicit back affordance — required on AAOS, where the
 * system bar offers no reliable way to leave a full-screen activity.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onSignIn: (() -> Unit)? = null,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    val scrobble by viewModel.scrobbleSettings.collectAsStateWithLifecycle()
    val toMain = { page = SettingsPage.Main }
    val toExceptions = { page = SettingsPage.ScrobbleExceptions }

    when (page) {
        SettingsPage.Main -> MainPage(
            viewModel = viewModel,
            scrobble = scrobble,
            onBack = onBack,
            onSignIn = onSignIn,
            navigate = { page = it },
            modifier = modifier,
        )

        SettingsPage.Libraries -> {
            LaunchedEffect(Unit) { viewModel.loadLibraryPicker() }
            val picker by viewModel.libraryPicker.collectAsStateWithLifecycle()
            val excluded by viewModel.excludedLibraryIds.collectAsStateWithLifecycle()
            LibrariesPage(
                state = picker,
                excludedIds = excluded,
                onToggle = viewModel::setLibrarySelected,
                onRetry = { viewModel.loadLibraryPicker(force = true) },
                onBack = toMain,
                modifier = modifier,
            )
        }

        SettingsPage.Quality -> {
            val qualityWifi by viewModel.qualityWifi.collectAsStateWithLifecycle()
            val qualityCellular by viewModel.qualityCellular.collectAsStateWithLifecycle()
            StreamQualityPage(
                qualityWifi = qualityWifi,
                qualityCellular = qualityCellular,
                onWifiSelected = viewModel::setQualityWifi,
                onCellularSelected = viewModel::setQualityCellular,
                onBack = toMain,
                modifier = modifier,
            )
        }

        SettingsPage.NowPlaying -> {
            val trackSubtitle by viewModel.trackSubtitle.collectAsStateWithLifecycle()
            val radioStationFirst by viewModel.radioStationFirst.collectAsStateWithLifecycle()
            NowPlayingPage(
                trackSubtitle = trackSubtitle,
                radioStationFirst = radioStationFirst,
                onTrackSubtitleSelected = viewModel::setTrackSubtitle,
                onRadioStationFirstSelected = viewModel::setRadioStationFirst,
                onBack = toMain,
                modifier = modifier,
            )
        }

        SettingsPage.ScrobbleExceptions -> ScrobbleExceptionsPage(
            scrobble = scrobble,
            onArtists = { page = SettingsPage.ScrobbleArtists },
            onPlaylists = { page = SettingsPage.ScrobblePlaylists },
            onLibraries = { page = SettingsPage.ScrobbleLibraries },
            onBack = toMain,
            modifier = modifier,
        )

        SettingsPage.ScrobbleArtists -> ExclusionPicker(
            picker = viewModel.artistPicker,
            onLoad = viewModel::loadArtistPicker,
            onRetry = viewModel::loadArtistPicker,
            title = stringResource(R.string.settings_scrobble_artists),
            emptyText = stringResource(R.string.settings_picker_no_artists),
            searchPlaceholder = stringResource(R.string.settings_picker_search_artists),
            excludedIds = scrobble.excludedArtistIds,
            onToggle = viewModel::setArtistExcluded,
            onBack = toExceptions,
            modifier = modifier,
        )

        SettingsPage.ScrobblePlaylists -> ExclusionPicker(
            picker = viewModel.playlistPicker,
            onLoad = viewModel::loadPlaylistPicker,
            onRetry = viewModel::loadPlaylistPicker,
            title = stringResource(R.string.settings_scrobble_playlists),
            emptyText = stringResource(R.string.settings_picker_no_playlists),
            searchPlaceholder = null,
            excludedIds = scrobble.excludedPlaylistIds,
            onToggle = viewModel::setPlaylistExcluded,
            onBack = toExceptions,
            modifier = modifier,
        )

        SettingsPage.ScrobbleLibraries -> ExclusionPicker(
            picker = viewModel.libraryPicker,
            onLoad = { viewModel.loadLibraryPicker() },
            onRetry = { viewModel.loadLibraryPicker(force = true) },
            title = stringResource(R.string.settings_scrobble_libraries),
            emptyText = stringResource(R.string.settings_picker_no_libraries),
            searchPlaceholder = null,
            excludedIds = scrobble.excludedLibraryIds,
            onToggle = viewModel::setLibraryScrobbleExcluded,
            onBack = toExceptions,
            modifier = modifier,
        )

        SettingsPage.SavedSongs -> {
            val songs by viewModel.savedSongs.collectAsStateWithLifecycle()
            val export by viewModel.savedSongsExport.collectAsStateWithLifecycle()
            SavedSongsPage(
                songs = songs,
                export = export,
                onRemove = viewModel::removeSavedSong,
                onClear = viewModel::clearSavedSongs,
                onBack = toMain,
                modifier = modifier,
            )
        }

        SettingsPage.Diagnostics -> {
            LaunchedEffect(Unit) { viewModel.loadDiagnostics() }
            val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
            val reportUrl by viewModel.reportUrl.collectAsStateWithLifecycle()
            DiagnosticsPage(
                text = diagnostics,
                reportUrl = reportUrl,
                onClear = viewModel::clearDiagnostics,
                onBack = toMain,
                modifier = modifier,
            )
        }

        SettingsPage.About -> AboutPage(onBack = toMain, modifier = modifier)
    }
}

/** One exclusion picker page (see [ScrobbleExceptionsPage]): loads its list on entry. */
@Composable
private fun ExclusionPicker(
    picker: StateFlow<PickerState>,
    onLoad: () -> Unit,
    onRetry: () -> Unit,
    title: String,
    emptyText: String,
    searchPlaceholder: String?,
    excludedIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoad() }
    val state by picker.collectAsStateWithLifecycle()
    ScrobbleExclusionScreen(
        title = title,
        emptyText = emptyText,
        searchPlaceholder = searchPlaceholder,
        state = state,
        excludedIds = excludedIds,
        onToggle = onToggle,
        onRetry = onRetry,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun MainPage(
    viewModel: SettingsViewModel,
    scrobble: ScrobbleSettings,
    onBack: (() -> Unit)?,
    onSignIn: (() -> Unit)?,
    navigate: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val autoplaySimilar by viewModel.autoplaySimilar.collectAsStateWithLifecycle()
    val librariesSummary by viewModel.librariesSummary.collectAsStateWithLifecycle()
    val savedSongs by viewModel.savedSongs.collectAsStateWithLifecycle()
    val dimens = LocalFormDimens.current

    SettingsPageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        when (val s = state) {
            is SessionManager.SessionState.Connected -> {
                // The summary needs the server's library list; cached 5 min.
                LaunchedEffect(Unit) { viewModel.loadLibraryPicker() }
                AccountHeader(
                    title = s.session.accountLabel,
                    subtitle = s.session.serverLabel,
                )
                HorizontalDivider()
                // Which server libraries to browse (see LibraryScope); all providers.
                NavigationRow(
                    title = stringResource(R.string.settings_libraries),
                    subtitle = when (val summary = librariesSummary) {
                        SettingsViewModel.LibrariesSummary.All ->
                            stringResource(R.string.settings_libraries_all)
                        is SettingsViewModel.LibrariesSummary.Some ->
                            stringResource(
                                R.string.settings_libraries_count,
                                summary.selected,
                                summary.total,
                            )
                        SettingsViewModel.LibrariesSummary.Unknown ->
                            stringResource(R.string.settings_libraries_hint)
                    },
                    onClick = { navigate(SettingsPage.Libraries) },
                )
                HorizontalDivider()
                // Per-network quality tiers (see StreamQualityPage), applied
                // at load time by the player's resolver — all providers.
                NavigationRow(
                    title = stringResource(R.string.settings_quality),
                    subtitle = stringResource(R.string.settings_quality_hint),
                    onClick = { navigate(SettingsPage.Quality) },
                )
                HorizontalDivider()
                // Queue-end radio (see QueueRadioListener); both providers.
                SettingsToggleRow(
                    title = stringResource(R.string.settings_autoplay_similar),
                    subtitle = stringResource(
                        if (autoplaySimilar) {
                            R.string.settings_autoplay_similar_on
                        } else {
                            R.string.settings_autoplay_similar_off
                        },
                    ),
                    checked = autoplaySimilar,
                    onToggle = viewModel::setAutoplaySimilar,
                )
                HorizontalDivider()
                // Master switch for all playback reporting — Subsonic
                // scrobbles and Plex timeline/mark-played alike. Off: plays
                // update neither server play history nor the server-linked
                // Last.fm/ListenBrainz accounts; the app itself never talks to
                // those services (see PlaybackReporter).
                SettingsToggleRow(
                    title = stringResource(R.string.settings_scrobble),
                    subtitle = stringResource(
                        if (scrobble.enabled) {
                            R.string.settings_scrobble_on
                        } else {
                            R.string.settings_scrobble_off
                        },
                    ),
                    checked = scrobble.enabled,
                    onToggle = viewModel::setScrobblingEnabled,
                )
                HorizontalDivider()
                // Exclusions only matter while reporting is on; hiding them
                // otherwise also keeps the off state to a single row.
                if (scrobble.enabled) {
                    ExclusionRow(
                        title = stringResource(R.string.settings_scrobble_exceptions),
                        subtitle = stringResource(R.string.settings_scrobble_exceptions_hint),
                        excludedCount = scrobble.excludedArtistIds.size +
                            scrobble.excludedPlaylistIds.size +
                            scrobble.excludedLibraryIds.size,
                        onClick = { navigate(SettingsPage.ScrobbleExceptions) },
                    )
                    HorizontalDivider()
                }
                SavedSongsRow(
                    count = savedSongs?.size,
                    onClick = { navigate(SettingsPage.SavedSongs) },
                )
                HorizontalDivider()
                // Display tweaks for two-line car hosts (see NowPlayingPage);
                // last among the settings, as few users need them.
                NavigationRow(
                    title = stringResource(R.string.settings_now_playing),
                    subtitle = stringResource(R.string.settings_now_playing_hint),
                    onClick = { navigate(SettingsPage.NowPlaying) },
                )
                HorizontalDivider()
                InfoRows(navigate)
                Spacer(Modifier.height(dimens.itemSpacing * 2))
                SettingsActionButton(
                    text = stringResource(R.string.settings_signout),
                    onClick = viewModel::signOut,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            else -> {
                AccountHeader(
                    title = stringResource(R.string.settings_signed_out),
                    subtitle = stringResource(R.string.signin_subtitle),
                )
                HorizontalDivider()
                // The list is the device's, not the server's: still worth
                // reaching after a sign-out, but only once it has entries.
                if (!savedSongs.isNullOrEmpty()) {
                    SavedSongsRow(
                        count = savedSongs?.size,
                        onClick = { navigate(SettingsPage.SavedSongs) },
                    )
                    HorizontalDivider()
                }
                InfoRows(navigate)
                Spacer(Modifier.height(dimens.itemSpacing * 2))
                onSignIn?.let {
                    SettingsActionButton(
                        text = stringResource(R.string.settings_signin),
                        onClick = it,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        outlined = false,
                    )
                }
            }
        }

        Spacer(Modifier.height(dimens.itemSpacing))
        VersionFooter(modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/**
 * About, privacy policy and diagnostics — reachable signed out too: a
 * failure that signed the user out is exactly what the log should explain.
 */
@Composable
private fun InfoRows(navigate: (SettingsPage) -> Unit) {
    AboutRow(onClick = { navigate(SettingsPage.About) })
    HorizontalDivider()
    PrivacyPolicyRow()
    HorizontalDivider()
    DiagnosticsRow(onClick = { navigate(SettingsPage.Diagnostics) })
    HorizontalDivider()
}
