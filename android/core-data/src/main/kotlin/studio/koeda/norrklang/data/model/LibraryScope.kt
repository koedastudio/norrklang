package studio.koeda.norrklang.data.model

/**
 * The libraries a browse/search/mix call is scoped to: the server's list
 * minus the user's excluded ids (see ServerSettingsRepository.excludedLibraryIds).
 */
data class LibraryScope(
    val libraries: List<MusicLibrary>,
    val selected: List<MusicLibrary>,
) {
    val selectedIds: List<String> get() = selected.map { it.id }

    /** Nothing is effectively excluded — Subsonic then omits musicFolderId. */
    val isAll: Boolean get() = selected.size == libraries.size

    /** Stable cache-key segment: "all" or the sorted selected ids joined by "+". */
    val key: String get() = if (isAll) ALL_KEY else selectedIds.sorted().joinToString("+")

    companion object {
        const val ALL_KEY = "all"

        /**
         * Stale ids (no longer on the server) are ignored; an exclusion that
         * would leave nothing selected falls back to all.
         */
        fun resolve(libraries: List<MusicLibrary>, excludedIds: Set<String>): LibraryScope {
            val kept = libraries.filterNot { it.id in excludedIds }
            return LibraryScope(libraries, if (kept.isEmpty()) libraries else kept)
        }

        /** Key from the raw setting alone (no server round-trip) — mix fingerprints, tile versions. */
        fun exclusionKey(excludedIds: Set<String>): String =
            if (excludedIds.isEmpty()) ALL_KEY else excludedIds.sorted().joinToString("+")
    }
}
