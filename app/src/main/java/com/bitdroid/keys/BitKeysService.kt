package com.bitdroid.keys

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout

class BitKeysService : InputMethodService(), KeyboardView.Listener, VoicePanelView.Listener {

    private var rootContainer: FrameLayout? = null
    private var keyboardView: KeyboardView? = null
    private var voicePanelView: VoicePanelView? = null
    private lateinit var suggester: Suggester

    private val currentWord = StringBuilder()
    private var previousCommittedWord: String? = null

    private var lastOriginalTyped: String? = null
    private var lastAutoCorrectedTo: String? = null
    private var lastSpaceTime = 0L

    override fun onCreate() {
        super.onCreate()
        Storage.load(this)
        suggester = Suggester(this)
    }

    override fun onCreateInputView(): View {
        Storage.load(this)
        val container = FrameLayout(this)

        val kv = KeyboardView(this).apply {
            listener = this@BitKeysService
            refreshConfig()
        }
        val vpv = VoicePanelView(this).apply {
            listener = this@BitKeysService
            theme = Themes.byId(Storage.themeId)
            visibility = View.GONE
        }

        container.addView(kv)
        container.addView(vpv)

        rootContainer = container
        keyboardView = kv
        voicePanelView = vpv
        refreshSuggestions()
        return container
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Storage.load(this)
        suggester.reloadUserWords()
        currentWord.clear()
        lastOriginalTyped = null
        lastAutoCorrectedTo = null

        // Always ensure voice mic is shut off when switching fields unless user presses Mic
        voicePanelView?.pauseListeningImmediate()
        voicePanelView?.visibility = View.GONE
        voicePanelView?.theme = Themes.byId(Storage.themeId)

        keyboardView?.visibility = View.VISIBLE
        keyboardView?.refreshConfig()
        updateAutoCapitalization()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Immediately cut off mic hardware when keyboard hides
        voicePanelView?.pauseListeningImmediate()
        super.onFinishInputView(finishingInput)
    }

    private fun updateAutoCapitalization() {
        if (!Storage.autoCapitalize) return
        val kv = keyboardView ?: return
        if (kv.shiftState == KeyboardView.ShiftState.CAPS_LOCK) return

        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(3, 0)?.toString() ?: ""
        val shouldShift = before.isEmpty() ||
            before.endsWith(". ") ||
            before.endsWith("! ") ||
            before.endsWith("? ") ||
            before.endsWith("\n")
        kv.shiftState = if (shouldShift) KeyboardView.ShiftState.SHIFTED else KeyboardView.ShiftState.LOWER
    }

    private fun refreshSuggestions() {
        val kv = keyboardView ?: return
        val result = suggester.evaluate(currentWord.toString(), previousCommittedWord)
        kv.updateSuggestions(result.candidates, result.primaryCorrection, currentWord.toString())
    }

    override fun onCharacter(ch: String) {
        val ic = currentInputConnection ?: return
        lastOriginalTyped = null
        lastAutoCorrectedTo = null

        val isWordChar = ch.length == 1 && (ch[0].isLetter() || ch[0] == '\'')
        if (isWordChar) {
            currentWord.append(ch)
            ic.commitText(ch, 1)
            refreshSuggestions()
        } else {
            commitPendingWordWithAutocorrectIfApplicable()
            ic.commitText(ch, 1)
            updateAutoCapitalization()
            refreshSuggestions()
        }
    }

