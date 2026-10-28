#!/usr/bin/env bash
# Reference signing pipeline for Norrklang mTLS (specs/mtls/protocol.md §4-5).
#
# server/mtls-upload saves uploaded CSRs as-is in its data directory, named
# <thumbprint>.csr (sha256 of the CSR's public key). Sign one with your CA:
# point <name> at that file's stem (e.g. copy the saved CSR here) and the
# resulting <name>.crt is what the receiver serves at GET /<name>.crt.
#
# Usage: ./sign-csr.sh <name>
#
# Expects ca.crt / ca.key in the working directory.

set -euo pipefail

name="${1:?usage: sign-csr.sh <name>}"

if [ ! -f "$name.csr" ]; then
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1 -out "$name.key" 2>/dev/null
  openssl req -new -key "$name.key" -subj "/CN=$name" -out "$name.csr"
fi

ext="
basicConstraints = CA:FALSE
extendedKeyUsage = clientAuth
"

openssl x509 -req -in "$name.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 3650 -sha256 -extfile <(printf '%s' "$ext") -out "$name.crt" 2>/dev/null

cat "$name.crt" "$name.key" > "client-$name.pem"
