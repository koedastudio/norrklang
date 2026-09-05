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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.components.QrCode
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/** Which page of the settings UI is showing; sub-pages return to [Main]. */
private enum class SettingsPage { Main, Quality, ScrobbleArtists, ScrobblePlaylists, Diagnostics }

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
    val scrobble by viewModel.scrobbleSettings.collectAsStateWithLifecycle()
    val dimens = LocalFormDimens.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }

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
    if (page != SettingsPage.Main) {
        ScrobbleExclusionPage(
            page = page,
            viewModel = viewModel,
            scrobble = scrobble,
            onBack = { page = SettingsPage.Main },
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
                AccountHeader(
                    title = s.session.accountLabel,
                    subtitle = s.session.serverLabel,
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
                ExclusionRow(
                    title = stringResource(R.string.settings_scrobble_artists),
                    subtitle = stringResource(R.string.settings_scrobble_artists_hint),
                    excludedCount = scrobble.excludedArtistIds.size,
                    onClick = { page = SettingsPage.ScrobbleArtists },
                )
                HorizontalDivider()
                ExclusionRow(
                    title = stringResource(R.string.settings_scrobble_playlists),
                    subtitle = stringResource(R.string.settings_scrobble_playlists_hint),
                    excludedCount = scrobble.excludedPlaylistIds.size,
                    onClick = { page = SettingsPage.ScrobblePlaylists },
                )
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

/** Routes the non-[SettingsPage.Main] pages to their exclusion picker. */
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
        SettingsPage.Main, SettingsPage.Quality, SettingsPage.Diagnostics -> Unit
    }
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
            ReportQrSection(reportUrl)
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
 * The QR hand-off: the phone's camera opens norrklang.app/report with the
 * trimmed log in the URL fragment (nothing is sent from the car; fragments
 * never reach the server), and that page offers a prefilled GitHub issue.
 */
@Composable
private fun ReportQrSection(reportUrl: String) {
    val dimens = LocalFormDimens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        QrCode(
            content = reportUrl,
            contentDescription = stringResource(R.string.settings_diagnostics_report_title),
            modifier = Modifier.size(dimens.controlMinHeight * 3),
        )
        Column(modifier = Modifier.padding(start = dimens.itemSpacing)) {
            Text(
                text = stringResource(R.string.settings_diagnostics_report_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.settings_diagnostics_report_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
