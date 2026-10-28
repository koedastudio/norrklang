package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.session.SubsonicSession
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.components.QrCode
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * Which page of the settings UI is showing. Sub-pages return to [Main],
 * except the three exclusion pickers, which return to [ScrobbleExceptions].
 */
private enum class SettingsPage {
    Main, Libraries, Quality, NowPlaying, Mtls, ScrobbleExceptions, ScrobbleArtists, ScrobblePlaylists,
    ScrobbleLibraries, SavedSongs, Diagnostics, About,
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
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val qualityWifi by viewModel.qualityWifi.collectAsStateWithLifecycle()
    val qualityCellular by viewModel.qualityCellular.collectAsStateWithLifecycle()
    val autoplaySimilar by viewModel.autoplaySimilar.collectAsStateWithLifecycle()
    val radioStationFirst by viewModel.radioStationFirst.collectAsStateWithLifecycle()
    val trackSubtitle by viewModel.trackSubtitle.collectAsStateWithLifecycle()
    val scrobble by viewModel.scrobbleSettings.collectAsStateWithLifecycle()
    val librariesSummary by viewModel.librariesSummary.collectAsStateWithLifecycle()
    val savedSongs by viewModel.savedSongs.collectAsStateWithLifecycle()
    val dimens = LocalFormDimens.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }

    if (page == SettingsPage.SavedSongs) {
        val export by viewModel.savedSongsExport.collectAsStateWithLifecycle()
        SavedSongsPage(
            songs = savedSongs,
            export = export,
            onRemove = viewModel::removeSavedSong,
            onClear = viewModel::clearSavedSongs,
            onBack = { page = SettingsPage.Main },
            modifier = modifier,
        )
        return
    }

    if (page == SettingsPage.Diagnostics) {
        LaunchedEffect(Unit) { viewModel.loadDiagnostics() }
        val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
        val reportUrl by viewModel.reportUrl.collectAsStateWithLifecycle()
        DiagnosticsPage(
            text = diagnostics,
            reportUrl = reportUrl,
            onClear = viewModel::clearDiagnostics,
            onBack = { page = SettingsPage.Main },
            modifier = modifier,
        )
        return
    }
    if (page == SettingsPage.About) {
        AboutPage(onBack = { page = SettingsPage.Main }, modifier = modifier)
        return
    }
    if (page == SettingsPage.Libraries) {
        LaunchedEffect(Unit) { viewModel.loadLibraryPicker() }
        val picker by viewModel.libraryPicker.collectAsStateWithLifecycle()
        val excluded by viewModel.excludedLibraryIds.collectAsStateWithLifecycle()
        LibrariesPage(
            state = picker,
            excludedIds = excluded,
            onToggle = viewModel::setLibrarySelected,
            onRetry = { viewModel.loadLibraryPicker(force = true) },
            onBack = { page = SettingsPage.Main },
            modifier = modifier,
        )
        return
    }
    if (page == SettingsPage.Quality) {
        StreamQualityPage(
            qualityWifi = qualityWifi,
            qualityCellular = qualityCellular,
            onWifiSelected = viewModel::setQualityWifi,
            onCellularSelected = viewModel::setQualityCellular,
            onBack = { page = SettingsPage.Main },
            modifier = modifier,
        )
        return
    }
    if (page == SettingsPage.NowPlaying) {
        NowPlayingPage(
            trackSubtitle = trackSubtitle,
            radioStationFirst = radioStationFirst,
            onTrackSubtitleSelected = viewModel::setTrackSubtitle,
            onRadioStationFirstSelected = viewModel::setRadioStationFirst,
            onBack = { page = SettingsPage.Main },
            modifier = modifier,
        )
        return
    }
    if (page == SettingsPage.Mtls) {
        val mtlsViewModel: MtlsViewModel = viewModel()
        MtlsPage(
            viewModel = mtlsViewModel,
            onBack = { page = SettingsPage.Main },
            modifier = modifier,
        )
        return
    }
    if (page == SettingsPage.ScrobbleExceptions) {
        ScrobbleExceptionsPage(
            scrobble = scrobble,
            onArtists = { page = SettingsPage.ScrobbleArtists },
            onPlaylists = { page = SettingsPage.ScrobblePlaylists },
            onLibraries = { page = SettingsPage.ScrobbleLibraries },
            onBack = { page = SettingsPage.Main },
            modifier = modifier,
        )
        return
    }
    if (page != SettingsPage.Main) {
        ScrobbleExclusionPage(
            page = page,
            viewModel = viewModel,
            scrobble = scrobble,
            onBack = { page = SettingsPage.ScrobbleExceptions },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                BackButton(onBack)
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = if (onBack != null) 8.dp else 0.dp),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()

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
                    onClick = { page = SettingsPage.Libraries },
                )
                HorizontalDivider()
                // Per-network quality tiers (see StreamQualityPage), applied
                // at load time by the player's resolver — all providers.
                NavigationRow(
                    title = stringResource(R.string.settings_quality),
                    subtitle = stringResource(R.string.settings_quality_hint),
                    onClick = { page = SettingsPage.Quality },
                )
                HorizontalDivider()
                // mTLS: the server authenticates the connection by client
                // certificate before any request — Subsonic only (Plex and
                // Jellyfin have no client-cert story).
                if (s.session is SubsonicSession) {
                    NavigationRow(
                        title = stringResource(R.string.settings_mtls),
                        subtitle = stringResource(R.string.settings_mtls_hint),
                        onClick = { page = SettingsPage.Mtls },
                    )
                    HorizontalDivider()
                }
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
                        onClick = { page = SettingsPage.ScrobbleExceptions },
                    )
                    HorizontalDivider()
                }
                SavedSongsRow(
                    count = savedSongs?.size,
                    onClick = { page = SettingsPage.SavedSongs },
                )
                HorizontalDivider()
                // Display tweaks for two-line car hosts (see NowPlayingPage);
                // last among the settings, as few users need them.
                NavigationRow(
                    title = stringResource(R.string.settings_now_playing),
                    subtitle = stringResource(R.string.settings_now_playing_hint),
                    onClick = { page = SettingsPage.NowPlaying },
                )
                HorizontalDivider()
                AboutRow(onClick = { page = SettingsPage.About })
                HorizontalDivider()
                PrivacyPolicyRow()
                HorizontalDivider()
                DiagnosticsRow(onClick = { page = SettingsPage.Diagnostics })
                HorizontalDivider()
                Spacer(Modifier.height(dimens.itemSpacing * 2))
                OutlinedButton(
                    onClick = viewModel::signOut,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(min = 200.dp)
                        .heightIn(min = dimens.controlMinHeight),
                ) {
                    Text(
                        text = stringResource(R.string.settings_signout),
                        fontWeight = FontWeight.Bold,
                    )
                }
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
                        onClick = { page = SettingsPage.SavedSongs },
                    )
                    HorizontalDivider()
                }
                AboutRow(onClick = { page = SettingsPage.About })
                HorizontalDivider()
                PrivacyPolicyRow()
                HorizontalDivider()
                // Reachable signed out too: a failure that signed the user
                // out is exactly what the log should explain.
                DiagnosticsRow(onClick = { page = SettingsPage.Diagnostics })
                HorizontalDivider()
                Spacer(Modifier.height(dimens.itemSpacing * 2))
                onSignIn?.let {
                    Button(
                        onClick = it,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(min = 200.dp)
                            .heightIn(min = dimens.controlMinHeight),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_signin),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(dimens.itemSpacing))
        VersionFooter(modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/** Routes the three exclusion-picker pages (see [ScrobbleExceptionsPage]). */
@Composable
private fun ScrobbleExclusionPage(
    page: SettingsPage,
    viewModel: SettingsViewModel,
    scrobble: ScrobbleSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        SettingsPage.ScrobbleArtists -> {
            LaunchedEffect(Unit) { viewModel.loadArtistPicker() }
            val picker by viewModel.artistPicker.collectAsStateWithLifecycle()
            ScrobbleExclusionScreen(
                title = stringResource(R.string.settings_scrobble_artists),
                emptyText = stringResource(R.string.settings_picker_no_artists),
                searchPlaceholder = stringResource(R.string.settings_picker_search_artists),
                state = picker,
                excludedIds = scrobble.excludedArtistIds,
                onToggle = viewModel::setArtistExcluded,
                onRetry = viewModel::loadArtistPicker,
                onBack = onBack,
                modifier = modifier,
            )
        }
        SettingsPage.ScrobblePlaylists -> {
            LaunchedEffect(Unit) { viewModel.loadPlaylistPicker() }
            val picker by viewModel.playlistPicker.collectAsStateWithLifecycle()
            ScrobbleExclusionScreen(
                title = stringResource(R.string.settings_scrobble_playlists),
                emptyText = stringResource(R.string.settings_picker_no_playlists),
                searchPlaceholder = null,
                state = picker,
                excludedIds = scrobble.excludedPlaylistIds,
                onToggle = viewModel::setPlaylistExcluded,
                onRetry = viewModel::loadPlaylistPicker,
                onBack = onBack,
                modifier = modifier,
            )
        }
        SettingsPage.ScrobbleLibraries -> {
            LaunchedEffect(Unit) { viewModel.loadLibraryPicker() }
            val picker by viewModel.libraryPicker.collectAsStateWithLifecycle()
            ScrobbleExclusionScreen(
                title = stringResource(R.string.settings_scrobble_libraries),
                emptyText = stringResource(R.string.settings_picker_no_libraries),
                searchPlaceholder = null,
                state = picker,
                excludedIds = scrobble.excludedLibraryIds,
                onToggle = viewModel::setLibraryScrobbleExcluded,
                onRetry = { viewModel.loadLibraryPicker(force = true) },
                onBack = onBack,
                modifier = modifier,
            )
        }
        SettingsPage.Main, SettingsPage.Libraries, SettingsPage.Quality, SettingsPage.NowPlaying,
        SettingsPage.Mtls, SettingsPage.ScrobbleExceptions, SettingsPage.SavedSongs,
        SettingsPage.Diagnostics, SettingsPage.About -> Unit
    }
}

