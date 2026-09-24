package studio.koeda.norrklang.ui.signin

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Whether a plain `http://` server address may be signed in to. The network
 * security config permits cleartext so radio streams play, so this is what
 * keeps server traffic (credentials, tokens) on HTTPS: only debug builds
 * allow cleartext, for local test servers.
 */
class CleartextServerPolicy(val allowed: Boolean) {

    @Inject constructor(@ApplicationContext context: Context) :
        this(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

    /** True when [serverUrl] is an explicit cleartext address this build refuses. */
    fun rejects(serverUrl: String): Boolean =
        !allowed && serverUrl.trim().startsWith("http://", ignoreCase = true)
}
