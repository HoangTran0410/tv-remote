package dev.tvremote.core.web
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * [Telegram.Api] over HTTPS to api.telegram.org. [base] is only ever another address in a debug build or a test (a fake
 * server standing in for Telegram: TV Setup reads it from a file only when the build is debuggable).
 * The token is in the address of every call, so an error from here never shows the address: it says the method and what went wrong.
 */
class TelegramHttp(private val base: String = "https://api.telegram.org") : Telegram.Api {
    @Volatile private var live: HttpURLConnection? = null

    override fun call(token: String, method: String, params: JSONObject, timeoutS: Int): Telegram.Reply =
        exchange(token, method, "application/json", params.toString().toByteArray(Charsets.UTF_8), timeoutS)

    override fun photo(token: String, chat: Long, png: ByteArray, caption: String): Telegram.Reply {
        val b = "tvr" + java.lang.Long.toHexString(SecureRandom().nextLong())
        val out = ByteArrayOutputStream()
        fun part(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        part("--$b\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chat\r\n")
        if (caption.isNotEmpty()) part("--$b\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n$caption\r\n")
        part("--$b\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"tv.png\"\r\nContent-Type: image/png\r\n\r\n")
        out.write(png)
        part("\r\n--$b--\r\n")
        return exchange(token, "sendPhoto", "multipart/form-data; boundary=$b", out.toByteArray(), 60)
    }

    override fun abort() { live?.disconnect() }

    private fun exchange(token: String, method: String, type: String, body: ByteArray, timeoutS: Int): Telegram.Reply {
        val c = URL("$base/bot$token/$method").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = timeoutS * 1000
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.setFixedLengthStreamingMode(body.size)
            if (method == "getUpdates") live = c                    // only the long poll is cut by abort(): a send from another thread (an alert) must finish
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Telegram.Reply(code, try { JSONObject(text) } catch (e: Exception) { JSONObject() })
        } catch (e: IOException) {
            throw IOException("$method: " + (e.javaClass.simpleName) + (e.message?.let { " " + it.replace(token, "…") } ?: ""))
        } finally {
            if (live === c) live = null
            c.disconnect()
        }
    }
}
