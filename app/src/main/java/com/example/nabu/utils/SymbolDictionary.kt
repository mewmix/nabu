package com.example.nabu.utils

/**
 * Utility for translating symbols and numbers into spoken words.
 *
 * In addition to mapping individual characters, this helper performs
 * programmatic translation of multi-digit numbers up to the billions
 * (e.g. 100 -> "one hundred") and skips any characters that are not
 * recognised. Skipping unknown characters prevents them from reaching the
 * tokenizer where they can cause playback failures.
 */
object SymbolDictionary {

    private val units = arrayOf(
        "zero",
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine"
    )

    private val teens = arrayOf(
        "ten",
        "eleven",
        "twelve",
        "thirteen",
        "fourteen",
        "fifteen",
        "sixteen",
        "seventeen",
        "eighteen",
        "nineteen"
    )

    private val tens = arrayOf(
        "",
        "",
        "twenty",
        "thirty",
        "forty",
        "fifty",
        "sixty",
        "seventy",
        "eighty",
        "ninety"
    )

    /**
     * Convert a numeric value to its English word representation.
     */
    private fun numberToWords(number: Long): String {
        if (number == 0L) return units[0]

        val scales = arrayOf(
            1_000_000_000L to "billion",
            1_000_000L to "million",
            1_000L to "thousand"
        )

        fun helper(n: Long): String {
            for ((value, label) in scales) {
                if (n >= value) {
                    val head = helper(n / value)
                    val tail = if (n % value != 0L) " " + helper(n % value) else ""
                    return "$head $label$tail"
                }
            }
            return when {
                n >= 100 -> {
                    val head = units[(n / 100).toInt()] + " hundred"
                    val tail = if (n % 100 != 0L) " " + helper(n % 100) else ""
                    head + tail
                }
                n >= 20 -> tens[(n / 10).toInt()] +
                        if (n % 10 != 0L) " " + units[(n % 10).toInt()] else ""
                n >= 10 -> teens[(n - 10).toInt()]
                else -> units[n.toInt()]
            }
        }

        return helper(number)
    }

    /**
     * Replace symbols and numbers within [text] with their spoken forms.
     * Unknown symbols are removed (replaced with a space) to avoid
     * introducing unsupported tokens into the phoneme stream.
     */
    fun replaceSymbols(text: String): String {
        val builder = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch.isDigit() -> {
                    var j = i
                    while (j < text.length && text[j].isDigit()) j++
                    val number = text.substring(i, j).toLongOrNull()
                    if (number != null) {
                        builder.append(' ')
                        builder.append(numberToWords(number))
                        builder.append(' ')
                    }
                    i = j
                }
                ch == '-' -> {
                    builder.append(" dash ")
                    i++
                }
                (ch in 'a'..'z' || ch in 'A'..'Z') || ch.isWhitespace() || ch in setOf(
                    ',', '.', '!', '?', ':', ';', '\'', '"'
                ) -> {
                    builder.append(ch)
                    i++
                }
                else -> {
                    // Skip unknown symbols entirely but maintain spacing
                    builder.append(' ')
                    i++
                }
            }
        }
        return builder.toString()
    }
}

