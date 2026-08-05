package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The resumption-queue rule shared by the generated mixes: the saved track
 * first (a freshly generated mix almost never contains it), then [fresh]
 * minus any duplicate of it — resume where you left off, flowing into new
 * music. A saved track the server no longer knows yields just [fresh].
 */
internal suspend fun savedTrackFirst(
    repository: MusicRepository,
    savedTrackId: String,
    fresh: List<Track>,
): List<Track> {
    val saved = runCatching { repository.track(savedTrackId) }.getOrNull() ?: return fresh
    return listOf(saved) + fresh.filterNot { it.id == saved.id }
}
