# Google Play store listing

The canonical copy for the `studio.koeda.norrklang` listing (Android
Automotive OS form factor). When the listing changes, edit it **here first**,
then paste into Play Console → **Grow users → Store presence → Main store
listing**. Keep this file and the live listing in sync.

Play's limits: app name ≤ 30 characters, short description ≤ 80, full
description ≤ 4000. All three are indexed for search, so Navidrome/Plex/
Subsonic should stay spelled out.

## App name

```
Norrklang - Navidrome & Plex
```

## Short description

```
Play your self-hosted Navidrome or Plex library, in your car.
```

## Full description

```
Norrklang is a music player for Navidrome and Plex, built for the screen in
your car.

You need your own server. Norrklang connects to a Navidrome server — or any
server speaking Subsonic API 1.16.1 — or to a Plex Media Server with a music
library. It is not a streaming service and ships with no music of its own.

In the car
• Browse your library: artists, albums, playlists and favourites
• Home screen with Quick play, your favourite artists, recently added tracks
and generated mixes — Best of, Similar to, genres and decades
• Full playback: queue, shuffle, seek, cover art
• Autoplay keeps the music going with similar songs when the queue runs out
(can be turned off)
• Add albums and songs as favourites while listening
• Voice and keyboard search across artists, albums and tracks
• Play counts reported to your own server, with per-artist and per-playlist
exclusions — or turned off entirely
• Picks up where you left off after the car restarts

Navidrome and Subsonic
• Sign in with your server address and account. The password is never stored
— only a token, encrypted on the device
• HTTPS required

Plex
• Link your Plex account with a short code or QR scan — no password typed in
the car
• Plays directly from your own Plex Media Server

Private by design
No ads, no analytics, no crash reporting, no third-party services. The app
talks only to your server (plus plex.tv to link a Plex account). Credentials
are encrypted and stay on the device. Diagnostics stay on the device too and
are shared only if you choose to submit a problem report.

Norrklang is open source (GPL-3.0-or-later):
https://github.com/koedastudio/norrklang
Privacy policy: https://norrklang.app/privacy
```

## Release notes ("What's new", ≤ 500 characters)

For the 1.1.0 release:

```
• Plex support — link your Plex account and play from your own Plex Media Server
• New Library tab gathering artists, albums and your collections
• Favourite artists, recently added tracks, genre and decade mixes on Home
• Autoplay keeps playing similar songs when the queue runs out
• Search results grouped by artists, albums and tracks
• Faster artwork loading
• On-device diagnostics with QR problem reports — nothing leaves the car
• Fixed a crash loop on Android 12 cars
```
