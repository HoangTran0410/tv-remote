package dev.tvremote.phone.picker

/**
 * What the picker's address box was given. Support logs showed "42", "192 168 100" and a Downloader code (2397346) typed there
 * and dialed as they were, so the box now says what is wrong instead of connecting to nothing.
 */
sealed class TypedAddress {
    data class Ok(val host: String, val port: Int = 0) : TypedAddress()
    object Empty : TypedAddress()
    /** 6-8 digits with no dots: a Downloader code, typed on the TV in Downloader, not here. */
    object DownloaderCode : TypedAddress()
    object Bad : TypedAddress()

    companion object {
        private val SEP = Regex("[\\s,;.:]+")
        private val DIGITS = Regex("\\d{1,5}")
        private val LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
        private val NAME = Regex("$LABEL(?:\\.$LABEL)+")
        private val NAME_PORT = Regex("(.+?)(?::(\\d{1,5}))?")
        private val V6 = Regex("\\[([0-9A-Fa-f:.]+)](?::(\\d{1,5}))?")

        /** 0 when no port was typed, null when the one typed is out of range. */
        private fun port(s: String?): Int? = if (s.isNullOrEmpty()) 0 else s.toIntOrNull()?.takeIf { it in 1..65535 }

        fun parse(raw: String): TypedAddress {
            val t = raw.trim()
            if (t.isEmpty()) return Empty
            V6.matchEntire(t)?.let { m ->                              // [fe80::1] or [fe80::1]:5555
                val p = port(m.groupValues[2]) ?: return Bad
                return if (m.groupValues[1].count { it == ':' } >= 2) Ok(m.groupValues[1], p) else Bad
            }
            if (t.all { it.isDigit() || it in " .,;:" }) {            // numbers: an IPv4 address, a port after it, or a code
                if (t.all { it.isDigit() } && t.length in 6..8) return DownloaderCode
                val parts = t.split(SEP).filter { it.isNotEmpty() }
                if (parts.size != 4 && parts.size != 5) return Bad
                if (!parts.all { DIGITS.matches(it) }) return Bad
                val octets = parts.take(4).map { it.toInt() }
                if (octets.any { it > 255 } || octets[0] == 0) return Bad
                val p = port(parts.getOrNull(4)) ?: return Bad
                return Ok(octets.joinToString("."), p)
            }
            val m = NAME_PORT.matchEntire(t) ?: return Bad             // tv.local, my-tv.tailnet.ts.net, optionally :port
            val p = port(m.groupValues[2]) ?: return Bad
            val host = m.groupValues[1]
            return if (NAME.matches(host) && host.any { it.isLetter() }) Ok(host, p) else Bad
        }
    }
}
