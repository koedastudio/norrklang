package studio.koeda.norrklang.data.mtls

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.data.mtls.MtlsManager.Failure
import studio.koeda.norrklang.data.mtls.MtlsManager.MtlsException
import studio.koeda.norrklang.data.mtls.MtlsManager.Status
import studio.koeda.norrklang.data.mtls.MtlsManager.UploadMethod
import studio.koeda.norrklang.data.settings.FakeCipher
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.settings.TestCertificates

class MtlsManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "test.preferences_pb")
        }

    private fun manager(
        scope: CoroutineScope,
        engine: MockEngine,
        deviceName: String = "Test Device",
    ): MtlsManager = MtlsManager(
        ServerSettingsRepository(dataStore(scope), FakeCipher()),
        scope,
        HttpClient(engine),
        deviceName,
    )

    /** A mock that answers every request; these tests never expect one. */
    private fun idleEngine() = MockEngine(MockEngineConfig().apply {
        addHandler { respond("unused") }
    })

    private fun freshKeyPair() = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    @Test
    fun `fresh store has no status`() = runTest {
        val mtls = manager(backgroundScope, idleEngine())
        assertIs<Status.None>(mtls.status.first())
    }

    @Test
    fun `generateKeyPair yields a PKCS10 CSR named after the device`() = runTest {
        val mtls = manager(backgroundScope, idleEngine(), deviceName = "Emulator 9")
        mtls.generateKeyPair()

        val status = assertIs<Status.KeyGenerated>(mtls.status.first())
        assertTrue(status.csrPem.startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
        assertTrue(status.csrPem.endsWith("-----END CERTIFICATE REQUEST-----"))
        val der = Base64.getDecoder().decode(
            status.csrPem
                .substringAfter("-----BEGIN CERTIFICATE REQUEST-----")
                .substringBefore("-----END CERTIFICATE REQUEST-----")
                .filterNot(Char::isWhitespace),
        )
        val request = JcaPKCS10CertificationRequest(der)
        assertEquals("CN=Emulator 9", request.subject.toString())
        assertContentEquals(mtls.currentPublicKey()!!.encoded, request.publicKey.encoded)
    }

    @Test
    fun `a certificate signed for the keypair is installed and completes the status`() = runTest {
        val mtls = manager(backgroundScope, idleEngine())
        mtls.generateKeyPair()
        val certificate = TestCertificates.selfSignedFor(mtls.currentPublicKey()!!)

        mtls.installCertificate(certificate.encoded).getOrThrow()

        val status = assertIs<Status.Ready>(mtls.status.first())
        assertEquals(certificate.subjectX500Principal.name, status.certificateSubject)
    }

    @Test
    fun `a PEM certificate is accepted like a DER one`() = runTest {
        val mtls = manager(backgroundScope, idleEngine())
        mtls.generateKeyPair()
        val certificate = TestCertificates.selfSignedFor(mtls.currentPublicKey()!!)
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getEncoder().encodeToString(certificate.encoded).chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE-----"

        mtls.installCertificate(pem.toByteArray()).getOrThrow()

        assertIs<Status.Ready>(mtls.status.first())
    }

    @Test
    fun `a certificate for a different key is rejected`() = runTest {
        val mtls = manager(backgroundScope, idleEngine())
        mtls.generateKeyPair()
        val certificate = TestCertificates.selfSignedFor(freshKeyPair().public)

        val result = mtls.installCertificate(certificate.encoded)

        val failure = assertIs<MtlsException>(result.exceptionOrNull()).failure
        assertEquals(Failure.KEY_MISMATCH, failure)
        assertIs<Status.KeyGenerated>(mtls.status.first())
    }

    @Test
    fun `garbage is rejected as not a certificate`() = runTest {
        val mtls = manager(backgroundScope, idleEngine())
        mtls.generateKeyPair()

        val result = mtls.installCertificate("not a certificate".toByteArray())

        val failure = assertIs<MtlsException>(result.exceptionOrNull()).failure
        assertEquals(Failure.INVALID_CERTIFICATE, failure)
    }

    @Test
    fun `installing without a keypair fails with NO_KEYPAIR`() = runTest {
        val mtls = manager(backgroundScope, idleEngine())

        val result = mtls.installCertificate(
            TestCertificates.selfSignedFor(freshKeyPair().public).encoded,
        )

        val failure = assertIs<MtlsException>(result.exceptionOrNull()).failure
        assertEquals(Failure.NO_KEYPAIR, failure)
    }

    @Test
    fun `GET upload sends the CSR as the csr parameter and reads the Location header`() = runTest {
        var seenCsr: String? = null
        val engine = MockEngine(MockEngineConfig().apply {
            addHandler { request ->
                seenCsr = request.url.parameters["csr"]
                respond(
                    "ok",
                    HttpStatusCode.Created,
                    headersOf(HttpHeaders.Location to listOf("https://cdn.example/cert.pem")),
                )
            }
        })
        val mtls = manager(backgroundScope, engine)
        mtls.generateKeyPair()
        val pem = (mtls.status.first() as Status.KeyGenerated).csrPem

        val result = mtls.uploadCsr("https://upload.example/add", UploadMethod.GET, pem)

        assertEquals(201, result.status)
        assertEquals("https://cdn.example/cert.pem", result.location)
        assertEquals(pem, seenCsr)
    }

    @Test
    fun `POST upload sends the PEM as the body`() = runTest {
        var body: String? = null
        var contentType: String? = null
        val engine = MockEngine(MockEngineConfig().apply {
            addHandler { request ->
                body = (request.body as? OutgoingContent.ByteArrayContent)
                    ?.bytes()
                    ?.decodeToString()
                // Ktor 3 strips Content-Type from the request headers and
                // carries it on the OutgoingContent instead.
                contentType = request.body.contentType?.toString()
                respond("stored", HttpStatusCode.OK)
            }
        })
        val mtls = manager(backgroundScope, engine)
        mtls.generateKeyPair()
        val pem = (mtls.status.first() as Status.KeyGenerated).csrPem

        val result = mtls.uploadCsr("https://upload.example/add", UploadMethod.POST, pem)

        assertEquals(200, result.status)
        assertNull(result.location)
        assertEquals(pem, body)
        assertTrue(contentType?.startsWith("text/plain") == true)
    }

    @Test
    fun `download and install complete the round trip`() = runTest {
        // The handler reads a slot that is filled with a cert for THIS
        // keypair once generation has happened.
        var certBytes: ByteArray = TestCertificates.selfSignedFor(freshKeyPair().public).encoded
        val engine = MockEngine(MockEngineConfig().apply {
            addHandler { respond(certBytes) }
        })
        val mtls = manager(backgroundScope, engine)
        mtls.generateKeyPair()
        certBytes = TestCertificates.selfSignedFor(mtls.currentPublicKey()!!).encoded

        val downloaded = mtls.downloadCertificate("https://cdn.example/cert.pem").getOrThrow()
        mtls.installCertificate(downloaded).getOrThrow()

        assertIs<Status.Ready>(mtls.status.first())
    }

    @Test
    fun `a non-2xx download fails`() = runTest {
        val engine = MockEngine(MockEngineConfig().apply {
            addHandler { respond("gone", HttpStatusCode.NotFound) }
        })
        val mtls = manager(backgroundScope, engine)
        mtls.generateKeyPair()

        val result = mtls.downloadCertificate("https://cdn.example/cert.pem")

        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun `a downloaded root CA is installed and reloads on a fresh manager`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = ServerSettingsRepository(store, FakeCipher())
        val ca = TestCertificates.selfSignedFor(freshKeyPair().public)
        val engine = MockEngine(MockEngineConfig().apply {
            addHandler { respond(ca.encoded) }
        })
        val mtls = MtlsManager(settings, backgroundScope, HttpClient(engine), "Test Device")

        mtls.installRootCa(mtls.downloadCertificate("https://ca.example/root.pem").getOrThrow())
            .getOrThrow()

        assertEquals(ca, mtls.rootCa.first())
        assertEquals(ca, mtls.rootCaNow)
        val fresh = MtlsManager(settings, backgroundScope, HttpClient(idleEngine()), "Test Device")
        fresh.ensureLoaded()
        assertEquals(ca, fresh.rootCaNow)
    }

    @Test
    fun `garbage root CA is rejected as not a certificate`() = runTest {
        val mtls = manager(backgroundScope, idleEngine())

        val result = mtls.installRootCa("not a certificate".toByteArray())

        val failure = assertIs<MtlsException>(result.exceptionOrNull()).failure
        assertEquals(Failure.INVALID_CERTIFICATE, failure)
        assertNull(mtls.rootCa.first())
    }

    @Test
    fun `removeRootCa clears the trusted CA`() = runTest {
        val mtls = manager(backgroundScope, idleEngine())
        mtls.installRootCa(TestCertificates.selfSignedFor(freshKeyPair().public).encoded).getOrThrow()
        assertNotNull(mtls.rootCa.first())

        mtls.removeRootCa()

        assertNull(mtls.rootCa.first())
        assertNull(mtls.rootCaNow)
    }
}
