package dev.ely.warp.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.inputmethod.InputMethodManager
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dictation, through Android's own recogniser.
 *
 * **Bound directly rather than launched as an intent.** `RecognizerIntent` is
 * five minutes of work and opens Google's own voice sheet — which looks like
 * Google's app, not this one, and hands back nothing but a string. Binding the
 * service gives the two things the design actually needs: the microphone level,
 * frame by frame, and control of when listening stops.
 *
 * The level is the whole reason. A waveform driven by a loop instead of by a
 * voice is obvious within a second, and it is most of what makes voice UI feel
 * cheap.
 *
 * Must be created and driven from the main thread — [SpeechRecognizer] enforces
 * that, and its failure when you do not is a silent one.
 */
class VoiceInput(private val context: Context) {

    sealed interface State {
        /** Nothing happening. */
        data object Idle : State

        /**
         * Recording. The level is meaningful only here.
         *
         * @param onDevice false when this had to fall back to the cloud, so the
         *   screen can say where the voice is going. Warp's rule is that things
         *   do not leave the phone, and the one time that cannot be honoured is
         *   the one time it has to be admitted.
         */
        data class Listening(val onDevice: Boolean) : State

        /**
         * Something went wrong, said in words a person can act on.
         *
         * Kept as state rather than thrown: a failed dictation is not an
         * exception, it is a thing that happened and has to be shown.
         */
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Every transition, so a misbehaving recogniser can be read rather than guessed at. */
    private fun moveTo(next: State) {
        if (_state.value != next) Log.i(TAG, "state: ${_state.value} -> $next")
        _state.value = next
    }

    /** Microphone level, 0..1, smoothed. Only moves while listening. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onText: ((String) -> Unit)? = null

    /** Set while cancelling, so a result arriving late is thrown away. */
    private var discarding = false

    fun start(onText: (String) -> Unit) {
        if (_state.value is State.Listening) return
        this.onText = onText
        discarding = false
        listen(onDevice = onDeviceUsable)
    }

    private fun listen(onDevice: Boolean) {
        _level.value = 0f

        val speech = runCatching { create(onDevice) }.getOrNull()
        if (speech == null) {
            moveTo(State.Failed("Voice input is not available on this phone."))
            return
        }

        usingOnDevice = onDevice
        recognizer = speech
        speech.setRecognitionListener(listener)
        speech.startListening(intent())
        moveTo(State.Listening(onDevice))
    }

    /** Stop and keep whatever was heard. */
    fun stop() {
        // `stopListening`, not `cancel`: it tells the recogniser the speech has
        // ended and to deliver what it has, where cancel throws it away.
        runCatching { recognizer?.stopListening() }
        _level.value = 0f
    }

    /** Stop and throw it away. */
    fun cancel() {
        discarding = true
        runCatching { recognizer?.cancel() }
        release()
        moveTo(State.Idle)
        _level.value = 0f
    }

    /** Let go of the microphone. Always call this when the screen goes away. */
    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        onText = null
    }

    /** Clears a failure once it has been read. */
    fun acknowledge() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    // ── the plumbing ─────────────────────────────────────────────────────

    /**
     * Whether the on-device engine is still worth trying.
     *
     * Turned off for the rest of the session the first time it says it cannot
     * speak the phone's language. It will not have learned one by the next tap,
     * and retrying it every time costs a visible flicker before every single
     * recording.
     */
    private var onDeviceUsable = Build.VERSION.SDK_INT >= 33

    private var usingOnDevice = false

