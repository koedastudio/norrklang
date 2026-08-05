# Release checklist

Run this against the **release build** (`bundleRelease`/`assembleRelease` —
minified, HTTPS-only) before promoting any release. It extends the
[manual test checklist](manual-test-checklist.md) (basic browse/playback),
which is assumed to pass first. Items marked **[car]** must run at least once
on real hardware per release; the rest can run on emulators.

## Accounts and servers

- [ ] **Two-server switch**: sign in to server A, browse, play a track. Sign
      out, sign in to server B (different library). Verify: no album/artist/
      playlist from A appears anywhere (browse tabs, home sections, search),
      artwork never shows A's covers (even for identical cover-art ids), and
      the "resume" queue does not offer A's track.
- [ ] **Same server, different account**: repeat with two accounts on one
      server that have different playlists — playlists tab must switch
      completely.
- [ ] **Auth revocation**: change the account password server-side while
      signed in. Next browse/play must surface the sign-in affordance in the
      car UI (no crash, no infinite spinner).
- [ ] **Credential storage**: after sign-in, `adb shell` (debuggable build) —
      confirm the DataStore file contains no plaintext token (`enc1:` values
      only). Confirm both manifests carry `allowBackup="false"`.

## Large libraries

- [ ] **500+ albums**: against a library with more than 500 albums, scroll
      the Albums tab to the very end — the album count must match the server
      (Navidrome UI shows the total). Check first/last alphabetical entries.
- [ ] Artists with many releases and playlists with hundreds of tracks load
      completely.
- [ ] Search returns results in all three groups (songs/albums/artists).

## Network loss and recovery

- [ ] Kill connectivity mid-playback (airplane mode / pull the emulator's
      network): playback rides out short gaps on the buffer; on longer
      outages it errors gracefully without a crash or ANR.
- [ ] Restore connectivity: playback resumes **by itself** within ~30 s
      (PlaybackRecoveryListener) — no play-button mashing; browsing works
      again without an app restart.
- [ ] Play Console → App integrity → **Automatic protection is OFF** for the
      release (a protected build shows Play's "Something went wrong" dialog
      in cars that wake up offline).
- [ ] Launch the app with no connectivity: browse shows an error state, and
      recovers once online.

## Playback resumption

- [ ] Play a track ~2 minutes in, force-stop the app (`adb shell am
      force-stop studio.koeda.norrklang`), relaunch via the car media UI:
      the control bar offers the same track within ~10 s of the stopped
      position (periodic save), not the position of the last pause.
- [ ] Resumption works after a device reboot.
- [ ] Resumption is silently empty when the server is unreachable (no crash).

## Permissions (mobile app)

- [ ] Fresh install on Android 13+: playback works with the notification
      permission **denied** (media notification is exempt, but verify no
      prompt loops or crashes).
- [ ] Grant the permission later: notification appears on next playback.

## Form factors

- [ ] **AAOS emulator**: full pass on the Automotive emulator, including the
      Polestar 2 image if available.
- [ ] **Android Auto (DHU)** _(not shipping initially — regression check only)_:
      pass of the manual checklist via the desktop head unit.
- [ ] **[car] Real car**: browse, voice search ("play <artist>"), playback
      through drive/park transitions, sign-out/sign-in from the car settings
      entry, resumption after leaving and re-entering the car.

## Release hygiene

- [ ] `./gradlew test` green; both bundles build **only** with
      `keystore.properties` present (build must fail loudly without it).
- [ ] Artifact verification done: upload-key signature checked
      (`jarsigner -verify` on each `.aab`) and version codes confirmed via
      `bundletool dump manifest` — `…1` + the `android.hardware.type.automotive`
      feature in the automotive bundle, `…0` without it in the mobile bundle
      (scheme: README §Versioning).
- [ ] Privacy policy URL live; demo server + reviewer account working.
- [ ] `CHANGELOG.md` updated; version bumped.