    private fun commitPendingWordWithAutocorrectIfApplicable(): Boolean {
        val ic = currentInputConnection ?: return false
        if (currentWord.isEmpty()) return false

        val typed = currentWord.toString()
        val eval = suggester.evaluate(typed, previousCommittedWord)
        val replacement = eval.primaryCorrection

        if (Storage.autoCorrect && eval.shouldAutoCommit && replacement != null && replacement != typed) {
            ic.deleteSurroundingText(typed.length, 0)
            ic.commitText(replacement, 1)
            lastOriginalTyped = typed
            lastAutoCorrectedTo = replacement
            previousCommittedWord = replacement
        } else {
            lastOriginalTyped = null
            lastAutoCorrectedTo = null
            previousCommittedWord = typed
        }
        currentWord.clear()
        return true
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()

        if (Storage.doubleSpacePeriod && currentWord.isEmpty() && (now - lastSpaceTime) < 340L) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTime = 0L
                updateAutoCapitalization()
                refreshSuggestions()
                return
            }
        }

        commitPendingWordWithAutocorrectIfApplicable()
        ic.commitText(" ", 1)
        lastSpaceTime = now
        updateAutoCapitalization()
        refreshSuggestions()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return

        val orig = lastOriginalTyped
        val corrected = lastAutoCorrectedTo
        if (currentWord.isEmpty() && orig != null && corrected != null) {
            val tail = ic.getTextBeforeCursor(corrected.length + 1, 0)?.toString() ?: ""
            if (tail == "$corrected ") {
                ic.deleteSurroundingText(corrected.length + 1, 0)
                ic.commitText(orig, 1)
                currentWord.clear()
                currentWord.append(orig)
                Storage.recordUserWord(this, orig)
                suggester.reloadUserWords()
                lastOriginalTyped = null
                lastAutoCorrectedTo = null
                refreshSuggestions()
                return
            }
            lastOriginalTyped = null
            lastAutoCorrectedTo = null
        }

        if (currentWord.isNotEmpty()) {
            currentWord.deleteCharAt(currentWord.length - 1)
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.deleteSurroundingText(1, 0)
            val before = ic.getTextBeforeCursor(20, 0)?.toString() ?: ""
            val trailingWord = before.takeLastWhile { it.isLetter() || it == '\'' }
            currentWord.clear()
            if (trailingWord.isNotEmpty()) {
                currentWord.append(trailingWord)
            }
        }
        updateAutoCapitalization()
        refreshSuggestions()
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        lastOriginalTyped = null
        lastAutoCorrectedTo = null

        if (currentWord.isNotEmpty()) {
            val len = currentWord.length
            currentWord.clear()
            ic.deleteSurroundingText(len, 0)
        } else {
            val before = ic.getTextBeforeCursor(32, 0)?.toString() ?: ""
            val trimmed = before.trimEnd()
            val lastSpace = trimmed.lastIndexOf(' ')
            val count = if (lastSpace == -1) before.length else (before.length - lastSpace - 1)
            if (count > 0) ic.deleteSurroundingText(count, 0)
        }
        updateAutoCapitalization()
        refreshSuggestions()
    }

    override fun onSuggestionClicked(word: String, index: Int) {
        val ic = currentInputConnection ?: return
        lastOriginalTyped = null
        lastAutoCorrectedTo = null

        if (currentWord.isNotEmpty()) {
            ic.deleteSurroundingText(currentWord.length, 0)
            currentWord.clear()
        }
        ic.commitText("$word ", 1)
        previousCommittedWord = word

        Storage.recordUserWord(this, word)
        suggester.reloadUserWords()

        updateAutoCapitalization()
        refreshSuggestions()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        commitPendingWordWithAutocorrectIfApplicable()

        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val isMultiLine = (info?.inputType?.and(InputType.TYPE_TEXT_FLAG_MULTI_LINE) ?: 0) != 0

        if (!isMultiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        updateAutoCapitalization()
        refreshSuggestions()
    }

    override fun onCursorMove(delta: Int) {
        val ic = currentInputConnection ?: return
        val keyCode = if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(delta)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    override fun onOpenSettings() {
        try {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun onVoiceStart() {
        commitPendingWordWithAutocorrectIfApplicable()
        keyboardView?.visibility = View.GONE
        voicePanelView?.apply {
            theme = Themes.byId(Storage.themeId)
            visibility = View.VISIBLE
            resumeListeningExplicit()
        }
    }

    // VoicePanelView.Listener callbacks
    override fun onVoiceCommit(text: String) {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        ic.commitText(text, 1)
    }

    override fun onVoicePartial(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isEmpty()) {
            ic.finishComposingText()
        } else {
            ic.setComposingText(text, 1)
        }
    }

    override fun onVoiceClose() {
        currentInputConnection?.finishComposingText()
        voicePanelView?.pauseListeningImmediate()
        voicePanelView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
        updateAutoCapitalization()
        refreshSuggestions()
    }

    override fun onVoiceBackspace() {
        currentInputConnection?.finishComposingText()
        onBackspace()
    }
}