    private fun create(onDevice: Boolean): SpeechRecognizer? = when {
        // On-device first, and that is a privacy decision rather than a
        // performance one: Warp's rule is that things do not leave the phone —
        // keys do not, conversations do not — and dictation should not quietly
        // be the exception that ships your voice to a server.
        //
        // But it is a preference, not a requirement. Preferring it *absolutely*
        // is what broke this on the first phone it ran on: the device's language
        // is Hebrew, the on-device model does not exist in Hebrew, and every tap
        // died in about a tenth of a second with error 12. Privacy that only
        // works in English is not a privacy feature, it is a language barrier.
        onDevice && Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

        SpeechRecognizer.isRecognitionAvailable(context) ->
            SpeechRecognizer.createSpeechRecognizer(context)

        else -> null
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        // The language the keyboard is on right now — not the phone's.
        //
        // The phone's locale was the obvious answer and it is the wrong one: a
        // system set to Hebrew belongs to somebody who also writes English, and
        // switching the keyboard is already how they say which one they mean.
        // Reading the keyboard makes the mic follow a decision that has been made
        // rather than asking for the same decision twice, and it is read at every
        // tap so switching between two sentences works.
        val tag = keyboardLanguage()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    /**
     * Which language the keyboard is set to, as a BCP-47 tag.
     *
     * Falls back to the phone's locale when the keyboard will not say — some
     * input methods report no subtype at all, and a recogniser given no language
     * picks its own, which is how Hebrew ends up being transcribed as English.
     */
    /**
     * The language to listen in.
     *
     * **Chosen, not detected, and that is a limitation rather than a
     * preference.** Following the keyboard was tried first and cannot work:
     * Gboard switches language internally with its own globe key and never
     * updates the subtype it reports to Android, so `currentInputMethodSubtype`
     * answers `he-IL` while you are typing English. There is no signal to read.
     *
     * So it is remembered instead — set once from the mic's long-press, and kept
     * until changed. Which is arguably better than following the keyboard would
     * have been: the language you dictate in is not always the language you type
     * in, and now it does not have to be.
     */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, null) ?: Locale.getDefault().toLanguageTag()
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).apply() }

    private val prefs = context.applicationContext
        .getSharedPreferences("warp_voice", Context.MODE_PRIVATE)

    private fun keyboardLanguage(): String = language.also { Log.i(TAG, "listening in $it") }

    private val listener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            // The API reports roughly -2..10 dB and it is jumpy. Normalised and
            // then eased towards the new value, because a bar chart that tracks
            // the raw number exactly looks like interference rather than a voice.
            val normalised = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _level.value = _level.value * 0.6f + normalised * 0.4f
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()

            if (!discarding && !text.isNullOrEmpty()) onText?.invoke(text)
            release()
            moveTo(State.Idle)
            _level.value = 0f
        }

        override fun onError(error: Int) {
            release()
            _level.value = 0f

            // Silence is not a failure. The recogniser reports it as one, but
            // stopping because nobody spoke is exactly what it should do, and
            // showing an error for it would mean an error every time somebody
            // changed their mind.
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            Log.i(TAG, "recogniser reported error $error")

            // The on-device engine does not speak this phone's language. Retry
            // once through the cloud rather than reporting a failure — the
            // person asked to talk, and "your language is unsupported" is not an
            // answer when a recogniser that speaks it is right there.
            val languageMissing = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            if (languageMissing && usingOnDevice && !discarding) {
                Log.i(TAG, "on-device has no ${keyboardLanguage()}; using the cloud")
                onDeviceUsable = false
                listen(onDevice = false)
                return
            }

            moveTo(when {
                discarding || quiet -> State.Idle
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    State.Failed("Warp needs permission to use the microphone.")
                error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    State.Failed("Voice needs a connection on this phone.")
                else -> State.Failed("Could not hear that. Try again.")
            })
        }

        override fun onEndOfSpeech() { _level.value = 0f }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "WarpVoice"
        private const val KEY_LANGUAGE = "language"

        /**
         * The languages worth offering, taken from the person's own keyboards.
         *
         * Every language enabled in any installed keyboard, which is a far better
         * list than one this app could invent: somebody who writes Hebrew and
         * English has already said so, twice, in a settings screen they have
         * already found.
         *
         * The phone's locale is added in case no keyboard reports anything, and
         * the whole thing is deduplicated by language rather than by region —
         * "English (UK)" and "English (US)" are one choice to a person dictating.
         */
        fun languages(context: Context): List<Locale> {
            val found = linkedMapOf<String, Locale>()

            fun add(tag: String?) {
                if (tag.isNullOrBlank()) return
                val locale = runCatching { Locale.forLanguageTag(tag.replace('_', '-')) }
                    .getOrNull() ?: return
                if (locale.language.isBlank()) return
                found.putIfAbsent(locale.language, locale)
            }

            add(Locale.getDefault().toLanguageTag())

            runCatching {
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm?.enabledInputMethodList?.forEach { ime ->
                    imm.getEnabledInputMethodSubtypeList(ime, true).forEach { subtype ->
                        add(subtype.languageTag.ifBlank { subtype.locale })
                    }
                }
            }

            // A floor, not a default. Somebody whose only keyboard reports
            // nothing still needs more than one thing in this list for it to be
            // worth opening.
            add("en-US")

            return found.values.toList()
        }

        /**
         * Whether this phone can do it at all.
         *
         * Checked so the mic can be *absent* rather than dead. MIUI has already
         * surprised this project twice, and a button that does nothing is worse
         * than a button that is not there.
         */
        fun isAvailable(context: Context): Boolean = runCatching {
            SpeechRecognizer.isRecognitionAvailable(context) ||
                (Build.VERSION.SDK_INT >= 33 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        }.getOrDefault(false)
    }
}
