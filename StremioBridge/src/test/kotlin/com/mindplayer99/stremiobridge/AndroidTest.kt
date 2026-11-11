package com.mindplayer99.stremiobridge

import android.app.Activity
import android.app.AlertDialog
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidTest {
    @Test
    fun savedProfileSurvivesReopenAndDetectsConcurrentEdits() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = ProfileStore(ctx)
        val old = Profile(name = "Cinema")
        store.replace(null, old)
        val current = ProfileStore(ctx).all().single()
        val next = current.copy(initialized = true)
        store.replace(current, next)
        assertThrows(BridgeFailure::class.java) { store.replace(current, null) }
        assertEquals(next, store.all().single())
        assertTrue(
            ctx.noBackupFilesDir.listFiles()!!.any { it.name.startsWith("stremiobridge-profiles") }
        )
    }

    @Test
    fun opaqueDurableReferencesAreProfileScoped() {
        val ctx = RuntimeEnvironment.getApplication()
        val refs = References(ctx)
        val tile =
            Tile(
                "source",
                "series",
                JSONObject().put("id", "custom:private-fixture").put("name", "Series"),
            )
        val k = refs.tileKey("one", tile)
        assertTrue(Regex("[0-9a-f]{64}").matches(k))
        val other = References(ctx)
        assertEquals(tile.id, other.tile("one", k).id)
        assertThrows(BridgeFailure::class.java) { other.tile("two", k) }
        val video = Video("series", listOf("kitsu:12:0"), "tvdb:5")
        val vk = other.videoKey("one", "meta", video)
        assertEquals(video, refs.video("one", vk))
        refs.drop("one")
        assertThrows(BridgeFailure::class.java) { other.video("one", vk) }
    }

    @Test
    fun nativeSettingsOpensCreateProfile() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val a = controller.get()
            Settings(a, ProfileStore(a)) { _, _ -> error("Unexpected save") }.manager()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertEquals("Create profile", dialog.listView.adapter.getItem(0))
            dialog.listView.performItemClick(dialog.listView.getChildAt(0), 0, 0)
            assertEquals(
                "Continue",
                ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .text
                    .toString(),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun hostHomeSearchMovieSeriesAnimeAndLinkCallbacks() = runBlocking {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val body =
                        when {
                            path == "/rows/manifest.json" ->
                                """{"id":"rows","resources":["catalog"],"catalogs":[{"type":"series","id":"home","extra":[{"name":"search"}]}]}"""
                            path == "/meta/manifest.json" ->
                                """{"id":"meta","types":["series","movie"],"resources":["meta"]}"""
                            path == "/play/manifest.json" ->
                                """{"id":"play","types":["series","movie"],"resources":["stream","subtitles"]}"""
                            path.startsWith("/rows/catalog/series/home") ->
                                """{"metas":[{"id":"tvdb:1","type":"series","name":"Series"},{"id":"tt123","type":"movie","name":"Movie"},{"id":"tvdb:9","type":"series","name":"Anime","genres":["Anime"]}]}"""
                            path == "/meta/meta/series/tvdb%3A1.json" ->
                                """{"meta":{"id":"tvdb:1","type":"series","name":"Series","videos":[{"id":"kitsu:22:0","season":0,"episode":0},{"id":"kitsu:99:4","season":2,"episode":4}]}}"""
                            path == "/meta/meta/series/tvdb%3A9.json" ->
                                """{"meta":{"id":"tvdb:9","type":"series","name":"Anime","genres":["Anime"],"videos":[{"id":"kitsu:99:4","season":3,"episode":4}]}}"""
                            path == "/meta/meta/movie/tt123.json" ->
                                """{"meta":{"id":"tt123","type":"movie","name":"Movie","runtime":"1h 20m"}}"""
                            path in
                                setOf(
                                    "/play/stream/series/kitsu%3A99%3A4.json",
                                    "/play/stream/movie/tt123.json",
                                ) ->
                                """{"streams":[{"url":"https://fixture.invalid/file?sig=one","name":"Hindi REMUX 1080p","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://fixture.invalid"}}}},{"url":"https://fixture.invalid/other.mpd","name":"4K WEB"}]}"""
                            path.startsWith("/play/subtitles/") ->
                                """{"subtitles":[{"lang":"hi","url":"https://fixture.invalid/hi.vtt"}]}"""
                            else -> return MockResponse().setResponseCode(404)
                        }
                    return MockResponse().setBody(body)
                }
            }
        server.start()
        try {
            val p =
                refresh(
                    Profile(
                        addons =
                            listOf("rows", "meta", "play").map {
                                Addon(
                                    it,
                                    server
                                        .url("/" + it + "/manifest.json?token=private-fixture")
                                        .toString(),
                                )
                            }
                    ),
                    Http,
                    true,
                )
            val provider = BridgeProvider(p, References(RuntimeEnvironment.getApplication()))
            val row = provider.mainPage.single()
            val cards =
                provider
                    .getMainPage(1, MainPageRequest(row.name, row.data, false))
                    .items
                    .single()
                    .list
            assertEquals(listOf("Series", "Movie", "Anime"), cards.map { it.name })
            assertFalse(cards.first().url.contains("private-fixture"))
            assertEquals(3, provider.search("fixture", 1).items.size)
            val series = provider.load(cards.first().url) as TvSeriesLoadResponse
            assertEquals(listOf(0, 2), series.episodes.map { it.season })
            assertEquals(listOf(0, 4), series.episodes.map { it.episode })
            val movie = provider.load(cards[1].url) as MovieLoadResponse
            assertEquals(80, movie.duration)
            val anime =
                (provider.load(cards[2].url) as AnimeLoadResponse)
                    .episodes
                    .values
                    .flatten()
                    .single()
            assertEquals(3, anime.season)
            for (data in listOf(series.episodes.last().data, movie.dataUrl, anime.data)) {
                val links = mutableListOf<ExtractorLink>()
                val langs = mutableListOf<String>()
                assertTrue(provider.loadLinks(data, false, { langs.add(it.lang) }, links::add))
                assertEquals(listOf(1080, 2160), links.map { it.quality })
                assertEquals("https://fixture.invalid", links.first().referer)
                assertFalse(links.first().toString().contains("sig=one"))
                assertEquals(listOf("hi"), langs)
            }
        } finally {
            server.shutdown()
        }
    }
}
