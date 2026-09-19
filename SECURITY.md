# Security Policy

## Reporting a vulnerability

Please report vulnerabilities privately via
[GitHub private vulnerability reporting](https://github.com/koedastudio/norrklang/security/advisories/new)
— not in public issues. You should get an initial response within a week.
Coordinated disclosure is appreciated; a fix will be released before details
are published.

## Supported versions

Only the **latest release** receives security fixes. There is no LTS.

## Scope and threat model

Norrklang is a client for a user-controlled Navidrome/Subsonic server.
Reports of particular interest:

- **Credential exposure**: the server auth secret (a token, or the password
  itself for servers that reject token auth) is stored encrypted (Android
  Keystore) with backups disabled — anything that leaks that secret, the
  derived stream URLs, or a password outside that encrypted store.
- **Cross-account leakage**: caches (library data, artwork) are namespaced
  per server/account — any way to see a previous account's data after
  switching is a bug.
- **The exported artwork provider** (`ArtworkProvider`): it must not be
  usable by other apps as an authenticated fetch proxy or for unbounded
  cache growth.
- **Network handling**: release builds are HTTPS-only; anything that
  downgrades or bypasses that.

Out of scope: vulnerabilities in Navidrome/Subsonic servers themselves, and
issues requiring a rooted device or a compromised car head unit.
