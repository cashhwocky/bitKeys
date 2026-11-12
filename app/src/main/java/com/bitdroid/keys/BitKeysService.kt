package com.bitdroid.keys

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class BitKeysService : InputMethodService(), KeyboardView.Listener, VoicePanelView.Listener, EmojiPanelView.Listener {

    private lateinit var keyboard: KeyboardView
    private lateinit var voice: VoicePanelView
    private lateinit var emoji: EmojiPanelView
    private lateinit var suggester: Suggester
    private var prefs = KeyboardPrefs()

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var needsLeadingSpace = false

    override fun onCreate() {
        super.onCreate()
        suggester = Suggester(this)
        prefs = KeyboardPrefs.load(this)
    }

    override fun onCreateInputView(): View {
        keyboard = KeyboardView(this).also { it.listener = this }
        voice = VoicePanelView(this).also {
            it.listener = this
            it.visibility = View.GONE
        }
        emoji = EmojiPanelView(this).also {
            it.listener = this
            it.visibility = View.GONE
        }
        return FrameLayout(this).apply {
            addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(voice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(emoji, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        prefs = KeyboardPrefs.load(this)
        keyboard.applyPrefs(prefs)
        voice.setPalette(keyboard.palette())
        emoji.setPalette(keyboard.palette(), prefs.shape)

        if (!restarting) {
            val cls = info.inputType and InputType.TYPE_MASK_CLASS
            keyboard.switchMode(
                when (cls) {
                    InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> KeyboardView.Mode.SYMBOLS
                    else -> KeyboardView.Mode.LETTERS
                }
            )
        }
        keyboard.setEnterStyle(enterStyleFor(info))
        showVoice(false)
        showEmoji(false)
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopVoice(hide = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!listening && ::keyboard.isInitialized) refresh()
    }

    // ------------------------------------------------------------ helpers

    private fun isPrivateField(info: EditorInfo?): Boolean {
        if (info == null) return true
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_TEXT && (
                v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
        ) return true
        if (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        if (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return true
        return info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    }

    private fun enterStyleFor(info: EditorInfo): KeyboardView.EnterStyle {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return KeyboardView.EnterStyle.ENTER
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> KeyboardView.EnterStyle.SEND
            EditorInfo.IME_ACTION_SEARCH -> KeyboardView.EnterStyle.SEARCH
            EditorInfo.IME_ACTION_GO -> KeyboardView.EnterStyle.GO
            EditorInfo.IME_ACTION_NEXT -> KeyboardView.EnterStyle.NEXT
            EditorInfo.IME_ACTION_DONE -> KeyboardView.EnterStyle.DONE
            else -> KeyboardView.EnterStyle.ENTER
        }
    }

    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        return before.takeLastWhile { it.isLetterOrDigit() || it == '\'' }
    }

    private fun refresh() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val caps = prefs.autoCaps && info != null && info.inputType != 0 && ic.getCursorCapsMode(info.inputType) != 0
        keyboard.setAutoShift(caps)

        val hasSelection = !ic.getSelectedText(0).isNullOrEmpty()
        val word = currentWord()
        keyboard.setSuggestions(
            if (hasSelection || word.isEmpty() || isPrivateField(info)) emptyList()
            else suggester.slots(word)
        )
    }

    private fun learnCurrentWord() {
        if (isPrivateField(currentInputEditorInfo)) return
        val w = currentWord()
        if (w.length >= 2 && w.any { it.isLetter() }) suggester.learn(w)
    }

    // ------------------------------------------------------------ KeyboardView.Listener

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        if (text == " ") {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                // double-space → ". "
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                refresh()
                return
            }
            learnCurrentWord()
        }
        ic.commitText(text, 1)
        refresh()
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        refresh()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        learnCurrentWord()
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (info == null || noEnterAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            sendKeyChar('\n')
        } else {
            ic.performEditorAction(action)
        }
        refresh()
    }

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val typed = currentWord()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (!isPrivateField(currentInputEditorInfo)) suggester.learn(word)
        refresh()
    }

    override fun onToolbar(action: KeyboardView.ToolbarAction) {
        when (action) {
            KeyboardView.ToolbarAction.CLIPBOARD -> {
                val cm = getSystemService(ClipboardManager::class.java)
                val clip = cm?.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this) else null
                if (text.isNullOrEmpty()) {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                } else {
                    currentInputConnection?.commitText(text, 1)
                    refresh()
                }
            }
            KeyboardView.ToolbarAction.EMOJI -> showEmoji(true)
            KeyboardView.ToolbarAction.SETTINGS -> openSettings(false)
            KeyboardView.ToolbarAction.MIC -> startVoice()
        }
    }

    override fun onCursorMove(steps: Int) {
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    private fun openSettings(requestMic: Boolean) {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SettingsActivity.EXTRA_REQUEST_MIC, requestMic)
        )
    }

    // ------------------------------------------------------------ emoji panel

    private fun showEmoji(show: Boolean) {
        if (!::emoji.isInitialized) return
        if (show) {
            emoji.fixedHeight = keyboard.height
            emoji.bottomInset = keyboard.navInset
            emoji.onOpen()
            emoji.visibility = View.VISIBLE
            keyboard.visibility = View.INVISIBLE
        } else {
            emoji.visibility = View.GONE
            keyboard.visibility = View.VISIBLE
        }
    }

    override fun onEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
    }

    override fun onEmojiDelete() {
        onDelete()
    }

    override fun onEmojiSpace() {
        currentInputConnection?.commitText(" ", 1)
    }

    override fun onEmojiAbc() {
        showEmoji(false)
        refresh()
    }

    // ------------------------------------------------------------ voice typing

    private fun showVoice(show: Boolean) {
        if (!::voice.isInitialized) return
        if (show) {
            voice.fixedHeight = keyboard.height
            voice.bottomInset = keyboard.navInset
            voice.visibility = View.VISIBLE
            keyboard.visibility = View.INVISIBLE
        } else {
            voice.visibility = View.GONE
            keyboard.visibility = View.VISIBLE
        }
    }

    private fun recognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(this) ||
            (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voice.setListening(true, "Listening…")
            }

            override fun onBeginningOfSpeech() {
                val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                needsLeadingSpace = before.isNotEmpty() && !before[0].isWhitespace()
            }

            override fun onRmsChanged(rmsdB: Float) {
                voice.setLevel((rmsdB + 2f) / 12f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrEmpty()) currentInputConnection?.setComposingText(withSpace(text), 1)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                val ic = currentInputConnection
                if (ic != null) {
                    if (!text.isNullOrEmpty()) ic.commitText(withSpace(text), 1) else ic.finishComposingText()
                }
                needsLeadingSpace = true
                restartListening()
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartListening()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> handler.postDelayed({ restartListening() }, 300)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        stopVoice(hide = true)
                        openSettings(true)
                    }
                    else -> {
                        listening = false
                        currentInputConnection?.finishComposingText()
                        voice.setListening(false, "Couldn't hear that — tap to retry")
                    }
                }
            }
        })
        recognizer = r
        return r
    }

    private fun withSpace(text: String) = if (needsLeadingSpace) " $text" else text

    private fun restartListening() {
        if (!listening) return
        handler.post { if (listening) recognizer?.startListening(recognizerIntent()) }
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openSettings(true)
            return
        }
        if (!recognitionAvailable()) {
            Toast.makeText(this, "No speech recognition service is installed", Toast.LENGTH_LONG).show()
            return
        }
        showVoice(true)
        listening = true
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
        needsLeadingSpace = before.isNotEmpty() && !before[0].isWhitespace()
        voice.setListening(true, "Starting…")
        ensureRecognizer().startListening(recognizerIntent())
    }

    private fun stopVoice(hide: Boolean) {
        if (listening) {
            listening = false
            recognizer?.cancel()
            currentInputConnection?.finishComposingText()
        }
        if (::voice.isInitialized) {
            voice.setListening(false, "Paused — tap to resume")
            if (hide) showVoice(false)
        }
        if (::keyboard.isInitialized && hide) refresh()
    }

    // ------------------------------------------------------------ VoicePanelView.Listener

    override fun onVoiceKeyboard() = stopVoice(hide = true)

    override fun onVoiceToggle() {
        if (listening) stopVoice(hide = false) else startVoice()
    }

    override fun onVoiceDeleteWord() {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        val trailing = before.takeLastWhile { it.isWhitespace() }
        val word = before.dropLast(trailing.length).takeLastWhile { !it.isWhitespace() }
        val n = trailing.length + word.length
        if (n > 0) ic.deleteSurroundingText(n, 0)
    }
}
