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

internal fun isDebridStream(stream: JSONObject): Boolean = debridService(stream) != null
