package com.bitdroid.keys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var step1Badge: TextView
    private lateinit var step2Badge: TextView
    private lateinit var step1Button: Button
    private lateinit var step2Button: Button
    private lateinit var statusHeaderSub: TextView
    private lateinit var livePreviewKeyboard: KeyboardView

    private val bgPrimary = Color.parseColor("#000000")
    private val cardSurface = Color.parseColor("#11141B")
    private val cardBorder = Color.parseColor("#222836")
    private val accentBlue = Color.parseColor("#A8C7FA")
    private val accentDarkText = Color.parseColor("#062E6F")
    private val textPrimary = Color.parseColor("#F2F4F8")
    private val textSecondary = Color.parseColor("#9AA0A6")
    private val successGreen = Color.parseColor("#81C995")

    override fun onCreate(savedInstanceState: Bundle?) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
        super.onCreate(savedInstanceState)
        Storage.load(this)

        window.statusBarColor = bgPrimary
        window.navigationBarColor = bgPrimary

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgPrimary)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }

        // 1. Hero Header
        val headerCard = createCard().apply {
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val badgePill = TextView(this).apply {
            text = "GBOARD ENGINE • MATERIAL 3"
            setTextColor(accentBlue)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val appTitle = TextView(this).apply {
            text = "bitKeys"
            setTextColor(textPrimary)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        statusHeaderSub = TextView(this).apply {
            text = "Spatial QWERTY Autocorrect & Pure AMOLED Keyboard"
            setTextColor(textSecondary)
            textSize = 14f
        }
        titleRow.addView(badgePill)
        titleRow.addView(appTitle)
        titleRow.addView(statusHeaderSub)
        headerCard.addView(titleRow)
        root.addView(headerCard, marginParams(bottom = 16))

        // 2. Interactive 2-Step Setup Wizard Card
        val setupCard = createCard()
        setupCard.addView(sectionHeader("QUICK SETUP WIZARD", "Enable & activate bitKeys in 2 taps"))

        // Step 1 Row
        val step1Row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(12))
        }
        step1Badge = TextView(this).apply {
            text = "STEP 1 • Enable in Android Settings"
            setTextColor(textPrimary)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val step1Desc = TextView(this).apply {
            text = "Toggle on bitKeys in your system On-screen keyboard list."
            setTextColor(textSecondary)
            textSize = 13f
            setPadding(0, dp(2), 0, dp(10))
        }
        step1Button = createPillButton("1. Open Keyboard Settings", true) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        step1Row.addView(step1Badge)
        step1Row.addView(step1Desc)
        step1Row.addView(step1Button)
        setupCard.addView(step1Row)

        // Step 2 Row
        val step2Row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        step2Badge = TextView(this).apply {
            text = "STEP 2 • Select bitKeys as Default"
            setTextColor(textPrimary)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val step2Desc = TextView(this).apply {
            text = "Switch your active input method to bitKeys."
            setTextColor(textSecondary)
            textSize = 13f
            setPadding(0, dp(2), 0, dp(10))
        }
        step2Button = createPillButton("2. Choose Input Method", false) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        step2Row.addView(step2Badge)
        step2Row.addView(step2Desc)
        step2Row.addView(step2Button)
        setupCard.addView(step2Row)
        root.addView(setupCard, marginParams(bottom = 16))

        // 3. Live Interactive Keyboard Preview & Typing Sandbox
        val sandboxCard = createCard()
        sandboxCard.addView(sectionHeader("LIVE TYPING SANDBOX", "Test spatial autocorrect, contractions & key feel right here"))

        val testInput = EditText(this).apply {
            hint = "Tap here & type fast (e.g. 'teh dont reciept')..."
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1F2B"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), cardBorder)
            }
        }
        sandboxCard.addView(testInput, marginParams(top = 8, bottom = 14))

        livePreviewKeyboard = KeyboardView(this).apply {
            updateSuggestions(listOf("don't", "dont", "done"), "don't", "dont")
        }
        sandboxCard.addView(
            livePreviewKeyboard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(sandboxCard, marginParams(bottom = 16))

        // 4. Theme Selector Card
        val themeCard = createCard()
        themeCard.addView(sectionHeader("GBOARD MATERIAL THEMES", "Instant live preview • Pure AMOLED ready"))

        val themeButtonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        fun rebuildThemeRows() {
            themeButtonsContainer.removeAllViews()
            Themes.ALL.forEach { t ->
                val isSelected = (Storage.themeId == t.id)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = GradientDrawable().apply {
                        setColor(if (isSelected) Color.parseColor("#1D2536") else Color.parseColor("#151922"))
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(if (isSelected) 2 else 1), if (isSelected) accentBlue else cardBorder)
                    }
                    setOnClickListener {
                        Storage.themeId = t.id
                        Storage.save(this@SettingsActivity)
                        livePreviewKeyboard.refreshConfig()
                        rebuildThemeRows()
                    }
                }
                val nameTv = TextView(this).apply {
                    text = if (isSelected) "✓  ${t.name}" else t.name
                    setTextColor(if (isSelected) accentBlue else textPrimary)
                    textSize = 15.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                val subTv = TextView(this).apply {
                    text = t.subtitle
                    setTextColor(textSecondary)
                    textSize = 12.5f
                    setPadding(0, dp(2), 0, 0)
                }
                row.addView(nameTv)
                row.addView(subTv)
                themeButtonsContainer.addView(row, marginParams(bottom = 10))
            }
        }
        rebuildThemeRows()
        themeCard.addView(themeButtonsContainer)
        root.addView(themeCard, marginParams(bottom = 16))

        // 5. Smart Autocorrect & Typing Engine Card
        val typingCard = createCard()
        typingCard.addView(sectionHeader("AUTOCORRECT & TYPING ENGINE", "Powered by QWERTY spatial proximity & Damerau-Levenshtein"))

        typingCard.addView(
            createToggleRow(
                "Smart Spatial Autocorrect",
                "Automatically fixes fat-finger typos, transpositions & contractions on space",
                Storage.autoCorrect
            ) {
                Storage.autoCorrect = it
                Storage.save(this)
                livePreviewKeyboard.refreshConfig()
            }
        )
        typingCard.addView(
            createToggleRow(
                "Aggressive Typo Recovery",
                "Catches multi-letter slips & restores missing apostrophes (don't, I'm, can't)",
                Storage.aggressiveAutocorrect
            ) {
                Storage.aggressiveAutocorrect = it
                Storage.save(this)
            }
        )
        typingCard.addView(
            createToggleRow(
                "Auto-Capitalize Sentences",
                "Capitalize the first word after periods and new lines",
                Storage.autoCapitalize
            ) {
                Storage.autoCapitalize = it
                Storage.save(this)
            }
        )
        typingCard.addView(
            createToggleRow(
                "Double-Space Period",
                "Double tap spacebar to insert a period followed by a space",
                Storage.doubleSpacePeriod
            ) {
                Storage.doubleSpacePeriod = it
                Storage.save(this)
            }
        )
        root.addView(typingCard, marginParams(bottom = 16))

        // 6. Layout & Feel Card
        val layoutCard = createCard()
        layoutCard.addView(sectionHeader("LAYOUT & HAPTICS", "Customize Gboard key borders, number row & feedback"))

        layoutCard.addView(
            createToggleRow(
                "Dedicated Number Row",
                "Always show 1–0 number keys along the top row",
                Storage.showNumberRow
            ) {
                Storage.showNumberRow = it
                Storage.save(this)
                livePreviewKeyboard.refreshConfig()
            }
        )
        layoutCard.addView(
            createToggleRow(
                "Key Borders",
                "Show sculpted Gboard rounded keycaps",
                Storage.keyBorders
            ) {
                Storage.keyBorders = it
                Storage.save(this)
                livePreviewKeyboard.refreshConfig()
            }
        )
        layoutCard.addView(
            createToggleRow(
                "Haptic Vibration on Keypress",
                "Crisp tactile pulse when tapping keys or scrubbing cursor",
                Storage.hapticEnabled
            ) {
                Storage.hapticEnabled = it
                Storage.save(this)
            }
        )
        root.addView(layoutCard, marginParams(bottom = 16))

        // 7. About / Developer Attribution Card
        val aboutCard = createCard()
        aboutCard.addView(sectionHeader("ABOUT BITKEYS", "Engineered for speed, accuracy & clean Material You design"))
        val devText = TextView(this).apply {
            text = "Developed by Alex Desrosier\nPackage: com.bitdroid.keys • Gboard Spatial Engine v2.0"
            setTextColor(textSecondary)
            textSize = 13.5f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        }
        aboutCard.addView(devText)
        root.addView(aboutCard)

        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshSetupWizardState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshSetupWizardState()
        }
    }

    private fun refreshSetupWizardState() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledList = imm.enabledInputMethodList
        val isEnabled = enabledList.any { it.packageName == packageName }

        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val isSelected = defaultIme.contains(packageName)

        if (isEnabled) {
            step1Badge.text = "✓ STEP 1 COMPLETE • bitKeys is Enabled"
            step1Badge.setTextColor(successGreen)
            step1Button.text = "Enabled in Settings"
        } else {
            step1Badge.text = "STEP 1 • Enable in Android Settings"
            step1Badge.setTextColor(textPrimary)
            step1Button.text = "1. Open Keyboard Settings"
        }

        if (isSelected) {
            step2Badge.text = "✓ STEP 2 COMPLETE • bitKeys is Your Active Keyboard"
            step2Badge.setTextColor(successGreen)
            step2Button.text = "Switch Keyboard"
            statusHeaderSub.text = "✓ Active & Ready • Spatial QWERTY Autocorrect Online"
            statusHeaderSub.setTextColor(successGreen)
        } else {
            step2Badge.text = "STEP 2 • Select bitKeys as Default"
            step2Badge.setTextColor(textPrimary)
            step2Button.text = "2. Choose Input Method"
            statusHeaderSub.text = "Complete the 2-step setup below to start typing"
            statusHeaderSub.setTextColor(textSecondary)
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(cardSurface)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), cardBorder)
            }
        }
    }

    private fun sectionHeader(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(accentBlue)
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = subtitle
                setTextColor(textSecondary)
                textSize = 13f
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun createPillButton(label: String, filled: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (filled) accentDarkText else accentBlue)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(if (filled) accentBlue else Color.parseColor("#1A2232"))
                cornerRadius = dp(24).toFloat()
                if (!filled) setStroke(dp(1), accentBlue)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createToggleRow(
        title: String,
        subtitle: String,
        initial: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(textPrimary)
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(textSecondary)
            textSize = 12.5f
            setPadding(0, dp(2), 0, 0)
        })

        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }

        row.addView(textCol)
        row.addView(sw)
        return row
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
