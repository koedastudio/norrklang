package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import studio.koeda.norrklang.data.radio.SavedRadioSong
import studio.koeda.norrklang.data.radio.SavedSongsExport
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.components.QrCode
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                text = stringResource(R.string.settings_saved_songs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()

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
                ExportQrSection(export, total = songs.size)
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
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(min = 200.dp)
                    .heightIn(min = dimens.controlMinHeight),
            ) {
                Text(
                    text = stringResource(R.string.settings_saved_songs_clear),
                    fontWeight = FontWeight.Bold,
                )
            }
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

/**
 * The QR hand-off: the phone's camera opens norrklang.app/songs with the
 * list in the URL fragment (nothing is sent from the car; fragments never
 * reach the server). Long lists are trimmed to fit the code — say so.
 */
@Composable
private fun ExportQrSection(export: SavedSongsExport.Export, total: Int) {
    val dimens = LocalFormDimens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        QrCode(
            content = export.url,
            contentDescription = stringResource(R.string.settings_saved_songs_export_title),
            modifier = Modifier.size(dimens.controlMinHeight * 3),
        )
        Column(modifier = Modifier.padding(start = dimens.itemSpacing)) {
            Text(
                text = stringResource(R.string.settings_saved_songs_export_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.settings_saved_songs_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (export.count < total) {
                Text(
                    text = stringResource(R.string.settings_saved_songs_export_partial, export.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
