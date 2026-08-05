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

- **Credential exposure**: the server auth token is stored encrypted
  (Android Keystore) with backups disabled — anything that leaks the token,
  the derived stream URLs, or the password (which must never be persisted).
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
