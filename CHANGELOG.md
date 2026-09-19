# Changelog

All notable changes to Norrklang, starting from the first public release.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow semver (`norrklang.version` in `android/gradle.properties`
is the single source of truth).

## [Unreleased]

### Added

- Library selection: choose which of the server's music libraries to show
  (Navidrome 0.58+ libraries, Plex music sections, Jellyfin music
  libraries) under Settings → Libraries; browsing, search, mixes and voice
  search follow the selection, playlists always show every library.
- Plex and Jellyfin sign-in asks which libraries to show when the server
  has several; existing sign-ins keep showing the one library they had
  until the selection is changed.
- Per-library play reporting exclusion next to the artist and playlist
  exclusions.
- Sign in to Subsonic servers that only accept the password itself, such as
  Nextcloud Music or LDAP-backed Subsonic: token auth is tried first, and
  the password is sent (and stored encrypted) only when the server rejects
  tokens.

## [1.2.1] — 2026-09-17

### Changed

- Add explicit exclusions for Android cloud backup and device-to-device
  transfer; privacy policy updated to say so.
- Update the README for Jellyfin, streaming quality and current limitations.
- Add regression coverage for playback account lifecycle, connectivity
  recovery, resumption, reporting and cache races.

### Fixed

- Keep each track's stream encoding stable across retries and seeks after a
  Wi-Fi/mobile-data handoff or a quality-setting change.
- Keep capped-bitrate (transcoded) tracks seekable with their real duration
  after a retry or seek, instead of ExoPlayer re-classifying them as live.
- Stop and clear playback when signing out or switching accounts; cancel
  the old account's background work and reject delayed reports, auth
  failures and resumption saves from that account.
- Count actual listening time for scrobbles, excluding seeks, pauses and
  buffering; submit completed repeat-one plays.
- Start at the beginning when a saved track has disappeared from its
  playlist or collection.
- Retry home mixes and eager queue restoration after connectivity returns,
  without replacing a queue the user has selected meanwhile.
- Prevent in-flight cache loads from undoing invalidation, and release
  per-key locks when their loads and waiters finish.

## [1.2.0] — 2026-09-06

### Added

- Jellyfin support: sign in with server address, username and password
  (only the access token is stored); browsing, mixes, favourites and play
  reporting on par with the other providers.
- Streaming quality tiers (Original / 320 / 192 / 128 kbps), set separately
  for Wi-Fi and mobile data. Defaults: Original on Wi-Fi, 320 kbps on
  mobile data. Replaces the "Stream original files" switch; an existing
  "off" setting carries over as 320 kbps.
- Assistant voice requests ("play <artist / album / song / playlist>")
  resolved against the library; "play some music" starts a random mix.

### Changed

- New launcher icon; browse and home tile icons switched to Material
  Symbols.
- Homepage and privacy policy updated for Jellyfin, streaming quality and
  the new icon.

## [1.1.0] — 2026-08-23

### Added

- Plex Media Server support: link a Plex account with a code or QR scan,
  direct-play streaming, favourites and play reporting.
- **Library** tab gathering artists, albums and the album collections;
  large lists split into alphabetical buckets.
- Decade and genre mixes, favourite artists and recently added tracks
  on the home tab.
- Autoplay: similar songs are appended when the queue runs out
  (on by default, toggle in Settings).
- Sectioned search results (artists / albums / tracks).
- Diagnostics screen with an on-device error log and QR problem reports.

### Changed

- Faster artwork loading (concurrent downloads).
- Tile artwork redesigned: collage covers show uncovered; icon-only tiles
  use a flat accent colour with an off-center faded icon motif.
- "Made for you" no longer repeats an artist across Best of and Similar to.
- Homepage and privacy policy updated for Plex and diagnostics.

### Fixed

- Crash loop on Android 12 cars (Polestar 4, Chevrolet Blazer EV):
  stream/artwork URLs were built with an Android 13+-only API.

### Security

- Diagnostic and crash logs strip server addresses and URL query strings,
  so auth tokens never appear on the diagnostics screen or in reports.

## [1.0.0] — 2026-08-06

Initial public release, on
[Google Play](https://play.google.com/store/apps/details?id=studio.koeda.norrklang)
as `studio.koeda.norrklang` (Android Automotive OS).

- Android Automotive OS media app for Navidrome: browse (home mixes, artists,
  albums, playlists), voice/keyboard search, ExoPlayer playback, favourites,
  scrobbling with exclusions, playback resumption and network recovery.
- Phone APK hosting Android Auto projection from the same codebase (built and
  tested, not part of the initial Play release).
- Project website [norrklang.app](https://norrklang.app) with the privacy policy.
