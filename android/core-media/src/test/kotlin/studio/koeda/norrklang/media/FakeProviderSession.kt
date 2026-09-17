package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.session.MusicProvider
import studio.koeda.norrklang.data.session.ProviderSession

internal class FakeProviderSession : ProviderSession {
    override val provider = MusicProvider.SUBSONIC
    override val accountLabel = "test"
    override val serverLabel = "server"
    override val cacheFingerprint = "test-account"
    override fun artworkUrl(artworkId: String) = "https://server/art/$artworkId"
    override fun streamUrl(ref: StreamRef, maxKbps: Int?) = "https://server/stream/${ref.trackId}"
    override fun close() = Unit
}
