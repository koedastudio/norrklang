package studio.koeda.norrklang.media

/**
 * Alphabetical folder partitioning for browse lists too long to hand a car
 * host in one piece.
 *
 * Car hosts don't pass paging options (Android Auto and AAOS don't support
 * pagination), so a tab's children arrive as ONE list — and media3's legacy
 * bridge silently truncates that list to fit a ~256 KB binder transaction
 * (MediaUtils.truncateListBySize), a few hundred items. A tab that would be
 * truncated is served as A–Z letter folders instead (empty letters skipped,
 * non-letter initials under "#"); a letter that alone exceeds the budget
 * splits into second-letter ranges ("Sa–Sk", "Sl–Sz"), so the whole library
 * stays reachable.
 *
 * A bucket's identity is its selection RULE, not a list position: the media
 * id carries the letter or prefix range, so ids handed to a host stay valid
 * while the library changes underneath.
 */
internal object Buckets {

    /**
     * Max children handed to a non-paging host in one list. Browse items
     * parcel at well under 1 KB each, so this stays comfortably inside the
     * bridge's 256 KB budget. (A single two-char prefix with more items than
     * this — hundreds of albums all named "St…" — is served whole and left
     * to the bridge's truncation; subdividing further isn't worth the UX.)
     */
    const val MAX_UNPAGED_CHILDREN = 200

    /** One folder: [key] is its stable media-id payload, [label] its title. */
    data class Bucket<T>(val key: String, val label: String, val items: List<T>)

    /** Whether a flat list of [size] items must be served as buckets. */
    fun needed(size: Int): Boolean = size > MAX_UNPAGED_CHILDREN

    /**
     * Letter folders over [items], explicitly ordered "#", A–Z, then other
     * scripts (which is where Å/Ä/Ö and CJK sort in plain string order) —
     * NOT by appearance in the list: the server's collation and [letterOf]
     * can disagree (articles, diacritics, transliteration), and appearance
     * order then shuffles the folders. Items keep their server order inside
     * each folder. [letterOf] is the item's letter group ("A"…, "#");
     * [nameOf] feeds the sort prefixes used when a letter needs splitting.
     */
    fun <T> partition(
        items: List<T>,
        letterOf: (T) -> String,
        nameOf: (T) -> String,
    ): List<Bucket<T>> {
        val letters = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            letters.getOrPut(letterOf(item).lowercase()) { mutableListOf() }.add(item)
        }
        return letters.entries
            .sortedBy { it.key }
            .flatMap { (letter, members) ->
                if (members.size <= MAX_UNPAGED_CHILDREN) {
                    listOf(Bucket(letter, letter.uppercase(), members))
                } else {
                    splitLetter(letter, members, nameOf)
                }
            }
    }

    /**
     * The items a bucket [key] (from [partition]) selects out of [items].
     * Malformed or stale keys select nothing — an empty folder, not an error.
     */
    fun <T> select(
        items: List<T>,
        key: String,
        letterOf: (T) -> String,
        nameOf: (T) -> String,
    ): List<T> {
        val parts = key.split(RANGE_SEPARATOR)
        return when (parts.size) {
            1 -> items.filter { letterOf(it).lowercase() == key }
            3 -> {
                val (letter, from, to) = parts
                // Grouped and ordered exactly as [splitLetter] packs them, so
                // a folder's children always match what partition() put in it.
                items.filter { letterOf(it).lowercase() == letter }
                    .groupBy { prefix(nameOf(it)) }
                    .entries
                    .sortedBy { it.key }
                    .filter { it.key in from..to }
                    .flatMap { it.value }
            }
            else -> emptyList()
        }
    }

    /** The display label for a bucket [key], or null when malformed. */
    fun labelFor(key: String): String? {
        val parts = key.split(RANGE_SEPARATOR)
        return when (parts.size) {
            1 -> key.takeIf(String::isNotEmpty)?.uppercase()
            3 -> rangeLabel(parts[1], parts[2])
            else -> null
        }
    }

    /** Single-char letter group for [name]: its sort initial, or "#". */
    fun letterKey(name: String): String {
        val first = sortKey(name).firstOrNull() ?: return HASH
        return if (first.isLetter()) first.uppercase() else HASH
    }

    /**
     * Sort key approximating the servers' ignored-articles collation, so
     * subdivision prefixes line up with the list order the server returns.
     * '/', '|' and ':' are dropped — they'd collide with the media-id
     * syntax when a prefix rides in a bucket key.
     */
    fun sortKey(name: String): String {
        val lowered = name.trim().lowercase()
            .filterNot { it == '/' || it == '|' || it == RANGE_SEPARATOR }
        for (article in ARTICLES) {
            if (lowered.startsWith(article)) {
                val stripped = lowered.removePrefix(article).trimStart()
                if (stripped.isNotEmpty()) return stripped
            }
        }
        return lowered
    }

    /**
     * [letter]'s members as second-letter range folders, adjacent prefixes
     * packed greedily up to the budget. Prefixes are sorted so the ranges
     * are disjoint and ascending even where the server's collation and
     * [sortKey] disagree (diacritics, unusual articles).
     */
    private fun <T> splitLetter(
        letter: String,
        members: List<T>,
        nameOf: (T) -> String,
    ): List<Bucket<T>> {
        val prefixes = members.groupBy { prefix(nameOf(it)) }.entries.sortedBy { it.key }
        val buckets = mutableListOf<Bucket<T>>()
        var from = ""
        var to = ""
        var acc = mutableListOf<T>()
        fun flush() {
            if (acc.isEmpty()) return
            val key = "$letter$RANGE_SEPARATOR$from$RANGE_SEPARATOR$to"
            buckets += Bucket(key, rangeLabel(from, to), acc)
            acc = mutableListOf()
        }
        for ((prefix, group) in prefixes) {
            if (acc.isNotEmpty() && acc.size + group.size > MAX_UNPAGED_CHILDREN) flush()
            if (acc.isEmpty()) from = prefix
            to = prefix
            acc.addAll(group)
        }
        flush()
        return buckets
    }

    private fun rangeLabel(from: String, to: String): String {
        val f = from.replaceFirstChar { it.uppercaseChar() }
        val t = to.replaceFirstChar { it.uppercaseChar() }
        return if (f == t) f else "$f–$t"
    }

    private fun prefix(name: String): String = sortKey(name).take(2)

    private const val HASH = "#"
    private const val RANGE_SEPARATOR = ':'
    private val ARTICLES = listOf("the ", "an ", "a ")
}
