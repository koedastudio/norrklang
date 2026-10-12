package studio.koeda.norrklang.data.settings

/**
 * What a library track shows under its title while it plays (now-playing and
 * queue views). Some hosts — Volvo's among them — show only a title and one
 * smaller line, so the album only reaches the screen when it shares that
 * line with the artist. Browse lists are not affected.
 */
enum class TrackSubtitle(val storageValue: String) {
    ARTIST("artist"),
    ARTIST_ALBUM("artist_album"),
    ALBUM_ARTIST("album_artist"),
    ;

    /**
     * The composed line, or null when this choice needs an album the track
     * lacks — the caller then falls back to the plain artist line.
     */
    fun line(artist: String?, album: String?): String? {
        if (this == ARTIST || album.isNullOrBlank()) return null
        if (artist.isNullOrBlank()) return album
        return if (this == ARTIST_ALBUM) "$artist$SEPARATOR$album" else "$album$SEPARATOR$artist"
    }

    companion object {
        val DEFAULT = ARTIST

        private const val SEPARATOR = " · "

        /** Null for unknown stored values (downgrades) — caller falls back. */
        fun fromStorageValue(value: String): TrackSubtitle? =
            entries.firstOrNull { it.storageValue == value }
    }
}
