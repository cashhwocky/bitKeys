package com.bitdroid.keys

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class VoicePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onVoiceCommit(text: String)
        fun onVoicePartial(text: String)
        fun onVoiceClose()
        fun onVoiceBackspace()
    }

    enum class State {
        PAUSED,
        LISTENING,
        NO_PERMISSION,
        ERROR
    }

    var listener: Listener? = null
    var theme: KeyboardTheme = Themes.byId(Storage.themeId)
        set(value) {
            field = value
            invalidate()
        }

    var state: State = State.PAUSED
        private set

    private var speechRecognizer: SpeechRecognizer? = null
    private var statusMessage: String = "Mic paused • Tap mic to talk"
    private var partialPreview: String = ""
    private var rmsLevel: Float = 0f

    // Strict hardware gate: when true, recognizer is destroyed and all callbacks are ignored
    @Volatile
    private var isMicLockedOff: Boolean = true

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val micCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val backBtnRect = RectF()
    private val micBtnRect = RectF()
    private val pauseToggleRect = RectF()
    private val backspaceBtnRect = RectF()

    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * scaledDensity

    private fun hasMicPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Immediately kills the microphone stream, cancels in-flight recognition,
     * and destroys the SpeechRecognizer instance so Android cuts mic access 100%.
     */
    fun pauseListeningImmediate() {
        isMicLockedOff = true
        state = State.PAUSED
        rmsLevel = 0f
        partialPreview = ""
        statusMessage = "Mic paused • Tap mic or Unpause to resume"

        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null

        listener?.onVoicePartial("")
        invalidate()
    }

    /**
     * Only called when user explicitly opens the voice panel or taps Unpause / Mic button.
     */
    fun resumeListeningExplicit() {
        if (!hasMicPermission()) {
            isMicLockedOff = true
            state = State.NO_PERMISSION
            statusMessage = "Microphone permission required in Android Settings"
            invalidate()
            return
        }

        // Clean up any stale instance first
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            isMicLockedOff = true
            state = State.ERROR
            statusMessage = "Speech recognition service unavailable"
            invalidate()
            return
        }

        isMicLockedOff = false
        state = State.LISTENING
        partialPreview = ""
        statusMessage = "Listening… Tap Pause or Mic to cut off immediately"
        invalidate()

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (isMicLockedOff) return
                    state = State.LISTENING
                    statusMessage = "Listening… Speak now"
                    invalidate()
                }

                override fun onBeginningOfSpeech() {
                    if (isMicLockedOff) return
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (isMicLockedOff || state != State.LISTENING) return
                    rmsLevel = max(0f, rmsdB).coerceAtMost(12f)
                    invalidate()
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (isMicLockedOff) return
                    rmsLevel = 0f
                    invalidate()
                }

                override fun onError(error: Int) {
                    if (isMicLockedOff) return
                    // When silence timeout or error happens, go to PAUSED state and shut down mic
                    pauseListeningImmediate()
                    statusMessage = "Mic paused • Tap mic to talk again"
                    invalidate()
                }

                override fun onResults(results: Bundle?) {
                    // Hard cutoff guard: ignore any late callback if user pressed Pause!
                    if (isMicLockedOff || state != State.LISTENING) return
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull()?.trim() ?: ""
                    if (best.isNotEmpty()) {
                        listener?.onVoicePartial("")
                        listener?.onVoiceCommit("$best ")
                    }
                    partialPreview = ""
                    // Keep listening continuously ONLY if user has NOT pressed pause
                    if (!isMicLockedOff && state == State.LISTENING) {
                        startRecognizerIntent()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Hard cutoff guard: ignore partial audio if user pressed Pause!
                    if (isMicLockedOff || state != State.LISTENING) return
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim() ?: ""
                    partialPreview = partial
                    listener?.onVoicePartial(partial)
                    invalidate()
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer = recognizer
            startRecognizerIntent()
        } catch (_: Exception) {
            pauseListeningImmediate()
            state = State.ERROR
            statusMessage = "Could not start microphone"
            invalidate()
        }
    }

    private fun startRecognizerIntent() {
        if (isMicLockedOff || state != State.LISTENING) return
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            pauseListeningImmediate()
        }
    }

    fun togglePauseResume() {
        if (state == State.LISTENING && !isMicLockedOff) {
            pauseListeningImmediate()
        } else {
            resumeListeningExplicit()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pauseListeningImmediate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val totalH = (dp(258f) * Storage.keyHeightScale).toInt()
        setMeasuredDimension(w, totalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat()
        val hf = h.toFloat()

        backBtnRect.set(dp(14f), dp(12f), dp(104f), dp(48f))
        backspaceBtnRect.set(wf - dp(68f), dp(12f), wf - dp(14f), dp(48f))

        val r = dp(40f)
        val cx = wf / 2f
        val cy = hf * 0.48f
        micBtnRect.set(cx - r, cy - r, cx + r, cy + r)

        val pillW = dp(164f)
        val pillH = dp(40f)
        pauseToggleRect.set(
            cx - pillW / 2f,
            hf - pillH - dp(16f),
            cx + pillW / 2f,
            hf - dp(16f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgPaint.color = theme.boardBg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Back to Keyboard button
        cardPaint.color = theme.funcKeyBg
        canvas.drawRoundRect(backBtnRect, dp(18f), dp(18f), cardPaint)
        titlePaint.color = theme.keyText
        titlePaint.textSize = sp(13.5f)
        canvas.drawText("⌨ ABC", backBtnRect.centerX(), backBtnRect.centerY() + dp(5f), titlePaint)

        // Backspace button
        canvas.drawRoundRect(backspaceBtnRect, dp(18f), dp(18f), cardPaint)
        canvas.drawText("⌫", backspaceBtnRect.centerX(), backspaceBtnRect.centerY() + dp(5f), titlePaint)

        val isActive = (state == State.LISTENING && !isMicLockedOff)

        // Status / Live Partial Text
        titlePaint.textSize = sp(15f)
        titlePaint.color = if (isActive) theme.accentBg else theme.subText
        val topDisplay = if (isActive && partialPreview.isNotEmpty()) {
            "“${partialPreview.takeLast(38)}”"
        } else {
            statusMessage
        }
        canvas.drawText(topDisplay, width / 2f, dp(72f), titlePaint)

        // Pulsing RMS halo when actively listening
        val cx = micBtnRect.centerX()
        val cy = micBtnRect.centerY()
        val baseRadius = micBtnRect.width() / 2f

        if (isActive) {
            ringPaint.color = theme.accentBg
            ringPaint.alpha = 110
            val pulseRadius = baseRadius + dp(8f) + (rmsLevel * dp(1.8f))
            canvas.drawCircle(cx, cy, pulseRadius, ringPaint)
        }

        // Main Center Mic Button
        micCirclePaint.color = if (isActive) Color.parseColor("#E24C4B") else theme.keyBg
        canvas.drawCircle(cx, cy, baseRadius, micCirclePaint)

        titlePaint.color = if (isActive) Color.WHITE else theme.keyText
        titlePaint.textSize = sp(26f)
        canvas.drawText(if (isActive) "⏸" else "🎤", cx, cy + dp(9f), titlePaint)

        // Bottom Explicit Pause / Unpause Pill Button
        cardPaint.color = if (isActive) Color.parseColor("#3B1D22") else theme.accentBg
        canvas.drawRoundRect(pauseToggleRect, dp(20f), dp(20f), cardPaint)

        titlePaint.color = if (isActive) Color.parseColor("#FFB4AB") else theme.accentText
        titlePaint.textSize = sp(14f)
        val actionLabel = if (isActive) "⏸ Pause Mic Now" else "🎤 Unpause Mic"
        canvas.drawText(
            actionLabel,
            pauseToggleRect.centerX(),
            pauseToggleRect.centerY() + dp(5f),
            titlePaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y

            if (backBtnRect.contains(x, y)) {
                pauseListeningImmediate()
                listener?.onVoiceClose()
                return true
            }

            if (backspaceBtnRect.contains(x, y)) {
                listener?.onVoiceBackspace()
                return true
            }

            if (micBtnRect.contains(x, y) || pauseToggleRect.contains(x, y)) {
                togglePauseResume()
                return true
            }
        }
        return true
    }
}
