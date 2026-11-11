package com.mindplayer99.stremiobridge

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.pow

/** Tonal roles use the host palette; text is adjusted only when its contrast needs it. */
internal class SettingsPalette(val surface: Int, text: Int, val accent: Int, error: Int) {
    val row = mix(surface, text, .09f)
    val selected = mix(row, accent, .10f)
    val pressed = mix(row, text, .08f)
    private val surfaces = listOf(surface, row, selected, pressed)
    val ink = readable(text, surfaces)
    val muted = readable(mix(surface, ink, .68f), surfaces)
    val accentInk = readable(accent, surfaces)
    val errorInk = readable(error, surfaces)
    val onAccent =
        if (contrast(Color.BLACK, accent) >= contrast(Color.WHITE, accent)) Color.BLACK
        else Color.WHITE
    val accentPressed = mix(accent, if (onAccent == Color.BLACK) Color.WHITE else Color.BLACK, .10f)
    val outline = readable(mix(surface, ink, .35f), listOf(surface, row), 3.0)

    companion object {
        fun mix(a: Int, b: Int, amount: Float): Int =
            Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * amount).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * amount).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * amount).toInt(),
            )

        fun contrast(a: Int, b: Int): Double {
            fun luminance(color: Int): Double {
                fun channel(value: Int): Double =
                    (value / 255.0).let {
                        if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4)
                    }
                return .2126 * channel(Color.red(color)) +
                    .7152 * channel(Color.green(color)) +
                    .0722 * channel(Color.blue(color))
            }
            val x = luminance(a)
            val y = luminance(b)
            return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
        }

        private fun readable(color: Int, backgrounds: List<Int>, minimum: Double = 4.5): Int {
            if (backgrounds.all { contrast(color, it) >= minimum }) return color
            val end =
                listOf(Color.BLACK, Color.WHITE).maxBy { candidate ->
                    backgrounds.minOf { contrast(candidate, it) }
                }
            return (1..100)
                .asSequence()
                .map { mix(color, end, it / 100f) }
                .firstOrNull { candidate -> backgrounds.all { contrast(candidate, it) >= minimum } }
                ?: end
        }
    }
}

/** Native Views only: no assumptions about the host's Material or Compose classpath. */
internal class SettingsView(private val activity: Activity, onClose: () -> Unit) {
    val dialog = Dialog(activity)
    private val appearance = activity.getSharedPreferences("stremiobridge-ui", 0)
    var amoled: Boolean
        get() = appearance.getBoolean("amoled", false)
        set(value) {
            appearance.edit().putBoolean("amoled", value).apply()
            palette = makePalette()
            root.background = shape(palette.surface, radius = 16)
        }

    private fun makePalette(): SettingsPalette {
        val background =
            hostColor(
                "primaryGrayBackground",
                color(android.R.attr.colorBackground, Color.rgb(24, 24, 28)),
            )
        val dark =
            SettingsPalette.contrast(background, Color.WHITE) >
                SettingsPalette.contrast(background, Color.BLACK)
        return SettingsPalette(
            if (amoled && dark) Color.BLACK else background,
            hostColor("textColor", color(android.R.attr.textColorPrimary, Color.WHITE)),
            hostColor("colorAccent", color(android.R.attr.colorAccent, Color.rgb(98, 91, 210))),
            hostColor("colorError", Color.rgb(186, 54, 48)),
        )
    }

    internal var palette = makePalette()
        private set

    private val root = column()
    val footer = column().apply { setPadding(0, dp(8), 0, 0) }
    private var backAction: (() -> Unit)? = null
    private var scroller: ScrollView? = null
    val scrollPosition
        get() = scroller?.scrollY ?: 0

