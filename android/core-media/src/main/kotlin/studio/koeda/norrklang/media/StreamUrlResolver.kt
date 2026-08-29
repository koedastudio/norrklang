package studio.koeda.norrklang.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import java.io.IOException
import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.settings.StreamQuality

/**
 * Turns the canonical `norrklang-stream://` URIs queued in MediaItems (see
 * [StreamRef]) into real server URLs when ExoPlayer opens the load — so every
 * load picks its quality from the network the car is on *now*, not the one it
 * was on when the queue was built. Quality/network fields are pushed in by
 * the service's collectors; loads happen on ExoPlayer's loader threads.
 *
 * Non-canonical URIs (tests, legacy queues) pass through untouched.
 */
@UnstableApi
internal class StreamUrlResolver(
    private val currentSession: () -> ProviderSession?,
) : ResolvingDataSource.Resolver {

    @Volatile var wifiQuality: StreamQuality = StreamQuality.DEFAULT_WIFI

    @Volatile var cellularQuality: StreamQuality = StreamQuality.DEFAULT_CELLULAR

    @Volatile var onCellular: Boolean = false

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val resolved = resolveUrl(dataSpec.uri.toString()) ?: return dataSpec
        return dataSpec.buildUpon().setUri(Uri.parse(resolved)).build()
    }

    /**
     * Null when [uri] is not a canonical stream URI. An [IOException] (the
     * only failure ExoPlayer treats as a load error rather than a crash)
     * covers the signed-out gap and a queue left over from another provider.
     */
    fun resolveUrl(uri: String): String? {
        val ref = StreamRef.parse(uri) ?: return null
        val session = currentSession()
            ?: throw IOException("No signed-in session to resolve the stream")
        if (session.provider != ref.provider) {
            throw IOException("Track queued from ${ref.provider}, signed in to ${session.provider}")
        }
        val quality = if (onCellular) cellularQuality else wifiQuality
        return try {
            session.streamUrl(ref, quality.maxKbps)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unresolvable stream reference", e)
        }
    }
}
