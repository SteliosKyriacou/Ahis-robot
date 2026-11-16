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
import android.speech.tts.Voice
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
    // Google's online recognizer is much better with small children's voices than the offline one.
    private var preferOffline = false
    private var questionDeadline = 0L
    private var musicMuted = false
    private val afterSpeech = mutableMapOf<String, () -> Unit>()
    private var utteranceCounter = 0

    private val client = RoseyClient(
        baseUrl = { prefs.getString("server_url", "") ?: "" },
        token = { prefs.getString("token", "") ?: "" },
    )
    private val voice by lazy { VoicePlayer(this, client) }
    private var serverVoice = false
    private var speechToken = 0

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

        initTts { main.post { maybeGreet() } }
    }

    /** Start the tablet's own speech engine (Google's by default: its voices sound most natural). */
    private fun initTts(onReady: () -> Unit = {}) {
        ttsReady = false
        tts?.shutdown()
        val wanted = prefs.getString("tts_engine", null) ?: GOOGLE_TTS
        val engine = if (isInstalled(wanted)) wanted else null
        var created: TextToSpeech? = null
        created = TextToSpeech(this, { result ->
            if (result == TextToSpeech.SUCCESS && created === tts) {
                created?.let { setupTtsVoice(it) }
                ttsReady = true
                onReady()
            } else if (result != TextToSpeech.SUCCESS) {
                setStatus("Text-to-speech is not available on this tablet.")
            }
        }, engine)
        tts = created
    }

    private fun setupTtsVoice(engine: TextToSpeech) {
        engine.setPitch(prefs.getFloat("tts_pitch", 1.1f))
        engine.setSpeechRate(prefs.getFloat("tts_rate", 0.95f))
        val voices = try { engine.voices.orEmpty() } catch (e: Exception) { emptySet() }
        val saved = prefs.getString("tts_voice", null)
        val voice = voices.firstOrNull { it.name == saved } ?: bestVoice(voices)
        if (voice != null) engine.voice = voice else engine.language = Locale.getDefault()
        Log.i(tag, "tablet voice: ${engine.defaultEngine} / ${voice?.name}")
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finishedSpeaking(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finishedSpeaking(id)
        })
    }

    /** English voices, best first: installed, high quality, same country as the tablet. */
    private fun englishVoices(engine: TextToSpeech): List<Voice> {
        val voices = try { engine.voices.orEmpty() } catch (e: Exception) { emptySet() }
        val country = Locale.getDefault().country
        return voices
            .filter { it.locale.language == "en" && !it.features.orEmpty().contains("notInstalled") }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenByDescending { it.locale.country == country }
                    .thenBy { it.locale.country }
                    .thenBy { it.name }
            )
    }

    private fun bestVoice(voices: Set<Voice>): Voice? {
        val country = Locale.getDefault().country
        return voices
            .filter { it.locale.language == "en" && !it.features.orEmpty().contains("notInstalled") }
            .maxWithOrNull(
                compareBy<Voice> { it.locale.country == country }
                    .thenBy { it.quality }
                    .thenBy { !it.isNetworkConnectionRequired }
            )
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) {
        false
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
        voice.stop()
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
        client.hasVoice { serverVoice = it; Log.i(tag, "server voice: $it") }
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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
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

    /** Listen for what Ahilya says. Keeps listening through pauses for [QUESTION_WINDOW_MS]. */
    private fun listenForQuestion() {
        state = State.QUESTION
        questionDeadline = System.currentTimeMillis() + QUESTION_WINDOW_MS
        face.activity = FaceView.Activity.LISTENING
        setStatus("Listening...")
        setMusicMuted(false)
        // A soft "ding" tells her it's her turn; start listening right after so it isn't heard.
        Chime.play()
        main.postDelayed({ if (state == State.QUESTION) startRecognizer(forQuestion = true) }, Chime.LENGTH_MS + 60)
    }

    /** Nothing heard yet: keep listening until the window is over, then go back to waiting for her name. */
    private fun keepListeningOrGiveUp() {
        if (System.currentTimeMillis() < questionDeadline) {
            main.postDelayed({ if (state == State.QUESTION) startRecognizer(forQuestion = true) }, 80)
        } else {
            listenForWakeWord()
        }
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
                State.QUESTION -> startRecognizer(forQuestion = true)
                else -> {}
            }
        }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // Roughly -2 dB (quiet) .. 10 dB (loud) -> 0..1, so the glow grows when she talks.
            face.voiceLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            face.voiceLevel = 0f
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.i(tag, "heard ($state): $heard")
            when (state) {
                State.WAKE -> onWakeResults(heard)
                State.QUESTION -> {
                    val text = heard.firstOrNull()?.trim().orEmpty()
                    if (text.isEmpty()) keepListeningOrGiveUp() else ask(stripName(text).ifBlank { text })
                }
                else -> {}
            }
        }

        override fun onError(error: Int) {
            if (state != State.WAKE && state != State.QUESTION) return
            Log.d(tag, "recognizer error $error in $state")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    // Nothing said (yet). Little kids take a while to start: keep listening a bit longer.
                    if (state == State.QUESTION) keepListeningOrGiveUp() else retryListening(100)
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
            onReply = { mood, reply, audio ->
                if (state == State.PAUSED) return@ask
                serverVoice = audio.isNotEmpty()  // the voice may come online (or go away) while running
                if (reply.isBlank()) {
                    speak("happy", "Hmm, can you ask me that again?") { listenForQuestion() }
                } else {
                    speakReply(mood, reply, audio) { listenForQuestion() }
                }
            },
            onError = { e ->
                Log.w(tag, "server error", e)
                if (state == State.PAUSED) return@ask
                // The server is unreachable, so this one is always in the tablet's own voice.
                startTalking("sad")
                ttsSpeak(
                    "Oh no, I can't reach my thinking cloud right now. Can you ask a grown-up to help me?",
                ) { listenForWakeWord() }
            },
        )
    }

    private fun startTalking(mood: String) {
        state = State.SPEAKING
        recognizer?.cancel()
        setMusicMuted(false)
        face.mood = mood
        face.activity = FaceView.Activity.TALKING
        setStatus("")
    }

    /** Say a fixed phrase, in Rosey's own (server) voice when there is one. */
    private fun speak(mood: String, text: String, then: () -> Unit) {
        startTalking(mood)
        if (!serverVoice || !useServerVoice()) {
            ttsSpeak(text, then)
            return
        }
        val token = ++speechToken
        client.say(text) { chunks ->
            if (token != speechToken || state != State.SPEAKING) return@say
            if (chunks.isEmpty()) ttsSpeak(text, then) else playChunks(chunks, then)
        }
    }

    /** Say Rosey's answer: server voice chunks if the server made them, else the tablet's voice. */
    private fun speakReply(mood: String, text: String, audio: List<SpeechChunk>, then: () -> Unit) {
        startTalking(mood)
        if (audio.isEmpty() || !useServerVoice()) ttsSpeak(text, then) else playChunks(audio, then)
    }

    private fun playChunks(chunks: List<SpeechChunk>, then: () -> Unit) {
        // Only move the mouth while sound is actually playing; show thinking dots while waiting.
        face.activity = FaceView.Activity.THINKING
        voice.play(
            chunks,
            onDone = { doneTalking(then) },
            onFail = { rest ->
                face.activity = FaceView.Activity.TALKING
                if (rest.isBlank()) doneTalking(then) else ttsSpeak(rest, then)
            },
            onSound = { playing ->
                if (state == State.SPEAKING) {
                    face.activity = if (playing) FaceView.Activity.TALKING else FaceView.Activity.THINKING
                }
            },
        )
    }

    private fun ttsSpeak(text: String, then: () -> Unit) {
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
                voice.stop()
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
        voice.stop()
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
            addView(Button(context).apply {
                text = "Choose tablet voice…"
                setOnClickListener { showVoicePicker() }
            })
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

    /**
     * Grown-up voice picker. First choice is the cloned voice made by the server; the rest are the
     * tablet's own voices. Tap one to hear it, then "Use this voice". The neutral button swaps
     * between the Google and Samsung speech engines.
     */
    private fun showVoicePicker() {
        val engine = tts ?: return
        if (!ttsReady) return
        val voices = englishVoices(engine)
        val engineName = if (engine.defaultEngine == GOOGLE_TTS) "Google" else "Samsung"
        val labels = listOf(
            "★ My cloned voice (made by the server)" + if (serverVoice) "" else "\n(not available right now)"
        ) + voices.mapIndexed { i, v ->
            val country = v.locale.displayCountry.ifBlank { v.locale.displayName }
            val quality = when {
                v.quality >= Voice.QUALITY_VERY_HIGH -> "very high quality"
                v.quality >= Voice.QUALITY_HIGH -> "high quality"
                else -> "normal quality"
            }
            val online = if (v.isNetworkConnectionRequired) ", needs internet" else ""
            "$engineName voice ${i + 1}: English ($country), $quality$online"
        }
        val current = if (useServerVoice()) 0
        else 1 + voices.indexOfFirst { it.name == engine.voice?.name }.coerceAtLeast(0)
        var chosen = current

        fun stopPreview() {
            engine.stop()
            voice.stop()
        }

        AlertDialog.Builder(this)
            .setTitle("Rosey's voice – tap to listen")
            .setSingleChoiceItems(labels.toTypedArray(), current) { _, which ->
                chosen = which
                stopPreview()
                if (which == 0) {
                    client.say(PREVIEW) { chunks ->
                        if (chunks.isEmpty()) {
                            setStatus("The cloned voice isn't available on the server right now.")
                        } else {
                            voice.play(chunks, onDone = {}, onFail = {})
                        }
                    }
                } else {
                    engine.voice = voices[which - 1]
                    engine.speak(PREVIEW, TextToSpeech.QUEUE_FLUSH, null, "preview")
                }
            }
            .setPositiveButton("Use this voice") { _, _ ->
                stopPreview()
                if (chosen == 0) {
                    prefs.edit().putBoolean("use_server_voice", true).apply()
                    setupTtsVoice(engine) // tablet voice stays as the backup
                } else {
                    prefs.edit()
                        .putBoolean("use_server_voice", false)
                        .putString("tts_voice", voices[chosen - 1].name)
                        .apply()
                    engine.voice = voices[chosen - 1]
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                stopPreview()
                setupTtsVoice(engine)
            }
            .setNeutralButton(if (engineName == "Google") "Samsung voices" else "Google voices") { _, _ ->
                stopPreview()
                val other = if (engineName == "Google") SAMSUNG_TTS else GOOGLE_TTS
                prefs.edit().putString("tts_engine", other).remove("tts_voice").apply()
                initTts { main.post { showVoicePicker() } }
            }
            .show()
    }

    private fun useServerVoice() = prefs.getBoolean("use_server_voice", true)

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
            "\\b(ro[sz](e|ey|ie|y|i|ee|a|ies|ys|es|ey's|ie's)?|roxy|rosary|cherr?(y|ie|i|ies|ys)|cheri[es]?|" +
                "sherr?(y|ie|i)|chary|jerry|cheery|ro[sz](ey|ie|y)cherr?(y|ie|i))\\b",
            RegexOption.IGNORE_CASE,
        )
        const val QUESTION_WINDOW_MS = 10_000L
        private val GREETING = Regex("^(hey|hi|hello|hiya|ok|okay|oh)\\b[\\s,.!]*", RegexOption.IGNORE_CASE)

        const val GOOGLE_TTS = "com.google.android.tts"
        const val SAMSUNG_TTS = "com.samsung.SMT"
        const val PREVIEW = "Hi Ahilya! I'm Rosey-Cherry. Shall I tell you a story about a little star?"

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
