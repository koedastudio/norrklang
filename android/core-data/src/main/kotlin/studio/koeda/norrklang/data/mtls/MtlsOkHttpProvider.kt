package studio.koeda.norrklang.data.mtls

import android.util.Log
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import studio.koeda.norrklang.subsonic.SubsonicEngineProvider

/**
 * The OkHttp client every transport shares. With a loaded [MtlsIdentity] it
 * presents the client certificate in the TLS handshake, so an
 * mTLS-configured server authenticates the connection before any HTTP
 * request reaches it; without one it is a plain client (unchanged behavior).
 *
 * The client is cached per certificate fingerprint and rebuilt when the
 * identity changes.
 */
@Singleton
class MtlsOkHttpProvider @Inject constructor(
    private val mtls: MtlsManager,
) {

    init {
        SubsonicEngineProvider.engineFactory = { _ -> engineFactory }
    }

    private companion object {
        const val TAG = "NorrklangMtls"
    }

    /** Ktor engine for [SubsonicClient]: the shared (optionally mTLS) OkHttp client. */
    val engineFactory: HttpClientEngineFactory<OkHttpConfig> =
        object : HttpClientEngineFactory<OkHttpConfig> {
            override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
                OkHttp.create {
                    block(this)
                    preconfigured = client()
                }
        }

    @Synchronized
    fun client(): OkHttpClient {
        val identity = mtls.identityNow
        val rootCa = mtls.rootCaNow
        runCatching {
            Log.d(
                TAG,
                "transport: " + when {
                    identity != null -> "mTLS, client cert ${identity.certificate.subjectX500Principal}"
                    rootCa != null -> "plain TLS + custom root CA ${rootCa.subjectX500Principal}"
                    else -> "plain TLS, platform trust only"
                },
            )
        }
        val fingerprint = (identity?.fingerprint ?: "none") +
            "/" + (rootCa?.encoded?.joinToString(":") { String.format("%02X", it) } ?: "none")
        cached?.takeIf { it.first == fingerprint }?.let { return it.second }
        val builder = OkHttpClient.Builder()
        val trustManager = trustManagerFor(rootCa)
        var combined: CombinedKeyAndTrustManager? = null
        when {
            identity != null -> {
                val keyManager = keyManagerFor(identity)
                val context = SSLContext.getInstance("TLS").apply {
                    init(arrayOf(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
                }
                combined = CombinedKeyAndTrustManager(keyManager, trustManager)
                builder.sslSocketFactory(context.socketFactory, combined)
            }
            rootCa != null -> {
                // No client certificate, but the server's CA must be trusted
                // too — the platform chain validation alone would reject it.
                val context = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                }
                builder.sslSocketFactory(context.socketFactory, trustManager)
            }
        }
        val client = builder.build()
        cached = fingerprint to client
        combinedManager = combined
        activeTrustManager = trustManager
        return client
    }

    /**
     * The shared [client] with playback-specific timeouts: media3's
     * `OkHttpDataSource.Factory` can't set them, so the stream data sources
     * wrap the client this way. `newBuilder` shares the pool and cache.
     */
    fun streamClient(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient =
        client().newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

    /** The manager installed by the last [client] rebuild; null for a plain client. */
    @Volatile
    internal var combinedManager: CombinedKeyAndTrustManager? = null
        private set

    /** The trust manager the last [client] rebuild installed; the platform one for a plain client. */
    @Volatile
    internal var activeTrustManager: X509TrustManager? = null
        private set

    private fun trustManagerFor(rootCa: X509Certificate?): X509TrustManager {
        val platform = platformTrustManager()
        if (rootCa == null) return platform
        val caStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("norrklang-root-ca", rootCa)
        }
        val caManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(caStore) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
        return FallbackTrustManager(platform, caManager)
    }

    private var cached: Pair<String?, OkHttpClient>? = null

    private fun keyManagerFor(identity: MtlsIdentity): X509KeyManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        store.setKeyEntry(
            identity.certificate.subjectX500Principal.name,
            identity.keyPair.private,
            null,
            arrayOf<Certificate>(identity.certificate),
        )
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, null) }
            .keyManagers
            .filterIsInstance<X509KeyManager>()
            .first()
    }

    private fun platformTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /**
     * OkHttp's `sslSocketFactory` takes a trust manager, but a client-auth
     * handshake also needs a key manager to pick the certificate to send.
     * This is both: key selection delegates to [keyManager], server
     * validation to the platform [trustManager] — standard trust, nothing
     * relaxed.
     */
    internal class CombinedKeyAndTrustManager(
        private val keyManager: X509KeyManager,
        private val trustManager: X509TrustManager,
    ) : X509KeyManager, X509TrustManager {
        override fun getClientAliases(keyType: String, issuers: Array<out Principal>): Array<out String> =
            keyManager.getClientAliases(keyType, issuers)
        override fun chooseClientAlias(
            keyTypes: Array<out String>,
            issuers: Array<out Principal>,
            socket: Socket,
        ): String = keyManager.chooseClientAlias(keyTypes, issuers, socket)
        override fun getServerAliases(keyType: String, issuers: Array<out Principal>): Array<out String> =
            keyManager.getServerAliases(keyType, issuers)
        override fun chooseServerAlias(
            keyType: String,
            issuers: Array<out Principal>,
            socket: Socket,
        ): String = keyManager.chooseServerAlias(keyType, issuers, socket)
        override fun getCertificateChain(alias: String): Array<out X509Certificate> =
            keyManager.getCertificateChain(alias)
        override fun getPrivateKey(alias: String): PrivateKey =
            keyManager.getPrivateKey(alias)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            trustManager.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            trustManager.checkServerTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> =
            trustManager.getAcceptedIssuers()
    }

    /**
     * Platform validation first, the user's root CA as a second chance.
     * Only server chains are extended — client validation keeps the
     * platform rules, and the CA is a single user-installed certificate.
     */
    internal class FallbackTrustManager(
        private val primary: X509TrustManager,
        private val fallback: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            primary.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                primary.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                fallback.checkServerTrusted(chain, authType)
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> =
            (primary.getAcceptedIssuers().toList() + fallback.getAcceptedIssuers().toList())
                .distinct()
                .toTypedArray()
    }
}
