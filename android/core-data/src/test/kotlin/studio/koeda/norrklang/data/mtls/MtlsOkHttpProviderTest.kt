package studio.koeda.norrklang.data.mtls

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.data.settings.FakeCipher
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.settings.TestCertificates

class MtlsOkHttpProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "test.preferences_pb")
        }

    private fun pair() = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    /** These tests never make HTTP calls; the engine just needs to exist. */
    private fun idleHttp() = HttpClient(
        MockEngine(MockEngineConfig().apply { addHandler { respond("unused") } }),
    )

    @Test
    fun `without an identity the client is the plain default`() = runTest {
        val settings = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        val mtls = MtlsManager(settings, backgroundScope, idleHttp(), "Test Device")
        mtls.ensureLoaded()
        val provider = MtlsOkHttpProvider(mtls)

        provider.client()

        assertNull(provider.combinedManager)
    }

    @Test
    fun `with an identity the client presents the certificate and caches per fingerprint`() = runTest {
        val settings = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        val pair = pair()
        settings.storeMtlsKeyPair(pair)
        settings.storeMtlsCertificate(TestCertificates.selfSignedFor(pair.public))
        val mtls = MtlsManager(settings, backgroundScope, idleHttp(), "Test Device")
        mtls.ensureLoaded()
        val provider = MtlsOkHttpProvider(mtls)

        val first = provider.client()
        val second = provider.client()

        assertSame(first, second)
        val manager = assertIs<MtlsOkHttpProvider.CombinedKeyAndTrustManager>(provider.combinedManager)
        // The key manager offers the stored certificate for the EC key type.
        val alias = manager.chooseClientAlias(arrayOf("EC"), emptyArray(), java.net.Socket())
        assertTrue(alias != null)
        val chain = manager.getCertificateChain(alias)
        assertEquals(1, chain.size)
        assertEquals(pair.public, chain[0].publicKey)
    }

    @Test
    fun `resetting the identity rebuilds the client`() = runTest {
        val settings = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        val pair = pair()
        settings.storeMtlsKeyPair(pair)
        settings.storeMtlsCertificate(TestCertificates.selfSignedFor(pair.public))
        val mtls = MtlsManager(settings, backgroundScope, idleHttp(), "Test Device")
        mtls.ensureLoaded()
        val provider = MtlsOkHttpProvider(mtls)

        val withCert = provider.client()
        assertIs<MtlsOkHttpProvider.CombinedKeyAndTrustManager>(provider.combinedManager)

        mtls.reset()
        val afterReset = provider.client()

        assertNull(provider.combinedManager)
        assertNotSame(withCert, afterReset)
    }

    @Test
    fun `with a root CA the client trusts it alongside the platform set`() = runTest {
        val settings = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        val ca = TestCertificates.selfSignedFor(pair().public)
        settings.storeMtlsRootCa(ca)
        val mtls = MtlsManager(settings, backgroundScope, idleHttp(), "Test Device")
        mtls.ensureLoaded()
        val provider = MtlsOkHttpProvider(mtls)

        provider.client()

        assertNull(provider.combinedManager)
        val trust = assertIs<MtlsOkHttpProvider.FallbackTrustManager>(provider.activeTrustManager)
        // A chain ending at the user-installed CA validates through the fallback.
        trust.checkServerTrusted(arrayOf(ca), "RSA")
        assertTrue(trust.getAcceptedIssuers().any { it == ca })
    }

    @Test
    fun `adding a root CA rebuilds the client`() = runTest {
        val settings = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        val mtls = MtlsManager(settings, backgroundScope, idleHttp(), "Test Device")
        mtls.ensureLoaded()
        val provider = MtlsOkHttpProvider(mtls)

        val plain = provider.client()

        mtls.installRootCa(TestCertificates.selfSignedFor(pair().public).encoded).getOrThrow()
        val withCa = provider.client()

        assertNotSame(plain, withCa)
        assertIs<MtlsOkHttpProvider.FallbackTrustManager>(provider.activeTrustManager)
    }

    @Test
    fun `the default engine factory is pointed at the provider`() = runTest {
        val settings = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        val mtls = MtlsManager(settings, backgroundScope, idleHttp(), "Test Device")
        mtls.ensureLoaded()
        val provider = MtlsOkHttpProvider(mtls)

        val factory = studio.koeda.norrklang.subsonic.SubsonicEngineProvider
            .engineFactory(studio.koeda.norrklang.subsonic.SubsonicCredentials("https://x.example", "u", studio.koeda.norrklang.subsonic.SubsonicPasswordAuth("p")))

        assertSame(provider.engineFactory, factory)
    }
}
