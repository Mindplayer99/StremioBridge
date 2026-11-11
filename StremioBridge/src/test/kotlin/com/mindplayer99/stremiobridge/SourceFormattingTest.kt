package com.mindplayer99.stremiobridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceFormattingTest {
    private fun addon(key: String = "source", formatter: SourceFormatter = SourceFormatter()) =
        Addon(
            key,
            "https://$key.example/manifest.json",
            manifest =
                Manifest(
                    JSONObject(
                        """{"id":"$key","name":"$key","types":["movie","series"],"resources":["stream","subtitles","meta"]}"""
                    )
                ),
            formatter = formatter,
        )

    private val raw =
        JSONObject(
            """{"name":"▣ 4K ⚡︎ · REMUX · HEVC · ◐ DV","description":"≋ 78 Mbps  │  ♬ DTS-HD MA · ♯ 5.1\n◉ EN  │  💬 SUB EN\n☁ TorBox · Meteor › library","title":"Original title\n💾 69.7 GB","url":"https://video.example/a.mkv?signature=keep","fileIdx":2,"behaviorHints":{"filename":"Hereditary.2160p.REMUX.HEVC.DV.mkv","videoHash":"1234567890abcdef","videoSize":69700000000,"proxyHeaders":{"request":{"Referer":"https://ref.example","User-Agent":"Fixture"}}},"streamData":{"type":"debrid","service":{"id":"torbox","cached":true},"indexer":"Meteor › library","bitrate":78000000,"parsedFile":{"resolution":"2160p","quality":"REMUX","encode":"HEVC","audioTags":["DTS-HD MA"],"audioChannels":["5.1"],"languages":["en"],"subtitles":["en"],"visualTags":["DV"]}}}"""
        )

    @Test
    fun defaultTextKeepsIconsWhitespaceLineBreaksAndEveryOriginalField() {
        val expected =
            raw.getString("name") +
                "\n" +
                raw.getString("description") +
                "\n" +
                raw.getString("title")
        for (name in listOf("Mechfire", "PenguPlay", "HdHub")) assertEquals(
            expected,
            adapt(addon(name), raw, false)!!.label,
        )
        val long =
            JSONObject()
                .put("url", "https://video.example/a")
                .put("name", "⚡︎" + "A".repeat(4_000))
                .put("description", "☁ first\n\n  ◉ EN\n💬 SUB HI")
        assertEquals(
            long.getString("name") + "\n" + long.getString("description"),
            adapt(addon(), long, false)!!.label,
        )
    }

    @Test
    fun optInPresetChangesOnlyTextAndCanBeTurnedOffAgain() {
        val a = addon()
        val original = adapt(a, raw, false)!!
        val formatted = adapt(a.copy(formatter = SourceFormatter(true)), raw, false)!!
        assertEquals(original, formatted.copy(label = original.label))
        assertTrue(formatted.label.contains("▣ 4K ⚡ Cached · REMUX · HEVC · DV"))
        assertTrue(formatted.label.contains("≋ 78 Mbps │ ♬ DTS-HD MA · ♯ 5.1"))
        assertTrue(formatted.label.contains("◉ EN │ 💬 SUB EN"))
        assertTrue(formatted.label.contains("☁ TorBox · Meteor › library · MKV"))
        assertTrue(formatted.label.contains("💾 69.7 GB"))
        assertEquals(
            original.label,
            adapt(a.copy(formatter = SourceFormatter(false)), raw, false)!!.label,
        )
    }

    @Test
    fun mixedHttpDebridAndCacheStatesAreIndependentAndNeverInferredFromBrandAlone() {
        val plain =
            JSONObject(
                """{"name":"↓ [TorBox] 2160p","url":"https://video.example/one.mkv","streamData":{"type":"http"}}"""
            )
        assertTrue(isDebridStream(plain))
        assertEquals(CacheStatus.UNKNOWN, cacheStatus(plain))
        val fast = JSONObject(plain.toString()).put("name", "⚡ [TorBox] 2160p")
        assertEquals(CacheStatus.CACHED, cacheStatus(fast))
        val slow = JSONObject(plain.toString()).put("name", "⏳ [TorBox] 2160p")
        assertEquals(CacheStatus.UNCACHED, cacheStatus(slow))
        fast
            .getJSONObject("streamData")
            .put("service", JSONObject().put("id", "torbox").put("cached", false))
        assertEquals(CacheStatus.UNCACHED, cacheStatus(fast))
        assertEquals(CacheStatus.UNKNOWN, cacheStatus(JSONObject("""{"name":"⚡ Fast HTTP"}""")))
        assertFalse(
            isDebridStream(
                JSONObject("""{"name":"HTTP 1080p","url":"https://video.example/http.mkv"}""")
            )
        )
        assertEquals(
            CacheStatus.UNKNOWN,
            sourceFields(addon(), plain, 2160)["cache"].let {
                if (it.isNullOrEmpty()) CacheStatus.UNKNOWN else CacheStatus.CACHED
            },
        )
    }

    @Test
    fun templatesSupportOptionalBlocksUnicodeAndSafeFallbackOnBadConfiguration() {
        assertEquals(
            "| 1080p |",
            renderTemplate("| {resolution} |", mapOf("resolution" to "1080p")),
        )
        assertEquals(
            "▣ 1080p\n◉ HI",
            renderTemplate(
                "[▣ {resolution}][ ♬ {audio}]\n[◉ {languages}]\n[💾 {size}]",
                mapOf("resolution" to "1080p", "languages" to "HI"),
            ),
        )
        validateFormatter("{addon}", "[♬ {audio}]")
        assertThrows(BridgeFailure::class.java) { validateFormatter("{resoluton}", "") }
        assertThrows(BridgeFailure::class.java) { validateFormatter("{resolution", "") }
        assertThrows(BridgeFailure::class.java) { validateFormatter("[[{resolution}]]", "") }
        val bad = addon(formatter = SourceFormatter(true, "{badField}", ""))
        assertEquals(originalSourceText(raw, ""), adapt(bad, raw, false)!!.label)
        val custom =
            addon(
                formatter =
                    SourceFormatter(
                        true,
                        "★ {addon} · {resolution}",
                        "[Audio: {audio}]\n[Language: {languages}]",
                    )
            )
        assertEquals(
            "★ source · 4K\nAudio: DTS-HD MA\nLanguage: EN",
            adapt(custom, raw, false)!!.label,
        )
    }

    @Test
    fun sparseFilenameSourcesGetHonestUsefulDetailsWithoutFabricatedCacheOrLanguages() {
        val stream =
            JSONObject(
                """{"name":"Stream","description":"1080p • 1.8 GB\nHereditary.2018.1080p.BluRay.AV1.Opus.5.1.mkv","url":"https://video.example/file.mkv"}"""
            )
        val fields = sourceFields(addon("111477"), stream, 1080)
        assertEquals("BluRay", fields["quality"])
        assertEquals("AV1", fields["codec"])
        assertEquals("Opus", fields["audio"])
        assertEquals("5.1", fields["channels"])
        assertEquals("1.8 GB", fields["size"])
        assertEquals("", fields["service"])
        assertEquals("", fields["cache"])
        assertEquals("", fields["languages"])
    }

    @Test
    fun addonFormattingAndRowVisibilitySurviveSnapshotsAndLegacyDefaultsRestoreOriginals() {
        val a = addon(formatter = SourceFormatter(true, "☁ {service}", ""))
        val p =
            Profile(
                addons = listOf(a),
                rows = listOf(Row(a.key, "movie", "one", enabled = false)),
                initialized = true,
            )
        val reopened = readProfile(profileJson(p))
        assertEquals(a.formatter, reopened.addons.single().formatter)
        assertFalse(reopened.rows.single().enabled)
        val old = profileJson(p)
        old.getJSONArray("addons").getJSONObject(0).remove("formatter")
        old.getJSONArray("rows").getJSONObject(0).remove("enabled")
        assertFalse(readProfile(old).addons.single().formatter.enabled)
        assertTrue(readProfile(old).rows.single().enabled)
    }

    private fun catalogAddon(count: Int): Addon =
        addon("catalog")
            .copy(
                manifest =
                    Manifest(
                        JSONObject()
                            .put("id", "catalog")
                            .put("types", JSONArray(listOf("movie")))
                            .put("resources", JSONArray(listOf("catalog")))
                            .put(
                                "catalogs",
                                JSONArray(
                                    (0 until count).map {
                                        JSONObject()
                                            .put("id", "c$it")
                                            .put("name", "Row $it")
                                            .put("type", "movie")
                                    }
                                ),
                            )
                    )
            )

    @Test
    fun allCatalogRowsAreAddedInManifestOrderAndLegacyMigrationKeepsCustomRowsAndStates() {
        val a = catalogAddon(15)
        val p =
            Profile(
                addons = listOf(a),
                initialized = true,
                rows = listOf(Row(a.key, "movie", "c2", title = "Custom", enabled = false)),
            )
        assertEquals(15, p.initialRows().size)
        assertEquals((0 until 15).map { "c$it" }, p.initialRows().map { it.catalog })
        val legacy = profileJson(p).apply { remove("catalogRowsInitialized") }
        val migrated = readProfile(legacy)
        assertEquals(15, migrated.rows.size)
        assertEquals(p.rows.first(), migrated.rows.first())
        assertEquals(migrated.rows, readProfile(profileJson(migrated)).rows)
        val expanded = addNewCatalogRows(migrated, migrated.copy(addons = listOf(catalogAddon(17))))
        assertEquals(listOf("c15", "c16"), expanded.rows.takeLast(2).map { it.catalog })
        assertFalse(expanded.rows.first().enabled)
    }

    @Test
    fun savingDoesNotResurrectRemovedRowsOrResetDisabledRows() = runBlocking {
        val a = catalogAddon(5)
        val p =
            Profile(
                addons = listOf(a),
                rows = listOf(Row(a.key, "movie", "c1", enabled = false)),
                initialized = true,
            )
        val saved =
            refresh(
                p,
                Transport { _, _ -> error("Existing manifests must not be refreshed on save") },
                false,
            )
        assertEquals(p.rows, saved.rows)
    }

    @Test
    fun combinedAndSeparateMetadataAndSubtitleProvidersStillReceiveCorrectIds() = runBlocking {
        val combined = addon("aiostream")
        val cinema = addon("cinemeta")
        val subs = addon("opensubtitles")
        val p = Profile(addons = listOf(combined, cinema, subs), preferred = cinema.key)
        val calls = mutableListOf<String>()
        val tr = Transport { url, _ ->
            calls.add(url)
            when {
                "/meta/series/tt123.json" in url ->
                    JSONObject(
                        """{"meta":{"id":"tt123","type":"series","name":"Series","videos":[{"id":"tt123:2:8","season":2,"episode":8}]}}"""
                    )
                "/subtitles/series/tt123%3A2%3A8.json" in url ->
                    JSONObject(
                        """{"subtitles":[{"lang":"en","url":"https://subs.example/${if ("opensubtitles" in url) "separate" else "combined"}.vtt"}]}"""
                    )
                else ->
                    JSONObject(
                        """{"streams":[{"name":"▣ 1080p ⚡\n☁ TorBox","url":"https://video.example/a.mkv"}]}"""
                    )
            }
        }
        val detail =
            Metadata(p, tr)
                .resolve(
                    Tile("aiostream", "series", JSONObject("""{"id":"tt123","name":"Series"}"""))
                )
        assertEquals(cinema.key, detail.addon)
        val video =
            Video(
                "series",
                listOf(detail.meta.objects("videos").single().getString("id")),
                detail.requested,
            )
        val playback = Playback(p, tr)
        val links = playback.links(video, false)
        val captions = playback.subtitles(video, links)
        assertEquals(2, captions.size)
        assertTrue(calls.any { "aiostream.example/subtitles/series/tt123%3A2%3A8.json" in it })
        assertTrue(calls.any { "opensubtitles.example/subtitles/series/tt123%3A2%3A8.json" in it })
        assertTrue(links.all { it.label.contains("▣ 1080p ⚡\n☁ TorBox") })
    }
}
