package com.mindplayer99.stremiobridge

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class Tile(val origin: String, val type: String, val meta: JSONObject) {
    val id
        get() = meta.getString("id")
}

internal data class Page(val tiles: List<Tile>, val more: Boolean)

private data class Batch(val count: Int, val entries: List<JSONObject>)

private data class Cursor(val offset: Int, val seen: Set<String>, val time: Long)

internal class Catalogs(private val profile: Profile, private val transport: Transport) {
    private val cache = Cache<Batch>(80, 90_000)
    private val cursors = LinkedHashMap<String, MutableMap<Int, Cursor>>(16, .75f, true)
    private val locks = Array(32) { Mutex() }

    suspend fun page(a: Addon, c: Catalog, args: Map<String, String>, wanted: Int): Page {
        val empty = Page(emptyList(), false)
        val input = c.firstArgs(args).toMutableMap()
        if (
            !a.uses("catalog") ||
                wanted !in 1..1000 ||
                !c.accepts(input) ||
                wanted > 1 && !c.supports("skip")
        )
            return empty
        val key = Row(a.key, c.type, c.id, args).key
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val history =
                synchronized(cursors) {
                    cursors
                        .getOrPut(key) { mutableMapOf() }
                        .also { if (cursors.size > 96) cursors.remove(cursors.keys.first()) }
                }
            if (wanted == 1) history.clear()
            val start =
                history.keys
                    .filter { it <= wanted && now() - history.getValue(it).time < 300_000 }
                    .maxOrNull() ?: 1
            var cursor = history[start] ?: Cursor(0, emptySet(), now())
            val seen = cursor.seen.toMutableSet()
            for (page in start..wanted) {
                if (cursor.offset > 0 || "skip" in input) input["skip"] = cursor.offset.toString()
                val url = Endpoint(a.url).resource("catalog", c.type, c.id, input)
                val response =
                    cache.read(url) {
                        val rows = transport.get(url, false).getJSONArray("metas")
                        Batch(rows.length(), rows.values().filterIsInstance<JSONObject>())
                    }
                val tiles =
                    response.entries.mapNotNull { meta ->
                        val id = meta.str("id") ?: return@mapNotNull null
                        val type = meta.str("type") ?: c.type
                        if (
                            type !in mediaTypes ||
                                meta.str("name") == null ||
                                !seen.add(type + "\u0000" + id)
                        )
                            null
                        else Tile(a.key, type, meta)
                    }
                val more = c.supports("skip") && response.count > 0 && tiles.isNotEmpty()
                if (more) {
                    cursor = Cursor(cursor.offset + response.count, seen.toSet(), now())
                    history[page + 1] = cursor
                    while (history.size > 3) history.remove(history.keys.minOrNull())
                }
                if (page == wanted) return@withLock Page(tiles, more)
                if (!more) return@withLock empty
            }
            empty
        }
    }

    suspend fun search(query: String, number: Int): Page {
        if (query.isBlank()) return Page(emptyList(), false)
        val routes =
            profile.addons
                .filter { it.uses("catalog") }
                .flatMap { a ->
                    a.manifest!!.catalogs.mapNotNull { c ->
                        if (!c.supports("search")) return@mapNotNull null
                        val rows =
                            profile.rows.filter {
                                it.addon == a.key && it.type == c.type && it.catalog == c.id
                            }
                        val args =
                            (listOf(emptyMap<String, String>()) + rows.map { it.args })
                                .map { it + ("search" to query) }
                                .firstOrNull { c.accepts(c.firstArgs(it)) }
                                ?: return@mapNotNull null
                        Triple(a, c, args)
                    }
                }
        val results = routes.independent { (a, c, args) -> page(a, c, args, number) }
        return Page(
            results
                .flatMap { it.tiles }
                .distinctBy {
                    Triple(if (globalId.matches(it.id)) "" else it.origin, it.type, it.id)
                },
            results.any { it.more },
        )
    }
}

internal val globalId = Regex("tt[0-9]+|(?:tmdb|tvdb|tvmaze|kitsu|mal|anilist|anidb):[0-9]+")

