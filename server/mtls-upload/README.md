# mTLS certificate-request upload receiver

A tiny drop-in for the user-hosted upload endpoint from
[specs/mtls/protocol.md](../../specs/mtls/protocol.md) §4: Norrklang generates
an EC P-256 keypair on the device and hands a PKCS#10 certificate request
(CSR) to this endpoint.

**Protocol** (matches `MtlsManager.uploadPublicKey` in `android/core-data`):

| Method | Request                                     |
| ------ | ------------------------------------------- |
| GET    | `/?csr=<CSR PEM>` (URL-encoded)            |
| POST   | `/` with the CSR PEM as a `text/plain` body |

Each CSR is validated (PKCS#10 shape, EC P-256 public key) and saved **as-is**
under the data directory as `<thumbprint>.csr`, where the thumbprint is the
SHA-256 hex of the CSR's SubjectPublicKeyInfo — stable across regenerations
(raw CSR bytes are not: ECDSA signatures vary). The signed certificate is
then dropped into the same directory as `<thumbprint>.crt` (e.g. by
`specs/mtls/sign-csr.sh`), which this server serves back at
`GET /<thumbprint>.crt` — so one origin covers upload *and* certificate
download (protocol §5).

Successful uploads answer **200** with the thumbprint in the body; started
with `-location <base>`, the response also carries
`Location: <base>/<thumbprint>.crt`, which the app fills into the
certificate-download field. Every request (success or failure) is appended
to `requests.jsonl`, including the received CSR verbatim.

## Run

JDK 17+ only — no dependencies, no build:

```
java MtlsUploadServer.java [port] [-data-dir <dir>] [-location <base>] [--crt <file>]
```

Defaults: port `8080`, data dir `./data`.

With `--crt <file>`, the root CA certificate in that file is served at
`GET /ca.crt` (404 when the flag is not given). The file is checked at
startup; a missing path aborts the server.

## Examples

Upload a CSR (both methods are equivalent to the server):

```
curl.exe -G http://localhost:8080/ --data-urlencode "csr@device.csr"
curl.exe -X POST --data-binary @device.csr http://localhost:8080/
```

Both answer e.g. `ok 4f20c3b18de097c87afb48dd0352681bb9dca042ebe33ee90cdd8821a9d15b42`.

Retrieve the signed certificate once it has been signed:

```
curl.exe http://localhost:8080/4f20c3b18de097c87afb48dd0352681bb9dca042ebe33ee90cdd8821a9d15b42.crt
```

(404 until the signer has dropped the `.crt` in place.) Health check:
`GET /health`.

The saved material lives in the data directory:

```
data/
├── 4f20c3b1...a9d15b42.csr    <- uploaded CSR (as-is)
├── 4f20c3b1...a9d15b42.crt    <- signed certificate (dropped by signer)
└── requests.jsonl
```

## Notes

- This service stores CSRs and serves signed certificates; it does **not**
  sign. Signing is done with your CA — reference pipeline in
  [specs/mtls/sign-csr.sh](../../specs/mtls/sign-csr.sh).
- The app verifies the certificate's public key against its local keypair
  before installing it, so a swapped `.crt` is rejected device-side.
- The saved CSRs and logs identify your devices; keep the data directory
  private.
