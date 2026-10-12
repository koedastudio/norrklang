package studio.koeda.norrklang.data.settings

/**
 * What a playing station shows on the line that hosts like Volvo's draw in
 * a large font: the stream's song (as before) or the station's name. With
 * the station on top, the song moves to the smaller second line, where a
 * long "artist - title" fits instead of being cut off.
 */
enum class RadioTitle(val storageValue: String) {
    SONG("song"),
    STATION("station"),
    ;

    /** The first line's text when it differs from the item's title, else null. */
    fun displayTitle(station: String?): String? = if (this == STATION) station else null

    /**
     * The second line: the station under the song, or the song under the
     * station. [song] is null until the stream names one; the station's own
     * name stands in.
     */
    fun lineTwo(station: String?, song: String?): String? =
        if (this == STATION) song ?: station else station

    companion object {
        val DEFAULT = SONG

        /** Null for unknown stored values (downgrades) — caller falls back. */
        fun fromStorageValue(value: String): RadioTitle? =
            entries.firstOrNull { it.storageValue == value }
    }
}
