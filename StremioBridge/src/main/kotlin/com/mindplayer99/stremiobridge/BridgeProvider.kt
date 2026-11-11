package com.mindplayer99.stremiobridge

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun mediaType(type: String, j: JSONObject): TvType {
    if (type == "tv" || j.optJSONObject("behaviorHints")?.optBoolean("isLive") == true)
        return TvType.Live
    val anime =
        type in setOf("anime", "anime.movie", "anime.series") ||
            j.str("id")?.startsWith("kitsu:") == true ||
            j.strings("genres").any { it.equals("anime", true) }
    return if (type in setOf("movie", "anime.movie")) {
        if (anime) TvType.AnimeMovie else TvType.Movie
    } else if (anime) TvType.Anime else TvType.TvSeries
}

internal fun runtimeMinutes(raw: String?): Int? {
    if (raw == null) return null
    val h = Regex("(?i)([0-9]+)\\s*h").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val m =
        Regex("(?i)([0-9]+)\\s*m").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: raw.toIntOrNull()
            ?: 0
    return (h * 60 + m).takeIf { it > 0 }
}

internal class BridgeProvider(private val profile: Profile, private val refs: References) :
    MainAPI() {
    override var name = "StremioBridge · " + profile.name
    override var mainUrl = "https://stremiobridge.invalid/" + profile.key
    override var lang = "en"
    override var canBeOverridden = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Live)
    override val searchTimeoutMs = 120_000L
    override val getMainPageTimeoutMs = 120_000L
    override val loadTimeoutMs = 150_000L
    override val loadLinksTimeoutMs = 240_000L
    private val catalogs = Catalogs(profile, Http)
    private val metadata = Metadata(profile, Http)
    private val playback = Playback(profile, Http)
    private val routes =
        profile.rows
            .filter { r -> profile.catalog(r)?.let { it.accepts(it.firstArgs(r.args)) } == true }
            .associateBy { it.key }
    override val mainPage = run {
        val used = mutableSetOf<String>()
        routes.values.map { r ->
            val base =
                r.title.ifBlank {
                    listOf(profile.catalog(r)!!.name, r.type)
                        .plus(r.args.values)
                        .joinToString(" · ")
                }
            var title = base
            var n = 1
            val addon = profile.addons.first { it.key == r.addon }.manifest!!.name
            while (!used.add(title)) {
                title = base + " · " + addon + (if (n == 1) "" else " " + n)
                n++
            }
            MainPageData(title, r.key)
        }
    }
    private val privateParts =
        profile.addons
            .mapNotNull { runCatching { URI(it.url) }.getOrNull() }
            .flatMap { u ->
                u.rawPath.orEmpty().split('/').filter { it.length >= 12 && it != "manifest.json" } +
                    u.rawQuery
                        .orEmpty()
                        .split('&')
                        .map { it.substringAfter('=', "") }
                        .filter(String::isNotEmpty)
            }

    private fun art(value: String?): String? =
        httpUrl(value)?.takeIf { raw ->
            val u = URI(raw)
            u.userInfo == null &&
                privateParts.none(raw::contains) &&
                u.rawQuery.orEmpty().split('&').none {
                    it.substringBefore('=').lowercase() in
                        setOf(
                            "key",
                            "api_key",
                            "apikey",
                            "token",
                            "access_token",
                            "sig",
                            "signature",
                            "policy",
                            "expires",
                        ) || it.startsWith("x-amz-", true) || it.startsWith("x-goog-", true)
                }
        }

    private fun cards(tiles: List<Tile>) =
        refs.transaction {
            tiles.map { t ->
                val url = mainUrl + "/item/" + refs.tileKey(profile.key, t)
                val title = t.meta.getString("name")
                val type = mediaType(t.type, t.meta)
                val card: SearchResponse =
                    when (type) {
                        TvType.Live -> newLiveSearchResponse(title, url, type)
                        TvType.Anime,
                        TvType.AnimeMovie -> newAnimeSearchResponse(title, url, type)
                        TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type)
                        else -> newMovieSearchResponse(title, url, type)
                    }
                card.apply {
                    posterUrl = art(t.meta.str("poster"))
                    score = Score.from10(t.meta.opt("imdbRating")?.toString())
                }
            }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            val result =
                routes[request.data]?.let { r ->
                    attempt {
                        catalogs.page(
                            profile.addons.first { it.key == r.addon },
                            profile.catalog(r)!!,
                            r.args,
                            page,
                        )
                    }
                } ?: Page(emptyList(), false)
            newHomePageResponse(request, cards(result.tiles), result.more)
        }

    override suspend fun search(query: String): List<SearchResponse> =
        withContext(Dispatchers.IO) { cards(catalogs.search(query, 1).tiles) }

    override suspend fun search(query: String, page: Int): SearchResponseList =
        withContext(Dispatchers.IO) {
            val result = catalogs.search(query, page)
            newSearchResponseList(cards(result.tiles), result.more)
        }

    override suspend fun load(url: String): LoadResponse =
        withContext(Dispatchers.IO) {
            require(url.startsWith(mainUrl + "/item/"))
            val tile = refs.tile(profile.key, url.substringAfterLast('/'))
            val detail = metadata.resolve(tile)
            val j = detail.meta
            val title = j.str("name") ?: tile.meta.getString("name")
            val type = mediaType(tile.type, j)
            val videos = j.objects("videos")
            val result =
                if (type == TvType.TvSeries || type == TvType.Anime) {
                    val episodes =
                        refs.transaction {
                            videos
                                .distinctBy { it.str("id") }
                                .mapNotNull { v ->
                                    val id = v.str("id") ?: return@mapNotNull null
                                    val video =
                                        Video(
                                            tile.type,
                                            listOf(id),
                                            detail.requested,
                                            detail.addon.takeIf { v.optBoolean("_inline") },
                                        )
                                    val key = refs.videoKey(profile.key, detail.addon, video)
                                    newEpisode(
                                        key,
                                        fix = false,
                                        initializer = {
                                            name = v.str("title") ?: v.str("name")
                                            season = v.int("season")
                                            episode = v.int("episode") ?: v.int("number")
                                            posterUrl = art(v.str("thumbnail"))
                                            description = v.str("overview") ?: v.str("description")
                                            addDate(v.str("released"))
                                        },
                                    )
                                }
                        }
                    if (type == TvType.Anime)
                        newAnimeLoadResponse(title, url, type) {
                            addEpisodes(DubStatus.Subbed, episodes)
                        }
                    else newTvSeriesLoadResponse(title, url, type, episodes)
                } else {
                    val default = j.optJSONObject("behaviorHints")?.str("defaultVideoId")
                    val selected =
                        videos.firstOrNull { it.str("id") == default }
                            ?: videos.firstOrNull { it.str("id") == j.str("id") }
                            ?: videos.singleOrNull()
                    val chosen =
                        if (type == TvType.Live) tile.id
                        else default ?: selected?.str("id") ?: j.str("id") ?: tile.id
                    val aliases =
                        if (type != TvType.Live && chosen in identities(j))
                            identities(j) + identities(tile.meta)
                        else emptyList()
                    val inline =
                        detail.addon.takeIf {
                            type != TvType.Live && selected?.optBoolean("_inline") == true
                        }
                    val data =
                        refs.videoKey(
                            profile.key,
                            detail.addon,
                            Video(
                                tile.type,
                                (listOf(chosen) + aliases).distinct(),
                                detail.requested,
                                inline,
                            ),
                        )
                    if (type == TvType.Live) newLiveStreamLoadResponse(title, url, data)
                    else newMovieLoadResponse(title, url, type, data)
                }
            result.apply {
                posterUrl = art(j.str("poster") ?: tile.meta.str("poster"))
                backgroundPosterUrl = art(j.str("background") ?: tile.meta.str("background"))
                logoUrl = art(j.str("logo"))
                plot = j.str("description") ?: tile.meta.str("description")
                year =
                    j.int("year")
                        ?: Regex("[0-9]{4}").find(j.optString("releaseInfo"))?.value?.toIntOrNull()
                score = Score.from10(j.opt("imdbRating")?.toString())
                duration = runtimeMinutes(j.opt("runtime")?.toString())
                val links = j.objects("links")
                fun names(category: String) =
                    links
                        .filter { it.str("category").equals(category, true) }
                        .mapNotNull { it.str("name") }
                tags = (j.strings("genres") + j.strings("genre") + names("Genres")).distinct()
                addActors((j.strings("cast") + names("Cast")).distinct())
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val video = refs.video(profile.key, data)
            val links = playback.links(video, isCasting)
            for (link in links) callback(BridgeLink(link))
            for (c in playback.subtitles(video, links)) subtitleCallback(
                newSubtitleFile(c.lang, c.url)
            )
            links.isNotEmpty()
        }
}

// The host logs link.toString() at mirror selection. Avoid printing the playback URL and Referer.
@Suppress("DEPRECATION")
internal class BridgeLink(s: Link) :
    ExtractorLink(
        source = s.source,
        name = s.label,
        url = s.url,
        referer = s.headers.entries.firstOrNull { it.key.equals("referer", true) }?.value.orEmpty(),
        headers = s.headers,
        quality = s.quality ?: Qualities.Unknown.value,
        extractorData = null,
        audioTracks = emptyList(),
        type =
            when (s.format) {
                Format.HLS -> ExtractorLinkType.M3U8
                Format.DASH -> ExtractorLinkType.DASH
                Format.VIDEO -> ExtractorLinkType.VIDEO
            },
    ) {
    override fun toString() = "StremioBridge playback link (" + type + ")"
}
