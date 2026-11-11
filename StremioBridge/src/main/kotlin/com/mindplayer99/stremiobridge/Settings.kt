package com.mindplayer99.stremiobridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import java.net.URI
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.*

internal class Settings(
    private val activity: Activity,
    private val store: ProfileStore,
    private val commit: (Profile?, Profile?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ui = SettingsView(activity) { scope.cancel() }
    private var original: Profile? = null
    private var draft = Profile()
    private val errors = mutableMapOf<String, String>()
    private val roleNames =
        mapOf(
            "catalog" to "Browse & search",
            "meta" to "Details & episodes",
            "stream" to "Playback",
            "subtitles" to "Subtitles",
        )

    private fun alive() = !activity.isFinishing && !activity.isDestroyed && ui.dialog.isShowing

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun dirty() = original?.let(::profileJson)?.toString() != profileJson(draft).toString()

    private fun host(addon: Addon) = runCatching { URI(addon.url).host }.getOrNull().orEmpty()

    private fun name(addon: Addon) = addon.manifest?.name ?: "Unverified addon"

    private fun summary(addon: Addon): String =
        if (!addon.enabled) "Disabled"
        else
            addon.manifest
                ?.capabilities
                .orEmpty()
                .filter { it in addon.switches }
                .joinToString(" · ") { roleNames.getValue(it) }
                .ifBlank { "No roles enabled" }

    private fun replaceAddon(key: String, value: Addon) {
        draft = draft.copy(addons = draft.addons.map { if (it.key == key) value else it })
    }

    private fun <T> moved(items: List<T>, index: Int, target: Int) =
        items.toMutableList().apply {
            val item = removeAt(index)
            add(target.coerceIn(0, size), item)
        }

    private fun <T> work(
        title: String,
        back: () -> Unit,
        task: suspend () -> T,
        done: (T) -> Unit,
    ) {
        var job: Job? = null
        val progress =
            ui.alert(
                AlertDialog.Builder(activity)
                    .setView(ui.loading(title))
                    .setNegativeButton("Cancel") { _, _ ->
                        job?.cancel()
                        if (alive()) back()
                    }
                    .setOnCancelListener {
                        job?.cancel()
                        if (alive()) back()
                    }
            )
        job =
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { task() }
                    ensureActive()
                    if (alive()) {
                        progress.dismiss()
                        done(result)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NeedsConfiguration) {
                    if (alive()) {
                        progress.dismiss()
                        ui.alert(
                            AlertDialog.Builder(activity)
                                .setTitle("Finish addon setup")
                                .setMessage(
                                    "Configure the addon in your browser, then paste its final install link."
                                )
                                .setPositiveButton("Open setup") { _, _ ->
                                    openBrowser(e.page)
                                    back()
                                }
                                .setNegativeButton("Back") { _, _ -> back() }
                                .setOnCancelListener { back() }
                        )
                    }
                } catch (e: Exception) {
                    if (alive()) {
                        progress.dismiss()
                        back()
                        ui.alert(
                            AlertDialog.Builder(activity)
                                .setTitle("Could not finish")
                                .setMessage(
                                    (e as? BridgeFailure)?.message
                                        ?: "Check the input and try again."
                                )
                                .setPositiveButton("OK", null),
                            error = true,
                        )
                    }
                } finally {
                    progress.dismiss()
                }
            }
    }

    private fun input(
        title: String,
        value: String = "",
        secret: Boolean = false,
        multiline: Boolean = false,
        action: String,
        back: () -> Unit,
        done: (String) -> Unit,
    ) {
        val body = ui.page(title, back, secure = secret)
        val field =
            EditText(activity).apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                setSingleLine(!multiline)
                if (multiline) {
                    minLines = 3
                    maxLines = 7
                }
                if (Build.VERSION.SDK_INT >= 26)
                    importantForAutofill =
                        android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
                setText(value)
                setSelection(length())
            }
        ui.styleField(field)
        body.addView(field, LinearLayout.LayoutParams(-1, -2))
        if (secret)
            ui.toggle(
                body,
                if (title.contains("passphrase", true)) "Show passphrase" else "Show links",
                false,
            ) {
                field.transformationMethod =
                    if (it) null else PasswordTransformationMethod.getInstance()
                field.setSelection(field.length())
            }
        if (multiline && title == "Add addons")
            ui.note(body, "Paste configured manifest URLs, one per line.")
        ui.button(ui.footer, action, primary = true) {
            val text = field.text.toString()
            if (text.isBlank()) field.error = "Enter a value."
            else done(if (title.contains("passphrase", true)) text else text.trim())
        }
    }

    fun manager() {
        val body = ui.page("StremioBridge")
        val profiles =
            try {
                store.all()
            } catch (_: Exception) {
                ui.note(body, "Saved profiles could not be read.")
                return
            }
        if (profiles.isEmpty())
            ui.note(body, "Create a profile to combine your catalog, metadata and playback addons.")
        for (profile in profiles) ui.card(
            body,
            profile.name,
            profile.addons.count { it.enabled }.toString() +
                " enabled addons · " +
                profile.rows.size +
                " Home rows",
        ) {
            original = profile
            draft = profile
            errors.clear()
            editor()
        }
        ui.button(ui.footer, "New profile", primary = true) {
            input("Profile name", action = "Create profile", back = ::manager) { value ->
                original = null
                draft = Profile(name = value.take(64))
                errors.clear()
                editor()
            }
        }
        ui.button(ui.footer, "Restore encrypted backup") { restore() }
    }

    private fun editor(message: String? = null) {
        val body = ui.page(draft.name, ::leaveEditor)
        if (message != null) ui.note(body, message)
        ui.card(
            body,
            "Addons",
            draft.addons.size.toString() +
                " installed · " +
                draft.addons.count { it.enabled } +
                " enabled",
            ::addons,
        )
        ui.card(body, "Home rows", draft.rows.size.toString() + " selected", ::rows)
        val preferred =
            draft.addons.find {
                it.key == draft.preferred && it.uses("meta") && "meta" in it.manifest!!.capabilities
            }
        ui.card(
            body,
            "Metadata provider",
            preferred?.let(::name) ?: "Follow addon order",
            ::metadata,
        )
        ui.card(body, "Backup & profile", "Encrypted backup, duplicate or delete", ::advanced)
        ui.button(
            ui.footer,
            "Save changes",
            primary = true,
            enabled = dirty() && draft.addons.isNotEmpty(),
        ) {
            save()
        }
        if (dirty()) ui.note(body, "Unsaved changes")
    }

    private fun leaveEditor() {
        if (!dirty()) {
            manager()
            return
        }
        ui.alert(
            AlertDialog.Builder(activity)
                .setTitle("Save your changes?")
                .setPositiveButton("Save") { _, _ -> save() }
                .setNegativeButton("Discard") { _, _ -> manager() }
                .setNeutralButton("Keep editing") { _, _ -> editor() }
        )
    }

    private fun save() {
        if (draft.addons.isEmpty()) {
            editor("Add at least one addon before saving.")
            return
        }
        work("Validating changed addons…", { editor() }, { refresh(draft, Http, false) }) { checked
            ->
            commit(original, checked)
            original = checked
            draft = checked
            toast("Profile saved")
            editor()
        }
    }

    private fun addons(message: String? = null) {
        val body = ui.page("Addons", { editor() })
        if (message != null) ui.note(body, message)
        if (draft.addons.isEmpty())
            ui.note(body, "Add a configured install link from your addon’s setup page.")
        for (addon in draft.addons) {
            val detail =
                listOf(host(addon), summary(addon), errors[addon.key])
                    .filterNotNull()
                    .filter(String::isNotBlank)
                    .joinToString("\n")
            ui.card(body, name(addon), detail) { addon(addon.key) }
        }
        ui.button(ui.footer, "Add addons", primary = true) { addLinks() }
        if (draft.addons.isNotEmpty())
            ui.button(ui.footer, "Refresh enabled manifests") {
                refreshManifests(draft.addons.filter { it.enabled }.map { it.key }.toSet()) {
                    addons(it)
                }
            }
    }

    private fun addLinks(value: String = "") {
        input(
            "Add addons",
            value,
            secret = true,
            multiline = true,
            action = "Discover addons",
            back = { addons() },
        ) { raw ->
            val added =
                try {
                    raw.lines()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .map { Endpoint(it).manifest }
                        .distinct()
                        .filter { link -> draft.addons.none { it.url == link } }
                        .map { Addon(freshId(), it) }
                } catch (e: BridgeFailure) {
                    toast(e.message.orEmpty())
                    addLinks(raw)
                    return@input
                }
            if (added.isEmpty()) {
                addons("Those install links are already in this profile.")
                return@input
            }
            work(
                "Discovering addons…",
                { addLinks(raw) },
                { refresh(draft.copy(addons = draft.addons + added), Http, false) },
            ) {
                draft = it
                addons("Added " + added.size + " addons. Choose visible catalogs under Home rows.")
            }
        }
    }

    private fun refreshManifests(keys: Set<String>, done: (String) -> Unit) {
        if (keys.isEmpty()) {
            done("Enable an addon to refresh its manifest.")
            return
        }
        work("Refreshing manifests…", { addons() }, { refreshSelected(draft, Http, keys) }) {
            draft = it.profile
            keys.forEach(errors::remove)
            errors.putAll(it.failures)
            done(
                if (it.failures.isEmpty()) "Manifests refreshed. Save changes to keep them."
                else
                    (keys.size - it.failures.size).toString() +
                        " refreshed · " +
                        it.failures.size +
                        " failed. Previous working manifests retained."
            )
        }
    }

    private fun addon(key: String) {
        val item = draft.addons.find { it.key == key } ?: return addons()
        val body = ui.page(name(item), { addons() })
        ui.note(body, host(item))
        ui.toggle(body, "Enabled", item.enabled) {
            replaceAddon(key, draft.addons.first { a -> a.key == key }.copy(enabled = it))
            addon(key)
        }
        ui.section(body, "Use this addon for")
        val available = item.manifest?.capabilities.orEmpty()
        if (available.isEmpty()) ui.note(body, "Refresh the manifest to discover supported roles.")
        for (role in available) ui.toggle(body, roleNames.getValue(role), role in item.switches) {
            on ->
            val current = draft.addons.first { it.key == key }
            replaceAddon(
                key,
                current.copy(
                    switches = if (on) current.switches + role else current.switches - role
                ),
            )
        }
        val checked =
            item.checked
                .takeIf { it > 0 }
                ?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"
        ui.divider(body)
        ui.note(body, "Manifest last fetched\n" + checked)
        errors[key]?.let { ui.error(body, it) }
        ui.button(body, "Refresh manifest") { refreshManifests(setOf(key)) { addon(key) } }
        if (item.manifest?.configurable == true) {
            ui.button(body, "Open addon settings") { openBrowser(Endpoint(item.url).configure()) }
            ui.note(
                body,
                "If setup gives you a new install link, paste it under Edit install link.",
            )
        }
        ui.button(body, "Edit install link") {
            input(
                "Install link",
                item.url,
                secret = true,
                action = "Validate link",
                back = { addon(key) },
            ) { raw ->
                val endpoint =
                    try {
                        Endpoint(raw)
                    } catch (e: BridgeFailure) {
                        toast(e.message.orEmpty())
                        addon(key)
                        return@input
                    }
                if (draft.addons.any { it.key != key && it.url == endpoint.manifest }) {
                    toast("That configuration is already installed.")
                    addon(key)
                    return@input
                }
                work(
                    "Validating install link…",
                    { addon(key) },
                    { Manifest.discover(Http.get(endpoint.manifest, false), endpoint) },
                ) { manifest ->
                    val current = draft.addons.first { it.key == key }
                    replaceAddon(
                        key,
                        current.copy(
                            url = endpoint.manifest,
                            manifest = manifest,
                            checked = System.currentTimeMillis(),
                        ),
                    )
                    errors.remove(key)
                    addon(key)
                }
            }
        }
        if (draft.addons.size > 1) ui.button(body, "Change addon order") { orderAddon(key) }
        ui.divider(body)
        ui.button(body, "Remove addon", destructive = true) {
            ui.alert(
                AlertDialog.Builder(activity)
                    .setMessage("Remove " + name(item) + " and its Home rows from this profile?")
                    .setPositiveButton("Remove") { _, _ ->
                        draft =
                            draft.copy(
                                addons = draft.addons.filterNot { it.key == key },
                                rows = draft.rows.filterNot { it.addon == key },
                                preferred = draft.preferred?.takeUnless { it == key },
                            )
                        errors.remove(key)
                        addons()
                    }
                    .setNegativeButton("Cancel", null),
                destructive = true,
            )
        }
    }

    private fun orderAddon(key: String) {
        val index = draft.addons.indexOfFirst { it.key == key }
        val body = ui.page("Addon order", { addon(key) })
        ui.note(
            body,
            "Used for metadata fallback and result collection. CloudStream applies its playback source priorities afterward.",
        )
        for ((label, target) in
            listOf(
                "Move up" to index - 1,
                "Move down" to index + 1,
                "Move to top" to 0,
                "Move to bottom" to draft.addons.lastIndex,
            )) ui.button(body, label, enabled = target in draft.addons.indices && target != index) {
            draft = draft.copy(addons = moved(draft.addons, index, target))
            addon(key)
        }
    }

    private fun metadata() {
        val body = ui.page("Metadata provider", { editor() })
        ui.note(
            body,
            "Provides descriptions, artwork and episodes. Compatible addons supply fallback when needed.",
        )
        ui.choice(body, "Follow addon order", selected = draft.preferred == null) {
            draft = draft.copy(preferred = null)
            metadata()
        }
        val eligible =
            draft.addons.filter { it.uses("meta") && "meta" in it.manifest!!.capabilities }
        if (eligible.isEmpty())
            ui.note(body, "Enable Details & episodes on an addon that provides metadata.")
        for (addon in eligible) ui.choice(
            body,
            name(addon),
            host(addon),
            selected = draft.preferred == addon.key,
        ) {
            draft = draft.copy(preferred = addon.key)
            metadata()
        }
    }

    private fun rowName(row: Row) =
        row.title.ifBlank { catalogForRow(draft, row)?.name ?: row.catalog }

    private fun rowSummary(row: Row): String {
        val addon = draft.addons.find { it.key == row.addon }
        val c = draft.catalog(row)
        val state =
            when {
                addon == null -> "Addon removed"
                !addon.enabled -> "Addon disabled"
                !addon.uses("catalog") -> "Browse & search disabled"
                c == null -> "Catalog unavailable after refresh"
                !c.accepts(c.firstArgs(row.args)) -> "Filters need attention"
                else -> null
            }
        return (listOfNotNull(addon?.let(::name), row.type, state) +
                row.args.map { it.key + " · " + it.value })
            .joinToString("\n")
    }

    private fun rows() {
        val body = ui.page("Home rows", { editor() })
        if (draft.rows.isEmpty()) ui.note(body, "Choose the catalogs you want on your Home screen.")
        for ((index, row) in draft.rows.withIndex()) ui.card(body, rowName(row), rowSummary(row)) {
            row(index)
        }
        ui.button(ui.footer, "Choose catalogs", primary = true) { picker() }
    }

    private fun row(index: Int) {
        val item = draft.rows.getOrNull(index) ?: return rows()
        val body = ui.page(rowName(item), ::rows)
        ui.note(body, rowSummary(item))
        ui.button(body, "Rename row") {
            input("Row title", rowName(item), action = "Apply title", back = { row(index) }) {
                draft = updateRow(draft, index, item.copy(title = it.take(100)))
                row(index)
            }
        }
        val catalog = catalogForRow(draft, item)
        val hasFilters = catalog?.extras?.any { it.name !in setOf("skip", "search") } == true
        if (catalog != null && (hasFilters || item.args.isNotEmpty())) {
            ui.button(body, "Edit filters") {
                filters(item, catalog, { row(index) }) { edited ->
                    try {
                        draft = updateRow(draft, index, edited)
                        row(index)
                    } catch (e: BridgeFailure) {
                        toast(e.message.orEmpty())
                        row(index)
                    }
                }
            }
            if (hasFilters)
                ui.button(body, "Add filtered copy") {
                    filters(item.copy(title = ""), catalog, { row(index) }) { edited ->
                        if (draft.rows.any { it.key == edited.key }) {
                            toast("Choose different filters for the new row.")
                            row(index)
                        } else {
                            draft = draft.copy(rows = draft.rows + edited)
                            rows()
                        }
                    }
                }
        }
        if (draft.rows.size > 1)
            for ((label, target) in
                listOf(
                    "Move up" to index - 1,
                    "Move down" to index + 1,
                    "Move to top" to 0,
                    "Move to bottom" to draft.rows.lastIndex,
                )) ui.button(
                body,
                label,
                enabled = target in draft.rows.indices && target != index,
            ) {
                draft = draft.copy(rows = moved(draft.rows, index, target))
                rows()
            }
        ui.divider(body)
        ui.button(body, "Remove from Home", destructive = true) {
            draft = draft.copy(rows = draft.rows.filterIndexed { i, _ -> i != index })
            rows()
        }
    }

    private fun picker() {
        val choices =
            draft.addons
                .filter { it.uses("catalog") }
                .flatMap { addon ->
                    addon.manifest!!
                        .catalogs
                        .filterNot { c -> c.extras.any { it.name == "search" && it.required } }
                        .map { addon to it }
                }
        val selected = linkedSetOf<String>()
        val body = ui.page("Choose catalogs", ::rows, scrolling = false)
        val filter =
            EditText(activity).apply {
                hint = "Find a catalog"
                setSingleLine()
            }
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        ui.styleField(filter)
        ui.styleList(list)
        body.addView(filter, LinearLayout.LayoutParams(-1, -2))
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        if (choices.isEmpty()) ui.note(body, "Enable Browse & search on a catalog addon first.")
        var visible = choices
        val add =
            ui.button(ui.footer, "Add selected", primary = true, enabled = false) {
                val rows =
                    choices
                        .filter { Row(it.first.key, it.second.type, it.second.id).key in selected }
                        .map { Row(it.first.key, it.second.type, it.second.id) }
                addRows(rows, 0, emptyList(), ::picker)
            }
        fun updateButton() {
            add.text = "Add " + selected.size + " rows"
            add.isEnabled = selected.isNotEmpty()
        }
        fun show(query: String) {
            visible =
                choices.filter { (a, c) ->
                    (c.name + " " + c.type + " " + name(a)).contains(query, true)
                }
            list.adapter =
                ui.catalogAdapter(
                    visible.map { (a, c) ->
                        val onHome =
                            draft.rows.any {
                                it.addon == a.key && it.type == c.type && it.catalog == c.id
                            }
                        c.name + "\n" + c.type + " · " + name(a) + if (onHome) " · On Home" else ""
                    }
                )
            visible.forEachIndexed { i, (a, c) ->
                list.setItemChecked(i, Row(a.key, c.type, c.id).key in selected)
            }
        }
        list.setOnItemClickListener { _, _, i, _ ->
            val (addon, catalog) = visible[i]
            val key = Row(addon.key, catalog.type, catalog.id).key
            if (list.isItemChecked(i)) selected.add(key) else selected.remove(key)
            updateButton()
        }
        filter.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun afterTextChanged(s: Editable?) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    show(s.toString())
                }
            }
        )
        show("")
    }

    private fun addRows(chosen: List<Row>, index: Int, prepared: List<Row>, cancel: () -> Unit) {
        if (index == chosen.size) {
            val merged = (draft.rows + prepared).distinctBy { it.key }
            val added = merged.size - draft.rows.size
            draft = draft.copy(rows = merged)
            toast(
                if (added == 0) "Selected rows are already on Home"
                else "Added " + added + " Home rows"
            )
            rows()
            return
        }
        val item = chosen[index]
        val catalog =
            catalogForRow(draft, item) ?: return addRows(chosen, index + 1, prepared, cancel)
        if (catalog.accepts(catalog.firstArgs(item.args)))
            addRows(chosen, index + 1, prepared + item, cancel)
        else filters(item, catalog, cancel) { addRows(chosen, index + 1, prepared + it, cancel) }
    }

    private fun filters(item: Row, catalog: Catalog, back: () -> Unit, done: (Row) -> Unit) {
        val body = ui.page("Filters · " + catalog.name, back)
        val current =
            item.copy(
                args =
                    item.args.filter { (key, value) ->
                        catalog.extras.any {
                            it.name == key && (it.options.isEmpty() || value in it.options)
                        }
                    }
            )
        if (current.args != item.args)
            ui.note(body, "Unavailable filter values were cleared. Review and apply your choices.")
        for (extra in catalog.extras.filterNot { it.name in setOf("search", "skip") }) {
            val value = current.args[extra.name]
            ui.card(
                body,
                extra.name,
                value ?: if (extra.required) "Required · choose a value" else "Any",
            ) {
                chooseFilter(current, catalog, extra, back, done)
            }
        }
        ui.button(ui.footer, "Apply filters", primary = true) {
            if (!catalog.accepts(catalog.firstArgs(current.args)))
                toast("Choose a valid value for every required filter.")
            else done(current)
        }
    }

    private fun chooseFilter(
        item: Row,
        catalog: Catalog,
        extra: Extra,
        back: () -> Unit,
        done: (Row) -> Unit,
    ) {
        val returnTo = { filters(item, catalog, back, done) }
        fun apply(value: String?) {
            val args = item.args.toMutableMap()
            if (value == null) args.remove(extra.name) else args[extra.name] = value
            filters(item.copy(args = args), catalog, back, done)
        }
        val body = ui.page(extra.name, returnTo)
        if (!extra.required)
            ui.choice(body, "Any", selected = extra.name !in item.args) { apply(null) }
        if (extra.options.isEmpty()) {
            ui.button(body, "Enter value") {
                input(
                    extra.name,
                    item.args[extra.name].orEmpty(),
                    action = "Use value",
                    back = returnTo,
                ) {
                    apply(it)
                }
            }
        } else
            for (option in extra.options) ui.choice(
                body,
                option,
                selected = item.args[extra.name] == option,
            ) {
                apply(option)
            }
    }

    private fun advanced() {
        val body = ui.page("Backup & profile", { editor() })
        ui.card(body, "Duplicate profile", "Try a different setup in a separate profile") {
            input(
                "New profile name",
                draft.name + " copy",
                action = "Create copy",
                back = ::advanced,
            ) {
                original = null
                draft = draft.copy(key = freshId(), name = it.take(64))
                editor()
            }
        }
        ui.card(body, "Export encrypted backup", "Includes addon links, roles and Home rows") {
            export()
        }
        ui.divider(body)
        ui.danger(body, "Delete profile", "Removes its setup and saved item references") {
            ui.alert(
                AlertDialog.Builder(activity)
                    .setMessage("Delete " + draft.name + " and its bridge references?")
                    .setPositiveButton("Delete") { _, _ ->
                        try {
                            original?.let { commit(it, null) }
                            manager()
                        } catch (e: Exception) {
                            toast((e as? BridgeFailure)?.message ?: "Could not delete profile.")
                        }
                    }
                    .setNegativeButton("Cancel", null),
                destructive = true,
            )
        }
    }

    private fun export() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Encrypted backups require Android 8 or newer.")
            return
        }
        input("Backup passphrase", secret = true, action = "Encrypt backup", back = ::advanced) {
            pass ->
            if (pass.length < 12) {
                toast("Use a passphrase with at least 12 characters.")
                export()
                return@input
            }
            work("Encrypting backup…", ::advanced, { Backup.seal(draft, pass.toCharArray()) }) {
                encrypted ->
                val body = ui.page("Encrypted backup", ::advanced)
                ui.note(body, "Save this encrypted text and keep the passphrase separately.")
                val text =
                    EditText(activity).apply {
                        setText(encrypted)
                        minLines = 3
                        maxLines = 6
                        keyListener = null
                        setTextIsSelectable(true)
                    }
                ui.styleField(text)
                body.addView(text, LinearLayout.LayoutParams(-1, -2))
                ui.button(ui.footer, "Copy encrypted backup", primary = true) {
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(
                            ClipData.newPlainText("Encrypted StremioBridge profile", encrypted)
                        )
                    toast("Encrypted backup copied")
                }
            }
        }
    }

    private fun restore() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Encrypted backups require Android 8 or newer.")
            return
        }
        input(
            "Restore encrypted backup",
            multiline = true,
            action = "Enter passphrase",
            back = ::manager,
        ) { text ->
            input(
                "Backup passphrase",
                secret = true,
                action = "Restore profile",
                back = ::manager,
            ) { pass ->
                work(
                    "Decrypting backup…",
                    ::manager,
                    {
                        try {
                            Backup.open(text, pass.toCharArray(), "Restored profile")
                        } catch (e: Exception) {
                            throw BridgeFailure(
                                "Could not decrypt the backup. Check its passphrase and complete backup text."
                            )
                        }
                    },
                ) { restored ->
                    input(
                        "Restored profile name",
                        restored.name,
                        action = "Review profile",
                        back = ::manager,
                    ) {
                        original = null
                        draft = restored.copy(name = it.take(64))
                        errors.clear()
                        editor(
                            "Refresh addon manifests to discover catalogs, then save this profile."
                        )
                    }
                }
            }
        }
    }

    private fun openBrowser(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("No browser could open this addon’s settings.")
        }
    }
}
