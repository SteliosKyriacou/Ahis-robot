package ai.rosey.cherry

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Plays Rosey's own (server-made) voice: downloads the sentence chunks in order in the background
 * and starts playing the first one as soon as it arrives, while the rest are still being made.
 */
class VoicePlayer(private val context: Context, private val client: RoseyClient) {

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    private inner class Session(
        val chunks: List<SpeechChunk>,
        val onDone: () -> Unit,
        val onFail: (remainingText: String) -> Unit,
        val onSound: (Boolean) -> Unit,
    ) {
        val files = arrayOfNulls<File>(chunks.size)
        var failedAt = -1
        var next = 0
        var player: MediaPlayer? = null
        var finished = false

        fun current() = session === this && !finished

        fun chunkReady(i: Int, f: File) {
            if (!current()) { f.delete(); return }
            files[i] = f
            if (player == null && i == next) playNext()
        }

        fun chunkFailed(i: Int) {
            if (!current()) return
            failedAt = i
            if (player == null && i == next) fail()
        }

        fun playNext() {
            if (!current()) return
            if (next >= chunks.size) { finish(); onDone(); return }
            val f = files[next]
            if (f == null) {
                if (failedAt == next) fail() else onSound(false)
                return // wait for the download
            }
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(f.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        player = null
                        f.delete()
                        next++
                        playNext()
                    }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        player = null
                        fail()
                        true
                    }
                    prepare()
                    start()
                }
                onSound(true)
            } catch (e: Exception) {
                Log.w("Rosey", "voice playback failed", e)
                player?.release()
                player = null
                fail()
            }
        }

        fun fail() {
            if (!current()) return
            val rest = chunks.drop(next).joinToString(" ") { it.text }
            finish()
            onFail(rest)
        }

        fun finish() {
            finished = true
            player?.release()
            player = null
            files.forEach { it?.delete() }
            if (session === this) session = null
        }
    }

    /** Speak [chunks]. [onFail] gets the text not yet spoken so the tablet's own voice can finish it. */
    fun play(
        chunks: List<SpeechChunk>,
        onDone: () -> Unit,
        onFail: (remainingText: String) -> Unit,
        onSound: (playing: Boolean) -> Unit = {},
    ) {
        stop()
        val s = Session(chunks, onDone, onFail, onSound)
        session = s
        Thread {
            for (i in chunks.indices) {
                if (!s.current()) return@Thread
                try {
                    val bytes = client.fetchAudio(chunks[i].url)
                    val f = File(context.cacheDir, "speech_${System.identityHashCode(s)}_$i.wav")
                    f.writeBytes(bytes)
                    main.post { s.chunkReady(i, f) }
                } catch (e: Exception) {
                    Log.w("Rosey", "voice download failed", e)
                    main.post { s.chunkFailed(i) }
                    return@Thread
                }
            }
        }.start()
    }

    /** Stop talking now (no callbacks). */
    fun stop() {
        session?.finish()
        session = null
    }
}
