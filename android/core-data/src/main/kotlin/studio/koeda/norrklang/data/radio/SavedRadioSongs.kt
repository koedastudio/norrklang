package studio.koeda.norrklang.data.radio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A song noted from a radio stream's metadata; [title] is the raw ICY text. */
data class SavedRadioSong(
    val savedAtMs: Long,
    val stationName: String,
    val title: String,
)

/**
 * The songs the user hearted while listening to internet radio, newest first.
 * The server keeps nothing about radio, so the list lives on this device: a
 * tab-separated file under filesDir, capped at [MAX_SONGS], and it survives a
 * sign-out (it holds titles and station names, not server ids).
 */
@Singleton
class SavedRadioSongs(private val file: File) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, "radio/saved-songs.tsv"))

    private val mutex = Mutex()
    private val state = MutableStateFlow<List<SavedRadioSong>?>(null)

    /** Newest first; reads the file on first collection. */
    val songs: Flow<List<SavedRadioSong>> = state.onStart { loaded() }.filterNotNull()

    suspend fun isSaved(stationName: String, title: String): Boolean {
        val key = SavedRadioSong(0, clean(stationName), clean(title))
        return loaded().any { it.matches(key) }
    }

    /**
     * Saves the song, or removes every entry for it when already saved (the
     * heart's toggle); returns the new saved state.
     */
    suspend fun toggle(
        stationName: String,
        title: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = mutex.withLock {
        val song = SavedRadioSong(nowMs, clean(stationName), clean(title))
        val current = loadedLocked()
        val saved = current.none { it.matches(song) }
        val next = if (saved) {
            (listOf(song) + current).take(MAX_SONGS)
        } else {
            current.filterNot { it.matches(song) }
        }
        write(next)
        saved
    }

    suspend fun remove(song: SavedRadioSong) = mutex.withLock {
        write(loadedLocked().filterNot { it == song })
    }

    suspend fun clear() = mutex.withLock { write(emptyList()) }

    private suspend fun loaded(): List<SavedRadioSong> =
        state.value ?: mutex.withLock { loadedLocked() }

    private suspend fun loadedLocked(): List<SavedRadioSong> =
        state.value ?: withContext(Dispatchers.IO) { read() }.also { state.value = it }

    /** Disk first, then memory: a failed write must not leave a phantom entry. */
    private suspend fun write(songs: List<SavedRadioSong>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(songs.joinToString("") { "${it.savedAtMs}\t${it.stationName}\t${it.title}\n" })
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        state.value = songs
    }

    private fun read(): List<SavedRadioSong> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                val savedAt = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val station = parts.getOrNull(1) ?: return@mapNotNull null
                val title = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                SavedRadioSong(savedAt, station, title)
            }.distinct().sortedByDescending { it.savedAtMs }.take(MAX_SONGS)
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun SavedRadioSong.matches(other: SavedRadioSong) =
        title.equals(other.title, ignoreCase = true) &&
            stationName.equals(other.stationName, ignoreCase = true)

    companion object {
        /** Oldest entries drop off beyond this. */
        const val MAX_SONGS = 500

        /** One line per song in the file, so line breaks and tabs become spaces. */
        internal fun clean(text: String): String =
            text.replace(Regex("[\\t\\r\\n]+"), " ").trim()
    }
}
