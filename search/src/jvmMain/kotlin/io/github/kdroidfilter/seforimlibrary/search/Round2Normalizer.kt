package io.github.kdroidfilter.seforimlibrary.search

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.Normalizer

/** Mirrors the default normalize_text settings used for the Round 2 training corpus. */
object Round2Normalizer {
    private val marks = Regex("[\u0591-\u05BD\u05BF-\u05C7]")
    private val invisible = Regex("[\u200B-\u200F\u202A-\u202E\u2060-\u2069\uFEFF]")
    private val repeatedPunctuation = Regex("([!?.,;:])\\1{2,}")
    private val whitespace = Regex("\\s+")
    private val translations = mapOf(
        '`' to "'", '´' to "'", '‘' to "'", '’' to "'", '‚' to "'", '‛' to "'", '׳' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"", '‟' to "\"", '״' to "\"",
        '־' to "-", '–' to "-", '—' to "-", '−' to "-", '…' to "...", '׃' to ":",
    )

    fun clean(text: String): String {
        if (text.isEmpty()) return ""
        val visible = if ('<' in text || '&' in text) Jsoup.parseBodyFragment(text).body().text() else text
        val decoded = Parser.unescapeEntities(visible, false)
        val normalized = Normalizer.normalize(decoded, Normalizer.Form.NFKC)
        val translated = buildString {
            for (ch in invisible.replace(marks.replace(normalized, ""), "")) {
                append(translations[ch] ?: ch.toString())
            }
        }
        val withoutLatin = buildString {
            var latinRun = false
            translated.codePoints().forEach { codePoint ->
                val isLatin = Character.isLetter(codePoint) &&
                    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                if (isLatin) {
                    if (!latinRun) append(' ')
                } else {
                    appendCodePoint(codePoint)
                }
                latinRun = isLatin
            }
        }
        return whitespace.replace(repeatedPunctuation.replace(withoutLatin, "$1$1"), " ").trim()
    }
}
