package studio.koeda.norrklang.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import java.io.IOException
import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.settings.StreamQuality

/** Shares current quality preferences, but gives each playback data source its own resolver. */
@UnstableApi
internal class StreamUrlResolver(
    private val currentSession: () -> ProviderSession?,
) {
    @Volatile var wifiQuality: StreamQuality = StreamQuality.DEFAULT_WIFI
    @Volatile var cellularQuality: StreamQuality = StreamQuality.DEFAULT_CELLULAR
    @Volatile var onCellular: Boolean = false

    fun createResolver(): StreamResolver = StreamResolver(currentSession())

    /**
     * Retries and seeks reuse this resolver and therefore the same encoded bytes.
     * The next data source (including a preloaded track) chooses its own quality.
     */
    inner class StreamResolver internal constructor(private val owner: ProviderSession?) :
        ResolvingDataSource.Resolver {
        private var pinnedUrl: Pair<String, String>? = null

        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val url = resolveUrl(dataSpec.uri.toString()) ?: return dataSpec
            return dataSpec.buildUpon().setUri(Uri.parse(url)).build()
        }

        fun resolveUrl(uri: String): String? {
            val ref = StreamRef.parse(uri) ?: return null
            val session = owner ?: throw IOException("No signed-in session to resolve the stream")
            if (currentSession() !== session) throw IOException("Playback account changed")
            if (session.provider != ref.provider) throw IOException("Track belongs to another provider")
            pinnedUrl?.let { (original, resolved) ->
                if (original != uri) throw IOException("Data source reused for a different track")
                return resolved
            }
            val quality = if (onCellular) cellularQuality else wifiQuality
            return try {
                session.streamUrl(ref, quality.maxKbps).also { pinnedUrl = uri to it }
            } catch (e: IllegalArgumentException) {
                throw IOException("Unresolvable stream reference", e)
            }
        }
    }
}
