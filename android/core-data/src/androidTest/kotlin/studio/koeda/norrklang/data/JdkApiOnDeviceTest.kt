package studio.koeda.norrklang.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.koeda.norrklang.plex.PlexUrlBuilder
import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicTokenAuth
import studio.koeda.norrklang.subsonic.SubsonicUrlBuilder

/**
 * Executes the pure-JVM modules' hot paths on a real Android runtime.
 *
 * Those modules (core-subsonic, core-plex) compile against the build JDK and
 * are invisible to Android lint, so a JDK-17-only call crashes old cars with
 * NoSuchMethodError while every JVM unit test stays green — it shipped twice
 * (SubsonicUrlBuilder 1.0.x, PlexUrlBuilder 1.1.0, both
 * `URLEncoder.encode(String, Charset)`, API 33+). scripts/check-dex-api.sh is
 * the static gate for known-bad reference families; this test is the dynamic
 * one — it fails on ANY missing JDK member in these paths, listed or not.
 *
 * Run it against the OLDEST practical AVD, not the newest: a phone image is
 * fine (no automotive image exists below API 33, and nothing here is
 * automotive-specific):
 *
 *   sdkmanager "system-images;android-32;google_apis;arm64-v8a"
 *   avdmanager create avd -n norrklang_api32 \
 *     -k "system-images;android-32;google_apis;arm64-v8a" -d pixel_5
 *   emulator -avd norrklang_api32 &
 *   ./gradlew :core-data:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class JdkApiOnDeviceTest {

    @Test
    fun subsonicUrlBuildingRunsOnThisAndroidVersion() {
        val urls = SubsonicUrlBuilder(
            SubsonicCredentials(
                baseUrl = "https://music.example",
                username = "user",
                auth = SubsonicTokenAuth(salt = "salt", token = "token"),
            ),
        )
        // Values that force actual percent/plus encoding work.
        val stream = urls.streamUrl("id with space & ampersand")
        assertTrue(stream, "id=id+with+space+%26+ampersand" in stream)
        val cover = urls.coverArtUrl("al-1")
        assertTrue(cover, "getCoverArt" in cover)
    }

    @Test
    fun plexUrlBuildingRunsOnThisAndroidVersion() {
        val urls = PlexUrlBuilder(baseUrl = "https://plex.example:32400", token = "tok en+&")
        val part = urls.partUrl("/library/parts/1/2/file.flac")
        assertTrue(part, "X-Plex-Token=tok+en%2B%26" in part)
        val art = urls.artworkUrl("/library/metadata/1/thumb/2")
        assertTrue(art, "url=%2Flibrary%2Fmetadata%2F1%2Fthumb%2F2" in art)
    }
}
