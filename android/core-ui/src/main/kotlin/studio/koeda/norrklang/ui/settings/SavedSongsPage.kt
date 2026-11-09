package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import java.text.DateFormat
import java.util.Date
import studio.koeda.norrklang.data.radio.SavedRadioSong
import studio.koeda.norrklang.data.radio.SavedSongsExport
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.CenteredBox
import studio.koeda.norrklang.ui.components.QrLinkSection
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * The songs hearted while listening to internet radio (see SavedRadioSongs),
 * newest first, with a QR code that opens the same list on the user's phone
 * (see [SavedSongsExport]). [songs] null: still reading the list from disk.
 */
@Composable
internal fun SavedSongsPage(
    songs: List<SavedRadioSong>?,
    export: SavedSongsExport.Export?,
    onRemove: (SavedRadioSong) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalFormDimens.current
    SettingsPageScaffold(
        title = stringResource(R.string.settings_saved_songs),
        onBack = onBack,
        modifier = modifier,
        scroll = false,
    ) {
        when {
            songs == null -> CenteredBox { CircularProgressIndicator() }
            songs.isEmpty() -> CenteredBox {
                Text(
                    text = stringResource(R.string.settings_saved_songs_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = dimens.screenPadding),
                )
            }
            else -> SongList(songs, export, onRemove, onClear)
        }
    }
}

@Composable
private fun SongList(
    songs: List<SavedRadioSong>,
    export: SavedSongsExport.Export?,
    onRemove: (SavedRadioSong) -> Unit,
    onClear: () -> Unit,
) {
    val dimens = LocalFormDimens.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (export != null) {
            item(key = "export") {
                Spacer(Modifier.height(dimens.itemSpacing))
                // Nothing is sent from the car: the list rides in the URL fragment
                // that norrklang.app/songs reads; long lists are trimmed to fit.
                QrLinkSection(
                    url = export.url,
                    title = stringResource(R.string.settings_saved_songs_export_title),
                    hint = stringResource(R.string.settings_saved_songs_export_hint),
                    note = stringResource(R.string.settings_saved_songs_export_partial, export.count)
                        .takeIf { export.count < songs.size },
                )
                Spacer(Modifier.height(dimens.itemSpacing))
                HorizontalDivider()
            }
        }
        // savedAtMs is unique enough: two saves can't land in the same ms.
        items(songs, key = { it.savedAtMs }) { song ->
            SongRow(
                song = song,
                savedAt = dateFormat.format(Date(song.savedAtMs)),
                onRemove = { onRemove(song) },
            )
            HorizontalDivider()
        }
        item(key = "clear") {
            Spacer(Modifier.height(dimens.itemSpacing * 2))
            SettingsActionButton(
                text = stringResource(R.string.settings_saved_songs_clear),
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(dimens.itemSpacing))
        }
    }
}

/** One saved song: the stream's title as sent, over station and time. */
@Composable
private fun SongRow(song: SavedRadioSong, savedAt: String, onRemove: () -> Unit) {
    val dimens = LocalFormDimens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.itemSpacing / 2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = listOf(song.stationName, savedAt).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(dimens.controlMinHeight),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.settings_saved_songs_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