    fun restoreScroll(position: Int) {
        scroller?.let { it.post { it.scrollTo(0, position) } }
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onClose() }
        dialog.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) backAction?.invoke() ?: dialog.dismiss()
                true
            } else false
        }
        root.background = shape(palette.surface, radius = 16)
        root.setPadding(dp(16), dp(8), dp(16), dp(16))
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun color(attr: Int, fallback: Int): Int {
        val values = activity.obtainStyledAttributes(intArrayOf(attr))
        return try {
            values.getColor(0, fallback)
        } catch (_: Exception) {
            fallback
        } finally {
            values.recycle()
        }
    }

    private fun hostColor(name: String, fallback: Int): Int {
        val attr = activity.resources.getIdentifier(name, "attr", activity.packageName)
        return if (attr == 0) fallback else color(attr, fallback)
    }

    private fun shape(fill: Int, stroke: Int? = null, radius: Int = 12, strokeWidth: Int = 2) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(strokeWidth), stroke)
        }

    private fun interactive(
        fill: Int = palette.row,
        primary: Boolean = false,
        field: Boolean = false,
    ) =
        StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(palette.row))
            if (!field)
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    shape(if (primary) palette.accentPressed else palette.pressed),
                )
            addState(
                intArrayOf(android.R.attr.state_focused),
                shape(fill, if (primary) palette.onAccent else palette.accentInk),
            )
            addState(intArrayOf(android.R.attr.state_selected), shape(palette.selected))
            addState(intArrayOf(android.R.attr.state_checked), shape(palette.selected))
            addState(
                intArrayOf(),
                shape(
                    fill,
                    if (field || primary && SettingsPalette.contrast(fill, palette.surface) < 1.5)
                        palette.outline
                    else null,
                    strokeWidth = 1,
                ),
            )
        }

    private fun textColors(enabled: Int) =
        ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.muted, enabled),
        )

    private fun choiceColors() =
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.accentInk, palette.muted),
        )

    fun page(
        title: String,
        back: (() -> Unit)? = null,
        secure: Boolean = false,
        scrolling: Boolean = true,
    ): LinearLayout {
        backAction = back
        root.removeAllViews()
        scroller = null
        footer.removeAllViews()
        val header =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
            }
        val name =
            text(title, 20f, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                contentDescription = title
                setPadding(0, dp(8), dp(8), dp(8))
            }
        header.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close =
            actionButton(if (back == null) "Close" else "Back", fill = palette.surface).apply {
                setTextColor(textColors(palette.accentInk))
                setOnClickListener { backAction?.invoke() ?: dialog.dismiss() }
            }
        header.addView(close, LinearLayout.LayoutParams(-2, -2))
        root.addView(header)
        val body =
            if (scrolling) column()
            else
                object : LinearLayout(activity) {
                    init {
                        orientation = VERTICAL
                    }

                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val max = (activity.resources.displayMetrics.heightPixels * .55f).toInt()
                        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
                        val available =
                            if (mode == View.MeasureSpec.UNSPECIFIED) max
                            else minOf(max, View.MeasureSpec.getSize(heightMeasureSpec))
                        super.onMeasure(
                            widthMeasureSpec,
                            if (mode == View.MeasureSpec.EXACTLY) heightMeasureSpec
                            else
                                View.MeasureSpec.makeMeasureSpec(
                                    available,
                                    View.MeasureSpec.AT_MOST,
                                ),
                        )
                    }
                }
        if (scrolling) {
            val scroll =
                object : ScrollView(activity) {
                        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                            val max =
                                (activity.resources.displayMetrics.heightPixels * .60f).toInt()
                            val mode = View.MeasureSpec.getMode(heightMeasureSpec)
                            val available =
                                if (mode == View.MeasureSpec.UNSPECIFIED) max
                                else minOf(max, View.MeasureSpec.getSize(heightMeasureSpec))
                            super.onMeasure(
                                widthMeasureSpec,
                                if (mode == View.MeasureSpec.EXACTLY) heightMeasureSpec
                                else
                                    View.MeasureSpec.makeMeasureSpec(
                                        available,
                                        View.MeasureSpec.AT_MOST,
                                    ),
                            )
                        }
                    }
                    .apply {
                        isFillViewport = false
                        clipToPadding = false
                        addView(body)
                    }
            root.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
            scroller = scroll
        } else {
            root.addView(body, LinearLayout.LayoutParams(-1, -2, 1f))
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        if (!dialog.isShowing) dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(minOf(dp(560), activity.resources.displayMetrics.widthPixels - dp(24)), -2)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        return body
    }

    fun text(value: String, size: Float = 14f, strong: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(if (strong) palette.ink else palette.muted)
            typeface =
                Typeface.create(if (strong) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            setPadding(0, dp(8), 0, dp(8))
        }

    fun note(parent: LinearLayout, value: String) {
        parent.addView(text(value))
    }

    fun error(parent: LinearLayout, value: String) {
        parent.addView(text(value).apply { setTextColor(palette.errorInk) })
    }

    fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 13f, true).apply { setPadding(dp(12), dp(16), dp(12), dp(8)) })
    }

    fun divider(parent: LinearLayout) {
        parent.addView(
            View(activity).apply {
                background = ColorDrawable(SettingsPalette.mix(palette.surface, palette.ink, .12f))
            },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            },
        )
    }

    fun card(parent: LinearLayout, title: String, summary: String? = null, action: () -> Unit) =
        row(parent, title, summary, null, false, action)

    fun choice(
        parent: LinearLayout,
        title: String,
        summary: String? = null,
        selected: Boolean,
        action: () -> Unit,
    ) = row(parent, title, summary, selected, false, action)

    fun danger(parent: LinearLayout, title: String, summary: String? = null, action: () -> Unit) =
        row(parent, title, summary, null, true, action)

    private fun row(
        parent: LinearLayout,
        title: String,
        summary: String?,
        selected: Boolean?,
        destructive: Boolean,
        action: () -> Unit,
    ): LinearLayout {
        val item =
            object : LinearLayout(activity) {
                    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(info)
                        info.className =
                            if (selected == null) Button::class.java.name
                            else RadioButton::class.java.name
                        info.isCheckable = selected != null
                        info.isChecked = isSelected
                    }
                }
                .apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = interactive()
                    setPaddingRelative(dp(12), dp(12), dp(8), dp(12))
                    minimumHeight = dp(if (summary.isNullOrBlank()) 56 else 64)
                    isClickable = true
                    isFocusable = true
                    isSelected = selected == true
                    contentDescription = listOfNotNull(title, summary).joinToString(", ")
                    setOnClickListener { action() }
                }
        val labels = column()
        labels.addView(
            text(title, 16f, true).apply {
                setPadding(0, 0, 0, 0)
                if (destructive) setTextColor(palette.errorInk)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        )
        if (!summary.isNullOrBlank())
            labels.addView(
                text(summary, 13f).apply {
                    setPadding(0, dp(4), 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            )
        item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val affordance =
            if (selected != null)
                RadioButton(activity).apply {
                    isChecked = selected
                    buttonTintList = choiceColors()
                    isClickable = false
                    isFocusable = false
                    setPadding(0, 0, 0, 0)
                    minimumWidth = 0
                }
            else
                text("›", 24f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                }
        affordance.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        item.addView(
            affordance,
            LinearLayout.LayoutParams(dp(if (selected == null) 24 else 40), -2),
        )
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        return item
    }

    private fun actionButton(title: String, primary: Boolean = false, fill: Int = palette.row) =
        Button(activity).apply {
            text = title
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            minHeight = dp(48)
            minWidth = dp(64)
            setSingleLine(false)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(textColors(if (primary) palette.onAccent else palette.ink))
            background = interactive(if (primary) palette.accent else fill, primary)
        }

    fun orderControls(
        row: LinearLayout,
        title: String,
        index: Int,
        count: Int,
        identity: String = title,
        move: (Int) -> Unit,
    ) {
        row.removeViewAt(row.childCount - 1)
        val controls = column()
        for ((symbol, delta, direction) in listOf(Triple("↑", -1, "up"), Triple("↓", 1, "down"))) {
            controls.addView(
                actionButton(symbol).apply {
                    minWidth = dp(48)
                    textSize = 22f
                    setPadding(0, dp(8), 0, dp(8))
                    contentDescription = "Move $title $direction"
                    tag = "order:$identity:$direction"
                    isEnabled = index + delta in 0 until count
                    setOnClickListener {
                        val focused = isFocused && !isInTouchMode
                        move(index + delta)
                        if (focused)
                            root.post {
                                val preferred =
                                    root.findViewWithTag<View>("order:$identity:$direction")
                                val alternate =
                                    root.findViewWithTag<View>(
                                        "order:$identity:${if (direction == "up") "down" else "up"}"
                                    )
                                (preferred?.takeIf { it.isEnabled } ?: alternate)?.requestFocus()
                            }
                    }
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
        row.addView(controls, LinearLayout.LayoutParams(dp(48), -2).apply { marginStart = dp(8) })
    }

    fun button(
        parent: LinearLayout,
        title: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        destructive: Boolean = false,
        action: () -> Unit,
    ): Button {
        val button =
            actionButton(title, primary, if (parent === footer) palette.surface else palette.row)
                .apply {
                    if (!primary)
                        gravity =
                            Gravity.CENTER_VERTICAL or
                                if (parent === footer) Gravity.CENTER_HORIZONTAL else Gravity.START
                    if (destructive) setTextColor(textColors(palette.errorInk))
                    isEnabled = enabled
                    setOnClickListener { action() }
                }
        parent.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return button
    }

    fun toggle(parent: LinearLayout, title: String, checked: Boolean, changed: (Boolean) -> Unit) {
        parent.addView(
            Switch(activity).apply {
                text = title
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(textColors(palette.ink))
                minHeight = dp(56)
                background = interactive()
                thumbTintList = choiceColors()
                trackTintList = null
                trackDrawable =
                    StateListDrawable().apply {
                        fun track(fill: Int) =
                            shape(fill, palette.outline, radius = 16, strokeWidth = 1).apply {
                                setSize(dp(48), dp(28))
                            }
                        addState(intArrayOf(android.R.attr.state_checked), track(palette.selected))
                        addState(intArrayOf(), track(palette.row))
                    }
                switchPadding = dp(16)
                isChecked = checked
                setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
                setOnCheckedChangeListener { _, value -> changed(value) }
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) },
        )
    }

    fun styleField(field: EditText) {
        field.apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
            highlightColor = palette.selected
            minHeight = dp(56)
            setPaddingRelative(dp(12), dp(12), dp(12), dp(12))
            background = interactive(field = true)
        }
    }

    fun styleList(list: ListView) {
        list.divider = ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = dp(4)
        list.selector =
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    shape(Color.TRANSPARENT, palette.accentInk),
                )
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    shape(Color.TRANSPARENT, palette.accentInk),
                )
                addState(
                    intArrayOf(android.R.attr.state_selected),
                    shape(Color.TRANSPARENT, palette.accentInk),
                )
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
        list.setDrawSelectorOnTop(true)
    }

    fun catalogAdapter(labels: List<String>) =
        object :
            ArrayAdapter<String>(
                activity,
                android.R.layout.simple_list_item_multiple_choice,
                labels,
            ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as CheckedTextView
                view.apply {
                    layoutParams =
                        layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    setSingleLine(false)
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    val label = getItem(position).orEmpty()
                    val split = label.indexOf('\n')
                    text =
                        SpannableString(label).apply {
                            if (split >= 0) {
                                setSpan(
                                    RelativeSizeSpan(.85f),
                                    split + 1,
                                    length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                                setSpan(
                                    ForegroundColorSpan(palette.muted),
                                    split + 1,
                                    length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                            }
                        }
                    textSize = 16f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(textColors(palette.ink))
                    checkMarkTintList = choiceColors()
                    minHeight = dp(64)
                    gravity = Gravity.CENTER_VERTICAL
                    setPaddingRelative(dp(12), dp(12), dp(12), dp(12))
                    background = interactive()
                }
                return view
            }
        }

    fun loading(title: String) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
            addView(
                ProgressBar(activity).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(palette.accentInk)
                },
                LinearLayout.LayoutParams(dp(24), dp(24)),
            )
            addView(
                text(title, 16f, true),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) },
            )
        }

    fun alert(
        builder: AlertDialog.Builder,
        destructive: Boolean = false,
        error: Boolean = false,
    ): AlertDialog =
        builder.show().apply {
            window?.setBackgroundDrawable(shape(palette.surface, radius = 16))
            findViewById<TextView>(android.R.id.message)?.setTextColor(palette.ink)
            val titleId = activity.resources.getIdentifier("alertTitle", "id", "android")
            findViewById<TextView>(titleId)?.apply {
                setTextColor(if (error) palette.errorInk else palette.ink)
                textSize = 20f
            }
            for (which in
                listOf(
                    AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEGATIVE,
                    AlertDialog.BUTTON_NEUTRAL,
                )) getButton(which)?.apply {
                isAllCaps = false
                textSize = 14f
                minHeight = dp(48)
                setTextColor(
                    textColors(
                        if (destructive && which == AlertDialog.BUTTON_POSITIVE) palette.errorInk
                        else palette.accentInk
                    )
                )
            }
        }
}
