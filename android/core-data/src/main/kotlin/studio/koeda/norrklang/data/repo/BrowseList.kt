package studio.koeda.norrklang.data.repo

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.model.Track

/**
 * The browse lists kept on disk between drives (see [SnapshotStore]), named
 * so a background refresh can say which list changed.
 */
enum class BrowseList(internal val fileName: String) {
    LIBRARIES("libraries"),
    RADIO_STATIONS("radio-stations"),
    PLAYLISTS("playlists"),
    ARTISTS("artists"),
    RECENTLY_ADDED_ALBUMS("recently-added-albums"),
    RECENTLY_PLAYED_ALBUMS("recently-played-albums"),
    FAVORITE_ALBUMS("favorite-albums"),
    FAVORITE_ARTISTS("favorite-artists"),
    FAVORITE_TRACKS("favorite-tracks"),

    /** What the track heart reads; stored so it is right without signal too. */
    FAVORITE_TRACK_IDS("favorite-track-ids"),
    RECENTLY_ADDED_TRACKS("recently-added-tracks"),
}

/** Serializers for the list shapes [BrowseList] stores. */
internal object BrowseListSerializers {
    val albums = ListSerializer(Album.serializer())
    val artists = ListSerializer(Artist.serializer())
    val tracks = ListSerializer(Track.serializer())
    val playlists = ListSerializer(Playlist.serializer())
    val stations = ListSerializer(RadioStation.serializer())
    val libraries = ListSerializer(MusicLibrary.serializer())
    val ids: KSerializer<Set<String>> = SetSerializer(String.serializer())
}
