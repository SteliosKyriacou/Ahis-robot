package ai.rosey.cherry

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Rosey-Cherry's main (and only) screen.
 *
 * Loop:  WAKE (quietly listening for her name)
 *          -> heard "Rosey-Cherry ..." -> QUESTION (listening) -> THINKING (server) -> SPEAKING
 *          -> QUESTION again for a follow-up -> back to WAKE if Ahilya says nothing.
 * Tapping the face also wakes her up. Holding the face for 3 seconds opens grown-up settings.
 */
class MainActivity : Activity() {

    private enum class State { WAKE, QUESTION, THINKING, SPEAKING, PAUSED }

    private val tag = "Rosey"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var face: FaceView
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("rosey", Context.MODE_PRIVATE) }
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var state = State.PAUSED
    private var greeted = false
    private var preferOffline = true
    private var musicMuted = false
    private val afterSpeech = mutableMapOf<String, () -> Unit>()
    private var utteranceCounter = 0

    private val client = RoseyClient(
        baseUrl = { prefs.getString("server_url", "") ?: "" },
        token = { prefs.getString("token", "") ?: "" },
    )

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyConfigFromIntent(intent)

        face = FaceView(this)
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#B07A88"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 24)
        }
        val root = FrameLayout(this)
        root.addView(face, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        setupTouch()

        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setPitch(1.35f)
                tts?.setSpeechRate(0.92f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = finishedSpeaking(id)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = finishedSpeaking(id)
                })
                ttsReady = true
                main.post { maybeGreet() }
            } else {
                setStatus("Text-to-speech is not available on this tablet.")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyConfigFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        start()
    }

    override fun onPause() {
        super.onPause()
        state = State.PAUSED
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        tts?.stop()
        setMusicMuted(false)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        setMusicMuted(false)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasMicPermission()) start() else setStatus("Rosey-Cherry needs the microphone to hear you.")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun start() {
        if (serverUrl().isBlank()) {
            showSettings()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("No speech recognition on this tablet. Install/enable the Google app.")
            return
        }
        if (recognizer == null) createRecognizer()
        if (ttsReady && !greeted) maybeGreet() else if (ttsReady) listenForWakeWord()
    }

    private fun maybeGreet() {
        if (state != State.PAUSED || greeted || !hasMicPermission() || serverUrl().isBlank()) return
        if (recognizer == null) return
        greeted = true
        speak("happy", "Hi Ahilya! I'm Rosey-Cherry! Say my name whenever you want to talk to me.") {
            listenForWakeWord()
        }
    }

    // ---------------------------------------------------------------- listening

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
    }

    private fun recognizerIntent(forQuestion: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (forQuestion) {
                // Little kids pause a lot while thinking; give her time.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
        }

    private fun listenForWakeWord() {
        state = State.WAKE
        face.activity = FaceView.Activity.IDLE
        face.mood = "happy"
        setStatus("Say \"Rosey-Cherry!\" or tap my face")
        // Hide the recognizer's start "beep" while waiting for her name.
        setMusicMuted(true)
        startRecognizer(forQuestion = false)
    }

    private fun listenForQuestion() {
        state = State.QUESTION
        face.activity = FaceView.Activity.LISTENING
        setStatus("Listening...")
        setMusicMuted(false)
        startRecognizer(forQuestion = true)
    }

    private fun startRecognizer(forQuestion: Boolean) {
        val r = recognizer ?: return
        try {
            r.startListening(recognizerIntent(forQuestion))
        } catch (e: Exception) {
            Log.w(tag, "startListening failed", e)
            createRecognizer()
            retryListening(1000)
        }
    }

    private fun retryListening(delayMs: Long) {
        main.postDelayed({
            when (state) {
                State.WAKE -> listenForWakeWord()
                State.QUESTION -> listenForQuestion()
                else -> {}
            }
        }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.i(tag, "heard ($state): $heard")
            when (state) {
                State.WAKE -> onWakeResults(heard)
                State.QUESTION -> {
                    val text = heard.firstOrNull()?.trim().orEmpty()
                    if (text.isEmpty()) listenForWakeWord() else ask(stripName(text).ifBlank { text })
                }
                else -> {}
            }
        }

        override fun onError(error: Int) {
            if (state != State.WAKE && state != State.QUESTION) return
            Log.d(tag, "recognizer error $error in $state")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    // Nothing said. In a conversation, quietly go back to waiting for her name.
                    if (state == State.QUESTION) listenForWakeWord() else retryListening(150)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, 11 /* server disconnected */ -> {
                    createRecognizer(); retryListening(600)
                }
                12, 13 /* language not supported / unavailable offline */ -> {
                    preferOffline = false; retryListening(300)
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    retryListening(2000)
                else -> retryListening(800)
            }
        }
    }

    private fun onWakeResults(heard: List<String>) {
        val match = heard.firstOrNull { NAME.containsMatchIn(it) }
        if (match == null) {
            retryListening(50)
            return
        }
        setMusicMuted(false)
        val question = stripName(match)
        if (question.split(Regex("\\s+")).count { it.isNotBlank() } >= 2) {
            ask(question)
        } else {
            speak("excited", WAKE_REPLIES.random()) { listenForQuestion() }
        }
    }

    // ---------------------------------------------------------------- thinking + talking

    private fun ask(text: String) {
        state = State.THINKING
        recognizer?.cancel()
        setMusicMuted(false)
        face.activity = FaceView.Activity.THINKING
        face.mood = "curious"
        setStatus("Thinking about: \"$text\"")
        client.ask(
            text,
            onReply = { mood, reply ->
                if (state == State.PAUSED) return@ask
                if (reply.isBlank()) {
                    speak("happy", "Hmm, can you ask me that again?") { listenForQuestion() }
                } else {
                    speak(mood, reply) { listenForQuestion() }
                }
            },
            onError = { e ->
                Log.w(tag, "server error", e)
                if (state == State.PAUSED) return@ask
                speak(
                    "sad",
                    "Oh no, I can't reach my thinking cloud right now. Can you ask a grown-up to help me?",
                ) { listenForWakeWord() }
            },
        )
    }

    private fun speak(mood: String, text: String, then: () -> Unit) {
        state = State.SPEAKING
        recognizer?.cancel()
        setMusicMuted(false)
        face.mood = mood
        face.activity = FaceView.Activity.TALKING
        setStatus("")
        val engine = tts
        if (!ttsReady || engine == null) {
            main.postDelayed({ doneTalking(then) }, 1500)
            return
        }
        val id = "u${utteranceCounter++}"
        afterSpeech[id] = then
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun finishedSpeaking(id: String?) {
        main.post {
            val then = afterSpeech.remove(id ?: return@post) ?: return@post
            doneTalking(then)
        }
    }

    private fun doneTalking(then: () -> Unit) {
        if (state == State.PAUSED) return
        face.activity = FaceView.Activity.IDLE
        // Keep the mood face for a moment, then go back to a gentle smile.
        main.postDelayed({ if (state != State.SPEAKING) face.mood = "happy" }, 2500)
        then()
    }

    // ---------------------------------------------------------------- touch + settings

    private fun setupTouch() {
        val openSettings = Runnable { showSettings() }
        face.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> main.postDelayed(openSettings, 3000)
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(openSettings)
                    if (ev.eventTime - ev.downTime < 600) onFaceTapped()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(openSettings)
            }
            true
        }
    }

    private fun onFaceTapped() {
        when (state) {
            State.WAKE -> {
                setMusicMuted(false)
                speak("excited", WAKE_REPLIES.random()) { listenForQuestion() }
            }
            State.SPEAKING -> {
                // Let her interrupt a long answer.
                tts?.stop()
                afterSpeech.clear()
                face.activity = FaceView.Activity.IDLE
                listenForQuestion()
            }
            else -> {}
        }
    }

    private fun showSettings() {
        val previous = state
        state = State.PAUSED
        recognizer?.cancel()
        tts?.stop()
        afterSpeech.clear()
        setMusicMuted(false)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val urlBox = EditText(this).apply {
            hint = "http://192.168.1.50:8765"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(serverUrl())
        }
        val tokenBox = EditText(this).apply {
            hint = "Token (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("token", ""))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = "Brain server address" })
            addView(urlBox)
            addView(TextView(context).apply { text = "Server token" })
            addView(tokenBox)
        }
        AlertDialog.Builder(this)
            .setTitle("Rosey-Cherry settings (grown-ups)")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("server_url", urlBox.text.toString().trim())
                    .putString("token", tokenBox.text.toString().trim())
                    .apply()
                start()
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (previous == State.PAUSED) start() else listenForWakeWord()
            }
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun applyConfigFromIntent(intent: Intent?) {
        val url = intent?.getStringExtra("server_url")
        val token = intent?.getStringExtra("token")
        if (url != null || token != null) {
            prefs.edit().apply {
                if (url != null) putString("server_url", url.trim())
                if (token != null) putString("token", token.trim())
            }.apply()
        }
    }

    private fun serverUrl() = prefs.getString("server_url", "") ?: ""

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun setMusicMuted(mute: Boolean) {
        if (mute == musicMuted) return
        try {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0,
            )
            musicMuted = mute
        } catch (e: Exception) {
            Log.w(tag, "could not change mute", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    companion object {
        /** Ways speech recognition tends to spell "Rosey-Cherry" when a 4-year-old says it. */
        val NAME = Regex(
            "\\b(rosey|rosie|rosy|rosi|rosee|rose|roses|rosa|cherry|cherie|cheri|sherry|" +
                "roseycherry|rosiecherry|rosycherry)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val GREETING = Regex("^(hey|hi|hello|hiya|ok|okay|oh)\\b[\\s,.!]*", RegexOption.IGNORE_CASE)

        val WAKE_REPLIES = listOf(
            "Yes, Ahilya?",
            "I'm listening!",
            "Hi Ahilya! What shall we talk about?",
            "Hello! I'm all ears. Well, robot ears!",
        )

        fun stripName(text: String): String {
            var t = NAME.replace(text.replace('-', ' '), " ")
            t = t.replace(Regex("\\s+"), " ").trim(' ', ',', '.', '!')
            t = GREETING.replace(t, "").trim(' ', ',', '.', '!')
            return t
        }
    }
}
