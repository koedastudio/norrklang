# Norrklang

A family of music clients for self-hosted music — [Navidrome](https://www.navidrome.org/), other Subsonic-compatible servers, and [Plex](https://www.plex.tv/).

The first project is a **car media app** for **Android Automotive OS** — it runs natively in cars like the Polestar 2.

The repo also contains `app-mobile`, a phone APK that hosts **Android Auto** projection from the same Kotlin codebase. It builds, passes the same tests, and shares all the core modules — but it has not had the same real-world testing as the car app, so it is not yet published. The first Play release is Android Automotive OS only; Android Auto will follow once it has been proven out.

**Status: 1.1.** The app is feature-complete for everyday in-car listening
and hardened for distribution (signed releases, encrypted credential storage,
per-account caches), but it has seen a small number of servers and cars.
Expect rough edges; please report them.

- **Server requirements**: Navidrome (current stable releases) or any server
  implementing **Subsonic API 1.16.1** with token authentication; or a
  **Plex Media Server** with a music library, linked with a Plex account.
- **HTTPS required**: release builds refuse cleartext HTTP — put your server
  behind TLS (a reverse proxy with Let's Encrypt is enough). Plain HTTP works
  only in debug builds against local test servers. Plex connections come with
  Plex's own `plex.direct` TLS, so nothing extra to set up there.
- **Known limitations**: no offline/downloaded playback; gapless playback
  only when streaming original files (the default setting) — server
  transcoding reintroduces gaps; Plex playback is direct play only (no
  transcoding fallback); no multi-server profiles (one signed-in provider
  and server at a time); search results are capped at 50 per category.
- **Install**: [Google Play](https://play.google.com/store/apps/details?id=studio.koeda.norrklang)
  (Android Automotive OS).
- **Support**: [GitHub issues](https://github.com/koedastudio/norrklang/issues).
- **Website**: [norrklang.app](https://norrklang.app).
- **Privacy**: [privacy policy](https://norrklang.app/privacy) — no analytics,
  no third parties; the app talks only to your server.
- **License**: [GPL-3.0-or-later](LICENSE) — the same license as Navidrome and
  the wider Subsonic client ecosystem.

## What's here

```
norrklang/
├── android/                  # Self-contained Gradle build for all Android apps
│   ├── app-automotive/       # Android Automotive OS APK (Polestar 2 & friends)
│   ├── app-mobile/           # Phone APK hosting Android Auto projection (not in the initial release)
│   ├── core-subsonic/        # Pure-JVM Subsonic/OpenSubsonic API client (Ktor)
│   ├── core-plex/            # Pure-JVM Plex client (plex.tv link + Plex Media Server)
│   ├── core-data/            # Session, settings (DataStore), repositories
│   ├── core-media/           # Media3 MediaLibraryService — browse tree + playback
│   ├── core-ui/              # Shared Compose UI (sign-in, settings, theme)
│   └── build-logic/          # Gradle convention plugins (SDK levels, signing, versioning)
├── www/                      # norrklang.app — Astro static site (landing + privacy policy)
├── docs/                     # Play store listing copy (below)
└── assets/                   # Brand sources (app icon + Play feature graphic) and Play store screenshots
```

The car never runs custom UI for browsing/playback — the OS renders the media
template from the browse tree that `core-media` serves. That's a platform
requirement for driver safety, and it's also why the same `core-media` browse
tree drives Android Auto head units unchanged when that form factor ships.

`core-subsonic` and `core-plex` are deliberately free of Android dependencies
so they can become the shared kernel (KMP) for future phone/desktop clients in
this monorepo.

## Features

- Sign in to a Navidrome/Subsonic server (token auth; the password is never
  stored) or link a Plex account with a code or QR scan — no password typed
  in the car
- Browse: Home with Quick play, Made for you (Best of / Similar to), Genre and
  Decade mixes, favourite artists and recently added tracks; Playlists;
  Library with all artists, all albums and the recently/most played, new and
  favourite album collections
- Full playback via ExoPlayer: queue, shuffle, seek, audio focus, artwork
- Favourites from the car UI: heart the playing track, heart albums while browsing
- Voice/keyboard search across artists, albums and tracks
- Play reporting back to the server (Navidrome scrobbles, Plex timelines),
  with on/off and per-artist/per-playlist exclusions
- Playback resumption after restarts; automatic recovery from network dropouts

## Guides

- [Play store listing copy](docs/play-store-listing.md)
- [Privacy policy](https://norrklang.app/privacy) (source: `www/src/pages/privacy.md`)

## Quick start (build + unit tests)

Requirements: a JDK (17+) to launch Gradle and the Android SDK. Gradle then
provisions its own pinned JDK 21 daemon (`gradle/gradle-daemon-jvm.properties`);
the SDK comes with Android Studio — point `local.properties` or `ANDROID_HOME`
at it.

```bash
cd android
./gradlew test                                   # JVM unit tests
./gradlew :app-automotive:assembleDebug          # AAOS APK
./gradlew :app-mobile:assembleDebug              # phone APK
```

### Release builds

`assembleRelease`/`bundleRelease` additionally need signing configured in
`android/keystore.properties` (gitignored):

```properties
storeFile=norrklang-upload.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Debug builds and tests never touch it; without the file, release tasks fail
with the same instructions.

### Shipping an AAOS release to Google Play

1. Bump `norrklang.version` in `android/gradle.properties` — new version name
   and an increased build number (see §Versioning) — and update
   `CHANGELOG.md`.
2. Build the automotive bundle (signing configured as above):

   ```bash
   cd android && ./gradlew :app-automotive:bundleRelease
   ```

   The artifact lands in
   `android/app-automotive/build/outputs/bundle/release/app-automotive-release.aab`.
3. Verify the artifact and run the test passes: check the signature
   (`jarsigner -verify`), the version code and declared features
   (`bundletool dump manifest`), and exercise the app on the Automotive
   emulator and a real car before promoting.
4. If the release changes what the app does or supports, refresh the store
   listing from [docs/play-store-listing.md](docs/play-store-listing.md)
   (edit the doc first, then paste into the Play Console).
5. Upload the `.aab` in the Play Console to a testing track and promote from
   there. Two Play settings matter for cars: the release must be opted out of
   **App integrity → automatic protection** (protected builds show Play's
   "Something went wrong" dialog in cars that wake up offline), and the
   bundle goes to the **Android Automotive OS** form factor of the single
   `studio.koeda.norrklang` listing.

## Contributing

Bug reports and pull requests are welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md). Security issues go through
[SECURITY.md](SECURITY.md), never public issues.

## Versioning

The app version is one line in `android/gradle.properties`, Flutter style:
`norrklang.version=<versionName>+<buildNumber>` (e.g. `0.1.1+1`) — the only
thing to edit before a release. Both APKs share the applicationId
`studio.koeda.norrklang` (one Play listing, one artifact per form factor);
version codes are derived in `build-logic` with a form-factor suffix
(mobile `…0`, automotive `…1`) so uploads never collide.

## License

Copyright (C) 2026 Koeda Studio

Norrklang is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for
more details.

Norrklang is a Subsonic API client: it talks to Navidrome over HTTP and
includes none of its code. The dependencies it does bundle (AndroidX, Media3,
Compose, Ktor) are Apache-2.0, which GPL-3.0 permits.
