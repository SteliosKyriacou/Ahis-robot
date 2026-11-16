package ai.rosey.cherry

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** A soft two-note "your turn" chime, generated in code (no sound files). */
object Chime {
    const val LENGTH_MS = 260L
    private const val RATE = 22050

    private val samples: ShortArray by lazy {
        val notes = listOf(784.0 to 0.0, 1175.0 to 0.11) // G5 then D6
        val n = (RATE * LENGTH_MS / 1000).toInt()
        ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((freq, start) in notes) {
                if (t >= start) {
                    val dt = t - start
                    val env = (1 - exp(-dt * 200)) * exp(-dt * 14) // quick attack, gentle decay
                    v += 0.22 * env * sin(2 * PI * freq * dt)
                }
            }
            (v * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    fun play() {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (e: Exception) {
            // A missing chime is never worth crashing over.
        }
    }
}
