package studio.koeda.norrklang.media

/**
 * Paging helpers for the browse tree.
 *
 * Car hosts pass `(page, pageSize)` through `onGetChildren` and
 * `onGetSearchResult`; hosts that don't page send `pageSize == Int.MAX_VALUE`
 * (the media3 legacy bridge's "everything" request).
 */
internal object Paging {

    /** True when the host asked for an actual page rather than everything. */
    fun isPaged(pageSize: Int): Boolean = pageSize in 1 until Int.MAX_VALUE

    /** The requested page of an already-complete list. */
    fun <T> slice(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (!isPaged(pageSize)) return items
        val from = page.toLong() * pageSize
        if (page < 0 || from >= items.size) return emptyList()
        val to = minOf(items.size.toLong(), from + pageSize)
        return items.subList(from.toInt(), to.toInt())
    }

    /**
     * Fetches `[offset, offset+size)` from a paged backend whose per-request
     * size is capped at [chunkSize] (Subsonic caps `getAlbumList2` at 500).
     * `size == null` fetches everything from [offset] to the end. Stops as
     * soon as the backend returns a short chunk.
     */
    suspend fun <T> window(
        offset: Int,
        size: Int?,
        chunkSize: Int,
        fetch: suspend (offset: Int, size: Int) -> List<T>,
    ): List<T> {
        val result = mutableListOf<T>()
        var cursor = offset
        while (true) {
            val want = if (size == null) chunkSize else minOf(chunkSize, size - result.size)
            if (want <= 0) break
            val chunk = fetch(cursor, want)
            result += chunk
            if (chunk.size < want) break
            cursor += chunk.size
        }
        return result
    }
}