internal fun identities(meta: JSONObject): List<String> {
    val ids = mutableListOf<String>()
    meta.str("id")?.let(ids::add)
    for (ns in listOf("imdb", "tmdb", "tvdb", "kitsu", "mal", "anilist")) {
        val v =
            (meta.opt(ns + "_id")
                    ?: meta.optJSONObject("externalIds")?.opt(ns)
                    ?: if (ns == "tmdb") meta.opt("moviedb_id") else null)
                ?.toString() ?: continue
        val id = if (ns == "imdb" || v.startsWith(ns + ":")) v else ns + ":" + v
        if (globalId.matches(id) && (ns != "imdb" || id.startsWith("tt"))) ids.add(id)
    }
    return ids.distinct()
}

internal data class Detail(val addon: String, val requested: String, val meta: JSONObject)

internal class Metadata(
    private val profile: Profile,
    private val transport: Transport,
    private val preferredGraceMs: Long = 8_000,
    private val deadlineMs: Long = 30_000,
) {
    private val cache = Cache<JSONObject>(80, 240_000)

    suspend fun resolve(tile: Tile): Detail = coroutineScope {
        val preview = Detail(tile.origin, tile.id, tile.meta)
        var fallback = preview
        val candidates =
            profile.addons.filter { it.uses("meta") }.sortedBy { it.key != profile.preferred }
        if (candidates.isEmpty()) return@coroutineScope preview
        data class Result(val index: Int, val detail: Detail?)
        val ready = Channel<Result>(Channel.UNLIMITED)
        val jobs =
            candidates.mapIndexed { index, a ->
                launch {
                    var result: Detail? = null
                    withTimeoutOrNull(deadlineMs - 1) {
                        for (id in identities(tile.meta)) {
                            if (!a.manifest!!.accepts("meta", tile.type, id)) continue
                            val url = Endpoint(a.url).resource("meta", tile.type, id)
                            val j =
                                attempt {
                                    cache.read(url) {
                                        transport.get(url, false).getJSONObject("meta").apply {
                                            objects("videos").forEach { v ->
                                                if (v.objects("streams").isNotEmpty())
                                                    v.put("_inline", true)
                                                v.remove("streams")
                                            }
                                        }
                                    }
                                } ?: continue
                            if (
                                id !in identities(j) ||
                                    j.str("type")?.let { it != tile.type } == true
                            )
                                continue
                            val found = Detail(a.key, id, j)
                            if (
                                tile.type in listOf("series", "anime", "anime.series", "channel") &&
                                    j.objects("videos").none { it.str("id") != null }
                            ) {
                                if (result == null) result = found
                            } else {
                                result = found
                                break
                            }
                        }
                    }
                    ready.trySend(Result(index, result))
                }
            }
        val started = now()
        val complete = sortedMapOf<Int, Detail>()
        var primaryDone = false
        var remaining = jobs.size
        fun hasEpisodes(detail: Detail) =
            tile.type !in listOf("series", "anime", "anime.series", "channel") ||
                detail.meta.objects("videos").any { it.str("id") != null }
        try {
            withTimeoutOrNull(deadlineMs) {
                while (remaining > 0) {
                    val grace = preferredGraceMs - (now() - started)
                    if (complete.isNotEmpty() && (primaryDone || grace <= 0))
                        return@withTimeoutOrNull complete.values.first()
                    val event =
                        if (complete.isNotEmpty())
                            withTimeoutOrNull(grace.coerceAtLeast(1)) { ready.receive() }
                        else ready.receive()
                    if (event == null) return@withTimeoutOrNull complete.values.first()
                    remaining--
                    if (event.index == 0) primaryDone = true
                    event.detail?.let { detail ->
                        if (hasEpisodes(detail)) complete[event.index] = detail
                        else if (fallback === preview && tile.meta.objects("videos").isEmpty())
                            fallback = detail
                    }
                }
                complete.values.firstOrNull() ?: fallback
            } ?: complete.values.firstOrNull() ?: fallback
        } finally {
            jobs.forEach { it.cancel() }
            ready.close()
        }
    }
}
