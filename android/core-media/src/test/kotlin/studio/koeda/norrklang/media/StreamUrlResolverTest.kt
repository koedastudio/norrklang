package studio.koeda.norrklang.media

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.session.MusicProvider
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.settings.StreamQuality

class StreamUrlResolverTest {

    private class FakeSession(
        override val provider: MusicProvider,
    ) : ProviderSession {
        override val accountLabel = "demo"
        override val serverLabel = "server"
        override val cacheFingerprint = "fp"
        override fun artworkUrl(artworkId: String) = "https://server/art/$artworkId"
        override fun streamUrl(ref: StreamRef, maxKbps: Int?) =
            "https://server/stream/${ref.trackId}?kbps=${maxKbps ?: "original"}"
        override fun close() = Unit
    }

    private var session: ProviderSession? = FakeSession(MusicProvider.SUBSONIC)
    private val resolver = StreamUrlResolver { session }

    private val uri = StreamRef(MusicProvider.SUBSONIC, "42").encode()

    @Test
    fun `non-canonical uris pass through as null`() {
        assertNull(resolver.createResolver().resolveUrl("https://server/stream/direct.mp3"))
    }

    @Test
    fun `resolves at original quality on the wifi tier by default`() {
        assertEquals("https://server/stream/42?kbps=original", resolver.createResolver().resolveUrl(uri))
    }

    @Test
    fun `cellular defaults to the capped tier before settings arrive`() {
        resolver.onCellular = true
        assertEquals("https://server/stream/42?kbps=320", resolver.createResolver().resolveUrl(uri))
    }

    @Test
    fun `cellular picks the cellular tier, wifi keeps its own`() {
        resolver.wifiQuality = StreamQuality.ORIGINAL
        resolver.cellularQuality = StreamQuality.HIGH

        resolver.onCellular = true
        assertEquals("https://server/stream/42?kbps=320", resolver.createResolver().resolveUrl(uri))

        resolver.onCellular = false
        assertEquals("https://server/stream/42?kbps=original", resolver.createResolver().resolveUrl(uri))
    }

    @Test
    fun `signed out resolves to a load error, not a crash`() {
        session = null
        assertFailsWith<IOException> { resolver.createResolver().resolveUrl(uri) }
    }

    @Test
    fun `a queue left over from another provider resolves to a load error`() {
        session = FakeSession(MusicProvider.PLEX)
        assertFailsWith<IOException> { resolver.createResolver().resolveUrl(uri) }
    }

    @Test
    fun `retry and seek retain the original encoding after a network or setting change`() {
        val source = resolver.createResolver()
        val firstUrl = source.resolveUrl(uri)
        resolver.onCellular = true
        resolver.cellularQuality = StreamQuality.LOW
        assertEquals(firstUrl, source.resolveUrl(uri))
        assertEquals("https://server/stream/42?kbps=128", resolver.createResolver().resolveUrl(uri))
    }

    @Test
    fun `an old source cannot resolve against a replacement account of the same provider`() {
        val source = resolver.createResolver()
        source.resolveUrl(uri)
        session = FakeSession(MusicProvider.SUBSONIC)
        assertFailsWith<IOException> { source.resolveUrl(uri) }
    }

    @Test
    fun `a source created before sign-out cannot start loading afterwards`() {
        val source = resolver.createResolver()
        session = null
        assertFailsWith<IOException> { source.resolveUrl(uri) }
    }
}
