package dev.tvremote.setup.voice

import java.text.Normalizer

/**
 * What TV Setup and TV Setup Voice agree on, ONE copy that both compile (as KeysProtocol). TV Setup asks the add-on through Android's
 * own SpeechRecognizer API, with [WORDS]: its commands as said, one entry per way ("said" or "said=written", Phrases.spoken); the add-on
 * listens for those only (a short list of whole phrases is what lets a small model run in real time on a TV box and be right).
 */
object VoiceProtocol {
    const val ADDON = "dev.tvremote.setup.voice"
    const val SERVICE = "dev.tvremote.setup.voice.VoiceService"
    /** Unpacks the model (and loads nothing): sent by TV Setup over ADB right after installing, so the first press does not wait for it. */
    const val WARM = "dev.tvremote.setup.voice.Warm"
    /** ArrayList<String> of folded words; every word of the model that folds to one of them is listened for. */
    const val WORDS = "dev.tvremote.voice.WORDS"

    /** Voice.fold: accents off, đ to d, lower case, words only. */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").replace('đ', 'd').replace('Đ', 'd')
        .lowercase().replace(SIGNS, " ").replace(SPACES, " ").trim()
    private val MARKS = Regex("\\p{M}+")                              // made once: the add-on folds the model's 19,500 words
    private val SIGNS = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")

    private val UNIT = mapOf("một" to 1, "hai" to 2, "ba" to 3, "bốn" to 4, "năm" to 5, "sáu" to 6, "bảy" to 7, "bẩy" to 7, "tám" to 8, "chín" to 9)
    private val AFTER_TEN = mapOf("mốt" to 1, "tư" to 4, "lăm" to 5, "nhăm" to 5)

    /**
     * Number words to digits, as Google's recognizer writes them ("ba mươi phút" -> "30 phút", "kênh mười bốn" -> "kênh 14"): the commands
     * read digits. Up to 999, the words as said, with their accents ("sau", after, is not "sáu", six): "trăm", "mươi" / "mười",
     * "linh" / "lẻ", "mốt" / "tư" / "lăm" after a ten. "một hai ba" stays three numbers.
     */
    fun digits(text: String): String {
        val w = text.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            var total = 0; var cur = 0; var tens = false; var j = i
            while (j < w.size) {
                val x = w[j].lowercase()
                val u = UNIT[x]
                when {
                    x == "mười" && cur == 0 && !tens -> { cur = 10; tens = true }
                    x == "mươi" && cur in 1..9 && !tens -> { cur *= 10; tens = true }
                    x == "trăm" && cur in 1..9 && !tens -> { total += cur * 100; cur = 0 }
                    (x == "linh" || x == "lẻ") && total > 0 && cur == 0 && j + 1 < w.size && w[j + 1].lowercase() in UNIT -> { }
                    u != null && cur == 0 && !tens -> cur = u
                    u != null && tens && cur % 10 == 0 -> cur += u
                    tens && cur % 10 == 0 && x in AFTER_TEN -> cur += AFTER_TEN.getValue(x)
                    else -> break
                }
                j++
            }
            if (j == i) { out += w[i]; i++ } else { out += (total + cur).toString(); i = j }
        }
        return out.joinToString(" ")
    }
}
