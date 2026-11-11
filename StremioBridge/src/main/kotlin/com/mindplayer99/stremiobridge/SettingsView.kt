package com.mindplayer99.stremiobridge

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Uses the host Activity theme and native widgets, including keyboard and TV focus. */
internal class SettingsView(private val activity: Activity, onClose: () -> Unit) {
    val dialog = Dialog(activity)
    private val background = color(android.R.attr.colorBackground, Color.rgb(20, 21, 24))
    private val ink = color(android.R.attr.textColorPrimary, Color.WHITE)
    private val muted = blend(background, ink, .68f)
    private val cardColor = blend(background, ink, .055f)
    private val outlineColor = blend(background, ink, .16f)
    private val accentColor = color(android.R.attr.colorAccent, Color.rgb(120, 113, 240))
    private val root = column()
    val footer = column()
    private var backAction: (() -> Unit)? = null
    private var sensitive = false

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
        root.background = shape(background)
        root.setPadding(dp(18), dp(12), dp(18), dp(12))
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun color(attr: Int, fallback: Int): Int {
        val values = activity.obtainStyledAttributes(intArrayOf(attr))
        return try {
            values.getColor(0, fallback)
        } finally {
            values.recycle()
        }
    }

    private fun blend(a: Int, b: Int, fraction: Float): Int =
        Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * fraction).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * fraction).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction).toInt(),
        )

    private fun shape(fill: Int, focused: Boolean = false) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(if (focused) 2 else 1), if (focused) ink else outlineColor)
        }

    private fun interactive() =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                shape(blend(cardColor, accentColor, .18f)),
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                shape(blend(cardColor, accentColor, .18f)),
            )
            addState(intArrayOf(), shape(cardColor))
        }

    private fun primaryBackground() =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(blend(accentColor, ink, .12f)))
            addState(intArrayOf(android.R.attr.state_focused), shape(accentColor, focused = true))
            addState(intArrayOf(), shape(accentColor))
        }

    fun page(
        title: String,
        back: (() -> Unit)? = null,
        secure: Boolean = false,
        scrolling: Boolean = true,
    ): LinearLayout {
        backAction = back
        sensitive = secure
        root.removeAllViews()
        footer.removeAllViews()
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val name = text(title, 22f, true)
        header.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close =
            Button(activity).apply {
                this.text = if (back == null) "Close" else "Back"
                isAllCaps = false
                minHeight = dp(48)
                minWidth = dp(64)
                textSize = 14f
                background = interactive()
                setTextColor(ink)
                setOnClickListener { backAction?.invoke() ?: dialog.dismiss() }
            }
        header.addView(close)
        root.addView(header)
        val body = column()
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
                        addView(body)
                    }
            root.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        } else {
            root.addView(
                body,
                LinearLayout.LayoutParams(
                    -1,
                    (activity.resources.displayMetrics.heightPixels * .55f).toInt(),
                    1f,
                ),
            )
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        if (!dialog.isShowing) dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(minOf(dp(600), activity.resources.displayMetrics.widthPixels - dp(24)), -2)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (sensitive) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        return body
    }

    fun text(value: String, size: Float = 14f, strong: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(if (strong) ink else muted)
            if (strong) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(8))
        }

    fun note(parent: LinearLayout, value: String) {
        parent.addView(text(value))
    }

    fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 15f, true))
    }

    fun card(
        parent: LinearLayout,
        title: String,
        summary: String? = null,
        action: () -> Unit,
    ): LinearLayout {
        val item =
            column().apply {
                background = interactive()
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minimumHeight = dp(56)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        item.addView(text(title, 16f, true))
        if (!summary.isNullOrBlank()) item.addView(text(summary, 13f))
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        return item
    }

    fun button(
        parent: LinearLayout,
        title: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        action: () -> Unit,
    ): Button {
        val button =
            Button(activity).apply {
                text = title
                isAllCaps = false
                textSize = 14f
                minHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(if (primary) contrast(accentColor) else ink)
                background = if (primary) primaryBackground() else interactive()
                isEnabled = enabled
                alpha = if (enabled) 1f else .45f
                setOnClickListener { action() }
            }
        parent.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return button
    }

    fun toggle(parent: LinearLayout, title: String, checked: Boolean, changed: (Boolean) -> Unit) {
        parent.addView(
            Switch(activity).apply {
                text = title
                textSize = 16f
                setTextColor(ink)
                minHeight = dp(52)
                isChecked = checked
                setPadding(dp(8), 0, dp(8), 0)
                setOnCheckedChangeListener { _, value -> changed(value) }
            }
        )
    }

    private fun contrast(color: Int) =
        if (Color.red(color) * .299 + Color.green(color) * .587 + Color.blue(color) * .114 > 155)
            Color.BLACK
        else Color.WHITE
}
