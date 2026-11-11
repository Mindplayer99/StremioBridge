package com.mindplayer99.stremiobridge

import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** Off means literal upstream text. Formatting is an explicit, per-addon choice. */
internal data class SourceFormatter(
    val enabled: Boolean = false,
    val name: String = DEFAULT_NAME,
    val details: String = DEFAULT_DETAILS,
) {
    companion object {
        const val DEFAULT_NAME =
            "[▣ {resolution}][ {cache}][ · {transport}][ · {quality}][ · {codec}][ · {visual}]"
        const val DEFAULT_DETAILS =
            "[≋ {bitrate}][ │ ♬ {audio}][ · ♯ {channels}]\n[◉ {languages}][ │ 💬 SUB {subtitles}]\n[☁ {service}][ · {provider}][ · {container}]\n[💾 {size}]\n[↳ {fallback}]"
        val tokens =
            setOf(
                "addon",
                "resolution",
                "cache",
                "transport",
                "quality",
                "codec",
                "visual",
                "bitrate",
                "audio",
                "channels",
                "languages",
                "subtitles",
                "service",
                "provider",
                "container",
                "size",
                "filename",
                "fallback",
                "originalName",
                "originalDescription",
                "originalTitle",
            )
    }
}

internal fun originalSourceText(stream: JSONObject, fallback: String): String =
    listOfNotNull(stream.str("name"), stream.str("description"), stream.str("title"))
        .distinct()
        .joinToString("\n")
        .ifBlank { fallback }

internal enum class CacheStatus {
    CACHED,
    UNCACHED,
    UNKNOWN,
}

internal fun debridService(stream: JSONObject): String? {
    val data = stream.optJSONObject("streamData")
    val service = data?.optJSONObject("service")
    val explicit =
        service?.str("name") ?: service?.str("id") ?: data?.str("service") ?: data?.str("serviceId")
    val candidates =
        listOfNotNull(explicit, stream.str("name"), stream.str("description"), stream.str("title"))
    val known =
        linkedMapOf(
            "tor[- ]?box|\\btb\\b" to "TorBox",
            "real[- ]?debrid|\\brd\\b" to "Real-Debrid",
            "all[- ]?debrid|\\bad\\b" to "AllDebrid",
            "premiumize" to "Premiumize",
            "easy[- ]?debrid" to "EasyDebrid",
            "debrid[- ]?link" to "Debrid-Link",
            "offcloud" to "Offcloud",
            "pikpak" to "PikPak",
        )
    for ((pattern, label) in known) {
        // Short service IDs are meaningful only in structured service data.
        val long = pattern.substringBefore('|')
        if (
            explicit != null && Regex("(?i)^(?:$pattern)$").matches(explicit) ||
                candidates.any { Regex("(?i)\\b(?:$long)\\b").containsMatchIn(it) }
        )
            return label
    }
    return if (data?.str("type") == "debrid") explicit ?: "Debrid" else null
}

internal fun cacheStatus(stream: JSONObject): CacheStatus {
    val service = stream.optJSONObject("streamData")?.optJSONObject("service")
    val explicit = service?.opt("cached") as? Boolean ?: stream.opt("cached") as? Boolean
    if (explicit != null) return if (explicit) CacheStatus.CACHED else CacheStatus.UNCACHED
    val raw = originalSourceText(stream, "")
    if (Regex("(?i)\\b(?:uncached|not cached|cache miss)\\b").containsMatchIn(raw))
        return CacheStatus.UNCACHED
    if (Regex("(?i)\\bcached\\b").containsMatchIn(raw)) return CacheStatus.CACHED
    if (debridService(stream) != null) {
        if ('⏳' in raw) return CacheStatus.UNCACHED
        if ('⚡' in raw) return CacheStatus.CACHED
    }
    return CacheStatus.UNKNOWN
}

