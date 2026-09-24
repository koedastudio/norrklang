package studio.koeda.norrklang.ui.signin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleartextServerPolicyTest {

    @Test
    fun `release refuses explicit http addresses only`() {
        val release = CleartextServerPolicy(allowed = false)
        assertTrue(release.rejects("http://music.local:4533"))
        assertTrue(release.rejects("  HTTP://music.local "))
        assertFalse(release.rejects("https://music.example.com"))
        // Bare hosts get https:// prepended downstream.
        assertFalse(release.rejects("music.example.com"))
        assertFalse(release.rejects("httpd.example.com"))
    }

    @Test
    fun `debug allows http for local test servers`() {
        assertFalse(CleartextServerPolicy(allowed = true).rejects("http://192.168.1.10:4533"))
    }
}
