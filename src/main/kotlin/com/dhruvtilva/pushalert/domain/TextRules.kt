package com.dhruvtilva.pushalert.domain

import java.text.Normalizer
import java.util.Locale

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data object Empty : ValidationResult
    data class TooLong(val length: Int, val max: Int) : ValidationResult

    val isValid: Boolean get() = this is Valid

    fun userMessage(): String? = when (this) {
        Valid -> null
        Empty -> "Message is empty."
        is TooLong -> "Message is $length characters; Discord allows at most $max."
    }
}

object TextRules {
    const val DISCORD_MAX_LENGTH = 2000
    const val GROUP_NAME_MAX_LENGTH = 100

    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val WHITESPACE_RUN = Regex("\\s+")
    private val BLANK_LINE_RUN = Regex("\n{3,}")

    fun stripInvisible(s: String): String = INVISIBLE.replace(s, "")

    /**
     * Cleans notification text without altering its meaning: unifies line endings, removes
     * invisible direction marks, trims trailing spaces per line and collapses runs of blank lines.
     */
    fun cleanMessage(raw: String): String {
        val unified = stripInvisible(raw).replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ')
        val lines = unified.split('\n').joinToString("\n") { it.trimEnd() }
        return BLANK_LINE_RUN.replace(lines, "\n\n").trim()
    }

    /** Key used for case-insensitive exact comparison of group / sender names. */
    fun normalizeKey(raw: String): String =
        WHITESPACE_RUN.replace(Normalizer.normalize(stripInvisible(raw), Normalizer.Form.NFKC).trim(), " ")
            .lowercase(Locale.ROOT)

    /** Discord counts characters, not UTF-16 units, so use code points. */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    fun validate(text: String): ValidationResult {
        if (text.isBlank()) return ValidationResult.Empty
        val len = length(text)
        return if (len > DISCORD_MAX_LENGTH) ValidationResult.TooLong(len, DISCORD_MAX_LENGTH) else ValidationResult.Valid
    }
}
