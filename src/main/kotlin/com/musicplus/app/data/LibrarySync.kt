package com.musicplus.app.data

import kotlinx.coroutines.delay

private const val TAG = "LibrarySync"

/**
 * How long [mirrorFromServer] waits before its confirming second pass — long
 * enough that a library caught mid-change (a scan still running, a page
 * boundary that shifted while paging) has settled, short enough that a real
 * deletion still disappears while the person is looking at the list.
 */
private const val CONFIRM_DELAY_MS = 5_000L

/** Ids per `DELETE/UPDATE ... WHERE id IN (...)` — well under SQLite's bound-variable limit however large a library gets. */
internal const val ID_BATCH_SIZE = 500

/**
 * Reads a server-side list of any length as one continuous stream of pages.
 *
 * It ends on an *empty* page, not on a short one: a server that caps its own
 * page size below what was asked for (and Subsonic servers are free to) would
 * otherwise look like it had run out early, and everything past the cap would
 * silently be missing from the library. It advances by the number of items it
 * actually got for the same reason, and it also stops if a page brings nothing
 * it hasn't already seen — a server that ignores `offset` would otherwise hand
 * back page one forever.
 *
 * Throws whatever [fetchPage] throws: a list that failed half way is not a
 * list, and [mirrorFromServer] relies on that to never act on a partial one.
 */
internal suspend fun <E : Any> pageThrough(
    idOf: (E) -> String,
    fetchPage: suspend (offset: Int) -> List<E>,
    onPage: suspend (List<E>) -> Unit,
) {
    val seen = HashSet<String>()
    var offset = 0
    while (true) {
        val page = fetchPage(offset)
        if (page.isEmpty()) return
        var anyNew = false
        for (item in page) if (seen.add(idOf(item))) anyNew = true
        if (!anyNew) return
        onPage(page)
        offset += page.size
    }
}

/**
 * One "make the cache match the server" pass over a collection the server can
 * list in full (artists, albums, songs, playlists): additions and changes are
 * written as pages arrive — so a big list fills in as it loads, exactly like
 * Songs always did — and anything the server has stopped listing is removed.
 *
 * **Only what changed is written.** Room re-runs every observer of a table on
 * any write to it, even one that stores identical values, and each re-run
 * re-maps the whole list (several real seconds for Songs — see
 * [AppLibraryCache]). A refresh that finds nothing new therefore writes
 * nothing, which is what makes refreshing every time a list page opens cheap.
 * [merge] lets a caller keep a local value the server doesn't know yet (an
 * offline favorite that is still queued).
 *
 * **Deleting is the dangerous half, so it is deliberately conservative:**
 *  - it only ever happens after a *complete* pass — [fetchAll] throwing (offline,
 *    server error, cancellation) leaves the cache exactly as it was;
 *  - a server that lists nothing at all is never read as "everything was deleted";
 *  - an id must be missing from **two** complete passes [CONFIRM_DELAY_MS] apart
 *    before it goes, so a library that shifted under the paging (a scan running)
 *    can't take still-existing items with it;
 *  - [keep] names ids that must survive regardless — songs the person has
 *    downloaded, playlists created offline that haven't reached the server yet.
 *    Those are excluded before the confirming pass is even decided, so a kept
 *    ghost can't make every future refresh pay for a second pass.
 */
internal suspend fun <E : Any> mirrorFromServer(
    label: String,
    idOf: (E) -> String,
    cached: suspend () -> Map<String, E>,
    fetchAll: suspend (onPage: suspend (List<E>) -> Unit) -> Unit,
    write: suspend (List<E>) -> Unit,
    remove: suspend (List<String>) -> Unit,
    merge: (fetched: E, cached: E?) -> E = { fetched, _ -> fetched },
    keep: suspend (candidates: List<String>) -> Set<String> = { emptySet() },
) {
    val startedAt = System.currentTimeMillis()
    val before = cached()
    val seen = HashSet<String>()
    var written = 0
    fetchAll { page ->
        val changed = ArrayList<E>()
        for (fetched in page) {
            val id = idOf(fetched)
            seen += id
            val local = before[id]
            val merged = merge(fetched, local)
            if (merged != local) changed += merged
        }
        if (changed.isNotEmpty()) {
            write(changed)
            written += changed.size
        }
    }

    if (seen.isEmpty()) {
        AppLogger.d(TAG, "$label: the server listed nothing; keeping all ${before.size} cached")
        return
    }
    val missing = before.keys.filter { it !in seen }
    val spared = if (missing.isEmpty()) emptySet() else keep(missing)
    val gone = missing.filter { it !in spared }
    var removed = 0
    if (gone.isNotEmpty()) {
        delay(CONFIRM_DELAY_MS)
        val seenAgain = HashSet<String>()
        fetchAll { page -> for (item in page) seenAgain += idOf(item) }
        if (seenAgain.isNotEmpty()) {
            val confirmed = gone.filter { it !in seenAgain }
            confirmed.chunked(ID_BATCH_SIZE).forEach { remove(it) }
            removed = confirmed.size
        }
    }
    AppLogger.d(
        TAG,
        "$label: ${seen.size} on the server, $written written, ${gone.size} missing, $removed removed, " +
            "${spared.size} kept (${System.currentTimeMillis() - startedAt} ms)",
    )
}

/**
 * Favorites counterpart to [mirrorFromServer]. `getStarred2` returns *every*
 * starred artist, album and song in one call, so there is no paging to shift
 * and nothing to confirm — and what a removal means here is only "clear the
 * star" (the row stays), so a wrong answer costs a flag that the next refresh
 * puts back, not data.
 *
 * [fetched] must already carry `starred = true`. Ids in [pending] — favorites
 * the person toggled while offline that haven't reached the server yet — are
 * left exactly as they are locally in both directions, otherwise opening
 * Favorites before the queue drained would undo what they just did.
 */
internal suspend fun <E : Any> mirrorStarred(
    label: String,
    fetched: List<E>,
    idOf: (E) -> String,
    cachedByIds: suspend (List<String>) -> List<E>,
    write: suspend (List<E>) -> Unit,
    localStarredIds: suspend () -> List<String>,
    clearStarred: suspend (List<String>) -> Unit,
    pending: Set<String>,
) {
    val ids = fetched.map(idOf)
    val cachedById = ids.chunked(ID_BATCH_SIZE).flatMap { cachedByIds(it) }.associateBy(idOf)
    val changed = fetched.filter { idOf(it) !in pending && cachedById[idOf(it)] != it }
    if (changed.isNotEmpty()) write(changed)
    val onServer = ids.toSet()
    val unstarred = localStarredIds().filter { it !in onServer && it !in pending }
    unstarred.chunked(ID_BATCH_SIZE).forEach { clearStarred(it) }
    AppLogger.d(TAG, "$label: ${ids.size} starred on the server, ${changed.size} written, ${unstarred.size} un-starred")
}
