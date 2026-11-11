package com.mindplayer99.stremiobridge

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTest {
    private fun addon(key: String, capabilities: String = "[\"catalog\",\"meta\"]") =
        Addon(
            key,
            "http://127.0.0.1:1/" + key + "/manifest.json",
            manifest =
                Manifest(
                    JSONObject(
                        """
        {"id":"fixture.$key","name":"Fixture $key","types":["movie","series"],"resources":$capabilities,
         "behaviorHints":{"configurable":true},
         "catalogs":[{"type":"movie","id":"popular","name":"Popular","extra":[{"name":"genre","options":["Horror","Comedy"]}]},
                     {"type":"series","id":"series","name":"Series"}]}
        """
                    )
                ),
            checked = 1_790_000_000_000L,
        )

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    private fun dialog() = ShadowDialog.getLatestDialog()

    private fun views() = descendants(dialog().window!!.decorView)

    private fun click(label: String) {
        val view =
            views().filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }
                ?: error(
                    "Missing control " +
                        label +
                        " in " +
                        views().filterIsInstance<TextView>().map { it.text.toString() }
                )
        var target: View = view
        while (!target.isClickable) target =
            target.parent as? View ?: error("Control is not clickable: " + label)
        check(target.isEnabled)
        target.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun screenshot(name: String, maxHeight: Int = 1500) {
        val decor = dialog().window!!.decorView
        val width = 738
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(image))
        val directory =
            File(System.getProperty("java.io.tmpdir"), "stremiobridge-ui").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(decor.width > 0 && decor.height > 0)
        assertTrue(decor.height <= maxHeight)
    }

    private fun assertControlVisible(label: String) {
        val view = views().filterIsInstance<TextView>().single { it.text.toString() == label }
        val decor = dialog().window!!.decorView
        val position = IntArray(2)
        val origin = IntArray(2)
        view.getLocationInWindow(position)
        decor.getLocationInWindow(origin)
        assertTrue(
            label + " is below the window",
            position[1] - origin[1] + view.height <= decor.height,
        )
        assertTrue(label + " is above the window", position[1] >= origin[1])
        assertTrue(view.height > 0)
    }

    private fun awaitSaved(check: () -> Boolean) {
        repeat(150) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(20)
        }
        fail("Save did not complete")
    }

    private fun withSettings(
        profile: Profile?,
        light: Boolean = false,
        test: (ProfileStore) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller
            .get()
            .setTheme(
                if (light) android.R.style.Theme_Material_Light_NoActionBar
                else android.R.style.Theme_Material_NoActionBar
            )
        controller.setup()
        val activity = controller.get()
        val store = ProfileStore(activity)
        if (profile != null) store.replace(null, profile)
        Settings(activity, store) { old, next -> store.replace(old, next) }.manager()
        try {
            test(store)
        } finally {
            dialog().dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun batchCatalogSelectionPersistsWithoutRefreshingSavedAddons() {
        val a = addon("Catalog")
        val profile = Profile(name = "Cinema", addons = listOf(a), initialized = true)
        withSettings(profile) { store ->
            screenshot("profiles-dark")
            click("Cinema")
            screenshot("profile-dark")
            screenshot("profile-compact", 700)
            assertControlVisible("Save changes")
            click("Home rows")
            click("Choose catalogs")
            screenshot("catalog-picker-dark")
            screenshot("catalog-picker-compact", 700)
            assertControlVisible("Add selected")
            val list = views().filterIsInstance<ListView>().single()
            list.performItemClick(null, 0, 0)
            list.performItemClick(null, 1, 1)
            click("Add 2 rows")
            click("Back")
            click("Save changes")
            awaitSaved { store.all().single().rows.size == 2 }
            assertEquals(a.checked, store.all().single().addons.single().checked)
            assertEquals(a.url, store.all().single().addons.single().url)
            assertEquals(profile.key, store.all().single().key)
        }
    }

    @Test
    fun resourceSwitchChangesSurviveDisablingTheAddon() {
        val a =
            addon("Details", "[\"meta\"]")
                .copy(
                    manifest =
                        Manifest(
                            JSONObject(
                                """
            {"id":"details","name":"Details","types":["movie","series"],"resources":["meta"],"behaviorHints":{"configurable":true}}
        """
                            )
                        )
                )
        withSettings(Profile(name = "Cinema", addons = listOf(a), initialized = true)) { store ->
            click("Cinema")
            click("Addons")
            click("Details")
            screenshot("addon-dark")
            assertEquals(
                setOf("Enabled", "Details & episodes"),
                views().filterIsInstance<Switch>().map { it.text.toString() }.toSet(),
            )
            click("Details & episodes")
            click("Enabled")
            click("Back")
            click("Back")
            click("Save changes")
            awaitSaved { !store.all().single().addons.single().enabled }
            assertFalse("meta" in store.all().single().addons.single().switches)
        }
    }

    @Test
    fun editingFiltersAndReorderingPreservesOtherRows() {
        val a = addon("Catalog")
        val row = Row(a.key, "movie", "popular", mapOf("genre" to "Horror"))
        val other = Row(a.key, "series", "series")
        val profile =
            Profile(
                name = "Cinema",
                addons = listOf(a),
                rows = listOf(row, other),
                initialized = true,
            )
        withSettings(profile, light = true) { store ->
            click("Cinema")
            screenshot("profile-light")
            click("Home rows")
            click("Popular")
            click("Edit filters")
            screenshot("filters-light")
            click("genre")
            click("Comedy")
            click("Apply filters")
            click("Move to bottom")
            click("Back")
            click("Save changes")
            awaitSaved { store.all().single().rows.last().args["genre"] == "Comedy" }
            val actual = store.all().single().rows
            assertEquals(other, actual.first())
            assertEquals(a.key, actual.last().addon)
            assertEquals(2, actual.size)
        }
    }

    @Test
    fun canceledProfileEditsDoNotCommit() {
        val profile =
            Profile(name = "Cinema", addons = listOf(addon("Catalog")), initialized = true)
        withSettings(profile) { store ->
            click("Cinema")
            click("Addons")
            click("Fixture Catalog")
            click("Enabled")
            click("Back")
            click("Back")
            click("Back")
            click("Discard")
            assertTrue(store.all().single().addons.single().enabled)
        }
    }

    @Test
    fun obsoleteFiltersCanBeRepairedAfterAManifestChange() {
        val a = addon("Catalog")
        val stale =
            Row(
                a.key,
                "movie",
                "popular",
                mapOf("genre" to "Old value", "retired" to "yes"),
                "Favorites",
            )
        val other = Row(a.key, "series", "series")
        withSettings(
            Profile(
                name = "Cinema",
                addons = listOf(a),
                rows = listOf(stale, other),
                initialized = true,
            )
        ) { store ->
            click("Cinema")
            click("Home rows")
            click("Favorites")
            click("Edit filters")
            assertTrue(
                views().filterIsInstance<TextView>().any {
                    it.text.toString().startsWith("Unavailable filter values were cleared.")
                }
            )
            click("genre")
            click("Comedy")
            click("Apply filters")
            click("Back")
            click("Back")
            click("Save changes")
            awaitSaved { store.all().single().rows.first().args == mapOf("genre" to "Comedy") }
            assertEquals("Favorites", store.all().single().rows.first().title)
            assertEquals(other, store.all().single().rows.last())
        }
    }

    @Test
    fun refreshReportsFailuresAndRetainsPreviousSnapshots() = runBlocking {
        val good = addon("good")
        val offline = addon("offline")
        val profile =
            Profile(
                addons = listOf(good, offline),
                rows = listOf(Row(offline.key, "movie", "popular")),
                initialized = true,
            )
        val result =
            refreshSelected(
                profile,
                Transport { url, _ ->
                    if ("offline" in url)
                        throw BridgeFailure("Addon connection failed or timed out.")
                    JSONObject(
                        """{"id":"updated","name":"Updated","resources":["meta"],"types":["movie"]}"""
                    )
                },
                setOf(good.key, offline.key),
            )
        assertEquals("Updated", result.profile.addons.first().manifest!!.name)
        assertSame(offline.manifest, result.profile.addons.last().manifest)
        assertEquals(offline.checked, result.profile.addons.last().checked)
        assertEquals(profile.rows, result.profile.rows)
        assertEquals(setOf(offline.key), result.failures.keys)
    }

    @Test
    fun legacySnapshotsAndConfigurationLinksRemainUsable() {
        val a = addon("Configured")
        val before =
            Profile(
                name = "Existing",
                addons = listOf(a),
                rows = listOf(Row(a.key, "movie", "popular")),
                initialized = true,
            )
        val reopened = readProfile(profileJson(before))
        assertEquals(before.key, reopened.key)
        assertEquals(before.rows, reopened.rows)
        assertTrue(reopened.addons.single().manifest!!.configurable)
        assertEquals("http://127.0.0.1:1/Configured/configure", Endpoint(a.url).configure())
        assertThrows(BridgeFailure::class.java) {
            updateRow(
                before.copy(rows = before.rows + Row(a.key, "series", "series")),
                1,
                before.rows.single(),
            )
        }
    }
}
