# Manual test checklist

Run on the AAOS emulator for every notable change; on the Polestar 2 emulator
(and a release build) before any Play upload. Needs a reachable Navidrome
server with a few artists, albums and at least one playlist.

## Sign-in

1. [ ] Fresh install → open Norrklang in the car → "Sign in to your Navidrome server" message with a **Sign in** button appears in the media UI
2. [ ] Sign in with a **wrong password** → clear error, form stays usable
3. [ ] Sign in with an **unreachable URL** → network error message
4. [ ] Sign in with correct details → form closes, browse tabs appear without restarting the app

## Browse

5. [ ] Four tabs render: **Home, Artists, Albums, Playlists**
6. [ ] Home shows headed sections of tiles: **Quick play** (Random mix, Favourites) and **Browse** (Recently played, Most played, New albums, Favourite albums — in that order) — labels untruncated
7. [ ] Favourites opens the starred-track list; the Browse tiles open album grids **with artwork** (Recently played/Most played need some play history first; before any playback they open empty lists)
8. [ ] Random mix opens a ~50-track list; backing out and re-entering immediately shows the **same** list
9. [ ] While the mix plays, re-entering it (even minutes later) still shows the same list; after playing something else and waiting >1 min, re-entering shows a **new** list
10. [ ] On a server with the Last.fm agent enabled and some play history: a **Made for you** section appears between Quick play and Genre mixes shortly after connect (it may pop in a beat after the tab renders), with up to 3 **Best of <artist>** tiles followed by up to 3 **Similar to <artist>** tiles (6 max in total), artist artwork where available
10a. [ ] A Best of tile opens the artist's popular tracks (~10–20, that artist only); re-entering within the drive shows the **same** list
11. [ ] A Similar to tile opens a 20–50 track list mixing several artists — no two adjacent tracks by the same artist, no artist appearing more than ~5 times
12. [ ] Backing out, re-entering, and returning to Home later in the same drive shows the **same** tiles in the same order with the same tracks; restarting the app produces a fresh selection and order
13. [ ] On a Navidrome server with the Last.fm agent disabled (`ND_LASTFM_ENABLED=false`), the Made for you section (Best of and Similar to alike) is entirely absent and Home is otherwise normal
14. [ ] On a library with genre tags: a **Genre mixes** section appears before Browse (shortly after connect) with up to 6 tiles for the biggest genres, artwork from an album of each genre; a tile opens a ~50-track list of that genre only, and re-entering shows the **same** list within the drive
15. [ ] A **Decade mixes** section follows Genre mixes with one tile per populated decade (e.g. **1980s**), oldest first; a tile opens a ~50-track list from that decade only, same-list stability as above
16. [ ] On a library without genre tags (or years), the Genre mixes (or Decade mixes) section is entirely absent and Home is otherwise normal
17. [ ] Artists tab lists artists alphabetically under **A–Z letter headers** (matching Navidrome's index); tapping one shows that artist's releases
18. [ ] Albums tab shows the album grid; tapping an album lists its tracks in order (subtitle shows artist · year)
19. [ ] Playlists tab lists Navidrome playlists with track counts; tapping one lists its tracks

## Playback

20. [ ] Tapping track N in an album starts playback at track N and the queue is the whole album (skip-next goes to N+1)
21. [ ] Same from a playlist
22. [ ] Tapping track N in the Random mix plays it and the queue is exactly the list that was on screen (skip-next goes to N+1)
23. [ ] Same from a Best of mix, a Similar to mix, a Genre mix and a Decade mix
24. [ ] Play/pause, skip next/previous, and seek all work from the car controls
    — known emulator quirk: the stock AAOS emulator image tints prev/next so
    dark (~10% contrast on the Now Playing background) they look disabled.
    They work; it's the emulator's own Media Center theming, which only OEM
    overlays can change — not something the app can influence. Real cars ship
    their own themes and don't have this problem.
25. [ ] Now-playing shows title, artist and **artwork**
26. [ ] Track transition auto-advances with correct metadata
27. [ ] Audio focus: trigger a notification sound / assistant → music ducks or pauses and resumes properly
28. [ ] Shuffle mid-album (after a few tracks): playback continues through **all** remaining tracks, in random order, before stopping — never stops after just one or two
29. [ ] With shuffle already on, start a different album/playlist → same: the whole list plays in random order

## Search (car quality review tests this)

30. [ ] Voice/keyboard search for an artist, an album and a song title returns results; tapping a song plays it

## Resilience

31. [ ] Force-stop the app (or reboot the emulator) → reopen → still signed in, library loads
32. [ ] Playback resumption: play something, restart the car/emulator → the media UI offers to resume where you left off
33. [ ] Reboot while the Random mix was playing → resume starts on the saved track, followed by a fresh mix; browsing Random mix shows that same queue
34. [ ] Reboot while a Similar to (or Best of) mix was playing → resume starts on the saved track, followed by a regenerated mix for that artist; browsing the tile shows that same queue
35. [ ] Reboot while a Genre or Decade mix was playing → resume starts on the saved track, followed by a fresh mix for that genre/decade; browsing the tile shows that same queue
36. [ ] Sign out from Settings (car settings entry for the app) → media UI returns to the sign-in prompt; after signing back in, the Made for you (Best of + Similar to), Genre mixes and Decade mixes sections regenerate

## Server side

37. [ ] While a track plays, Navidrome web UI shows it under "now playing"
38. [ ] After listening past the halfway point, the play count/scrobble is registered in Navidrome
38a. [ ] Settings → **Report plays** off: neither "now playing" nor a play count appears in Navidrome; back on, both work again
38b. [ ] Settings → **Excluded artists**: search finds an artist, checking it moves it to the pinned Excluded group; plays of that artist (from any album, playlist or mix) register nothing in Navidrome, other artists still do
38c. [ ] Settings → **Excluded playlists**: check a playlist; playing tracks **from that playlist** registers nothing, playing the same track from its album still registers; exclusion counts show on the settings rows and survive an app restart

## Phone / Android Auto (DHU)

39. [ ] Phone app: sign-in works; now-playing strip appears and play/pause works
40. [ ] DHU: app appears in the media grid, browse + playback work, artwork renders
