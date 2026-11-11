package com.mindplayer99.stremiobridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * CloudStream stores the returned keys in bookmarks and episode history. Keep the routing data
 * private and durable.
 */
internal class References(ctx: Context) {
    private val db =
        SQLiteDatabase.openOrCreateDatabase(
            File(ctx.noBackupFilesDir, "stremiobridge-items-v1.db"),
            null,
        )

    init {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS refs(profile TEXT,key TEXT,payload TEXT NOT NULL,PRIMARY KEY(profile,key))"
        )
    }

    fun drop(profile: String) {
        db.delete("refs", "profile=?", arrayOf(profile))
    }

    fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        return try {
            block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun write(profile: String, id: List<String>, payload: JSONObject): String {
        val key = digest(JSONArray(listOf(profile) + id).toString())
        val values =
            ContentValues().apply {
                put("profile", profile)
                put("key", key)
                put("payload", payload.toString())
            }
        check(db.insertWithOnConflict("refs", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        return key
    }

    private fun read(profile: String, key: String) =
        db.rawQuery("SELECT payload FROM refs WHERE profile=? AND key=?", arrayOf(profile, key))
            .use {
                if (!it.moveToFirst())
                    throw BridgeFailure("Item no longer available in this profile.")
                JSONObject(it.getString(0))
            }

    fun tileKey(profile: String, t: Tile): String {
        val preview =
            JSONObject(
                t.meta,
                arrayOf(
                    "id",
                    "type",
                    "name",
                    "poster",
                    "background",
                    "logo",
                    "description",
                    "year",
                    "releaseInfo",
                    "runtime",
                    "imdbRating",
                    "genres",
                    "genre",
                    "cast",
                    "imdb_id",
                    "moviedb_id",
                    "tmdb_id",
                    "tvdb_id",
                    "kitsu_id",
                    "externalIds",
                ),
            )
        t.meta.optJSONObject("behaviorHints")?.let {
            preview.put("behaviorHints", JSONObject(it, arrayOf("defaultVideoId", "isLive")))
        }
        val videos =
            t.meta.objects("videos").map {
                JSONObject(
                    it,
                    arrayOf(
                        "id",
                        "title",
                        "name",
                        "season",
                        "episode",
                        "number",
                        "thumbnail",
                        "released",
                        "overview",
                        "description",
                    ),
                )
            }
        if (videos.isNotEmpty()) preview.put("videos", JSONArray(videos))
        return write(
            profile,
            listOf("item", t.origin, t.type, t.id),
            JSONObject().put("origin", t.origin).put("type", t.type).put("meta", preview),
        )
    }

    fun tile(profile: String, key: String): Tile =
        read(profile, key).let {
            Tile(it.getString("origin"), it.getString("type"), it.getJSONObject("meta"))
        }

    fun videoKey(profile: String, origin: String, v: Video): String =
        write(
            profile,
            listOf("video", origin, v.type, v.ids.first(), v.parent.orEmpty()),
            JSONObject()
                .put("type", v.type)
                .put("ids", JSONArray(v.ids))
                .put("parent", v.parent)
                .put("inline", v.inline),
        )

    fun video(profile: String, key: String): Video =
        read(profile, key).let {
            Video(it.getString("type"), it.strings("ids"), it.str("parent"), it.str("inline"))
        }
}
