package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Words.plural against CLDR (what Intl.PluralRules gives the page): Russian 2-4 / 22-24 are "few", not "other". */
class WordsPluralTest {
    @Test fun russian() {
        assertEquals(listOf("one", "few", "many", "one", "few", "many", "many", "many", "few", "other"),
            listOf(1, 2, 5, 21, 22, 25, 11, 12, 104).map { Words.plural("ru", it.toDouble()) } + Words.plural("ru", 1.5))
    }

    @Test fun arabic() {
        assertEquals(listOf("zero", "one", "two", "few", "many", "other", "few", "many"),
            listOf(0, 1, 2, 3, 11, 100, 103, 111).map { Words.plural("ar", it.toDouble()) })
    }

    @Test fun frenchEnglishVietnamese() {
        assertEquals(listOf("one", "one", "other"), listOf(0, 1, 2).map { Words.plural("fr", it.toDouble()) })
        assertEquals(listOf("other", "one", "other"), listOf(0, 1, 2).map { Words.plural("en", it.toDouble()) })
        assertEquals("other", Words.plural("vi", 1.0))
    }

    @Test fun aFewFormIsPickedFromTheWords() {
        val was = Words.open
        Words.open = { name -> if (name == "i18n/ru.json") """{"x":{"one":"{n} файл","few":"{n} файла","many":"{n} файлов","other":"{n} файла"}}""".byteInputStream() else null }
        try {
            assertEquals(listOf("1 файл", "2 файла", "5 файлов", "22 файла", "11 файлов"), listOf(1, 2, 5, 22, 11).map { Words.get("ru", "x", "n" to it) })
        } finally { Words.open = was }
    }
}
