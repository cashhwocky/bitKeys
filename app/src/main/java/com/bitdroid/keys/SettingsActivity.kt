package com.bitdroid.keys

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * bitKeys settings: setup steps, theme cards, accent color, key shape and preferences.
 * Built in code (no XML layouts) to keep the app tiny and dependency-free.
 */
class SettingsActivity : Activity() {

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
        private const val REQ_MIC = 7
        private val BLACK = 0xFF000000.toInt()
        private val SURFACE = 0xFF121214.toInt()
        private val TEXT = 0xFFF2F2F5.toInt()
        private val SUB = 0xFFA0A0A8.toInt()
        private val LINE = 0xFF2C2C31.toInt()
    }

    private lateinit var column: LinearLayout
    private lateinit var tryField: EditText
    private var prefs = KeyboardPrefs()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KeyboardPrefs.load(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BLACK)
            clipToPadding = false
            isFillViewport = true
        }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(dpi(20f), bars.top + dpi(12f), dpi(20f), bars.bottom + dpi(24f))
            insets
        }
        setContentView(scroll)

        tryField = EditText(this).apply {
            hint = "Try bitKeys here"
            setHintTextColor(SUB)
            setTextColor(TEXT)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = rounded(SURFACE, dp(24f))
            setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(14f))
            minHeight = dpi(52f)
        }

        render()
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) requestMic()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) render() // picks up the result of the input-method picker
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun update(p: KeyboardPrefs) {
        prefs = p
        p.save(this)
        render()
    }

    // ------------------------------------------------------------ state checks

    private fun imeEnabled(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun imeSelected(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return id.startsWith("$packageName/")
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ UI

    private fun render() {
        val accent = Themes.accentColor(this, prefs.accent)
        column.removeAllViews()

        // Header
        column.addView(text("bitKeys", 34f, TEXT, bold = true))
        column.addView(text("The keyboard for bitDroid", 15f, SUB).also { it.setPadding(0, dpi(2f), 0, dpi(20f)) })

        // Setup steps (hidden once everything is done)
        val enabled = imeEnabled()
        val selected = imeSelected()
        val mic = micGranted()
        if (!enabled || !selected || !mic) {
            column.addView(section("SET UP", accent))
            val card = card()
            card.addView(step(1, "Turn on bitKeys", "Enable it in your keyboard list", enabled, accent, "Open") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            card.addView(step(2, "Switch to bitKeys", "Make it your active keyboard", selected, accent, "Choose") {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            })
            card.addView(step(3, "Allow the microphone", "Needed for voice typing", mic, accent, "Allow") { requestMic() })
            column.addView(card)
        }

        // Try it
        column.addView(section("TRY IT", accent))
        (tryField.parent as? ViewGroup)?.removeView(tryField)
        column.addView(tryField)

        // Theme cards
        column.addView(section("KEYBOARD THEME", accent))
        Themes.THEMES.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (id, name) ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    contentDescription = "$name theme"
                    setOnClickListener { update(prefs.copy(theme = id)) }
                }
                item.addView(ThemePreviewView(this).apply {
                    palette = Themes.palette(this@SettingsActivity, id, prefs.accent)
                    shape = prefs.shape
                    chosen = prefs.theme == id
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(104f)))
                val label = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpi(4f), dpi(8f), 0, dpi(14f))
                }
                label.addView(text(name, 14f, TEXT, medium = true))
                if (id == "amoled") {
                    label.addView(text("Default", 11f, BLACK, bold = true).apply {
                        background = rounded(accent, dp(8f))
                        setPadding(dpi(7f), dpi(2f), dpi(7f), dpi(2f))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(8f) })
                }
                item.addView(label)
                row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0) marginEnd = dpi(6f) else marginStart = dpi(6f)
                })
            }
            column.addView(row)
        }

        // Accent swatches
        column.addView(section("ACCENT", accent))
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Themes.ACCENTS.forEach { (id, name) ->
            val color = Themes.accentColor(this, id)
            val isSel = prefs.accent == id
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (isSel) setStroke(dpi(3f), TEXT)
                }
                contentDescription = "$name accent" + if (isSel) ", selected" else ""
                isClickable = true
                setOnClickListener { update(prefs.copy(accent = id)) }
            }
            swatches.addView(dot, LinearLayout.LayoutParams(dpi(44f), dpi(44f)).apply { marginEnd = dpi(14f) })
        }
        column.addView(swatches)
        column.addView(text("“Wallpaper” follows your Material You colors.", 13f, SUB).also { it.setPadding(0, dpi(8f), 0, 0) })

        // Key shape segmented control
        column.addView(section("KEY SHAPE", accent))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(SURFACE, dp(24f))
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        }
        Themes.SHAPES.forEach { (id, name) ->
            val sel = prefs.shape == id
            seg.addView(text(name, 14f, if (sel) BLACK else 0xFFC8C8CE.toInt(), medium = true).apply {
                gravity = Gravity.CENTER
                if (sel) background = rounded(accent, dp(20f))
                isClickable = true
                setOnClickListener { update(prefs.copy(shape = id)) }
            }, LinearLayout.LayoutParams(0, dpi(44f), 1f))
        }
        column.addView(seg)

        // Toggles
        column.addView(section("PREFERENCES", accent))
        column.addView(toggle("Auto-capitalization", "Capitalize the first letter of each sentence", prefs.autoCaps, accent) { update(prefs.copy(autoCaps = it)) })
        column.addView(toggle("Number pad", "Show a big number pad in number and phone fields (or tap 1234 on the symbols page)", prefs.numpad, accent) { update(prefs.copy(numpad = it)) })
        column.addView(toggle("Number row", "Always show 1–0 above the letters", prefs.numberRow, accent) { update(prefs.copy(numberRow = it)) })
        column.addView(toggle("Key borders", "Outline each key", prefs.borders, accent) { update(prefs.copy(borders = it)) })
        column.addView(toggle("Haptic feedback", "Vibrate on keypress", prefs.haptics, accent) { update(prefs.copy(haptics = it)) })
        column.addView(toggle("Sound on keypress", "Play a soft click", prefs.sound, accent) { update(prefs.copy(sound = it)) })

        // Footer
        column.addView(Button(this).apply {
            text = "Clear learned words"
            setTextColor(TEXT)
            isAllCaps = false
            background = rounded(SURFACE, dp(24f))
            setOnClickListener {
                Suggester(this@SettingsActivity).forgetAll()
                text = "Learned words cleared"
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48f)).apply { topMargin = dpi(24f) })
        column.addView(text("Made by Alex Desrosier · bitDroid", 13f, SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dpi(24f), 0, 0)
        })
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, medium: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = when {
            bold -> Typeface.create(Typeface.DEFAULT, 600, false)
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }

    private fun section(title: String, accent: Int) = text(title, 13f, accent, bold = true).apply {
        letterSpacing = 0.04f
        setPadding(0, dpi(26f), 0, dpi(12f))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dp(24f))
        setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(8f))
    }

    private fun step(n: Int, title: String, sub: String, done: Boolean, accent: Int, action: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(10f), 0, dpi(10f))
        }
        row.addView(text(if (done) "✓" else n.toString(), 15f, if (done) BLACK else TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (done) setColor(accent) else setStroke(dpi(1.5f), LINE)
            }
        }, LinearLayout.LayoutParams(dpi(32f), dpi(32f)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, TEXT))
        texts.addView(text(sub, 13f, SUB))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(14f) })
        if (!done) {
            row.addView(Button(this).apply {
                text = action
                isAllCaps = false
                setTextColor(BLACK)
                background = rounded(accent, dp(20f))
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(40f)))
        }
        return row
    }

    @Suppress("DEPRECATION")
    private fun toggle(title: String, sub: String, on: Boolean, accent: Int, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(60f)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, TEXT))
        texts.addView(text(sub, 13f, SUB))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = on
            contentDescription = title
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(BLACK, 0xFF8A8A93.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(accent, LINE))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return FrameLayout(this).apply { addView(row) }
    }
}