/** Navigation row into the songs hearted on radio (see SavedSongsPage). */
@Composable
private fun SavedSongsRow(count: Int?, onClick: () -> Unit) {
    NavigationRow(
        title = stringResource(R.string.settings_saved_songs),
        subtitle = if (count == null || count == 0) {
            stringResource(R.string.settings_saved_songs_hint)
        } else {
            pluralStringResource(R.plurals.settings_saved_songs_count, count, count)
        },
        onClick = onClick,
    )
}

/**
 * Read-only dump of [studio.koeda.norrklang.data.diagnostics.Diagnostics] —
 * the last crash and recent errors — so a user can read a failure back to us
 * from the car, where nothing else (logcat, Play vitals) is available.
 */
@Composable
private fun DiagnosticsPage(
    text: String,
    reportUrl: String?,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalFormDimens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                text = stringResource(R.string.settings_diagnostics),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()
        Spacer(Modifier.height(dimens.itemSpacing))
        if (reportUrl != null) {
            // Nothing is sent from the car: the trimmed log rides in the URL
            // fragment, and norrklang.app/report offers a prefilled GitHub issue.
            QrLinkSection(
                url = reportUrl,
                title = stringResource(R.string.settings_diagnostics_report_title),
                hint = stringResource(R.string.settings_diagnostics_report_hint),
            )
            Spacer(Modifier.height(dimens.itemSpacing))
            HorizontalDivider()
            Spacer(Modifier.height(dimens.itemSpacing))
        }
        if (text.isBlank()) {
            Text(
                text = stringResource(R.string.settings_diagnostics_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(dimens.itemSpacing * 2))
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(min = 200.dp)
                    .heightIn(min = dimens.controlMinHeight),
            ) {
                Text(
                    text = stringResource(R.string.settings_diagnostics_clear),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(dimens.itemSpacing))
    }
}

/**
 * Website pointer. Deliberately says nothing about tips or sponsorship:
 * Play's payments policy bars in-app calls to action towards outside payment
 * methods, so the ask lives on norrklang.app only.
 */
@Composable
private fun AboutPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = LocalFormDimens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                text = stringResource(R.string.settings_about),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()
        Spacer(Modifier.height(dimens.itemSpacing))
        QrLinkSection(
            url = WEBSITE_URL,
            title = stringResource(R.string.settings_about_qr_title),
            hint = stringResource(R.string.settings_about_qr_hint),
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()
        Spacer(Modifier.height(dimens.itemSpacing))
        Text(
            text = stringResource(R.string.settings_about_license),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = GITHUB_URL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        VersionFooter()
        Spacer(Modifier.height(dimens.itemSpacing))
    }
}

/**
 * QR hand-off to the phone's camera, with the URL in the QR only — cars have
 * no browser to tap through to.
 */
@Composable
private fun QrLinkSection(url: String, title: String, hint: String) {
    val dimens = LocalFormDimens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        QrCode(
            content = url,
            contentDescription = title,
            modifier = Modifier.size(dimens.controlMinHeight * 3),
        )
        Column(modifier = Modifier.padding(start = dimens.itemSpacing)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
