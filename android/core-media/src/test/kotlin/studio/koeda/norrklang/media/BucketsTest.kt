package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketsTest {

    private fun letterOf(name: String) = Buckets.letterKey(name)

    private fun partition(names: List<String>) =
        Buckets.partition(names, ::letterOf) { it }

    private fun select(names: List<String>, key: String) =
        Buckets.select(names, key, ::letterOf) { it }

    @Test
    fun `bucketing kicks in only past the unpaged limit`() {
        assertFalse(Buckets.needed(0))
        assertFalse(Buckets.needed(Buckets.MAX_UNPAGED_CHILDREN))
        assertTrue(Buckets.needed(Buckets.MAX_UNPAGED_CHILDREN + 1))
    }

    @Test
    fun `folders come out hash then A to Z regardless of list order`() {
        val names = listOf("Bowie", "Abba", "2Pac", "Cardigans", "Ace of Base")
        val buckets = partition(names)
        assertEquals(listOf("#", "a", "b", "c"), buckets.map { it.key })
        assertEquals(listOf("#", "A", "B", "C"), buckets.map { it.label })
        // Items keep their incoming (server) order inside a folder.
        assertEquals(listOf("Abba", "Ace of Base"), buckets[1].items)
        assertEquals(listOf("2Pac"), buckets[0].items)
    }

    @Test
    fun `non-latin letters sort after Z, not by appearance`() {
        val names = listOf("有頂天", "Örebro Ensemble", "Abba", "Zoot")
        assertEquals(listOf("a", "z", "ö", "有"), partition(names).map { it.key })
    }

    @Test
    fun `leading articles do not become their own letters`() {
        val buckets = partition(listOf("Beach House", "The Beatles", "A Tribe Called Quest"))
        assertEquals(listOf("b", "t"), buckets.map { it.key })
        assertEquals(listOf("Beach House", "The Beatles"), buckets[0].items)
        assertEquals(listOf("A Tribe Called Quest"), buckets[1].items)
    }

    @Test
    fun `every bucket key selects exactly its own members`() {
        val names =
            (1..Buckets.MAX_UNPAGED_CHILDREN + 50).map { "Sample %04d".format(it) } +
                listOf("Abba", "The Beatles", "2Pac", "Zoot")
        val buckets = partition(names)
        for (bucket in buckets) {
            assertEquals(
                bucket.items,
                select(names, bucket.key),
                "key ${bucket.key} did not round-trip",
            )
        }
        // Together the buckets cover everything exactly once.
        assertEquals(names.sorted(), buckets.flatMap { it.items }.sorted())
    }

    @Test
    fun `oversized letters split into ranged folders within the budget`() {
        // 2.5 budgets of "S" names spread across second letters.
        val second = "abcdefghij"
        val names = (0 until Buckets.MAX_UNPAGED_CHILDREN * 5 / 2).map {
            "S${second[it % second.length]}-band $it"
        }
        val buckets = partition(names)
        assertTrue(buckets.size >= 3, "expected a split, got ${buckets.size} bucket(s)")
        for (bucket in buckets) {
            assertTrue(bucket.items.size <= Buckets.MAX_UNPAGED_CHILDREN)
            assertTrue(bucket.key.startsWith("s:"), "unexpected key ${bucket.key}")
            assertEquals(bucket.items, select(names, bucket.key))
        }
        assertEquals(names.size, buckets.sumOf { it.items.size })
    }

    @Test
    fun `range labels span their prefixes`() {
        assertEquals("Sa–Sk", Buckets.labelFor("s:sa:sk"))
        assertEquals("St", Buckets.labelFor("s:st:st"))
        assertEquals("A", Buckets.labelFor("a"))
        assertEquals("#", Buckets.labelFor("#"))
        assertNull(Buckets.labelFor(""))
        assertNull(Buckets.labelFor("s:sa"))
    }

    @Test
    fun `stale or garbage keys select nothing`() {
        val names = listOf("Abba", "Bowie")
        assertEquals(emptyList(), select(names, "q"))
        assertEquals(emptyList(), select(names, "three"))
        assertEquals(emptyList(), select(names, "a:aa:ab:ac"))
    }

    @Test
    fun `media-id syntax characters cannot leak into keys`() {
        // '/', '|' and ':' in names are dropped from sort keys, so a
        // range key built from them stays parseable as a media id.
        assertEquals("A", Buckets.letterKey("|/:Abba"))
        assertEquals("ab", Buckets.sortKey("A/B:C|").take(2))
    }
}
