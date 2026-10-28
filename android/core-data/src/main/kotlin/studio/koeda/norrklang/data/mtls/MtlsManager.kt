package studio.koeda.norrklang.data.mtls

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import io.ktor.client.statement.bodyAsBytes
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import studio.koeda.norrklang.data.di.ApplicationScope
import studio.koeda.norrklang.data.di.DeviceName
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.diagnostics.Diagnostics

/**
 * Owns the device's mTLS identity: the EC keypair generated here (the private
 * key never leaves the device — it is stored encrypted, see
 * [ServerSettingsRepository]) and the certificate the server signs for its
 * public key.
 *
 * The keypair leaves the device only as a PKCS#10 certificate request
 * (subject: the device name), handed over as a QR code, copied, or via
 * [uploadCsr] to the user's own endpoint. The server signs the CSR; the
 * certificate comes back via [downloadCertificate] + [installCertificate],
 * which verifies it matches the generated keypair.
 */
@Singleton
class MtlsManager @Inject constructor(
    private val settings: ServerSettingsRepository,
    @ApplicationScope scope: CoroutineScope,
    private val http: HttpClient = defaultHttp,
    /** Subject of the CSR (`CN=$deviceName`); injectable for tests. */
    @DeviceName private val deviceName: String,
) {

    sealed interface Status {
        data object None : Status
        data class KeyGenerated(val csrPem: String) : Status
        data class Ready(val csrPem: String, val certificateSubject: String) : Status
    }

    enum class UploadMethod { GET, POST }

    /** 2xx means the key arrived; [location] is the response's `Location` header, if any. */
    data class UploadResult(val status: Int, val location: String?)

    /** Why [installCertificate] (or a store read) failed. */
    enum class Failure {
        /** No keypair has been generated yet. */
        NO_KEYPAIR,

        /** The download is not a parseable X.509 certificate. */
        INVALID_CERTIFICATE,

        /** The certificate was signed for a different key. */
        KEY_MISMATCH,
    }

    class MtlsException(val failure: Failure) : Exception(failure.name)

    /**
     * The live identity for transport (see [MtlsOkHttpProvider]). Backed by a
     * volatile so the (non-suspend) engine factory can read it; refreshed by
     * every mutation and by [ensureLoaded] before sessions are built.
     */
    @Volatile
    var identityNow: MtlsIdentity? = null
        private set

    /** The server's root CA, additionally trusted (see [MtlsOkHttpProvider]). */
    @Volatile
    var rootCaNow: X509Certificate? = null
        private set

    val status: Flow<Status> = settings.mtlsMaterial
        .map(::toStatus)
        .distinctUntilChanged()

    val rootCa: Flow<X509Certificate?> = settings.mtlsRootCa

    init {
        scope.launch {
            try {
                ensureLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.record("mtls-load", e)
            }
        }
    }

    /** Reads the identity and the root CA once from the store; safe to call repeatedly. */
    suspend fun ensureLoaded() {
        identityNow = (settings.mtlsMaterial.first() as? MtlsMaterial.Complete)?.identity
        rootCaNow = settings.mtlsRootCa.first()
    }

    /** The PKCS#10 certificate request (subject `CN=$deviceName`) as a PEM block. */
    fun csrPem(keyPair: KeyPair): String = runCatching {
        val builder = JcaPKCS10CertificationRequestBuilder(
            X500Name("CN=$deviceName"),
            keyPair.public,
        )
        // The signer carries the private key; the request is signed with it.
        val request = builder.build(
            JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private),
        )
        val base64 = Base64.getEncoder().encodeToString(request.getEncoded())
        "-----BEGIN CERTIFICATE REQUEST-----\n" +
            base64.chunked(PEM_LINE_LENGTH).joinToString("\n") +
            "\n-----END CERTIFICATE REQUEST-----"
    }.getOrThrow()

    /** The generated public key, before or after signing; null when nothing is generated. */
    suspend fun currentPublicKey(): PublicKey? = (settings.mtlsMaterial.first())?.let {
        when (it) {
            is MtlsMaterial.KeyPairOnly -> it.keyPair.public
            is MtlsMaterial.Complete -> it.identity.keyPair.public
        }
    }

    /**
     * Generates a fresh EC P-256 keypair and stores it, replacing any
     * previous one (which also drops its certificate — a new key invalidates
     * it).
     */
    suspend fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE_P256))
        }
        settings.storeMtlsKeyPair(generator.generateKeyPair())
        ensureLoaded()
    }

    /**
     * Parses [data] (DER or PEM), verifies the certificate was signed for the
     * stored keypair's public key and installs it.
     */
    suspend fun installCertificate(data: ByteArray): Result<MtlsIdentity> {
        val keyPair = (settings.mtlsMaterial.first())?.let {
            when (it) {
                is MtlsMaterial.KeyPairOnly -> it.keyPair
                is MtlsMaterial.Complete -> it.identity.keyPair
            }
        } ?: return Result.failure(MtlsException(Failure.NO_KEYPAIR))
        val certificate = try {
            parseCertificate(data)
        } catch (e: Exception) {
            return Result.failure(MtlsException(Failure.INVALID_CERTIFICATE))
        }
        if (certificate.publicKey != keyPair.public) {
            return Result.failure(MtlsException(Failure.KEY_MISMATCH))
        }
        settings.storeMtlsCertificate(certificate)
        ensureLoaded()
        return Result.success(
            MtlsIdentity(keyPair, certificate).also { identityNow = it },
        )
    }

    /** Removes the keypair and any certificate; the next connection is plain TLS. */
    suspend fun reset() {
        settings.removeMtlsIdentity()
        ensureLoaded()
    }

    /**
     * Parses [data] (DER or PEM) and installs it as a root CA trusted in
     * addition to the platform set — for servers whose certificate chain
     * ends at a CA the device does not know.
     */
    suspend fun installRootCa(data: ByteArray): Result<X509Certificate> {
        val certificate = try {
            parseCertificate(data)
        } catch (e: Exception) {
            return Result.failure(MtlsException(Failure.INVALID_CERTIFICATE))
        }
        settings.storeMtlsRootCa(certificate)
        rootCaNow = certificate
        return Result.success(certificate)
    }

    /** Removes the additionally trusted root CA; the next connection trusts the platform set only. */
    suspend fun removeRootCa() {
        settings.removeMtlsRootCa()
        rootCaNow = null
    }

    /**
     * Sends the PKCS#10 [csr] (PEM) to the user's upload endpoint. GET: as
     * the `csr` query parameter (URL-encoded by Ktor); POST: as a `text/plain`
     * body. The endpoint is user-hosted (the issue leaves its implementation
     * to the server owner), so only the transport is standardized here.
     */
    suspend fun uploadCsr(
        url: String,
        method: UploadMethod,
        csr: String,
    ): UploadResult = withContext(Dispatchers.IO) {
        val response = if (method == UploadMethod.GET) {
            http.get(url) { parameter("csr", csr) }
        } else {
            http.post(url) {
                // `setBody` wraps the PEM in a `TextContent` (text/plain); Ktor
                // drops any manually set Content-Type and the engine takes it
                // from the body.
                setBody(csr)
            }
        }
        UploadResult(response.status.value, response.headers[HttpHeaders.Location])
    }

    /** Downloads the signed certificate the user points at; no special hosting needed. */
    suspend fun downloadCertificate(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val response = http.get(url)
            if (!response.status.isSuccess()) {
                Result.failure(IOException("HTTP ${response.status.value}"))
            } else {
                Result.success(response.bodyAsBytes())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun toStatus(material: MtlsMaterial?): Status = when (material) {
        null -> Status.None
        is MtlsMaterial.KeyPairOnly ->
            Status.KeyGenerated(csrPem(material.keyPair))
        is MtlsMaterial.Complete ->
            Status.Ready(
                csrPem(material.identity.keyPair),
                material.identity.certificate.subjectX500Principal.name,
            )
    }

    private fun parseCertificate(data: ByteArray): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        try {
            return factory.generateCertificate(ByteArrayInputStream(data)) as X509Certificate
        } catch (e: Exception) {
            // Not raw DER — try a PEM document.
        }
        val text = String(data, Charsets.ISO_8859_1)
        val begin = "-----BEGIN CERTIFICATE-----"
        val end = "-----END CERTIFICATE-----"
        val start = text.indexOf(begin)
        val finish = text.indexOf(end, start.coerceAtLeast(0))
        if (start < 0 || finish < 0) throw IllegalArgumentException("Not a DER or PEM certificate")
        val base64 = text.substring(start + begin.length, finish).filterNot(Char::isWhitespace)
        return factory.generateCertificate(
            ByteArrayInputStream(Base64.getDecoder().decode(base64)),
        ) as X509Certificate
    }

    companion object {
        /** Fixed key length per the feature request: NIST P-256 ECDSA. */
        private const val CURVE_P256 = "secp256r1"
        private const val PEM_LINE_LENGTH = 64
        /** CSR signature algorithm — supported by every JDK and Android provider. */
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        private val defaultHttp = HttpClient(OkHttp.create()) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
            }
        }
    }
}
