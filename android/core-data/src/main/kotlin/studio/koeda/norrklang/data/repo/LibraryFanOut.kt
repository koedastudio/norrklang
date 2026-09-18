package studio.koeda.norrklang.data.repo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/*
 * Helpers for repositories whose server takes one library per request
 * (Plex sections, Jellyfin views): query each selected library, then merge.
 */

/** Runs [fetch] for every id concurrently, keeping [ids] order. */
internal suspend fun <T> fanOut(
    ids: List<String>,
    fetch: suspend (String) -> List<T>,
): List<Pair<String, List<T>>> = coroutineScope {
    ids.map { id -> async { id to fetch(id) } }.awaitAll()
}

/** Merges per-library lists; a single list is returned untouched (no re-sort). */
internal fun <T> mergeSorted(lists: List<List<T>>, comparator: Comparator<in T>): List<T> =
    when (lists.size) {
        0 -> emptyList()
        1 -> lists[0]
        else -> lists.flatten().sortedWith(comparator)
    }

/** Round-robin interleave, keeping each list's own order (search relevance). */
internal fun <T> interleave(lists: List<List<T>>): List<T> {
    if (lists.size == 1) return lists[0]
    val out = ArrayList<T>(lists.sumOf { it.size })
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) {
        for (list in lists) if (i < list.size) out.add(list[i])
    }
    return out
}

/**
 * Splits [total] across libraries in proportion to [weights] (item counts),
 * summing to [total]; every non-empty library gets at least one when [total]
 * allows, and all-zero weights split evenly.
 */
internal fun allocate(total: Int, weights: List<Int>): List<Int> {
    if (weights.isEmpty() || total <= 0) return List(weights.size) { 0 }
    val w = if (weights.all { it <= 0 }) List(weights.size) { 1 } else weights.map { maxOf(it, 0) }
    val sum = w.sum()
    val shares = w.map { (it.toLong() * total / sum).toInt() }.toMutableList()
    val nonEmpty = w.indices.filter { w[it] > 0 }
    if (total >= nonEmpty.size) {
        for (i in nonEmpty) if (shares[i] == 0) shares[i] = 1
    }
    val byWeight = nonEmpty.sortedByDescending { w[it] }
    var remainder = total - shares.sum()
    var k = 0
    while (remainder > 0) {
        shares[byWeight[k++ % byWeight.size]]++
        remainder--
    }
    while (remainder < 0) {
        val i = byWeight[k++ % byWeight.size]
        if (shares[i] > 1) {
            shares[i]--
            remainder++
        }
    }
    return shares
}
