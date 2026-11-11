package com.mindplayer99.stremiobridge

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun addon(key: String, json: String) =
        Addon(
            key,
            "https://" + key + ".example/manifest.json",
            manifest = Manifest(JSONObject(json)),
        )

    private val a =
        addon(
            "catalog",
            """{"id":"A","types":["movie"],"idPrefixes":["tt"],"resources":["catalog"],"catalogs":[{"type":"series","id":"mine","extra":[{"name":"skip"}]},{"type":"anime.series","id":"find","extra":[{"name":"search","isRequired":true}]}]}""",
        )
    private val b =
        addon(
            "meta",
            """{"id":"B","resources":[{"name":"meta","types":["series","movie"],"idPrefixes":["tvdb:","tt"]}]}""",
        )
    private val c =
        addon(
            "stream",
            """{"id":"C","resources":[{"name":"stream","types":["series","movie"],"idPrefixes":["kitsu:","tt"]}]}""",
        )
    private val d =
        addon("subtitle", """{"id":"D","types":["series","movie"],"resources":["subtitles"]}""")
    private val p = Profile(addons = listOf(a, b, c, d))

    @Test
    fun encodedManifestAndResourceRouting() {
        val e = Endpoint("stremio://a.example/c%2F1/a%2Bb/manifest.json?k=x%2Fy&k=2")
        assertEquals(
            "https://a.example/c%2F1/a%2Bb/catalog/series/list%2Fa/search=Hindi%20%26%20dub&genre=Sci%2FFi.json?k=x%2Fy&k=2",
            e.resource(
                "catalog",
                "series",
                "list/a",
                linkedMapOf("search" to "Hindi & dub", "genre" to "Sci/Fi"),
            ),
        )
        val m =
            Manifest(
                JSONObject(
                    """{"id":"a","types":["movie"],"idPrefixes":["tt"],"resources":["meta",{"name":"stream","types":["series"]}]}"""
                )
            )
        assertTrue(m.accepts("meta", "movie", "tt12"))
        assertFalse(m.accepts("meta", "movie", "tvdb:1"))
        assertTrue(m.accepts("stream", "series", "opaque:1"))
        assertFalse(m.accepts("stream", "movie", "tt1"))
        assertEquals("mine", p.initialRows().single().catalog)
        assertThrows(BridgeFailure::class.java) { Endpoint("https://a.example/configure") }
        assertThrows(NeedsConfiguration::class.java) {
            Manifest.discover(
                JSONObject("""{"id":"x","behaviorHints":{"configurationRequired":true}}"""),
                e,
            )
        }
    }

    @Test
    fun separateCatalogMetadataStreamsAndSubtitles() = runBlocking {
        val calls = mutableListOf<String>()
        val tr = Transport { url, _ ->
            calls.add(url)
            JSONObject(
                when {
                    url.contains("catalog.example/catalog/series/mine") ->
                        """{"metas":[{"type":"series","id":"tvdb:55","name":"Series"}]}"""
                    url.contains("meta.example/meta/series/tvdb%3A55") ->
                        """{"meta":{"id":"tvdb:55","type":"series","name":"Series","videos":[{"id":"kitsu:33:0","season":0,"episode":0},{"id":"kitsu:94:8","season":2,"episode":8}]}}"""
                    url.contains("stream.example/stream/series/kitsu%3A94%3A8") ->
                        """{"streams":[{"name":"Hindi 1080p REMUX","url":"https://video.example/file?sig=1","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://ref.example"}}}},{"name":"4K WEB","url":"https://video.example/master.m3u8"}]}"""
                    url.contains("subtitle.example/subtitles/series/kitsu%3A94%3A8") ->
                        """{"subtitles":[{"lang":"hin","url":"https://video.example/hi.vtt"}]}"""
                    else -> error("Unexpected request")
                }
            )
        }
        val t = Catalogs(p, tr).page(a, a.manifest!!.catalogs.first(), emptyMap(), 1).tiles.single()
        val detail = Metadata(p, tr).resolve(t)
        val videos = detail.meta.objects("videos")
        assertEquals(listOf(0, 2), videos.map { it.int("season") })
        assertEquals(listOf(0, 8), videos.map { it.int("episode") })
        val v = Video("series", listOf(videos.last().getString("id")), detail.requested)
        val playback = Playback(p, tr)
        val links = playback.links(v, false)
        assertEquals(listOf(1080, 2160), links.map { it.quality })
        assertEquals(listOf(Format.VIDEO, Format.HLS), links.map { it.format })
        assertEquals("https://ref.example", links.first().headers["Referer"])
        assertEquals("hin", playback.subtitles(v, links).single().lang)
        assertEquals(4, calls.size)
    }

    @Test
    fun movieAliasesAndUnrelatedMetadata() = runBlocking {
        val t =
            Tile(
                "a",
                "movie",
                JSONObject("""{"id":"tmdb:44","name":"Same title","imdb_id":"tt123"}"""),
            )
        val wrong = b.copy(key = "wrong", url = "https://wrong.example/manifest.json")
        val tr = Transport { url, _ ->
            JSONObject(
                if ("wrong.example" in url)
                    """{"meta":{"id":"tt444","name":"Same title","type":"movie"}}"""
                else """{"meta":{"id":"tt123","name":"Verified","type":"movie"}}"""
            )
        }
        assertEquals(
            "Verified",
            Metadata(p.copy(addons = listOf(wrong, b)), tr).resolve(t).meta.str("name"),
        )
        val links =
            Playback(
                    p,
                    Transport { url, _ ->
                        assertTrue(url.endsWith("/stream/movie/tt123.json"))
                        JSONObject("""{"streams":[{"url":"https://video.example/resolved"}]}""")
                    },
                )
                .links(Video(t.type, identities(t.meta), null), false)
        assertEquals(Format.VIDEO, links.single().format)
    }

    @Test
    fun reconstructPaginationAndStopRepeats() = runBlocking {
        val offsets = mutableListOf<Int>()
        val svc =
            Catalogs(
                p,
                Transport { url, _ ->
                    val offset = Regex("skip=([0-9]+)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
                    offsets.add(offset)
                    val ids = if (offset == 0) listOf("a", "b", "c") else listOf("d", "e")
                    JSONObject()
                        .put(
                            "metas",
                            JSONArray(ids.map { JSONObject().put("id", it).put("name", it) }),
                        )
                },
            )
        val cat = a.manifest!!.catalogs.first()
        assertEquals(listOf("d", "e"), svc.page(a, cat, emptyMap(), 2).tiles.map { it.id })
        assertFalse(svc.page(a, cat, emptyMap(), 3).more)
        assertEquals(listOf(0, 3, 5), offsets)
    }

    @Test
    fun distinctFilesHeadersAndCasting() {
        val j =
            JSONObject(
                """{"name":"1080p","url":"https://video.example/a.mpd?sign=1","fileIdx":0,"behaviorHints":{"proxyHeaders":{"request":{"Authorization":"fixture"}}}}"""
            )
        val one = adapt(c, j, false)!!
        assertEquals(0, one.index)
        assertEquals(Format.DASH, one.format)
        assertNull(adapt(c, j, true))
        j.remove("fileIdx")
        val missing = adapt(c, j, false)!!
        j.getJSONObject("behaviorHints")
            .getJSONObject("proxyHeaders")
            .getJSONObject("request")
            .put("Authorization", "variant")
        assertEquals(3, listOf(one, missing, adapt(c, j, false)!!, one).distinct().size)
        assertNull(adapt(c, JSONObject("""{"infoHash":"fixture","fileIdx":0}"""), false))
        assertNull(adapt(c, JSONObject("""{"externalUrl":"https://fixture.example"}"""), false))
    }

    @Test
    fun independentInstancesAndFreshSignedUrls() = runBlocking {
        val calls = AtomicInteger()
        val tr = Transport { url, _ ->
            if ("offline" in url) throw IOException()
            JSONObject()
                .put(
                    "streams",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put(
                                    "url",
                                    "https://video.example/file?sig=" + calls.incrementAndGet(),
                                )
                        ),
                )
        }
        val svc =
            Playback(
                p.copy(
                    addons =
                        listOf(
                            c.copy(key = "failed", url = "https://offline.example/manifest.json"),
                            c,
                            c.copy(key = "two", url = "https://stream.example/other/manifest.json"),
                        )
                ),
                tr,
            )
        val v = Video("movie", listOf("tt123"), null)
        val one = svc.links(v, false)
        assertEquals(listOf("stream", "two"), one.map { it.addon })
        assertTrue(
            one.map { it.url }.intersect(svc.links(v, false).map { it.url }.toSet()).isEmpty()
        )
    }

    @Test
    fun subtitleFileIdentity() = runBlocking {
        val one =
            adapt(
                c,
                JSONObject(
                    """{"url":"https://video.example/a","subtitles":[{"lang":"en","url":"https://video.example/only.vtt"}],"behaviorHints":{"filename":"A & B.mkv","videoHash":"1234567890abcdef","videoSize":555}}"""
                ),
                false,
            )!!
        val requests = mutableListOf<String>()
        val svc =
            Playback(
                p,
                Transport { url, _ ->
                    requests.add(url)
                    JSONObject(
                        """{"subtitles":[{"lang":"hin","url":"https://video.example/any.vtt"}]}"""
                    )
                },
            )
        val v = Video("movie", listOf("tt123"), null)
        assertEquals(2, svc.subtitles(v, listOf(one)).size)
        assertTrue(
            requests
                .single()
                .contains("videoHash=1234567890abcdef&videoSize=555&filename=A%20%26%20B.mkv")
        )
        requests.clear()
        assertEquals(
            "hin",
            svc.subtitles(v, listOf(one, one.copy(hash = null, captions = emptyList())))
                .single()
                .lang,
        )
        assertTrue(requests.single().endsWith("/tt123.json"))
    }

    @Test
    fun backupIsolationAndAtomicRefresh() = runBlocking {
        val raw = profileJson(p).toString()
        try {
            refresh(p, Transport { _, _ -> throw IOException() }, true)
            fail("Expected refresh failure")
        } catch (_: BridgeFailure) {}
        assertEquals(raw, profileJson(p).toString())
        val encrypted = Backup.seal(p, "example test password".toCharArray())
        assertFalse(encrypted.contains("example/manifest"))
        assertFalse(encrypted.contains(p.key))
        assertThrows(Exception::class.java) {
            Backup.open(encrypted, "incorrect test password".toCharArray(), "Copy")
        }
        val copy = Backup.open(encrypted, "example test password".toCharArray(), "Copy")
        assertNotEquals(p.key, copy.key)
        assertEquals(p.addons.map { it.url }, copy.addons.map { it.url })
    }

    @Test
    fun loopbackRedirectStripsCredentials() = runBlocking {
        val one = MockWebServer()
        val two = MockWebServer()
        try {
            one.start()
            two.start()
            one.enqueue(MockResponse().setResponseCode(302).setHeader("Location", two.url("/ok")))
            two.enqueue(MockResponse().setBody("""{"id":"ok"}"""))
            val url =
                one.url("/a%2Fb/manifest.json?key=x%2Fy")
                    .newBuilder()
                    .username("demo")
                    .password("test")
                    .build()
            assertEquals("ok", Http.get(url.toString(), false).str("id"))
            val sent = one.takeRequest()
            assertEquals("/a%2Fb/manifest.json?key=x%2Fy", sent.path)
            assertNotNull(sent.getHeader("Authorization"))
            assertNull(two.takeRequest().getHeader("Authorization"))
        } finally {
            one.shutdown()
            two.shutdown()
        }
    }
}
