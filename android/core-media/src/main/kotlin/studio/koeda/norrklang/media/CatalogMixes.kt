package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The random genre and decade mixes: behind the Shuffle tile of a genre or
 * decade album grid, and behind voice genre requests. One instance per
 * process (see MediaModule), so every path serves the same list.
 */
internal typealias CatalogMixes = MixQueues<MediaId.CatalogMix>

internal fun CatalogMixes(repository: MusicRepository): CatalogMixes =
    MixQueues(repository) { mix ->
        when (mix) {
            is MediaId.GenreMix -> repository.randomTracksByGenre(mix.name, CATALOG_MIX_SIZE)
            is MediaId.DecadeMix ->
                repository.randomTracksByYearRange(mix.startYear, mix.startYear + 9, CATALOG_MIX_SIZE)
        }
    }

internal const val CATALOG_MIX_SIZE = 50
