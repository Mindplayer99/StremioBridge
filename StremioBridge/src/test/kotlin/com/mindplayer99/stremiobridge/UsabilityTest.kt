package com.mindplayer99.stremiobridge

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsabilityTest {
    private fun addon(key: String, name: String = key) =
        Addon(
            key,
            "https://$key.example/manifest.json",
            manifest =
                Manifest(
                    JSONObject(
                        """{"id":"$key","name":"$name","types":["movie","series"],"resources":["meta","stream"],"catalogs":[{"id":"movies","type":"movie","name":"Movies"},{"id":"you","type":"movie","name":"For You"},{"id":"you","type":"series","name":"For You"}]}"""
                    )
                ),
        )

    @Test
    fun friendlyNamesPreserveProtocolFiltersAndCustomTitles() {
        val a = addon("catalog")
        val row = Row(a.key, "movie", "movies", mapOf("genre" to "None"))
        val p = Profile(addons = listOf(a))
        val key = row.key
        assertEquals("Movies", rowTitle(p, row))
        assertEquals("None", row.args["genre"])
        assertEquals(key, row.key)
        assertEquals("For You (Movies)", rowTitle(p, Row(a.key, "movie", "you")))
        assertEquals(
            "For You (Series) · Horror",
            rowTitle(p, Row(a.key, "series", "you", mapOf("genre" to "Horror"))),
        )
        assertEquals("My.movie.none", rowTitle(p, row.copy(title = "My.movie.none")))
    }

    @Test
    fun aiometadataCastPreservesPhotosRolesAndNameOnlyFallbacks() {
        val j =
            JSONObject(
                """{"cast":["Brad Pitt","Missing Photo"],"app_extras":{"cast":[{"name":"Brad Pitt","character":"James Belmont","photo":"https://images.example/brad.jpg"},{"name":"Other Actor","character":"Amber","photo":null}]},"credits_cast":[{"name":"TMDB Actor","profile_path":"/actor.jpg"}]}"""
            )
        val cast = castData(j, ::httpUrl)
        assertEquals(
            listOf("Brad Pitt", "Other Actor", "TMDB Actor", "Missing Photo"),
            cast.map { it.actor.name },
        )
        assertEquals("https://images.example/brad.jpg", cast.first().actor.image)
        assertEquals("James Belmont", cast.first().roleString)
        assertNull(cast.last().actor.image)
        assertEquals("https://image.tmdb.org/t/p/w185/actor.jpg", cast[2].actor.image)
        val names = castData(JSONObject("""{"cast":["Brad Pitt"]}"""), ::httpUrl)
        assertEquals("https://images.example/brad.jpg", mergeCast(names, cast).first().actor.image)
    }

    @Test
    fun compactLabelsKeepServiceLanguageAndRealHttpHeaders() {
        val stream =
            JSONObject(
                """{"name":"▣ 4K · REMUX · HEVC","description":"♬ Atmos · TrueHD 7.1\n◉ HIN · ENG\n☁ TorBox · Xperience · MFP","url":"https://media.example/a.mkv","streamData":{"type":"debrid","service":{"id":"torbox","cached":true}},"behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://player.example/","User-Agent":"Example"}}}}"""
            )
        val result = adapt(addon("aiostream", "Mechfire"), stream, false)!!
        assertTrue(result.debrid)
        assertTrue(result.label.contains("Cached"))
        assertTrue(
            isDebridStream(JSONObject("""{"streamData":{"type":"debrid","service":{"id":"tb"}}}"""))
        )
        assertFalse(
            isDebridStream(
                JSONObject(
                    """{"name":"TorBox compatible HTTP provider","streamData":{"type":"http"}}"""
                )
            )
        )
        assertEquals(2160, result.quality)
        assertEquals(2, result.label.lines().size)
        assertTrue(result.label.contains("TorBox"))
        assertTrue(result.label.contains("HIN"))
        assertTrue(result.label.length <= 209)
        assertEquals("https://player.example/", result.headers["Referer"])
        assertNull(adapt(addon("http"), stream, true))
        for (provider in listOf("PenguPlay", "HdHub", "111477")) {
            val http =
                adapt(
                    addon("http", provider),
                    JSONObject(
                        """{"name":"$provider 1080p","title":"Hindi · English · WEB-DL","url":"https://media.example/master.m3u8"}"""
                    ),
                    false,
                )!!
            assertEquals(Format.HLS, http.format)
            assertFalse(http.debrid)
            assertTrue(http.label.startsWith(provider))
            assertTrue(http.label.contains("Hindi"))
            assertEquals(1080, http.quality)
        }
    }

    @Test
    fun nativeGroupPrioritiesKeepQualityValuesInsideTheirAddonGroup() {
        val qualities = listOf(-5, 0, 1, 4, 12, 20)
        for (index in 0..4) assertTrue(
            NativeSources.priority(index, 6, qualities) + qualities.min() >
                NativeSources.priority(index + 1, 6, qualities) + qualities.max()
        )
        val a = addon("one", "HTTP")
        val p = Profile(name = "Cinema", addons = listOf(a))
        assertNotEquals(NativeSources.name(p, a, true), NativeSources.name(p, a, false))
        assertFalse(NativeSources.name(p.copy(name = "Cinema/Family"), a, false).contains('/'))
        val old = profileJson(p).apply { remove("groupPlayback") }
        assertTrue(readProfile(old).groupPlayback)
        assertFalse(readProfile(profileJson(p.copy(groupPlayback = false))).groupPlayback)
    }

    @Test
    fun slowPreferredMetadataDoesNotBlockHealthyFallbackAndEpisodesStayExact() = runBlocking {
        val primary = addon("primary")
        val backup = addon("backup")
        val stopped = CompletableDeferred<Unit>()
        val svc =
            Metadata(
                Profile(addons = listOf(primary, backup), preferred = primary.key),
                Transport { url, _ ->
                    if ("primary" in url)
                        try {
                            delay(10_000)
                            error("unreachable")
                        } finally {
                            stopped.complete(Unit)
                        }
                    JSONObject(
                        """{"meta":{"id":"tt123","type":"series","name":"Verified","videos":[{"id":"tt123:2:8","season":2,"episode":8}]}}"""
                    )
                },
                preferredGraceMs = 50,
                deadlineMs = 1_000,
            )
        val detail =
            withTimeout(800) {
                svc.resolve(
                    Tile("primary", "series", JSONObject("""{"id":"tt123","name":"Preview"}"""))
                )
            }
        assertEquals("backup", detail.addon)
        assertEquals("tt123:2:8", detail.meta.objects("videos").single().str("id"))
        assertTrue(stopped.isCompleted)
    }

    @Test
    fun preferredMetadataWinsDuringGraceAndWrongIdentityIsNeverUsed() = runBlocking {
        val p = Profile(addons = listOf(addon("first"), addon("second")))
        val tile = Tile("first", "movie", JSONObject("""{"id":"tt123","name":"Preview"}"""))
        val preferred =
            Metadata(
                    p,
                    Transport { url, _ ->
                        if ("first" in url) delay(40)
                        JSONObject(
                            """{"meta":{"id":"tt123","type":"movie","name":"${if ("first" in url) "Preferred" else "Backup"}"}}"""
                        )
                    },
                    200,
                    1_000,
                )
                .resolve(tile)
        assertEquals("Preferred", preferred.meta.str("name"))
        val wrong =
            Metadata(
                    p,
                    Transport { _, _ -> JSONObject("""{"meta":{"id":"tt999","name":"Wrong"}}""") },
                    20,
                    1_000,
                )
                .resolve(tile)
        assertEquals("Preview", wrong.meta.str("name"))
    }

    @Test
    fun readySourceBatchesArriveBeforeSlowAddonsAndReturnConfiguredOrder() = runBlocking {
        val p = Profile(addons = listOf(addon("slow"), addon("fast")))
        val first = CompletableDeferred<String>()
        val pending = async {
            Playback(
                    p,
                    Transport { url, _ ->
                        if ("slow" in url) delay(300)
                        JSONObject(
                            """{"streams":[{"url":"https://media.example/${if ("slow" in url) "slow" else "fast"}.mkv"}]}"""
                        )
                    },
                )
                .links(Video("movie", listOf("tt123"), null), false) {
                    if (it.isNotEmpty() && !first.isCompleted) first.complete(it.first().addon)
                }
        }
        assertEquals("fast", withTimeout(200) { first.await() })
        assertFalse(pending.isCompleted)
        assertEquals(listOf("slow", "fast"), pending.await().map { it.addon })
    }
}
