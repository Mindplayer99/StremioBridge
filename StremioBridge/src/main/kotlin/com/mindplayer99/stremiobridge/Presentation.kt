package com.mindplayer99.stremiobridge

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import org.json.JSONObject

internal fun typeLabel(type: String) =
    when (type) {
        "movie" -> "Movies"
        "series" -> "Series"
        "anime.movie" -> "Anime movies"
        "anime",
        "anime.series" -> "Anime"
        "tv",
        "channel" -> "Live TV"
        else -> type.replaceFirstChar { it.uppercase() }
    }

internal fun visibleFilter(value: String) =
    value.isNotBlank() && value.trim().lowercase() !in setOf("none", "any", "all", "null")

/** Changes display names only; Row.key, types and protocol filter values stay intact. */
internal fun rowTitle(profile: Profile, row: Row): String {
    if (row.title.isNotBlank()) return row.title
    val title = (catalogForRow(profile, row)?.name ?: row.catalog).trim()
    val alreadyTyped =
        when (row.type) {
            "movie" -> Regex("(?i)\\b(movies?|films?)\\b").containsMatchIn(title)
            "series" -> Regex("(?i)\\b(series|tv shows?)\\b").containsMatchIn(title)
            else -> title.contains(typeLabel(row.type), true)
        }
    val base = if (alreadyTyped) title else "$title (${typeLabel(row.type)})"
    return (listOf(base) +
            row.args.filterKeys { it !in setOf("search", "skip") }.values.filter(::visibleFilter))
        .joinToString(" · ")
}

/** AIOMetadata supplies photos/characters in app_extras.cast; standard cast names still work. */
internal fun castData(meta: JSONObject, image: (String?) -> String?): List<ActorData> {
    val extras = meta.optJSONObject("app_extras")
    val credits = meta.optJSONObject("credits")
    val entries =
        extras?.optJSONArray("cast").values() +
            meta.optJSONArray("credits_cast").values() +
            credits?.optJSONArray("cast").values() +
            meta.optJSONArray("cast").values()
    val actors = linkedMapOf<String, ActorData>()
    for (entry in entries) {
        val person = entry as? JSONObject
        val nested = person?.optJSONObject("actor")
        val name =
            (entry as? String)?.takeIf { it.isNotBlank() }
                ?: person?.str("name")
                ?: nested?.str("name")
                ?: continue
        val rawImage =
            person?.str("photo")
                ?: person?.str("profile_path")
                ?: person?.str("image")
                ?: nested?.str("image")
        val photo =
            image(
                rawImage?.let {
                    if (it.startsWith("/") && !it.startsWith("//"))
                        "https://image.tmdb.org/t/p/w185$it"
                    else it
                }
            )
        val role = person?.str("character") ?: person?.str("role")
        val key = name.lowercase()
        val previous = actors[key]
        if (previous == null) actors[key] = ActorData(Actor(name, photo), roleString = role)
        else
            actors[key] =
                previous.copy(
                    actor = Actor(previous.actor.name, previous.actor.image ?: photo),
                    roleString = previous.roleString ?: role,
                )
    }
    for (link in meta.objects("links").filter { it.str("category").equals("Cast", true) }) {
        val name = link.str("name") ?: continue
        if (name.lowercase() !in actors) actors[name.lowercase()] = ActorData(Actor(name))
    }
    return actors.values.toList()
}

internal fun mergeCast(primary: List<ActorData>, fallback: List<ActorData>): List<ActorData> {
    val result = linkedMapOf<String, ActorData>()
    for (actor in primary + fallback) {
        val key = actor.actor.name.lowercase()
        val previous = result[key]
        result[key] =
            if (previous == null) actor
            else
                previous.copy(
                    actor = previous.actor.copy(image = previous.actor.image ?: actor.actor.image),
                    roleString = previous.roleString ?: actor.roleString,
                )
    }
    return result.values.toList()
}

private fun cleanStreamText(value: String): String =
    value
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[\\p{So}\\p{Cc}\\uFE0F]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '·', '|', '-', '›')

private fun shortText(value: String, limit: Int): String {
    if (value.length <= limit) return value
    val prefix = value.take(limit - 1)
    val end = prefix.lastIndexOf(' ').takeIf { it > limit / 2 } ?: prefix.length
    return prefix.take(end).trimEnd() + "…"
}

/** Keep HTTP addon's own useful wording, while fitting CloudStream's single source-name field. */
internal fun streamLabel(
    addon: String,
    raw: String,
    quality: Int?,
    cached: Boolean? = null,
): String {
    val lines = raw.lines().map(::cleanStreamText).filter(String::isNotEmpty).distinct()
    val first = lines.firstOrNull().orEmpty().removePrefix(addon).trim(' ', '·', '|', '-', ':')
    val heading =
        listOf(
                addon,
                first.takeIf { it.isNotBlank() },
                cached?.let { if (it) "Cached" else "Uncached" },
            )
            .filterNotNull()
            .joinToString(" · ")
    val withQuality =
        if (quality != null && !Regex("(?i)\\b(?:${quality}p?|4k|8k)\\b").containsMatchIn(heading))
            "$heading · ${quality}p"
        else heading
    val rest =
        lines
            .drop(1)
            .sortedBy {
                if (Regex("(?i)torbox|debrid|premiumize|offcloud|pikpak").containsMatchIn(it)) 0
                else 1
            }
            .filterNot { it.equals(addon, true) || it == first }
            .joinToString(" · ")
    return listOf(shortText(withQuality, 84), shortText(rest, 124))
        .filter(String::isNotBlank)
        .joinToString("\n")
}

internal fun isDebridStream(stream: JSONObject): Boolean {
    val data = stream.optJSONObject("streamData")
    when (data?.str("type")) {
        "debrid" -> return true
        "http" -> return false
    }
    val service = data?.optJSONObject("service")
    val text =
        listOfNotNull(
                service?.str("id"),
                service?.str("name"),
                data?.str("service"),
                data?.str("serviceId"),
                stream.str("name"),
                stream.str("description"),
                stream.str("title"),
            )
            .joinToString(" ")
    return Regex(
            "(?i)\\b(torbox|real[- ]?debrid|all[- ]?debrid|premiumize|easy[- ]?debrid|debrid[- ]?link|offcloud|pikpak)\\b"
        )
        .containsMatchIn(text)
}
