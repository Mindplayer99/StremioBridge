package com.mindplayer99.stremiobridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import org.json.JSONArray
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
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualTest {
    private val longName = "Xperience catalogs for cinema, family evenings and extended collections"

    private fun addon(key: String, name: String = longName, enabled: Boolean = true) =
        Addon(
            key,
            "http://127.0.0.1:1/$key/manifest.json",
            enabled = enabled,
            manifest =
                Manifest(
                    JSONObject()
                        .put("id", key)
                        .put("name", name)
                        .put("types", JSONArray(listOf("movie", "series")))
                        .put("resources", JSONArray(roles))
                        .put("behaviorHints", JSONObject().put("configurable", true))
                        .put(
                            "catalogs",
                            JSONArray(
                                (0..11).map { index ->
                                    JSONObject()
                                        .put("id", "c$index")
                                        .put("type", "movie")
                                        .put(
                                            "name",
                                            "Collection $index · Award winning cinema and weekend favorites",
                                        )
                                        .put(
                                            "extra",
                                            JSONArray()
                                                .put(
                                                    JSONObject()
                                                        .put("name", "genre")
                                                        .put(
                                                            "options",
                                                            JSONArray(
                                                                listOf(
                                                                    "Horror",
                                                                    "Comedy",
                                                                    "Science fiction and fantasy",
                                                                )
                                                            ),
                                                        )
                                                ),
                                        )
                                }
                            ),
                        )
                ),
            checked = 1_790_000_000_000L,
        )

    private fun profile(): Profile {
        val a = addon("catalog")
        val b = addon("details", "AIOMetadata", false)
        return Profile(
            name = "Cinema",
            addons = listOf(a, b),
            initialized = true,
            rows =
                listOf(
                    Row(a.key, "movie", "c0", mapOf("genre" to "Horror"), "Weekend favorites"),
                    Row(a.key, "movie", "c1"),
                ),
        )
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    private fun dialog() = ShadowDialog.getShownDialogs().last { it.isShowing }

    private fun views() = descendants(dialog().window!!.decorView)

    private fun label(text: String) =
        views().filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
            ?: error("Missing control: $text")

    private fun target(text: String): View {
        var item: View = label(text)
        while (!item.isClickable) item = item.parent as View
        return item
    }

    private fun click(text: String) {
        val item = target(text)
        assertTrue(item.isEnabled)
        item.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun back() {
        dialog().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun render(
        activity: Activity,
        name: String,
        widthDp: Int? = null,
        heightDp: Int = 720,
    ) {
        shadowOf(Looper.getMainLooper()).idle()
        val density = activity.resources.displayMetrics.density
        val width =
            widthDp?.let { (it * density).toInt() }
                ?: activity.resources.displayMetrics.widthPixels - (24 * density).toInt()
        val maxHeight = (heightDp * density).toInt()
        val decor = dialog().window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        assertTrue(decor.measuredHeight <= maxHeight)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val directory =
            File(System.getProperty("java.io.tmpdir"), "stremiobridge-ui-v5").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        // These are the actual action Views, including clickable rows, not their decorative
        // children.
        for (view in
            views().filter {
                it.isShown && it.isClickable && (it is Button || it is Switch || it is LinearLayout)
            }) {
            assertTrue(
                "Small target: ${view.contentDescription ?: (view as? TextView)?.text}",
                view.height >= (48 * density).toInt(),
            )
            assertTrue(view.width >= (48 * density).toInt())
            if (view is Button) assertEquals(1f, view.alpha)
        }
        for (view in views().filterIsInstance<CheckedTextView>()) {
            assertTrue("Catalog row too short", view.height >= (64 * density).toInt())
            val lastLine = view.layout.lineCount - 1
            assertTrue(
                "Catalog text is clipped",
                view.layout.getLineBottom(lastLine) +
                    view.compoundPaddingTop +
                    view.compoundPaddingBottom <= view.height,
            )
        }
    }

    private fun visible(text: String) {
        val view = target(text)
        val rect = Rect()
        assertTrue("Not visible: $text", view.getGlobalVisibleRect(rect))
        assertEquals("Clipped action: $text", view.height, rect.height())
    }

    private fun withSettings(
        profiles: List<Profile>,
        light: Boolean = false,
        body: (Activity, ProfileStore) -> Unit,
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
        store.all().forEach { store.replace(it, null) }
        profiles.forEach { store.replace(null, it) }
        Settings(activity, store) { old, next -> store.replace(old, next) }.manager()
        try {
            body(activity, store)
        } finally {
            dialog().dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun settingsScreensKeepControlsSelectionsAndDraftsInBothThemes() {
        for (light in listOf(false, true)) {
            val p = profile()
            val mode = if (light) "light" else "dark"
            val others =
                listOf(
                    p.copy(key = freshId(), name = "Family · Films and evening collections"),
                    p.copy(key = freshId(), name = "Weekend"),
                )
            withSettings(listOf(p) + others, light) { activity, store ->
                val unchanged = store.all().map { profileJson(it).toString() }
                render(activity, "profiles-$mode")
                visible("New profile")
                visible("Restore encrypted backup")
                click("Cinema")
                render(activity, "overview-$mode")
                assertFalse(target("Save changes").isEnabled)
                for (control in
                    listOf(
                        "Addons",
                        "Home rows",
                        "Metadata provider",
                        "Backup & profile",
                    )) assertTrue(target(control).isFocusable)
                click("Metadata provider")
                render(activity, "metadata-$mode")
                assertEquals(1, views().filterIsInstance<RadioButton>().count { it.isChecked })
                assertTrue(target("Follow addon order").createAccessibilityNodeInfo().isChecked)
                click(longName)
                render(activity, "metadata-selected-$mode")
                assertTrue(target(longName).createAccessibilityNodeInfo().isChecked)
                back()
                click("Addons")
                render(activity, "addons-$mode")
                assertTrue(
                    views().filterIsInstance<TextView>().any {
                        it.text.toString().contains("Disabled")
                    }
                )
                click(longName)
                render(activity, "addon-top-$mode")
                assertEquals(
                    setOf(
                        "Enabled",
                        "Browse & search",
                        "Details & episodes",
                        "Playback",
                        "Subtitles",
                        "Use bridge formatter",
                    ),
                    views().filterIsInstance<Switch>().map { it.text.toString() }.toSet(),
                )
                for (control in
                    listOf(
                        "Refresh manifest",
                        "Open addon settings",
                        "Edit install link",
                        "Remove addon",
                    )) label(control)
                val scroll = views().filterIsInstance<ScrollView>().single()
                assertTrue(scroll.canScrollVertically(1))
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                render(activity, "addon-bottom-$mode")
                visible("Remove addon")
                back()
                back()
                click("Home rows")
                render(activity, "rows-$mode")
                click("Weekend favorites")
                render(activity, "row-actions-$mode")
                for (control in
                    listOf(
                        "Rename row",
                        "Edit filters",
                        "Add filtered copy",
                        "Remove from Home",
                    )) label(control)
                assertFalse(
                    views().filterIsInstance<TextView>().any { it.text.toString() == "Move up" }
                )
                click("Edit filters")
                click("genre")
                render(activity, "filter-choice-$mode")
                assertEquals(1, views().filterIsInstance<RadioButton>().count { it.isChecked })
                back()
                back()
                back()
                back()
                click("Backup & profile")
                render(activity, "backup-$mode")
                click("Delete profile")
                render(activity, "delete-dialog-$mode")
                click("Cancel")
                back()
                back()
                click("Discard")
                assertEquals(unchanged, store.all().map { profileJson(it).toString() })
            }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun narrowFormsKeepValidationVisibilityAndKeyboardResize() {
        for (light in listOf(false, true)) withSettings(emptyList(), light) { activity, store ->
            val mode = if (light) "light" else "dark"
            render(activity, "empty-$mode", heightDp = 520)
            click("New profile")
            render(activity, "name-form-$mode", heightDp = 520)
            click("Create profile")
            val name = views().filterIsInstance<EditText>().single()
            assertEquals("Enter a value.", name.error.toString())
            render(activity, "validation-$mode", heightDp = 520)
            name.setText("New profile")
            click("Create profile")
            click("Home rows")
            click("Add catalogs")
            render(activity, "empty-picker-$mode", heightDp = 520)
            assertFalse(target("Add selected").isEnabled)
            back()
            back()
            click("Addons")
            click("Add addons")
            val field = views().filterIsInstance<EditText>().single()
            val text =
                "https://example.invalid/very-long-configured-install-link-that-wraps/manifest.json\nhttps://example.invalid/second-addon/manifest.json"
            field.setText(text)
            field.requestFocus()
            val input =
                activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            assertTrue(shadowOf(input).isSoftInputVisible)
            assertEquals(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                dialog().window!!.attributes.softInputMode and
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
            )
            assertTrue(
                dialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
            render(activity, "links-keyboard-$mode", heightDp = 280)
            visible("Discover addons")
            val scroll = views().filterIsInstance<ScrollView>().single()
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            render(activity, "links-keyboard-bottom-$mode", heightDp = 280)
            visible("Show links")
            visible("Discover addons")
            assertNotNull(field.transformationMethod)
            click("Show links")
            assertNull(field.transformationMethod)
            assertEquals(text, field.text.toString())
            render(activity, "links-visible-$mode", heightDp = 520)
            input.hideSoftInputFromWindow(field.windowToken, 0)
            back()
            back()
            back()
            click("Discard")
            assertTrue(store.all().isEmpty())
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land-xhdpi")
    fun landscapePickerPreservesBatchSelectionAcrossSearchAndFocus() {
        val p = profile()
        withSettings(listOf(p)) { activity, store ->
            val before = profileJson(store.all().single()).toString()
            click("Cinema")
            click("Addons")
            render(activity, "addons-landscape", widthDp = 560, heightDp = 312)
            visible("Add addons")
            visible("Edit order")
            click("Edit order")
            render(activity, "addons-order-landscape", widthDp = 560, heightDp = 312)
            visible("Done ordering")
            val viewRoot =
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                    dialog().window!!.decorView,
                    "getViewRootImpl",
                )
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(
                viewRoot,
                "ensureTouchModeLocally",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    Boolean::class.javaPrimitiveType!!,
                    false,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()
            val arrow = views().single { it.contentDescription == "Move AIOMetadata up" }
            assertTrue(
                "Arrow is not focusable; touch mode=${arrow.isInTouchMode}, shown=${arrow.isShown}, enabled=${arrow.isEnabled}, focusable=${arrow.isFocusable}",
                arrow.requestFocus(),
            )
            arrow.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                views().single { it.contentDescription == "Move AIOMetadata down" }.isFocused
            )
            back()
            click("Home rows")
            click("Add catalogs")
            render(activity, "picker-landscape", widthDp = 560, heightDp = 312)
            visible("Add selected")
            val search = views().filterIsInstance<EditText>().single()
            val list = views().filterIsInstance<ListView>().single()
            list.performItemClick(null, 1, 1)
            search.setText("Collection 3")
            render(activity, "picker-filtered-landscape", widthDp = 560, heightDp = 312)
            assertEquals(1, list.count)
            list.performItemClick(null, 0, 0)
            search.setText("")
            render(activity, "picker-selected-landscape", widthDp = 560, heightDp = 312)
            assertTrue(list.isItemChecked(1))
            assertTrue(list.isItemChecked(2))
            val add = target("Add 2 rows")
            assertTrue(add.requestFocusFromTouch())
            assertTrue(add.isFocused)
            render(activity, "picker-focus-landscape", widthDp = 560, heightDp = 312)
            visible("Add 2 rows")
            click("Add 2 rows")
            back()
            back()
            click("Discard")
            assertEquals(before, profileJson(store.all().single()).toString())
        }
    }

    @Test
    fun paletteMaintainsTextAndControlContrastForHostThemesAndAccents() {
        // Colors extracted from the supported CloudStream APK, plus Android theme extremes.
        val themes =
            listOf(
                0xff2b2c30.toInt() to 0xffe9eaee.toInt(),
                0xfff1f1f1.toInt() to 0xff202125.toInt(),
                Color.BLACK to Color.WHITE,
                Color.WHITE to Color.BLACK,
            )
        val accents =
            listOf(
                0xff3b65f5.toInt(),
                0xffd50000.toInt(),
                0xffe4d448.toInt(),
                Color.WHITE,
                Color.BLACK,
                Color.GRAY,
            )
        for ((surface, text) in themes) for (accent in accents) {
            val palette = SettingsPalette(surface, text, accent, 0xffba3630.toInt())
            for (background in
                listOf(
                    palette.surface,
                    palette.row,
                    palette.selected,
                    palette.pressed,
                )) for (foreground in
                listOf(palette.ink, palette.muted, palette.accentInk, palette.errorInk)) assertTrue(
                "Insufficient text contrast",
                SettingsPalette.contrast(foreground, background) >= 4.5,
            )
            assertTrue(SettingsPalette.contrast(palette.onAccent, accent) >= 4.5)
            assertTrue(SettingsPalette.contrast(palette.onAccent, palette.accentPressed) >= 4.5)
            assertTrue(SettingsPalette.contrast(palette.outline, palette.row) >= 3.0)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun amoledPreferencePersistsAndLightThemeRemainsLight() {
        withSettings(listOf(profile())) { activity, store ->
            val before = store.all().map { profileJson(it).toString() }
            if (!activity.getSharedPreferences("stremiobridge-ui", 0).getBoolean("amoled", false))
                click("AMOLED black")
            render(activity, "amoled-profiles", heightDp = 520)
            val check = SettingsView(activity) {}
            assertEquals(Color.BLACK, check.palette.surface)
            click("Cinema")
            click("Home rows")
            click("Edit order")
            render(activity, "amoled-order-narrow", heightDp = 520)
            visible("Done ordering")
            assertEquals(before, store.all().map { profileJson(it).toString() })
        }
        withSettings(emptyList(), light = true) { activity, _ ->
            // The preference persists through the host-theme change.
            assertTrue(
                activity.getSharedPreferences("stremiobridge-ui", 0).getBoolean("amoled", false)
            )
            assertNotEquals(Color.BLACK, SettingsView(activity) {}.palette.surface)
            render(activity, "amoled-light", heightDp = 520)
            click("AMOLED black")
            assertFalse(
                activity.getSharedPreferences("stremiobridge-ui", 0).getBoolean("amoled", true)
            )
        }
    }

    @Test
    fun existingLoadingErrorAndConfirmationStatesRenderWithoutChangingActions() {
        for (light in listOf(false, true)) {
            val controller = Robolectric.buildActivity(Activity::class.java)
            controller
                .get()
                .setTheme(
                    if (light) android.R.style.Theme_Material_Light_NoActionBar
                    else android.R.style.Theme_Material_NoActionBar
                )
            controller.setup()
            val activity = controller.get()
            val ui = SettingsView(activity) {}
            ui.page("Settings")
            var canceled = false
            val progress =
                ui.alert(
                    AlertDialog.Builder(activity)
                        .setView(ui.loading("Refreshing manifests…"))
                        .setNegativeButton("Cancel") { _, _ -> canceled = true }
                )
            render(activity, "progress-${if (light) "light" else "dark"}")
            click("Cancel")
            assertTrue(canceled)
            assertFalse(progress.isShowing)
            val error =
                ui.alert(
                    AlertDialog.Builder(activity)
                        .setTitle("Could not finish")
                        .setMessage("Addon connection failed or timed out.")
                        .setPositiveButton("OK", null),
                    error = true,
                )
            render(activity, "error-${if (light) "light" else "dark"}")
            click("OK")
            assertFalse(error.isShowing)
            ui.dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun optionalFormatterPreviewAndOffRowsRemainUsableInBothThemesAndWithKeyboard() {
        for (light in listOf(false, true)) withSettings(listOf(profile()), light) { activity, store
            ->
            val before = profileJson(store.all().single()).toString()
            val mode = if (light) "light" else "dark"
            click("Cinema")
            click("Addons")
            click(longName)
            click("Use bridge formatter")
            click("Edit bridge formatter")
            render(activity, "formatter-top-$mode", heightDp = 520)
            visible("Apply formatter")
            val scroll = views().filterIsInstance<ScrollView>().single()
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            render(activity, "formatter-preview-$mode", heightDp = 520)
            assertTrue(
                views().filterIsInstance<TextView>().any {
                    it.text.toString().contains("▣ 4K ⚡ Cached")
                }
            )
            visible("Apply formatter")
            val field = views().filterIsInstance<EditText>().first()
            field.requestFocus()
            val input =
                activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            assertTrue(shadowOf(input).isSoftInputVisible)
            render(activity, "formatter-keyboard-$mode", heightDp = 280)
            visible("Apply formatter")
            input.hideSoftInputFromWindow(field.windowToken, 0)
            back()
            back()
            back()
            click("Home rows")
            val off =
                views().filterIsInstance<Switch>().single {
                    it.contentDescription == "Show Weekend favorites on Home"
                }
            off.performClick()
            render(activity, "row-off-$mode", heightDp = 520)
            assertFalse(off.isChecked)
            click("Edit order")
            render(activity, "row-order-$mode", heightDp = 520)
            visible("Done ordering")
            back()
            back()
            click("Discard")
            assertEquals(before, profileJson(store.all().single()).toString())
        }
    }
}
