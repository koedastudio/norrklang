# Norrklang mTLS protocol

The end-to-end protocol by which a Norrklang device obtains a client
certificate from the user's server and then authenticates to it on every
request. App-side reference implementation: `MtlsManager` and
`MtlsOkHttpProvider` in `android/core-data`.

## Participants

- **Device** — Norrklang (car or phone). Generates the keypair, never
  exposes the private key.
- **Server** — the user's Subsonic/Navidrome server, terminated with TLS and
  configured to require a client certificate.
- **Upload endpoint** — a user-hosted receiver that accepts the certificate
  request and (typically) triggers the server to sign it.
- **Static hosting** — anything that can serve the signed certificate over
  GET (the upload endpoint, a web root, a file share).

## 1. Identity

- The device generates an **EC P-256** (`secp256r1`) keypair locally.
- The private key is stored encrypted at rest (Keystore-backed cipher, see
  `ServerSettingsRepository`) and never leaves the device.
- The public key leaves the device only inside a certificate request —
  never as a bare key.

## 2. Certificate request (CSR)

- Format: **PKCS#10** (RFC 2986), PEM-encoded
  (`-----BEGIN CERTIFICATE REQUEST-----`).
- Subject: `CN=<device name>` — the Android device model
  (`android.os.Build.MODEL`, e.g. `CN=sdk_gphone64_arm64`). The server may
  use it to label the device; it is not a unique ID.
- Public key: the device's EC P-256 public key.
- Signature algorithm: `SHA256withECDSA`.
- The CSR is a pure function of the keypair and device name; the app
  regenerates it on demand. A new keypair (regeneration) invalidates any
  previously installed certificate.

## 3. Handing the CSR to the server

The user chooses one of:

1. **QR code** — rendered on the car/phone screen; the server operator scans
   it with their phone.
2. **Clipboard** — the CSR PEM is copied and pasted wherever the server
   wants it.
3. **Upload endpoint** — the app POSTs/GETs it to a URL the user configured
   (§4).

## 4. Upload endpoint

User-hosted; the app only standardizes the transport.

| Method | Request |
| ------ | ------- |
| `GET`  | `/?csr=<CSR PEM>` — URL-encoded |
| `POST` | `/` with the CSR PEM as a `text/plain` body |

- `2xx` — accepted.
- non-`2xx` — rejected; the app shows the HTTP status code in its response
  log.
- Optional **`Location` response header** — absolute URL of the signed
  certificate. When present, the app fills the certificate-download field
  with it, so a signing pipeline can hand the result back in one round trip.
- The endpoint receives the CSR as-is; it must validate it before signing
  (a valid PKCS#10 request for an EC P-256 key with the expected subject
  shape).

## 5. Certificate download

- `GET <certificate URL>`; any static hosting works.
- Body: the signed X.509 certificate as **DER or PEM**.
- The app verifies the certificate's public key **equals the device's
  generated public key** before installing; a mismatch is reported
  (`KEY_MISMATCH`) and the certificate is discarded.
- Non-`2xx` or an unparseable body is reported as a network/parse error.

## 6. Root CA (optional, order-independent)

- Servers whose certificate chain ends at a CA the device does not know
  (self-signed) can be trusted by downloading that root CA through the same
  download transport (§5) and installing it.
- The installed CA is trusted **in addition to** the platform trust store
  for every connection; removing it restores platform-only trust.
- This can be done **before** any keypair exists — it is what makes a fresh
  self-signed server reachable at all.

## 7. Connection behaviour

| Device state | Transport |
| ------------ | --------- |
| keypair + certificate (identity complete) | TLS with client authentication on every Subsonic request |
| keypair only | plain TLS — a server requiring a client certificate will fail the handshake until the certificate is installed |
| nothing | plain TLS |

The mTLS `OkHttpClient` is cached per `(identity fingerprint, root-CA
fingerprint)` and rebuilt on change; sessions are built after the identity
and CA are loaded from disk.

## 8. Reference server

`server/mtls-upload` is the drop-in upload receiver: it accepts the `csr`
parameter/body per §4, validates the CSR (PKCS#10, EC P-256), and saves it
as-is as `<sha256-of-SubjectPublicKeyInfo>.csr`. A signer (reference
pipeline in `specs/mtls/sign-csr.sh`) drops the signed certificate into the
same directory as `<thumbprint>.crt`, which the receiver serves at
`GET /<thumbprint>.crt` (satisfying §5 from the same origin); the upload
response's optional `Location` header points there. Started with
`--crt <file>`, the root CA from that file is served at `GET /ca.crt`.
