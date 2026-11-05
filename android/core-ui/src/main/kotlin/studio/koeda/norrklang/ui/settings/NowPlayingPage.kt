package studio.koeda.norrklang.ui.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.koeda.norrklang.data.settings.TrackSubtitle
import studio.koeda.norrklang.ui.R

/**
 * Now-playing layout sub-page: the line under a song's title (see
 * [TrackSubtitle]) and which line carries a radio stream's song. Both apply
 * at once, the playing item included.
 *
 * The radio choice is a boolean underneath but is shown as two radio options
 * so the two sections read the same way.
 */
@Composable
internal fun NowPlayingPage(
    trackSubtitle: TrackSubtitle,
    radioStationFirst: Boolean,
    onTrackSubtitleSelected: (TrackSubtitle) -> Unit,
    onRadioStationFirstSelected: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.settings_now_playing),
        onBack = onBack,
        modifier = modifier,
        hint = stringResource(R.string.settings_now_playing_page_hint),
    ) {
        OptionSection(title = stringResource(R.string.settings_now_playing_songs)) {
            TrackSubtitle.entries.forEach { subtitle ->
                OptionRow(
                    label = stringResource(subtitle.labelRes()),
                    hint = stringResource(
                        if (subtitle == TrackSubtitle.ARTIST) {
                            R.string.settings_track_subtitle_artist_hint
                        } else {
                            R.string.settings_track_subtitle_album_hint
                        },
                    ),
                    selected = subtitle == trackSubtitle,
                    onSelected = { onTrackSubtitleSelected(subtitle) },
                )
            }
        }
        HorizontalDivider()
        OptionSection(title = stringResource(R.string.settings_now_playing_radio)) {
            OptionRow(
                label = stringResource(R.string.settings_radio_song_first),
                hint = stringResource(R.string.settings_radio_song_first_hint),
                selected = !radioStationFirst,
                onSelected = { onRadioStationFirstSelected(false) },
            )
            OptionRow(
                label = stringResource(R.string.settings_radio_station_first),
                hint = stringResource(R.string.settings_radio_station_first_hint),
                selected = radioStationFirst,
                onSelected = { onRadioStationFirstSelected(true) },
            )
        }
    }
}

internal fun TrackSubtitle.labelRes(): Int = when (this) {
    TrackSubtitle.ARTIST -> R.string.settings_track_subtitle_artist
    TrackSubtitle.ARTIST_ALBUM -> R.string.settings_track_subtitle_artist_album
    TrackSubtitle.ALBUM_ARTIST -> R.string.settings_track_subtitle_album_artist
}