private val placeholder = Regex("\\{([A-Za-z][A-Za-z0-9]*)}")
private val optional = Regex("\\[([^\\[\\]]*\\{[A-Za-z][A-Za-z0-9]*}[^\\[\\]]*)]")

internal fun validateFormatter(name: String, details: String) {
    if (name.isBlank()) throw BridgeFailure("Enter a name template.")
    if (name.length + details.length > 8_000)
        throw BridgeFailure("Keep templates under 8,000 characters in total.")
    val unknown =
        placeholder
            .findAll(name + "\n" + details)
            .map { it.groupValues[1] }
            .filter { it !in SourceFormatter.tokens }
            .distinct()
            .toList()
    if (unknown.isNotEmpty()) throw BridgeFailure("Unknown fields: " + unknown.joinToString(", "))
    val stripped = placeholder.replace(name + "\n" + details, "")
    if ('{' in stripped || '}' in stripped)
        throw BridgeFailure("Use fields like {resolution}; each field needs matching braces.")
    if (
        stripped.count { it == '[' } != stripped.count { it == ']' } ||
            "[[" in stripped ||
            "]]" in stripped
    )
        throw BridgeFailure("Optional blocks use one matching pair: [text {field}].")
}

internal fun renderTemplate(template: String, values: Map<String, String>): String {
    val blocks =
        optional.replace(template) { match ->
            val fields =
                placeholder.findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
            if (fields.all { !values[it].isNullOrBlank() }) match.groupValues[1] else ""
        }
    return placeholder
        .replace(blocks) { values[it.groupValues[1]].orEmpty() }
        .lines()
        .filter(String::isNotBlank)
        .joinToString("\n")
}

internal fun formatSource(addon: Addon, stream: JSONObject, resolution: Int?): String {
    val original =
        originalSourceText(
            stream,
            stream.optJSONObject("behaviorHints")?.str("filename") ?: addon.manifest!!.name,
        )
    if (!addon.formatter.enabled) return original
    return runCatching {
            validateFormatter(addon.formatter.name, addon.formatter.details)
            val values = sourceFields(addon, stream, resolution)
            fun render(template: String, preset: String): String {
                val result = renderTemplate(template, values)
                return if (template == preset)
                    result
                        .lines()
                        .map { it.trim().trim('·', '│', '|').trim() }
                        .filter(String::isNotBlank)
                        .joinToString("\n")
                else result
            }
            val name =
                render(addon.formatter.name, SourceFormatter.DEFAULT_NAME).ifBlank {
                    addon.manifest!!.name
                }
            val details = render(addon.formatter.details, SourceFormatter.DEFAULT_DETAILS)
            listOf(name, details).filter(String::isNotBlank).joinToString("\n")
        }
        .getOrDefault(original)
}

