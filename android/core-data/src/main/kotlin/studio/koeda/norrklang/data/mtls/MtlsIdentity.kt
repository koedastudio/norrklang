package studio.koeda.norrklang.data.mtls

import java.security.KeyPair
import java.security.cert.X509Certificate

/**
 * A complete mTLS identity: the device's EC keypair plus the certificate the
 * server signed for its public key.
 */
data class MtlsIdentity(val keyPair: KeyPair, val certificate: X509Certificate) {
    /** Stable per-certificate key for the client built for this identity. */
    val fingerprint: String
        get() = certificate.encoded.joinToString(":") { String.format("%02X", it) }
}

/**
 * What the store currently holds: the keypair before the server has signed it,
 * or the complete identity once a matching certificate was installed.
 */
sealed interface MtlsMaterial {
    data class KeyPairOnly(val keyPair: KeyPair) : MtlsMaterial
    data class Complete(val identity: MtlsIdentity) : MtlsMaterial
}
