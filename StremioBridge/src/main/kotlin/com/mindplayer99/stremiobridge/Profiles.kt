package com.mindplayer99.stremiobridge

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal fun profileJson(p: Profile) =
    JSONObject().apply {
        put("key", p.key)
        put("name", p.name)
        put("preferred", p.preferred)
        put("initialized", p.initialized)
        put("groupPlayback", p.groupPlayback)
        put("catalogRowsInitialized", p.catalogRowsInitialized)
        put(
            "addons",
            JSONArray(
                p.addons.map { a ->
                    JSONObject().apply {
                        put("key", a.key)
                        put("url", a.url)
                        put("enabled", a.enabled)
                        put("roles", JSONArray(a.switches))
                        put("manifest", a.manifest?.json)
                        put("checked", a.checked)
                        put(
                            "formatter",
                            JSONObject()
                                .put("enabled", a.formatter.enabled)
                                .put("name", a.formatter.name)
                                .put("details", a.formatter.details),
                        )
                    }
                }
            ),
        )
        put(
            "rows",
            JSONArray(
                p.rows.map { r ->
                    JSONObject().apply {
                        put("addon", r.addon)
                        put("type", r.type)
                        put("catalog", r.catalog)
                        put("args", JSONObject(r.args))
                        put("title", r.title)
                        put("enabled", r.enabled)
                    }
                }
            ),
        )
    }

internal fun readProfile(j: JSONObject) =
    Profile(
            j.getString("key"),
            j.getString("name"),
            j.objects("addons").map { a ->
                Addon(
                    a.getString("key"),
                    a.getString("url"),
                    a.getBoolean("enabled"),
                    a.strings("roles").toSet(),
                    a.optJSONObject("manifest")?.let(::Manifest),
                    a.optLong("checked"),
                    a.optJSONObject("formatter").let { f ->
                        SourceFormatter(
                            f?.optBoolean("enabled") == true,
                            f?.str("name") ?: SourceFormatter.DEFAULT_NAME,
                            f?.optString("details", SourceFormatter.DEFAULT_DETAILS)
                                ?: SourceFormatter.DEFAULT_DETAILS,
                        )
                    },
                )
            },
            j.objects("rows").map { r ->
                Row(
                    r.getString("addon"),
                    r.getString("type"),
                    r.getString("catalog"),
                    r.optJSONObject("args")?.mapStrings().orEmpty(),
                    r.optString("title"),
                    r.optBoolean("enabled", true),
                )
            },
            j.str("preferred"),
            j.optBoolean("initialized"),
            j.optBoolean("groupPlayback", true),
            j.optBoolean("catalogRowsInitialized", false),
        )
        .let { if (it.catalogRowsInitialized) it else addNewCatalogRows(null, it) }

internal suspend fun refresh(draft: Profile, transport: Transport, force: Boolean): Profile =
    coroutineScope {
        val checked =
            draft.addons
                .mapIndexed { i, a ->
                    async {
                        if (!a.enabled || !force && a.manifest != null) return@async a
                        try {
                            val endpoint = Endpoint(a.url)
                            val manifest =
                                Manifest.discover(transport.get(endpoint.manifest, false), endpoint)
                            a.copy(
                                url = endpoint.manifest,
                                manifest = manifest,
                                checked = System.currentTimeMillis(),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: NeedsConfiguration) {
                            throw e
                        } catch (e: Exception) {
                            throw BridgeFailure(
                                "Addon " +
                                    (i + 1) +
                                    " • " +
                                    ((e as? BridgeFailure)?.message ?: "Refresh failed.")
                            )
                        }
                    }
                }
                .awaitAll()
        val p = draft.copy(addons = checked)
        if (p.initialized) addNewCatalogRows(draft, p)
        else p.copy(rows = p.initialRows(), initialized = true)
    }

internal data class RefreshResult(val profile: Profile, val failures: Map<String, String>)

/** A failed refresh retains that addon's last validated snapshot and switches. */
internal suspend fun refreshSelected(
    profile: Profile,
    transport: Transport,
    keys: Set<String>,
): RefreshResult = coroutineScope {
    val results =
        profile.addons
            .map { addon ->
                async {
                    if (addon.key !in keys) return@async addon to null
                    try {
                        val endpoint = Endpoint(addon.url)
                        val manifest =
                            Manifest.discover(transport.get(endpoint.manifest, false), endpoint)
                        addon.copy(manifest = manifest, checked = System.currentTimeMillis()) to
                            null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message =
                            when (e) {
                                is NeedsConfiguration ->
                                    "Setup required. Paste the configured install link from the addon’s setup page."
                                is BridgeFailure -> e.message ?: "Manifest refresh failed."
                                else -> "Manifest refresh failed."
                            }
                        addon to message
                    }
                }
            }
            .awaitAll()
    RefreshResult(
        addNewCatalogRows(profile, profile.copy(addons = results.map { it.first })),
        results.mapNotNull { (addon, error) -> error?.let { addon.key to it } }.toMap(),
    )
}

internal fun catalogForRow(profile: Profile, row: Row): Catalog? =
    profile.addons
        .firstOrNull { it.key == row.addon }
        ?.manifest
        ?.catalogs
        ?.firstOrNull { it.type == row.type && it.id == row.catalog }

internal fun updateRow(profile: Profile, index: Int, replacement: Row): Profile {
    if (profile.rows.withIndex().any { it.index != index && it.value.key == replacement.key })
        throw BridgeFailure("This catalog and filter combination is already on Home.")
    return profile.copy(rows = profile.rows.toMutableList().apply { set(index, replacement) })
}

internal class ProfileStore(ctx: Context) {
    private companion object {
        val lock = Any()
    }

    private val file = AtomicFile(File(ctx.noBackupFilesDir, "stremiobridge-profiles-v1.json"))

    fun all(): List<Profile> =
        synchronized(lock) {
            val raw =
                try {
                    file.openRead().bufferedReader().use { it.readText() }
                } catch (_: FileNotFoundException) {
                    return emptyList()
                }
            JSONArray(raw).values().map { readProfile(it as JSONObject) }
        }

    fun replace(expected: Profile?, updated: Profile?): List<Profile> =
        synchronized(lock) {
            val key = (expected ?: updated)!!.key
            val next = all().toMutableList()
            val idx = next.indexOfFirst { it.key == key }
            if (
                next.getOrNull(idx)?.let(::profileJson)?.toString() !=
                    expected?.let(::profileJson)?.toString()
            )
                throw BridgeFailure("This profile changed in another editor. Reopen it.")
            if (updated != null && next.any { it.key != key && it.name.equals(updated.name, true) })
                throw BridgeFailure("Choose a different profile name.")
            if (updated == null) next.removeAt(idx)
            else if (idx < 0) next.add(updated) else next[idx] = updated
            val out = file.startWrite()
            try {
                out.write(JSONArray(next.map(::profileJson)).toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
            next
        }
}
