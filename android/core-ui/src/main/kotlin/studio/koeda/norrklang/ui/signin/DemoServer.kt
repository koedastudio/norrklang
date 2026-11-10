package studio.koeda.norrklang.ui.signin

import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicTokenAuth

/**
 * The public demo server's shared account. Only a salted token is shipped:
 * it works for the Subsonic API but not for the server's web login, which
 * needs the password itself.
 */
internal object DemoServer {
    val credentials = SubsonicCredentials(
        baseUrl = "https://demo-music.norrklang.app",
        username = "demo",
        auth = SubsonicTokenAuth(salt = "294bc937f0a2c6d6", token = "5304d0658c84cb2ab45ea5de0981059d"),
    )
}
