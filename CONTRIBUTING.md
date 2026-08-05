# Contributing to Norrklang

Contributions are welcome. This page is short on purpose — when in doubt,
open an issue and ask.

## Before you start

- **Bugs**: open an issue using the bug template. Server version, car model
  (or emulator image) and app version matter — car hosts differ a lot.
- **Features**: open an issue first. The car renders its own UI from the
  browse tree, so many ideas are constrained by what the AAOS media template
  allows; agreeing on an approach up front avoids throwaway work.
- **Security**: never in public issues — see [SECURITY.md](SECURITY.md).

## Building

JDK 21 and the Android SDK (details in the [README](README.md#quick-start-build--unit-tests)).

```bash
cd android
./gradlew test                                   # the merge gate
./gradlew :app-automotive:assembleDebug          # AAOS APK
./gradlew :app-mobile:assembleDebug              # phone APK
```

Website: `cd www && npm ci && npm run check && npm run build`.

## Pull requests

- Keep them small and focused — one change per PR.
- `./gradlew test` must pass, and new behaviour needs tests (the core modules
  are deliberately unit-testable on the JVM).
- Match the surrounding style; `.editorconfig` is authoritative.
- No new dependencies without discussing them in the issue first.
- For changes visible in the car UI, run the relevant items of the
  [manual test checklist](docs/manual-test-checklist.md) and say which ones
  in the PR description.

By contributing you agree that your work is licensed under the project
license, [GPL-3.0-or-later](LICENSE).
