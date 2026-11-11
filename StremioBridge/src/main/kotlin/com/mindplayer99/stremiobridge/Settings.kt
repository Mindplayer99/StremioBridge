package com.mindplayer99.stremiobridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.*

internal class Settings(
    private val a: Activity,
    private val store: ProfileStore,
    private val commit: (Profile?, Profile?) -> Unit,
) {
    private var original: Profile? = null
    private var draft = Profile()

    private fun toast(message: String) = Toast.makeText(a, message, Toast.LENGTH_LONG).show()

    private fun menu(
        title: String,
        labels: List<String>,
        back: (() -> Unit)? = null,
        select: (Int) -> Unit,
    ) {
        val b =
            AlertDialog.Builder(a).setTitle(title).setItems(labels.toTypedArray()) { _, i ->
                select(i)
            }
        if (back != null)
            b.setNegativeButton("Back") { _, _ -> back() }.setOnCancelListener { back() }
        b.show()
    }

    private fun input(
        title: String,
        value: String = "",
        secret: Boolean = false,
        multi: Boolean = false,
        back: () -> Unit,
        done: (String) -> Unit,
    ) {
        val field =
            EditText(a).apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0) or
                        (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                setSingleLine(!multi)
                if (multi) minLines = 3
                setText(value)
                setSelection(length())
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    importantForAutofill =
                        android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        val dialog =
            AlertDialog.Builder(a)
                .setTitle(title)
                .setView(field)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Back") { _, _ -> back() }
                .setOnCancelListener { back() }
                .create()
        if (secret) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val s = field.text.toString().trim()
            if (s.isBlank()) field.error = "Enter a value."
            else {
                dialog.dismiss()
                done(s)
            }
        }
    }

    private fun <T> work(
        title: String,
        back: () -> Unit,
        task: suspend () -> T,
        done: (T) -> Unit,
    ) {
        val job = SupervisorJob()
        val dialog =
            AlertDialog.Builder(a)
                .setMessage(title)
                .setNegativeButton("Cancel") { _, _ ->
                    job.cancel()
                    back()
                }
                .setOnCancelListener {
                    job.cancel()
                    back()
                }
                .show()
        CoroutineScope(Dispatchers.Main + job).launch {
            try {
                val result = withContext(Dispatchers.IO) { task() }
                if (!a.isFinishing && !a.isDestroyed) {
                    dialog.dismiss()
                    done(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsConfiguration) {
                if (!a.isFinishing && !a.isDestroyed) {
                    dialog.dismiss()
                    AlertDialog.Builder(a)
                        .setTitle("Addon setup required")
                        .setMessage(
                            "Finish setup in the browser, then paste the configured install link."
                        )
                        .setPositiveButton("Open setup") { _, _ ->
                            runCatching {
                                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.page)))
                            }
                            back()
                        }
                        .setNegativeButton("Back") { _, _ -> back() }
                        .setOnCancelListener { back() }
                        .show()
                }
            } catch (e: Exception) {
                if (!a.isFinishing && !a.isDestroyed) {
                    dialog.dismiss()
                    toast(
                        (e as? BridgeFailure)?.message
                            ?: "Operation failed. Check the input and try again."
                    )
                    back()
                }
            } finally {
                job.complete()
            }
        }
    }

    fun manager() {
        val profiles =
            try {
                store.all()
            } catch (_: Exception) {
                toast("Saved profiles could not be read.")
                return
            }
        menu(
            "StremioBridge profiles",
            profiles.map { it.name } +
                listOf("Create profile", "Import safe template", "Restore encrypted profile"),
        ) { n ->
            if (n < profiles.size) {
                original = profiles[n]
                draft = profiles[n]
                editor()
            } else
                input("Profile name", back = ::manager) { name ->
                    original = null
                    when (n - profiles.size) {
                        0 -> {
                            draft = Profile(name = name.take(64))
                            addLinks()
                        }
                        1 ->
                            input("Paste template", multi = true, back = ::manager) { raw ->
                                try {
                                    draft = importTemplate(raw, name.take(64))
                                    editor()
                                } catch (_: Exception) {
                                    toast("Invalid profile template.")
                                    manager()
                                }
                            }
                        else -> restore(name.take(64))
                    }
                }
        }
    }

    private fun editor() {
        menu(
            draft.name,
            listOf(
                "Add manifest links",
                "Manage addons (" + draft.addons.size + ")",
                "Home rows (" + draft.rows.size + ")",
                "Preferred metadata addon",
                "Save + refresh",
                "Advanced",
            ),
            ::discard,
        ) { i ->
            when (i) {
                0 -> addLinks()
                1 -> addons()
                2 -> rows()
                3 -> preferred()
                4 ->
                    if (
                        draft.addons.isEmpty() ||
                            draft.addons.any { it.enabled && it.url.isBlank() }
                    ) {
                        toast("Paste an install link for each enabled addon.")
                        editor()
                    } else
                        discover(draft, true) { updated ->
                            commit(original, updated)
                            toast("Profile saved.")
                            manager()
                        }
                5 -> advanced()
            }
        }
    }

    private fun discard() {
        if (original == draft || original == null && draft.addons.isEmpty()) {
            manager()
            return
        }
        AlertDialog.Builder(a)
            .setMessage("Discard these changes?")
            .setPositiveButton("Discard") { _, _ -> manager() }
            .setNegativeButton("Keep editing") { _, _ -> editor() }
            .setOnCancelListener { editor() }
            .show()
    }

    private fun discover(p: Profile, force: Boolean, done: (Profile) -> Unit) =
        work("Checking enabled addon manifests…", ::editor, { refresh(p, Http, force) }, done)

    private fun addLinks() =
        input("Paste manifest links, one per line", secret = true, multi = true, back = ::editor) {
            raw ->
            try {
                val added =
                    raw.lines()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .map { Endpoint(it).manifest }
                        .distinct()
                        .filter { url -> draft.addons.none { it.url == url } }
                        .map { Addon(freshId(), it) }
                if (added.isEmpty()) {
                    toast("These links are already in this profile.")
                    editor()
                } else {
                    draft = draft.copy(addons = draft.addons + added)
                    discover(draft, false) {
                        draft = it
                        rows()
                    }
                }
            } catch (e: BridgeFailure) {
                toast(e.message.orEmpty())
                editor()
            }
        }

    private fun label(item: Addon) =
        (draft.addons.indexOf(item) + 1).toString() +
            ". " +
            (item.manifest?.name ?: "Paste install link") +
            (if (item.enabled) "" else " (off)")

    private fun addons() =
        menu("Addon priority", draft.addons.map(::label) + "Add manifest links", ::editor) { i ->
            if (i == draft.addons.size) addLinks() else addon(i)
        }

    private fun replace(i: Int, item: Addon) {
        draft = draft.copy(addons = draft.addons.toMutableList().apply { set(i, item) })
    }

    private fun <T> move(items: List<T>, i: Int, to: Int) =
        items.toMutableList().apply { add(to.coerceIn(indices), removeAt(i)) }

    private fun addon(i: Int) {
        val item = draft.addons[i]
        val stamp =
            item.checked
                .takeIf { it > 0 }
                ?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"
        menu(
            label(item) + "\nLast checked: " + stamp,
            listOf(
                if (item.enabled) "Disable" else "Enable",
                "Resource switches",
                "Edit install link",
                "Move up",
                "Move down",
                "Check now",
                "Remove addon",
            ),
            ::addons,
        ) { n ->
            when (n) {
                0 -> {
                    replace(i, item.copy(enabled = !item.enabled))
                    addons()
                }
                1 -> {
                    val selected = roles.map { it in item.switches }.toBooleanArray()
                    val names =
                        roles.map {
                            it +
                                if (it in item.manifest?.capabilities.orEmpty()) ""
                                else " (unadvertised)"
                        }
                    AlertDialog.Builder(a)
                        .setTitle("Resource switches")
                        .setMultiChoiceItems(names.toTypedArray(), selected) { _, idx, on ->
                            selected[idx] = on
                        }
                        .setPositiveButton("Apply") { _, _ ->
                            replace(
                                i,
                                item.copy(
                                    switches =
                                        roles.filterIndexed { idx, _ -> selected[idx] }.toSet()
                                ),
                            )
                            addon(i)
                        }
                        .setNegativeButton("Back") { _, _ -> addon(i) }
                        .setOnCancelListener { addon(i) }
                        .show()
                }
                2 ->
                    input(
                        "Configured install link",
                        item.url,
                        secret = true,
                        back = { addon(i) },
                    ) { raw ->
                        try {
                            val url = Endpoint(raw).manifest
                            if (draft.addons.any { it.key != item.key && it.url == url })
                                throw BridgeFailure("This configuration is already in the profile.")
                            val updated =
                                draft.addons.toMutableList().apply {
                                    set(i, item.copy(url = url, manifest = null, checked = 0))
                                }
                            discover(draft.copy(addons = updated), false) {
                                draft = it
                                addons()
                            }
                        } catch (e: BridgeFailure) {
                            toast(e.message.orEmpty())
                            addon(i)
                        }
                    }
                3,
                4 -> {
                    draft = draft.copy(addons = move(draft.addons, i, i + if (n == 3) -1 else 1))
                    addons()
                }
                5 ->
                    discover(Profile(addons = listOf(item.copy(enabled = true))), true) {
                        replace(i, it.addons.single().copy(enabled = item.enabled))
                        addon(i)
                    }
                6 -> {
                    draft =
                        draft.copy(
                            addons = draft.addons - item,
                            rows = draft.rows.filterNot { it.addon == item.key },
                            preferred = draft.preferred?.takeUnless { it == item.key },
                        )
                    addons()
                }
            }
        }
    }

    private fun preferred() {
        val choices =
            draft.addons.filter { it.uses("meta") && "meta" in it.manifest!!.capabilities }
        menu(
            "Preferred metadata",
            listOf("Follow addon order") +
                choices.map { label(it) + (if (it.key == draft.preferred) " ✓" else "") },
            ::editor,
        ) { i ->
            draft = draft.copy(preferred = choices.getOrNull(i - 1)?.key)
            editor()
        }
    }

    private fun rowName(r: Row) =
        r.title.ifBlank {
            (listOf(draft.catalog(r)?.name ?: r.catalog, r.type) + r.args.values).joinToString(
                " · "
            )
        }

    private fun rows() =
        menu(
            "Home rows in display order",
            listOf("Add a catalog row") + draft.rows.map(::rowName),
            ::editor,
        ) {
            if (it == 0) pick() else row(it - 1)
        }

    private fun row(i: Int) {
        val r = draft.rows[i]
        menu(rowName(r), listOf("Rename", "Move up", "Move down", "Hide from Home"), ::rows) { n ->
            when (n) {
                0 ->
                    input("Row title", rowName(r), back = ::rows) { value ->
                        draft =
                            draft.copy(
                                rows =
                                    draft.rows.toMutableList().apply {
                                        set(i, r.copy(title = value.take(100)))
                                    }
                            )
                        rows()
                    }
                1,
                2 -> {
                    draft = draft.copy(rows = move(draft.rows, i, i + if (n == 1) -1 else 1))
                    rows()
                }
                3 -> {
                    draft = draft.copy(rows = draft.rows.filterIndexed { idx, _ -> idx != i })
                    rows()
                }
            }
        }
    }

    private fun pick() {
        val all =
            draft.addons
                .filter { it.uses("catalog") }
                .flatMap { a ->
                    a.manifest!!
                        .catalogs
                        .filterNot { c -> c.extras.any { it.name == "search" && it.required } }
                        .map { a to it }
                }
        val panel = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val filter =
            EditText(a).apply {
                hint = "Find a catalog"
                setSingleLine()
            }
        val list = ListView(a)
        panel.addView(filter)
        panel.addView(list)
        var visible = all
        fun show(q: String) {
            visible =
                all.filter { (a, c) ->
                    (c.name + " " + c.type + " " + a.manifest!!.name).contains(q, true)
                }
            list.adapter =
                ArrayAdapter(
                    a,
                    android.R.layout.simple_list_item_1,
                    visible.map { (a, c) -> c.name + " · " + c.type + " · " + a.manifest!!.name },
                )
        }
        show("")
        filter.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    show(s.toString())
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
        val d =
            AlertDialog.Builder(a)
                .setTitle("Choose a catalog")
                .setView(panel)
                .setNegativeButton("Back") { _, _ -> rows() }
                .setOnCancelListener { rows() }
                .show()
        list.setOnItemClickListener { _, _, i, _ ->
            d.dismiss()
            val (addon, c) = visible[i]
            extra(
                Row(addon.key, c.type, c.id),
                c.extras.filterNot { it.name in listOf("skip", "search") },
                0,
            )
        }
    }

    private fun extra(r: Row, extras: List<Extra>, i: Int) {
        val e = extras.getOrNull(i)
        if (e == null) {
            if (draft.rows.none { it.key == r.key }) draft = draft.copy(rows = draft.rows + r)
            else toast("Row already selected.")
            rows()
            return
        }
        fun next(v: String?) {
            extra(if (v == null) r else r.copy(args = r.args + (e.name to v)), extras, i + 1)
        }
        val options =
            (if (e.required) emptyList() else listOf("Any")) +
                e.options.ifEmpty { listOf("Enter value…") }
        menu(e.name, options, ::rows) { n ->
            if (!e.required && n == 0) next(null)
            else if (e.options.isEmpty()) input(e.name, back = ::rows) { next(it) }
            else next(e.options[n - if (e.required) 0 else 1])
        }
    }

    private fun copy(label: String, text: String) {
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText(label, text)
        )
    }

    private fun restore(name: String) {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            toast("Full backups require Android 8 or newer.")
            manager()
            return
        }
        input("Paste encrypted backup", multi = true, back = ::manager) { raw ->
            input("Backup passphrase", secret = true, back = ::manager) { pass ->
                work(
                    "Restoring profile…",
                    ::manager,
                    { Backup.open(raw, pass.toCharArray(), name) },
                ) {
                    draft = it
                    discover(draft, true) { p ->
                        draft = p
                        editor()
                    }
                }
            }
        }
    }

    private fun advanced() {
        menu(
            "Advanced",
            listOf(
                "Duplicate profile",
                "Copy safe template",
                "Copy encrypted full backup",
                "Delete profile",
            ),
            ::editor,
        ) { n ->
            when (n) {
                0 ->
                    input("New profile name", draft.name + " copy", back = ::editor) {
                        original = null
                        draft = draft.copy(key = freshId(), name = it.take(64))
                        editor()
                    }
                1 -> {
                    copy("StremioBridge template", template(draft))
                    toast("Copied slots and switches. Re-enter links and rows after import.")
                    editor()
                }
                2 ->
                    if (android.os.Build.VERSION.SDK_INT < 26) {
                        toast("Full backups require Android 8 or newer.")
                        advanced()
                    } else
                        input(
                            "Backup passphrase (12 characters minimum)",
                            secret = true,
                            back = ::advanced,
                        ) { pass ->
                            if (pass.length < 12) {
                                toast("Use at least 12 characters.")
                                advanced()
                            } else
                                work(
                                    "Encrypting profile…",
                                    ::advanced,
                                    { Backup.seal(draft, pass.toCharArray()) },
                                ) {
                                    copy("Encrypted StremioBridge profile", it)
                                    toast("Encrypted profile copied.")
                                    editor()
                                }
                        }
                3 ->
                    AlertDialog.Builder(a)
                        .setMessage("Delete this profile and its bridge references?")
                        .setPositiveButton("Delete") { _, _ ->
                            try {
                                original?.let { commit(it, null) }
                                manager()
                            } catch (_: Exception) {
                                toast("Could not delete this profile.")
                                editor()
                            }
                        }
                        .setNegativeButton("Back") { _, _ -> editor() }
                        .setOnCancelListener { editor() }
                        .show()
            }
        }
    }
}
