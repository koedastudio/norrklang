---
layout: ../layouts/Legal.astro
title: Privacy Policy
description: What Norrklang stores, what it sends, and to whom. No analytics, no third parties.
effectiveDate: 2026-09-25
---

Norrklang is a music player for [Navidrome](https://www.navidrome.org/) and
Subsonic-compatible servers, [Plex](https://www.plex.tv/) Media Server and
[Jellyfin](https://jellyfin.org/), published by Koeda Studio for Android
Automotive OS. It is a client for a server **you** choose and control. This policy
describes what the app itself does with data, plus how this website is
hosted.

## What the app stores, and where

All data the app stores stays **on your device**:

- **Server connection details** — the server address, your username, and an
  authentication token. For Navidrome/Subsonic the token is derived from your
  password once at sign-in and the password itself is not retained — unless
  your server cannot verify such tokens (Nextcloud Music, LDAP-backed
  Subsonic), in which case the password is stored instead, protected the
  same way as a token; for
  Jellyfin it is the access token your server issues at sign-in (the password
  is sent to your server once and never retained); for Plex it is the token
  plex.tv issues when you link the device (the app never sees your Plex
  password at all). Whichever token it is, it is encrypted with a key that never
  leaves the device's Android Keystore, so the stored value is useless
  anywhere else. The app also opts out of Android's automatic backup and
  device-to-device transfer, so none of its data is copied into cloud backups
  or onto a new device.
- **Playback state** — the last played track and position, so playback can
  resume after a restart.
- **Radio** — which of your server's internet radio stations you have
  listened to and how often (the 50 most listened, so Home can show the ones
  you play most), and the songs you heart while a station plays: title,
  station name and time, up to 500 entries in a file on the device, listed
  under **Settings → Saved from radio** until you remove them. Your server
  keeps no radio play counts, and none of this is sent to it.
- **Your settings** — which of your server's music libraries to show,
  whether scrobbling is on, any artists, playlists or libraries you exclude
  from it, autoplay, and the streaming quality tier for Wi-Fi and for
  mobile data. Which of the two applies is decided on the device from the
  car's current connection; nothing about the network is sent anywhere.
- **Caches** — library listings and cover art fetched from your server, kept
  per account. Listings are held in memory only and disappear when the app
  stops; cached cover art is stored in the app's cache area and deleted when a
  different account signs in.
- **Diagnostics** — a short log of recent errors and the last crash, shown on
  the in-car **Settings → Diagnostics** screen to help you report problems.
  Entries are sanitized before they are written: server addresses and URL
  query strings — which can carry authentication tokens — are stripped. The
  log never leaves the device on its own (see below for the report flow).

Signing out deletes the stored connection details, your settings, the
playback state and the radio listening history. Two things stay: the songs
saved from radio, which hold titles and station names rather than anything
about the account, until you clear the list; and the random device identifier
the app mints for Plex, so that linking again does not register a duplicate
device on your Plex account — it identifies the install, not you. Uninstalling
the app deletes everything.

## What the app sends, and to whom

The app communicates **with the server you configure** — and with nothing
else, apart from the plex.tv exception below:

- browsing and search queries, to show your library
- audio streaming requests, to play music
- playback reports ("scrobbles"), so your server can track play counts
- favourite updates you trigger (e.g. the heart button in the car)

If you sign in with Plex, the app also talks to **plex.tv**, Plex's own
service: at sign-in to link the device, and to list your servers and their
connection addresses. Your music library, streaming and play reports still go
only to your own Plex Media Server. Plex's handling of that service traffic is
governed by [Plex's privacy policy](https://www.plex.tv/about/privacy-legal/),
not this one. Jellyfin sign-in involves no such intermediary: the app talks to
your Jellyfin server alone.

**Internet radio** is the one case where audio comes from somewhere other than
your server. Your Navidrome/Subsonic server only lists the stations; when you
play one, the car streams from the station's own server, at the address your
server lists for it, so that station's operator sees what it sees of any
listener — the stream request and your car's IP address. Some stations offer
only plain `http://` streams, which the app plays as they are; the connection
to your own server stays HTTPS.

The **Diagnostics** screen can show a QR code that lets you share the
sanitized log when reporting a problem. The car uploads nothing: the log is
encoded into the link itself (in the URL fragment, which browsers do not send
to any server), and the page at norrklang.app/report decodes it locally on
your phone. The report reaches the developers only if you then choose to
submit the pre-filled GitHub issue, under
[GitHub's privacy policy](https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement).

The **Saved from radio** screen offers a QR code that works the same way: the
list is encoded into the link itself and the page at norrklang.app/songs
decodes it on your phone. Nothing is uploaded by the car or by the page.

Norrklang has **no analytics, no advertising, no crash reporting, and no
third-party services** — the app bundles no Google or other third-party
tracking libraries. Koeda Studio receives no data from the app, so there is
nothing on its side to export or delete on request: everything is on your
device and on your server. What your server operator logs is governed by that
server's own policies — if you use someone else's Navidrome server, ask them.

Installing and updating the app goes through Google Play, which is covered by
[Google's privacy policy](https://policies.google.com/privacy), not this one.

## Data shared with the car

On Android Automotive OS, the car's media interface displays your library
metadata (titles, artists, cover art) and playback state. That data stays
within the car's media system; the app grants it no access to your
credentials.

Voice search and "play …" requests are handled by the car's own assistant:
it transcribes what you say and hands Norrklang the resulting text, which the
app matches against your library by sending it to your server as a search
query. The recording never reaches the app or the developer, and
how the car handles it is governed by the car maker's policies.

## This website

norrklang.app is a static site: it sets no cookies and uses no analytics. The
only scripts it runs are on the /report and /songs pages, which decode the link
you scanned inside your browser and contact no server. It is served by [Cloudflare](https://www.cloudflare.com/privacypolicy/),
which as the hosting provider processes standard request metadata (such as IP
address and requested URL) to deliver the site and protect it from abuse.
Koeda Studio does not collect or retain visitor logs.

## Children

Norrklang is not directed at children and collects no personal data from
anyone.

## Changes

Changes to this policy will be published on this page and noted in the
[project changelog](https://github.com/koedastudio/norrklang/blob/main/CHANGELOG.md)
with a new effective date.

## Contact

Questions or concerns: open an issue at
<https://github.com/koedastudio/norrklang/issues>.

