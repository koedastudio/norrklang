package studio.koeda.norrklang.data.radio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import studio.koeda.norrklang.data.diagnostics.FragmentPayload

/**
 * Builds the URL behind the "open on your phone" QR code on the saved songs
 * page: `https://norrklang.app/songs#1.<base64url(rawDeflate(text))>`, one
 * `savedAt<TAB>station<TAB>title` line per song, newest first, timestamps in
 * UTC ISO-8601. Decoded by www/src/pages/songs.astro; both sides must agree
 * on [FORMAT_VERSION].
 *
 * The list is trimmed from the oldest end until the URL fits
 * [FragmentPayload.MAX_URL_LENGTH]; the full list stays on the settings page.
 */
object SavedSongsExport {

    const val SONGS_URL = "https://norrklang.app/songs"
    const val FORMAT_VERSION = "1"

    /** The export URL and how many of [songs] (newest first) it carries; null when empty. */
    data class Export(val url: String, val count: Int)

    fun build(songs: List<SavedRadioSong>): Export? {
        if (songs.isEmpty()) return null
        var count = songs.size
        while (true) {
            val url = FragmentPayload.url(SONGS_URL, FORMAT_VERSION, compose(songs.take(count)))
            if (url.length <= FragmentPayload.MAX_URL_LENGTH || count == 1) return Export(url, count)
            // Shrink proportionally to the overshoot, and by at least one.
            val scaled = (count * (FragmentPayload.MAX_URL_LENGTH.toDouble() / url.length)).toInt()
            count = scaled.coerceIn(1, count - 1)
        }
    }

    internal fun compose(songs: List<SavedRadioSong>): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        return songs.joinToString("\n") {
            "${format.format(Date(it.savedAtMs))}\t${it.stationName}\t${it.title}"
        }
    }
}
