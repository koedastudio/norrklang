#!/usr/bin/env bash
# Guards against JDK-only API references reaching an APK.
#
# Why this exists: core-subsonic and core-plex are pure-JVM Gradle modules, so
# Android lint NEVER analyzes them and the Kotlin compiler resolves any method
# the build JDK has — including ones Android lacks below API 33. That shipped
# the same NoSuchMethodError twice (SubsonicUrlBuilder 1.0.x, PlexUrlBuilder
# 1.1.0): URLEncoder.encode(String, Charset) crashing every Android 12 car.
# The built dex is the only ground truth, so this script scans it.
#
# Usage:
#   scripts/check-dex-api.sh [release|debug]
#
#   release (default) — scans the R8-merged dex (app code + all libraries,
#     dead guarded branches already stripped). The pre-upload gate:
#       ./gradlew :app-automotive:assembleRelease && scripts/check-dex-api.sh
#   debug — scans only first-party dex (project + our library modules). For
#     CI, which builds debug (no release keystore there); external libraries
#     are excluded because their SDK_INT-guarded new-API calls would false-
#     positive before R8 strips them:
#       ./gradlew :app-automotive:assembleDebug && scripts/check-dex-api.sh debug
set -euo pipefail

cd "$(dirname "$0")/.."

variant="${1:-release}"
case "$variant" in
  release)
    dex_files=(app-automotive/build/intermediates/dex/release/minifyReleaseWithR8/classes*.dex)
    assemble_hint=":app-automotive:assembleRelease"
    ;;
  debug)
    dex_files=(
      app-automotive/build/intermediates/dex/debug/mergeProjectDexDebug/*/classes*.dex
      app-automotive/build/intermediates/dex/debug/mergeLibDexDebug/*/classes*.dex
    )
    assemble_hint=":app-automotive:assembleDebug"
    ;;
  *)
    echo "check-dex-api: unknown variant '$variant' (use release or debug)" >&2
    exit 2
    ;;
esac

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
DEXDUMP=$(ls "$sdk"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1)
[ -n "$DEXDUMP" ] || { echo "check-dex-api: no dexdump under $sdk/build-tools" >&2; exit 2; }

ls "${dex_files[@]}" >/dev/null 2>&1 || {
  echo "check-dex-api: no $variant dex found — run $assemble_hint first" >&2
  exit 2
}

refs=$("$DEXDUMP" -d "${dex_files[@]}" 2>/dev/null \
  | grep -oE "Ljava/[a-zA-Z0-9/_\$]+;\.[a-zA-Z0-9_<>]+:\([^)]*\)[^ /]*" | sort -u)

# Each pattern is a JDK API family absent below the app's supported floor
# (minSdk 28, verified against API 32 — the newest Android our own cars can't
# test). Charset-parameter overloads are matched broadly and the API-1-safe
# ones are allowlisted, because that family is exactly what shipped twice.
banned=$(echo "$refs" | grep -E \
  -e "Ljava/nio/charset/Charset;\)" \
  -e "readAllBytes|readNBytes|;\.transferTo:\(Ljava/io/OutputStream;\)" \
  -e "Ljava/util/Optional;\.isEmpty" \
  -e "Ljava/lang/String;\.(strip|isBlank|lines|repeat|chars):" \
  -e "toUnmodifiable(List|Set|Map)" \
  -e "Ljava/nio/file/Files;\.(readString|writeString|mismatch)" \
  -e "Ljava/net/http/" \
  | grep -vE \
    -e "Ljava/io/InputStreamReader;\.<init>" \
    -e "Ljava/io/OutputStreamWriter;\.<init>" \
    -e "Ljava/lang/String;\.<init>" \
    -e "Ljava/lang/String;\.getBytes" \
    -e "Ljava/io/PrintStream;\.<init>" \
  || true)

if [ -n "$banned" ]; then
  echo "check-dex-api: FAIL — $variant dex references JDK APIs missing on in-support Android versions:" >&2
  echo "$banned" >&2
  echo "(Fix the call site to an API-28-safe overload; see SubsonicUrlBuilder.urlEncode.)" >&2
  exit 1
fi

echo "check-dex-api: OK [$variant] ($(echo "$refs" | wc -l | tr -d ' ') java.* references scanned, none banned)"
