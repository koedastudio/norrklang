package studio.koeda.norrklang.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.data.mtls.MtlsMaterial

class MtlsPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val privateKeyKey = stringPreferencesKey("mtls_private_key")
    private val publicKeyKey = stringPreferencesKey("mtls_public_key")
    private val certificateKey = stringPreferencesKey("mtls_certificate")
    private val rootCaKey = stringPreferencesKey("mtls_root_ca")

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "test.preferences_pb")
        }

    private fun keyPair() = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    @Test
    fun `nothing stored reads as no material`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        assertNull(repo.mtlsMaterial.first())
    }

    @Test
    fun `stored keypair round-trips with an encrypted private key`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        val pair = keyPair()

        repo.storeMtlsKeyPair(pair)

        val material = assertIs<MtlsMaterial.KeyPairOnly>(repo.mtlsMaterial.first())
        assertEquals(pair.public, material.keyPair.public)
        assertEquals(pair.private, material.keyPair.private)
        // The private key is never stored in the clear.
        val rawPrivate = store.data.first()[privateKeyKey]!!
        assertTrue(rawPrivate.startsWith(FakeCipher.PREFIX))
        assertTrue(String(pair.private.encoded, Charsets.UTF_8) !in rawPrivate)
    }

    @Test
    fun `certificate for the stored keypair completes the identity`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        val pair = keyPair()
        repo.storeMtlsKeyPair(pair)
        val certificate = TestCertificates.selfSignedFor(pair.public)

        repo.storeMtlsCertificate(certificate)

        val material = assertIs<MtlsMaterial.Complete>(repo.mtlsMaterial.first())
        assertEquals(certificate, material.identity.certificate)
        assertEquals(pair.private, material.identity.keyPair.private)
        assertTrue(material.identity.fingerprint.isNotBlank())
    }

    @Test
    fun `a new keypair drops the old certificate`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.storeMtlsKeyPair(keyPair())
        repo.storeMtlsCertificate(TestCertificates.selfSignedFor(keyPair().public))
        assertIs<MtlsMaterial.Complete>(repo.mtlsMaterial.first())

        repo.storeMtlsKeyPair(keyPair())

        assertIs<MtlsMaterial.KeyPairOnly>(repo.mtlsMaterial.first())
        assertNull(store.data.first()[certificateKey])
    }

    @Test
    fun `removeMtlsIdentity clears everything`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        val pair = keyPair()
        repo.storeMtlsKeyPair(pair)
        repo.storeMtlsCertificate(TestCertificates.selfSignedFor(pair.public))

        repo.removeMtlsIdentity()

        assertNull(repo.mtlsMaterial.first())
        val prefs = store.data.first()
        assertNull(prefs[privateKeyKey])
        assertNull(prefs[publicKeyKey])
        assertNull(prefs[certificateKey])
    }

    @Test
    fun `root CA round-trips, survives a sign-out, and is removable`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        val ca = TestCertificates.selfSignedFor(keyPair().public)

        repo.storeMtlsRootCa(ca)

        assertEquals(ca, repo.mtlsRootCa.first())
        repo.save(
            studio.koeda.norrklang.subsonic.SubsonicCredentials.fromInput(
                "https://music.example.com", "demo", "secret",
            ),
        )
        repo.clearAccount()
        assertEquals(ca, repo.mtlsRootCa.first())

        repo.removeMtlsRootCa()

        assertNull(repo.mtlsRootCa.first())
        assertNull(store.data.first()[rootCaKey])
    }

    @Test
    fun `mTLS identity survives a sign-out — it is device-scoped`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        val pair = keyPair()
        repo.storeMtlsKeyPair(pair)
        repo.storeMtlsCertificate(TestCertificates.selfSignedFor(pair.public))
        repo.save(
            studio.koeda.norrklang.subsonic.SubsonicCredentials.fromInput(
                "https://music.example.com", "demo", "secret",
            ),
        )

        repo.clearAccount()

        assertNull(repo.currentCredentials())
        assertIs<MtlsMaterial.Complete>(repo.mtlsMaterial.first())
    }
}

/** Self-signed certs for tests, via the BouncyCastle test dependency. */
internal object TestCertificates {

    fun selfSignedFor(publicKey: java.security.PublicKey): X509Certificate {
        val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val signer = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val now = System.currentTimeMillis()
        val name = org.bouncycastle.asn1.x500.X500Name("CN=norrklang-test")
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            name,
            java.math.BigInteger.valueOf(now),
            java.util.Date(now - 60_000),
            java.util.Date(now + 365L * 24 * 3_600_000),
            name,
            publicKey,
        )
        val content = builder.build(
            org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
                .build(signer.private),
        )
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(content)
    }
}
