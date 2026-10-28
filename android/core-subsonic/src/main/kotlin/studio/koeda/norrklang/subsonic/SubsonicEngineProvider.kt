package studio.koeda.norrklang.subsonic

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig

/**
 * Process-wide hook for the HTTP engine of [SubsonicClient]s that don't pass
 * one explicitly. The host app overrides [engineFactory] to attach
 * transport-level options (an mTLS client) without touching every client
 * construction site; tests keep passing a [io.ktor.client.engine.mock.MockEngine]
 * directly.
 */
object SubsonicEngineProvider {

    @Volatile
    var engineFactory: (SubsonicCredentials) -> HttpClientEngineFactory<OkHttpConfig> =
        { _ -> OkHttp }
}
