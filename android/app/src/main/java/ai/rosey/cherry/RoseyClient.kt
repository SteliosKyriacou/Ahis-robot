package ai.rosey.cherry

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One sentence of Rosey's own voice: fetch [url] from the server; [text] is what it says. */
data class SpeechChunk(val url: String, val text: String)

/** Talks to the Rosey-Cherry brain server. Callbacks run on the main thread. */
class RoseyClient(private val baseUrl: () -> String, private val token: () -> String) {

    private val main = Handler(Looper.getMainLooper())

    private fun open(path: String, method: String, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL(baseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = readTimeoutMs
        val tok = token()
        if (tok.isNotBlank()) conn.setRequestProperty("X-Rosey-Token", tok)
        return conn
    }

    private fun postJson(path: String, body: JSONObject, readTimeoutMs: Int): String {
        val conn = open(path, "POST", readTimeoutMs)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code != 200) throw RuntimeException("Server said HTTP $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun chunks(arr: JSONArray?): List<SpeechChunk> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SpeechChunk(o.getString("url"), o.optString("text"))
        }
    }

    fun ask(
        text: String,
        onReply: (mood: String, reply: String, audio: List<SpeechChunk>) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        Thread {
            try {
                val body = JSONObject().put("text", text).put("session", "tablet")
                val json = JSONObject(postJson("/chat", body, 90_000))
                val mood = json.optString("mood", "happy")
                val reply = json.optString("reply", "")
                val audio = chunks(json.optJSONArray("audio"))
                main.post { onReply(mood, reply, audio) }
            } catch (e: Exception) {
                main.post { onError(e) }
            }
        }.start()
    }

    /** Ask the server for Rosey's voice saying a fixed phrase. Empty list = no server voice. */
    fun say(text: String, onResult: (List<SpeechChunk>) -> Unit) {
        Thread {
            val result = try {
                chunks(JSONArray(postJson("/say", JSONObject().put("text", text), 5_000)))
            } catch (e: Exception) {
                emptyList()
            }
            main.post { onResult(result) }
        }.start()
    }

    /** Download one chunk of speech (blocking; call from a background thread). */
    fun fetchAudio(path: String): ByteArray {
        val conn = open(path, "GET", 150_000)
        if (conn.responseCode != 200) throw RuntimeException("Speech HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes() }
    }

    /** Does the server have Rosey's own voice ready? (callback on main thread) */
    fun hasVoice(onResult: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                val conn = open("/health", "GET", 5_000)
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optBoolean("voice", false)
            } catch (e: Exception) {
                false
            }
            main.post { onResult(ok) }
        }.start()
    }
}
