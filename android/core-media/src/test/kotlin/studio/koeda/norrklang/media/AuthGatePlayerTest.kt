package studio.koeda.norrklang.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@UnstableApi
class AuthGatePlayerTest {

    /** Just enough player to register listeners and raise an error. */
    private class ErroringPlayer : FakePlayer() {
        val listeners = mutableListOf<Player.Listener>()
        var error: PlaybackException? = null

        override fun addListener(listener: Player.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: Player.Listener) {
            listeners.remove(listener)
        }

        override fun getPlayerError(): PlaybackException? = error

        fun fail(e: PlaybackException) {
            error = e
            listeners.toList().forEach {
                it.onPlayerErrorChanged(e)
                it.onPlayerError(e)
            }
        }
    }

    private class Recorder : Player.Listener {
        val seen = mutableListOf<PlaybackException?>()
        override fun onPlayerError(error: PlaybackException) { seen += error }
        override fun onPlayerErrorChanged(error: PlaybackException?) { seen += error }
    }

    private val raw = PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

    @Test
    fun `player errors reach listeners and the getter as presented`() {
        val inner = ErroringPlayer()
        val gate = AuthGatePlayer(inner) { PlaybackException("Station is down", it.cause, it.errorCode) }
        val recorder = Recorder()
        gate.addListener(recorder)

        inner.fail(raw)

        assertEquals(listOf("Station is down", "Station is down"), recorder.seen.map { it?.message })
        assertEquals(raw.errorCode, recorder.seen.first()?.errorCode)
        assertEquals("Station is down", gate.playerError?.message)
    }

    @Test
    fun `removed listeners are gone from the wrapped player`() {
        val inner = ErroringPlayer()
        val gate = AuthGatePlayer(inner)
        val recorder = Recorder()
        gate.addListener(recorder)
        gate.removeListener(recorder)

        inner.fail(raw)

        assertEquals(emptyList(), recorder.seen)
        assertEquals(emptyList(), inner.listeners)
    }

    @Test
    fun `the auth error wins over a presented player error`() {
        val inner = ErroringPlayer()
        val gate = AuthGatePlayer(inner) { PlaybackException("presented", null, it.errorCode) }
        val auth = PlaybackException("sign in", null, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED)
        inner.error = raw

        gate.setAuthError(auth)

        assertSame(auth, gate.playerError)
    }
}

/**
 * Drives every Player.Listener callback through the gate player and checks
 * it reaches the registered listener with the same arguments — a wrapper
 * that forwards only some callbacks starves the session of state updates.
 */
@UnstableApi
class AuthGatePlayerForwardingTest {

    private class CapturingPlayer : FakePlayer() {
        val listeners = mutableListOf<Player.Listener>()
        override fun addListener(listener: Player.Listener) { listeners += listener }
        override fun removeListener(listener: Player.Listener) { listeners.remove(listener) }
        override fun getPlayerError(): PlaybackException? = null
    }

    private data class Call(val name: String, val args: List<Any?>)

    @Test
    fun `every listener callback is forwarded with its arguments`() {
        val inner = CapturingPlayer()
        val gate = AuthGatePlayer(inner) { PlaybackException("presented", it.cause, it.errorCode) }
        val calls = mutableListOf<Call>()
        val delegate = java.lang.reflect.Proxy.newProxyInstance(
            Player.Listener::class.java.classLoader,
            arrayOf(Player.Listener::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "delegate"
                else -> { calls += Call(method.name, args?.toList().orEmpty()); null }
            }
        } as Player.Listener
        gate.addListener(delegate)
        val registered = inner.listeners.single()

        val callbacks = Player.Listener::class.java.declaredMethods
            .filter { !it.isSynthetic && java.lang.reflect.Modifier.isPublic(it.modifiers) }
        assertTrue(callbacks.size > 30, "expected the full Player.Listener surface, got ${callbacks.size}")
        for (method in callbacks) {
            calls.clear()
            val args = method.parameterTypes.map(::sample)
            method.invoke(registered, *args.toTypedArray())
            val call = calls.singleOrNull() ?: error("${method.name} not forwarded (${calls.size} calls)")
            // media3's own ForwardingListener maps the deprecated loading callback onto the current one.
            val expectedName = if (method.name == "onLoadingChanged") "onIsLoadingChanged" else method.name
            assertEquals(expectedName, call.name, "${method.name} not forwarded")
            val expected = when (method.name) {
                "onEvents" -> listOf(gate, args[1])
                "onPlayerError", "onPlayerErrorChanged" -> listOf("presented")
                else -> args
            }
            val actual = if (method.name.startsWith("onPlayerError")) call.args.map { (it as PlaybackException).message } else call.args
            assertEquals(expected, actual, "${method.name} arguments")
        }
    }

    private fun sample(type: Class<*>): Any? = when (type) {
        java.lang.Integer.TYPE -> 3
        java.lang.Boolean.TYPE -> true
        java.lang.Long.TYPE -> 5L
        java.lang.Float.TYPE -> 0.5f
        Player::class.java -> FakePlayer()
        Player.Events::class.java -> Player.Events(FlagSet.Builder().add(Player.EVENT_IS_PLAYING_CHANGED).build())
        Timeline::class.java -> Timeline.EMPTY
        MediaItem::class.java -> MediaItem.EMPTY
        Tracks::class.java -> Tracks.EMPTY
        MediaMetadata::class.java -> MediaMetadata.EMPTY
        Player.Commands::class.java -> Player.Commands.EMPTY
        TrackSelectionParameters::class.java -> TrackSelectionParameters.DEFAULT
        PlaybackException::class.java -> PlaybackException("raw", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        Player.PositionInfo::class.java -> Player.PositionInfo(null, 0, MediaItem.EMPTY, null, 0, 0L, 0L, -1, -1)
        PlaybackParameters::class.java -> PlaybackParameters.DEFAULT
        AudioAttributes::class.java -> AudioAttributes.DEFAULT
        DeviceInfo::class.java -> DeviceInfo.UNKNOWN
        VideoSize::class.java -> VideoSize.UNKNOWN
        List::class.java -> emptyList<Cue>()
        CueGroup::class.java -> CueGroup.EMPTY_TIME_ZERO
        Metadata::class.java -> Metadata()
        else -> error("no sample for ${type.name}")
    }
}
