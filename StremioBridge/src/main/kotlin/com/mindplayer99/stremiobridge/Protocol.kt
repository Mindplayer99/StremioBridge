package com.mindplayer99.stremiobridge

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.str(key: String) = (opt(key) as? String)?.takeIf(String::isNotBlank)

internal fun JSONObject.int(key: String) = opt(key)?.toString()?.toIntOrNull()

internal fun JSONArray?.values(): List<Any> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::opt)

internal fun JSONObject.objects(key: String) =
    optJSONArray(key).values().filterIsInstance<JSONObject>()

internal fun JSONObject.strings(key: String) = optJSONArray(key).values().filterIsInstance<String>()

internal fun JSONObject.mapStrings(): Map<String, String> =
    keys().asSequence().mapNotNull { k -> str(k)?.let { k to it } }.toMap()

internal fun freshId() = UUID.randomUUID().toString()

internal fun digest(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        ""
    ) {
        "%02x".format(it)
    }

internal class BridgeFailure(message: String) : Exception(message)

internal class NeedsConfiguration(val page: String) :
    Exception("Complete addon setup in the browser.")

internal fun encode(value: String) =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")

internal val roles = listOf("catalog", "meta", "stream", "subtitles")
internal val mediaTypes =
    setOf("movie", "series", "anime.movie", "anime.series", "anime", "tv", "channel")

internal class Endpoint(input: String) {
    val manifest: String
    private val base: String
    private val query: String

    init {
        val raw =
            input.trim().let {
                if (it.startsWith("stremio://", true)) "https://" + it.substringAfter("://") else it
            }
        val uri =
            try {
                URI(raw)
            } catch (_: Exception) {
                null
            }
        if (
            uri == null ||
                uri.scheme?.lowercase() !in setOf("http", "https") ||
                uri.host.isNullOrBlank() ||
                uri.rawFragment != null
        )
            throw BridgeFailure("Enter an HTTP, HTTPS or stremio install link.")
        if (!uri.rawPath.endsWith("/manifest.json"))
            throw BridgeFailure(
                "Copy the install link ending in /manifest.json from the addon setup page."
            )
        base = uri.scheme + "://" + uri.rawAuthority + uri.rawPath.removeSuffix("manifest.json")
        query = uri.rawQuery?.let { "?" + it }.orEmpty()
        manifest = base + "manifest.json" + query
    }

    fun configure() = base + "configure" + query

    fun resource(
        name: String,
        type: String,
        id: String,
        args: Map<String, String> = emptyMap(),
    ): String {
        require(name in roles && type !in setOf(".", "..") && id !in setOf(".", ".."))
        val extra = args.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        return base +
            name +
            "/" +
            encode(type) +
            "/" +
            encode(id) +
            (if (extra.isEmpty()) "" else "/" + extra) +
            ".json" +
            query
    }
}

internal data class Rule(val name: String, val types: List<String>, val prefixes: List<String>)

internal data class Extra(val name: String, val required: Boolean, val options: List<String>)

internal data class Catalog(
    val type: String,
    val id: String,
    val name: String,
    val extras: List<Extra>,
) {
    fun supports(key: String) = extras.any { it.name == key }

    fun accepts(args: Map<String, String>) =
        extras.all { !it.required || it.name in args } &&
            args.all { (key, value) ->
                extras.any { it.name == key && (it.options.isEmpty() || value in it.options) }
            }

    fun firstArgs(args: Map<String, String>) =
        if (extras.any { it.name == "skip" && it.required }) args + ("skip" to "0") else args
}

internal class Manifest(val json: JSONObject) {
    val name = json.str("name") ?: json.getString("id")
    val rules =
        json.optJSONArray("resources").values().mapNotNull { r ->
            when (r) {
                is String -> Rule(r, json.strings("types"), json.strings("idPrefixes"))
                is JSONObject ->
                    r.str("name")?.let { Rule(it, r.strings("types"), r.strings("idPrefixes")) }
                else -> null
            }
        }
    val catalogs =
        json
            .objects("catalogs")
            .mapNotNull { c ->
                val id = c.str("id") ?: return@mapNotNull null
                val type = c.str("type") ?: return@mapNotNull null
                val extras =
                    c.objects("extra")
                        .mapNotNull { e ->
                            e.str("name")?.let {
                                Extra(it, e.optBoolean("isRequired"), e.strings("options"))
                            }
                        }
                        .ifEmpty {
                            (c.strings("extraRequired") + c.strings("extraSupported"))
                                .distinct()
                                .map { Extra(it, it in c.strings("extraRequired"), emptyList()) }
                        }
                Catalog(type, id, c.str("name") ?: id, extras)
            }
            .distinctBy { it.type to it.id }
    val capabilities =
        roles.filter { k ->
            if (k == "catalog") catalogs.isNotEmpty() else rules.any { it.name == k }
        }

    fun accepts(name: String, type: String, id: String) =
        rules.any {
            it.name == name &&
                type in it.types &&
                (it.prefixes.isEmpty() || it.prefixes.any(id::startsWith))
        }

    companion object {
        fun discover(raw: JSONObject, endpoint: Endpoint): Manifest {
            if (raw.optJSONObject("behaviorHints")?.optBoolean("configurationRequired") == true)
                throw NeedsConfiguration(endpoint.configure())
            if (raw.str("id") == null)
                throw BridgeFailure("The endpoint did not return a valid manifest.")
            return Manifest(
                    JSONObject(
                        raw,
                        arrayOf("id", "name", "types", "idPrefixes", "resources", "catalogs"),
                    )
                )
                .also {
                    if (it.capabilities.isEmpty())
                        throw BridgeFailure("This addon advertises no supported resources.")
                }
        }
    }
}

internal data class Addon(
    val key: String,
    val url: String,
    val enabled: Boolean = true,
    val switches: Set<String> = roles.toSet(),
    val manifest: Manifest? = null,
    val checked: Long = 0,
) {
    fun uses(role: String) = enabled && role in switches && manifest != null
}

internal data class Row(
    val addon: String,
    val type: String,
    val catalog: String,
    val args: Map<String, String> = emptyMap(),
    val title: String = "",
) {
    val key
        get() =
            digest(
                JSONArray(
                        listOf(addon, type, catalog) +
                            args.toSortedMap().flatMap { listOf(it.key, it.value) }
                    )
                    .toString()
            )
}

internal data class Profile(
    val key: String = freshId(),
    val name: String = "My profile",
    val addons: List<Addon> = emptyList(),
    val rows: List<Row> = emptyList(),
    val preferred: String? = null,
    val initialized: Boolean = false,
) {
    fun catalog(r: Row) =
        addons
            .firstOrNull { it.key == r.addon && it.uses("catalog") }
            ?.manifest
            ?.catalogs
            ?.firstOrNull { it.id == r.catalog && it.type == r.type }

    fun initialRows() =
        addons
            .filter { it.uses("catalog") }
            .flatMap { a ->
                a.manifest!!
                    .catalogs
                    .filter { it.accepts(it.firstArgs(emptyMap())) }
                    .take(3)
                    .map { Row(a.key, it.type, it.id) }
            }
            .take(8)
}
