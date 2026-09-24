package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StationLogoTest {

    @Test
    fun `apple touch icon wins and resolves against the page`() {
        val html = """
            <link rel="shortcut icon" href="/favicon.ico"/>
            <link rel="icon" type="image/png" href="/img/favicon-32x32.png" sizes="32x32"/>
            <link rel="apple-touch-icon" href="/img/ios_152px.png">
        """
        assertEquals(
            "https://www.kexp.org/img/ios_152px.png",
            StationLogo.pickIconUrl(html, "https://www.kexp.org/"),
        )
    }

    @Test
    fun `open graph image is used when there is no touch icon`() {
        val html = """
            <meta property="og:image" content="https://somafm.com/logos/400/dronezone400.png" />
            <link rel="icon" href="/favicon.ico" type="image/x-icon" />
        """
        assertEquals(
            "https://somafm.com/logos/400/dronezone400.png",
            StationLogo.pickIconUrl(html, "https://somafm.com/dronezone/"),
        )
    }

    @Test
    fun `the largest declared touch icon is preferred`() {
        val html = """
            <link rel='apple-touch-icon' sizes='76x76' href='/a76.png'>
            <link rel='apple-touch-icon' sizes='180x180' href='/a180.png'>
            <link rel='apple-touch-icon' sizes='120x120' href='/a120.png'>
        """
        assertEquals("https://x.example/a180.png", StationLogo.pickIconUrl(html, "https://x.example/p"))
    }

    @Test
    fun `tiny favicons, ico and svg never qualify`() {
        val html = """
            <link rel="icon" href="/favicon.ico" sizes="any" />
            <link rel="icon" href="/favicon.svg" type="image/svg+xml" />
            <link rel="icon" href="/favicon.png" type="image/png" sizes="48x48" />
            <link rel="icon" href="/unknown-size.png" />
        """
        assertNull(StationLogo.pickIconUrl(html, "https://x.example/"))
    }

    @Test
    fun `a large png favicon qualifies below an og image`() {
        val html = """
            <link rel="icon" href="/big.png" sizes="192x192" />
            <meta name="og:image" content="/share.jpg?v=2">
        """
        assertEquals("https://x.example/share.jpg?v=2", StationLogo.pickIconUrl(html, "https://x.example/"))
        assertEquals(
            "https://x.example/big.png",
            StationLogo.pickIconUrl("""<link rel="icon" href="/big.png" sizes="192x192">""", "https://x.example/"),
        )
    }

    @Test
    fun `garbage page urls and non-http icons are ignored`() {
        assertNull(StationLogo.pickIconUrl("""<link rel="apple-touch-icon" href="/a.png">""", "not a url"))
        assertNull(
            StationLogo.pickIconUrl("""<link rel="apple-touch-icon" href="data:image/png;base64,AAAA">""", "https://x.example/"),
        )
    }
}
