package ai.rosey.cherry

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the Rosey-Cherry brain server. Callbacks run on the main thread. */
class RoseyClient(private val baseUrl: () -> String, private val token: () -> String) {

    private val main = Handler(Looper.getMainLooper())

    fun ask(text: String, onReply: (mood: String, reply: String) -> Unit, onError: (Exception) -> Unit) {
        Thread {
            try {
                val url = URL(baseUrl().trimEnd('/') + "/chat")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val tok = token()
                    if (tok.isNotBlank()) setRequestProperty("X-Rosey-Token", tok)
                }
                val body = JSONObject().put("text", text).put("session", "tablet").toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) throw RuntimeException("Server said HTTP $code")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val mood = json.optString("mood", "happy")
                val reply = json.optString("reply", "")
                main.post { onReply(mood, reply) }
            } catch (e: Exception) {
                main.post { onError(e) }
            }
        }.start()
    }
}