internal fun sourceFields(addon: Addon, stream: JSONObject, resolution: Int?): Map<String, String> {
    val data = stream.optJSONObject("streamData")
    val parsed = data?.optJSONObject("parsedFile")
    val hints = stream.optJSONObject("behaviorHints")
    val raw = originalSourceText(stream, "")
    val filename =
        hints?.str("filename")
            ?: data?.str("filename")
            ?: stream.str("filename")
            ?: raw.lines()
                .firstOrNull { Regex("(?i)\\.(mkv|mp4|webm|avi|m4v)(?:\\b|$)").containsMatchIn(it) }
                .orEmpty()
    val text = filename + "\n" + raw
    fun found(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.value.orEmpty()
    fun number(value: Double) = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    fun tags(key: String) = parsed?.strings(key).orEmpty()
    fun first(vararg values: String?) = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    val quality =
        first(
            parsed?.str("quality"),
            when {
                found("\\bREMUX\\b").isNotEmpty() -> "REMUX"
                found("\\bWEB[ ._-]?DL\\b").isNotEmpty() -> "WEB-DL"
                found("\\bWEB[ ._-]?Rip\\b").isNotEmpty() -> "WEBRip"
                found("\\bBlu[ ._-]?Ray\\b|\\bBDRip\\b").isNotEmpty() -> "BluRay"
                else -> found("\\b(?:HDTV|DVD|CAM|TS)\\b").uppercase()
            },
        )
    val codec =
        first(
            parsed?.str("encode"),
            when {
                found("\\b(?:HEVC|x265|H[ .]?265)\\b").isNotEmpty() -> "HEVC"
                found("\\b(?:AVC|x264|H[ .]?264)\\b").isNotEmpty() -> "AVC"
                else -> found("\\b(?:AV1|VP9)\\b").uppercase()
            },
        )
    val visual =
        tags("visualTags")
            .ifEmpty {
                buildList {
                    if (found("\\b(?:DV|DoVi|Dolby[ ._-]?Vision)\\b").isNotEmpty()) add("DV")
                    if (found("\\bHDR10\\+").isNotEmpty()) add("HDR10+")
                    else if (found("\\bHDR10\\b").isNotEmpty()) add("HDR10")
                    else if (found("\\bHDR\\b").isNotEmpty()) add("HDR")
                    if (found("\\bHLG\\b").isNotEmpty()) add("HLG")
                    if (found("\\b10[ ._-]?bit\\b").isNotEmpty()) add("10bit")
                }
            }
            .distinct()
            .joinToString(" · ")
    val audio =
        tags("audioTags")
            .ifEmpty {
                listOf(
                        "Atmos" to "\\bAtmos\\b",
                        "TrueHD" to "\\bTrue[ ._-]?HD\\b",
                        "DTS-HD MA" to "\\bDTS[ ._-]?HD[ ._-]?MA\\b",
                        "DTS-HD" to "\\bDTS[ ._-]?HD\\b",
                        "DTS" to "\\bDTS\\b",
                        "DD+" to "\\b(?:DDP|E[ ._-]?AC3)\\b|DD\\+",
                        "DD" to "\\b(?:DD|AC3)\\b",
                        "AAC" to "\\bAAC\\b",
                        "Opus" to "\\bOpus\\b",
                        "FLAC" to "\\bFLAC\\b",
                    )
                    .filter { found(it.second).isNotEmpty() }
                    .map { it.first }
            }
            .let { list ->
                list.filterNot {
                    it == "DTS" && list.any { x -> x.startsWith("DTS-") } ||
                        it == "DTS-HD" && "DTS-HD MA" in list
                }
            }
            .distinct()
            .joinToString(" · ")
    val channels =
        first(
            tags("audioChannels").joinToString(" · "),
            found("(?<![0-9])(?:7\\.1|5\\.1|2\\.0|1\\.0)(?![0-9])"),
        )
    val languagesMap =
        linkedMapOf(
            "Hindi" to "HI",
            "English" to "EN",
            "Tamil" to "TA",
            "Telugu" to "TE",
            "Kannada" to "KN",
            "Malayalam" to "ML",
            "Marathi" to "MR",
            "Bengali" to "BN",
            "Japanese" to "JA",
            "Korean" to "KO",
            "Chinese" to "ZH",
            "Spanish" to "ES",
            "French" to "FR",
            "German" to "DE",
            "Arabic" to "AR",
            "Punjabi" to "PA",
            "Urdu" to "UR",
        )
    fun language(value: String): String =
        languagesMap.entries.firstOrNull { it.key.equals(value, true) }?.value
            ?: when (value.lowercase()) {
                "hin" -> "HI"
                "eng" -> "EN"
                "jpn" -> "JA"
                else -> value.uppercase()
            }
    val languageValues =
        tags("languages").ifEmpty { languagesMap.keys.filter { found("\\b$it\\b").isNotEmpty() } }
    val languageLine =
        raw.lines()
            .firstOrNull { Regex("(?i)◉|🗣|\\blanguages?:").containsMatchIn(it) }
            ?.substringBefore('│')
            .orEmpty()
    val codes =
        Regex("(?i)\\b(?:HI|HIN|EN|ENG|TA|TE|KN|ML|MR|BN|JA|JPN|KO|ZH|ES|FR|DE|AR|PA|UR)\\b")
            .findAll(languageLine)
            .map { language(it.value) }
            .toList()
    val languages = (languageValues.map(::language) + codes).distinct().joinToString(" · ")
    val subtitleRaw =
        Regex("(?i)\\bSUB(?:TITLES?)?\\s+([A-Z]{2,3}(?:[/ ·]+[A-Z]{2,3})*)")
            .find(raw)
            ?.groupValues
            ?.get(1)
            .orEmpty()
    val subtitles =
        (tags("subtitles") +
                stream.objects("subtitles").mapNotNull { it.str("lang") ?: it.str("language") } +
                subtitleRaw.split(Regex("[/ ·]+")))
            .filter(String::isNotBlank)
            .map(::language)
            .distinct()
            .joinToString("/")
    val bitrate =
        (data?.opt("bitrate") as? Number)
            ?.toDouble()
            ?.takeIf { it.isFinite() && it > 0 }
            ?.let { number(it / 1_000_000) + " Mbps" }
            ?: found("[0-9]+(?:\\.[0-9]+)?\\s*(?:Mbps|kbps)")
    val bytes =
        ((data?.opt("size") as? Number)
                ?: (hints?.opt("videoSize") as? Number)
                ?: (stream.opt("size") as? Number))
            ?.toDouble()
            ?.takeIf { it.isFinite() && it > 0 }
    val size =
        bytes?.let {
            if (it >= 1_000_000_000) number(it / 1_000_000_000) + " GB"
            else number(it / 1_000_000) + " MB"
        } ?: found("[0-9]+(?:\\.[0-9]+)?\\s*(?:GiB|GB|MiB|MB|TB)")
    val path = runCatching { URI(stream.str("url").orEmpty()).path.orEmpty() }.getOrDefault("")
    val container =
        first(
                parsed?.str("container"),
                Regex("(?i)\\.(mkv|mp4|webm|avi|m4v|m3u8|mpd)$").find(path)?.groupValues?.get(1),
                Regex("(?i)\\.(mkv|mp4|webm|avi|m4v)$").find(filename)?.groupValues?.get(1),
            )
            .uppercase()
            .let {
                when (it) {
                    "M3U8" -> "HLS"
                    "MPD" -> "DASH"
                    else -> it
                }
            }
    val service = debridService(stream).orEmpty()
    val provider =
        first(data?.str("indexer"), data?.optJSONObject("addon")?.str("name"), addon.manifest?.name)
    val cache =
        when (cacheStatus(stream)) {
            CacheStatus.CACHED -> "⚡ Cached"
            CacheStatus.UNCACHED -> "⏳ Uncached"
            CacheStatus.UNKNOWN -> ""
        }
    return mapOf(
        "addon" to addon.manifest!!.name,
        "resolution" to (resolution?.let { if (it == 2160) "4K" else "${it}p" }.orEmpty()),
        "cache" to cache,
        "transport" to if (service.isBlank()) "↗ HTTP" else "",
        "quality" to quality,
        "codec" to codec,
        "visual" to visual,
        "bitrate" to bitrate,
        "audio" to audio,
        "channels" to channels,
        "languages" to languages,
        "subtitles" to subtitles,
        "service" to service,
        "provider" to provider,
        "container" to container,
        "size" to size,
        "filename" to filename,
        "fallback" to
            (filename.ifBlank { stream.str("title") ?: stream.str("description").orEmpty() })
                .takeIf { quality.isBlank() && codec.isBlank() && audio.isBlank() }
                .orEmpty(),
        "originalName" to stream.str("name").orEmpty(),
        "originalDescription" to stream.str("description").orEmpty(),
        "originalTitle" to stream.str("title").orEmpty(),
    )
}
