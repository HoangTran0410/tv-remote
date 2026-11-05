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
    /**
     * Boolean extra of the results Bundle: RESULTS_RECOGNITION is then [what the commands' list heard, what free speech heard] (not Google's
     * ranked alternatives), for [pick]. The add-on adds the second only when the first is not [sure] (dev/flows/voice.md, "free speech").
     */
    const val FREE = "dev.tvremote.voice.FREE"
    /** The add-on's own `RecognitionListener.onError` code: its model or word list could not load (not a network fault, so not ERROR_SERVER). Outside Android's 1..13. */
    const val ERROR_NO_MODEL = 1001
    const val FREE_SPEECH = "dev.tvremote.voice.FREE_SPEECH"      // request extra (Boolean, default false): also decode free speech (the user chose "commands + free speech"); false = nothing of it runs
    /** Application meta-data of the add-on: the language of its model ("vi", "en"). Absent = an old add-on, which is Vietnamese. */
    const val LANG_META = "dev.tvremote.voice.LANG"

    /** The languages there is an add-on for, each its own package ([addon]) and release file: "vi" tv-voice.apk, "en" tv-voice-en.apk. */
    val LANGS = listOf("vi", "en")
    /** The add-on's package for [lang]: Vietnamese keeps the first one's name (installed TVs), the others a suffix, so several sit side by side. */
    fun addon(lang: String) = if (lang == "en") "$ADDON.en" else ADDON

    /** The language of the add-on [pkg] (by [LANG_META]); null when it is not installed. */
    fun langOf(pm: android.content.pm.PackageManager, pkg: String): String? = try {
        pm.getApplicationInfo(pkg, android.content.pm.PackageManager.GET_META_DATA).metaData?.getString(LANG_META) ?: "vi"
    } catch (e: Exception) { null }

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
    fun digits(text: String, lang: String = "vi"): String = if (lang == "en") digitsEn(text) else digitsVi(text)

    private val EN_UNIT = mapOf("zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val EN_TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    /** English: "set timer thirty minutes" -> "set timer 30 minutes", "channel twenty five" -> "channel 25", "one hundred and five" -> "105"; "one two three" stays three numbers. */
    private fun digitsEn(text: String): String {
        val w = text.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            var total = 0; var cur = 0; var j = i; var any = false
            while (j < w.size) {
                val x = w[j].lowercase()
                val u = EN_UNIT[x]; val t = EN_TENS[x]
                when {
                    t != null && cur == 0 -> cur = t
                    u != null && cur == 0 && !(u == 0 && any) -> cur = u
                    u != null && u in 1..9 && cur in 20..90 && cur % 10 == 0 -> cur += u
                    x == "hundred" && cur in 1..9 -> { total += cur * 100; cur = 0 }
                    x == "and" && total > 0 && cur == 0 && j + 1 < w.size && (w[j + 1].lowercase() in EN_UNIT || w[j + 1].lowercase() in EN_TENS) -> { }
                    else -> break
                }
                any = true; j++
            }
            if (j == i) { out += w[i]; i++ } else { out += (total + cur).toString(); i = j }
        }
        return out.joinToString(" ")
    }

    private fun digitsVi(text: String): String {
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

    /** The commands' list heard every word with this confidence: a command said clearly, so no second look at the sound is needed. */
    fun sure(confs: List<Double>): Boolean = confs.isNotEmpty() && confs.min() >= 0.9 && confs.average() >= 0.95

    const val SEARCH = "search"                                    // what [pick]'s `kind` answers for a search; any other non-null answer names a command

    /**
     * Which text TV Setup acts on, from [heard] (grammar text, then free text) when the grammar was not sure. [kind] says what a text is
     * (null nothing, [SEARCH], else the command it parses to); [asSearch] turns a text into a search sentence.
     *  a. free is a search: free ("sơn tùng trên youtube" against a garbled "sau home tục menu 6").
     *  b. grammar is a search and free is no command: a search with the free text ("tìm ngay xuống thanh đổi" against "kỷ niệm ngày xưa anh nổi").
     *  c. grammar is a command: it, unless free is a different command ("hẹn giờ tắt 30 phu" stays; free "mở youtube" against "tiếng ngay" wins).
     *  d. neither parses: free (it goes to the TV's own search like the grammar text would, and the grammar was forced onto words it
     *     did not know: "sau home tục menu 6" against "sơn tùng chia mưu sinh"). No free text = the grammar text, as before.
     */
    fun pick(heard: List<String>, kind: (String) -> String?, asSearch: (String) -> String): String {
        val g = heard.firstOrNull { it.isNotEmpty() }.orEmpty()
        val f = heard.lastOrNull { it.isNotEmpty() }.orEmpty()
        if (heard.size < 2 || g == f) return g
        val gk = kind(g); val fk = kind(f)
        return when {
            fk == SEARCH -> f
            gk == SEARCH -> if (fk == null) asSearch(f) else f
            gk != null -> if (fk != null && fk != gk) f else g
            fk != null -> f
            else -> f
        }
    }
}
