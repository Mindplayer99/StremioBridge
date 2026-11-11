package com.mindplayer99.stremiobridge

import java.net.URI
import java.util.TreeMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class Video(
    val type: String,
    val ids: List<String>,
    val parent: String?,
    val inline: String? = null,
)

internal data class Caption(val lang: String, val url: String)

internal enum class Format {
    VIDEO,
    HLS,
    DASH,
}

internal data class Link(
    val addon: String,
    val source: String,
    val label: String,
    val url: String,
    val format: Format,
    val quality: Int?,
    val headers: Map<String, String>,
    val captions: List<Caption>,
    val index: Int?,
    val hash: String?,
    val size: Long?,
    val file: String?,
    val group: String?,
    val debrid: Boolean = false,
)

internal fun httpUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val u =
        try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
    return raw.takeIf { u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrEmpty() }
}

private fun captions(j: JSONObject) =
    j.objects("subtitles")
        .mapNotNull { s ->
            httpUrl(s.str("url"))?.let {
                Caption(s.str("lang") ?: s.str("language") ?: "Unknown", it)
            }
        }
        .distinct()

private val pixels = Regex("(?i)(?<![0-9])(4320|2160|1440|1080|720|576|540|480|360|240)p?(?![0-9])")
private val token = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

internal fun adapt(a: Addon, s: JSONObject, casting: Boolean): Link? {
    val url = httpUrl(s.str("url")) ?: return null
    val hints = s.optJSONObject("behaviorHints") ?: JSONObject()
    val proxy = hints.optJSONObject("proxyHeaders") ?: s.optJSONObject("proxyHeaders")
    val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
    headers.putAll(hints.optJSONObject("headers")?.mapStrings().orEmpty())
    headers.putAll(proxy?.optJSONObject("request")?.mapStrings().orEmpty())
    headers.entries.removeAll { (key, value) ->
        !token.matches(key) ||
            key.lowercase() in
                setOf(
                    "host",
                    "content-length",
                    "proxy-authorization",
                    "transfer-encoding",
                    "connection",
                ) ||
            value.any { it.code < 32 && it != '\t' || it.code == 127 }
    }
    val uri = URI(url)
    if (casting && (headers.isNotEmpty() || uri.userInfo != null)) return null
    val path = uri.path.orEmpty().lowercase()
    if (path.endsWith(".torrent") || path.endsWith(".nzb") || s.has("drm") || s.has("drmKey"))
        return null
    val data = s.optJSONObject("streamData")
    if (data?.str("type") == "info") return null
    val file = hints.str("filename")
    val mime =
        (proxy
                ?.optJSONObject("response")
                ?.mapStrings()
                ?.entries
                ?.firstOrNull { it.key.equals("Content-Type", true) }
                ?.value ?: s.str("mimeType"))
            .orEmpty()
            .lowercase()
    val format =
        when {
            "mpegurl" in mime || path.endsWith(".m3u8") || file?.endsWith(".m3u8", true) == true ->
                Format.HLS
            "dash+xml" in mime || path.endsWith(".mpd") || file?.endsWith(".mpd", true) == true ->
                Format.DASH
            "text/html" in mime -> return null
            else -> Format.VIDEO
        }
    val source = a.manifest!!.name
    val label =
        listOfNotNull(s.str("name"), s.str("description"), s.str("title"))
            .distinct()
            .joinToString("\n")
            .ifBlank { file ?: source }
    val q =
        listOfNotNull(data?.optJSONObject("parsedFile")?.str("resolution"), label, file)
            .joinToString(" ")
    val height =
        pixels.find(q)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (Regex("(?i)\\b4k\\b").containsMatchIn(q)) 2160 else null
    return Link(
        a.key,
        source,
        formatSource(a, s, height),
        url,
        format,
        height,
        headers,
        captions(s),
        s.int("fileIdx"),
        hints.str("videoHash")?.takeIf { it.matches(Regex("[0-9a-fA-F]{16}")) }?.lowercase(),
        hints.opt("videoSize")?.toString()?.toLongOrNull()?.takeIf { it > 0 },
        file,
        hints.str("bingeGroup"),
        isDebridStream(s),
    )
}

internal class Playback(private val p: Profile, private val transport: Transport) {
    suspend fun links(v: Video, casting: Boolean, onBatch: (List<Link>) -> Unit = {}): List<Link> {
        val owner =
            p.addons.find { it.key == v.inline && it.uses("meta") && "stream" in it.switches }
        if (owner != null && v.parent != null)
            return attempt {
                    val url = Endpoint(owner.url).resource("meta", v.type, v.parent)
                    val j = transport.get(url, true).getJSONObject("meta")
                    if (v.parent !in identities(j) || j.str("type")?.let { it != v.type } == true)
                        emptyList()
                    else
                        j.objects("videos")
                            .firstOrNull { it.str("id") in v.ids }
                            ?.objects("streams")
                            .orEmpty()
                            .mapNotNull { adapt(owner, it, casting) }
                            .distinct()
                }
                .orEmpty()
                .also(onBatch)
        val delivery = Mutex()
        return coroutineScope {
            p.addons
                .filter { it.uses("stream") }
                .map { a ->
                    async {
                        val batch = withTimeoutOrNull(125_000) { fetch(a, v, casting) }.orEmpty()
                        delivery.withLock { onBatch(batch) }
                        batch
                    }
                }
                .awaitAll()
                .flatten()
                .distinct()
        }
    }

    private suspend fun fetch(a: Addon, v: Video, casting: Boolean): List<Link> {
        for (id in v.ids) {
            if (!a.manifest!!.accepts("stream", v.type, id)) continue
            val links =
                attempt {
                        transport
                            .get(Endpoint(a.url).resource("stream", v.type, id), true)
                            .objects("streams")
                            .mapNotNull { adapt(a, it, casting) }
                    }
                    .orEmpty()
            if (links.isNotEmpty()) return links
        }
        return emptyList()
    }

    suspend fun subtitles(v: Video, links: List<Link>): List<Caption> {
        val first = links.firstOrNull() ?: return emptyList()
        val attached = first.captions.filter { s -> links.all { s in it.captions } }
        val extras = linkedMapOf<String, String>()
        if (
            links.size == 1 ||
                first.hash != null &&
                    first.size != null &&
                    links.all { it.hash == first.hash && it.size == first.size }
        ) {
            first.hash?.let { extras["videoHash"] = it }
            first.size?.let { extras["videoSize"] = it.toString() }
            first.file
                ?.takeIf { file -> links.all { it.file == file } }
                ?.let { extras["filename"] = it }
        }
        val independent =
            p.addons
                .filter { it.uses("subtitles") }
                .independent(12_000) { a ->
                    val endpoint = Endpoint(a.url)
                    for (id in v.ids) {
                        val requests = mutableListOf(id to extras.toMap())
                        extras["videoHash"]?.let {
                            requests.add(
                                it to
                                    (extras.filterKeys { k -> k == "videoSize" } +
                                        ("videoID" to id))
                            )
                        }
                        for ((requestId, args) in requests) {
                            if (!a.manifest!!.accepts("subtitles", v.type, requestId)) continue
                            val result =
                                attempt {
                                        captions(
                                            transport.get(
                                                endpoint.resource(
                                                    "subtitles",
                                                    v.type,
                                                    requestId,
                                                    args,
                                                ),
                                                false,
                                            )
                                        )
                                    }
                                    .orEmpty()
                            if (result.isNotEmpty()) return@independent result
                        }
                    }
                    emptyList()
                }
                .flatten()
        return (attached + independent).distinct()
    }
}
