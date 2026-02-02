package com.mewmix.nabu.soprano

import java.text.Normalizer
import java.util.regex.Pattern

object SopranoTextNormalizer {
    private val ONES = arrayOf(
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )
    private val ORDINAL_ONES = arrayOf(
        "", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth"
    )
    private val ORDINAL_TENS = arrayOf(
        "", "", "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth"
    )

    private val UNICODE_MAP = mapOf(
        'à' to "a", 'á' to "a", 'â' to "a", 'ã' to "a", 'ä' to "a", 'å' to "a", 'æ' to "ae",
        'ç' to "c", 'è' to "e", 'é' to "e", 'ê' to "e", 'ë' to "e", 'ì' to "i", 'í' to "i",
        'î' to "i", 'ï' to "i", 'ñ' to "n", 'ò' to "o", 'ó' to "o", 'ô' to "o", 'õ' to "o",
        'ö' to "o", 'ø' to "o", 'ù' to "u", 'ú' to "u", 'û' to "u", 'ü' to "u", 'ý' to "y",
        'ÿ' to "y", 'ß' to "ss", 'œ' to "oe", 'ð' to "d", 'þ' to "th",
        'À' to "A", 'Á' to "A", 'Â' to "A", 'Ã' to "A", 'Ä' to "A", 'Å' to "A", 'Æ' to "AE",
        'Ç' to "C", 'È' to "E", 'É' to "E", 'Ê' to "E", 'Ë' to "E", 'Ì' to "I", 'Í' to "I",
        'Î' to "I", 'Ï' to "I", 'Ñ' to "N", 'Ò' to "O", 'Ó' to "O", 'Ô' to "O", 'Õ' to "O",
        'Ö' to "O", 'Ø' to "O", 'Ù' to "U", 'Ú' to "U", 'Û' to "U", 'Ü' to "U", 'Ý' to "Y",
        '\u201C' to "\"", '\u201D' to "\"", '\u2018' to "'", '\u2019' to "'", '\u2026' to "...",
        '\u2013' to "-", '\u2014' to "-",
        '\u00AB' to "\"", '\u00BB' to "\"", '\u2039' to "'", '\u203A' to "'", '\u2022' to "*",
        '\u2032' to "'", '\u2033' to "\""
    )

    private val ABBREVIATIONS = listOf(
        Pattern.compile("\\bmrs\\.", Pattern.CASE_INSENSITIVE) to "misuss",
        Pattern.compile("\\bms\\.", Pattern.CASE_INSENSITIVE) to "miss",
        Pattern.compile("\\bmr\\.", Pattern.CASE_INSENSITIVE) to "mister",
        Pattern.compile("\\bdr\\.", Pattern.CASE_INSENSITIVE) to "doctor",
        Pattern.compile("\\bst\\.", Pattern.CASE_INSENSITIVE) to "saint",
        Pattern.compile("\\bco\\.", Pattern.CASE_INSENSITIVE) to "company",
        Pattern.compile("\\bjr\\.", Pattern.CASE_INSENSITIVE) to "junior",
        Pattern.compile("\\bmaj\\.", Pattern.CASE_INSENSITIVE) to "major",
        Pattern.compile("\\bgen\\.", Pattern.CASE_INSENSITIVE) to "general",
        Pattern.compile("\\bdrs\\.", Pattern.CASE_INSENSITIVE) to "doctors",
        Pattern.compile("\\brev\\.", Pattern.CASE_INSENSITIVE) to "reverend",
        Pattern.compile("\\blt\\.", Pattern.CASE_INSENSITIVE) to "lieutenant",
        Pattern.compile("\\bhon\\.", Pattern.CASE_INSENSITIVE) to "honorable",
        Pattern.compile("\\bsgt\\.", Pattern.CASE_INSENSITIVE) to "sergeant",
        Pattern.compile("\\bcapt\\.", Pattern.CASE_INSENSITIVE) to "captain",
        Pattern.compile("\\besq\\.", Pattern.CASE_INSENSITIVE) to "esquire",
        Pattern.compile("\\bltd\\.", Pattern.CASE_INSENSITIVE) to "limited",
        Pattern.compile("\\bcol\\.", Pattern.CASE_INSENSITIVE) to "colonel",
        Pattern.compile("\\bft\\.", Pattern.CASE_INSENSITIVE) to "fort"
    )

    private val CASED_ABBREVIATIONS = listOf(
        Pattern.compile("\\bTTS\\b") to "text to speech",
        Pattern.compile("\\bHz\\b") to "hertz",
        Pattern.compile("\\bkHz\\b") to "kilohertz",
        Pattern.compile("\\bKBs\\b") to "kilobytes",
        Pattern.compile("\\bKB\\b") to "kilobyte",
        Pattern.compile("\\bMBs\\b") to "megabytes",
        Pattern.compile("\\bMB\\b") to "megabyte",
        Pattern.compile("\\bGBs\\b") to "gigabytes",
        Pattern.compile("\\bGB\\b") to "gigabyte",
        Pattern.compile("\\bTBs\\b") to "terabytes",
        Pattern.compile("\\bTB\\b") to "terabyte",
        Pattern.compile("\\bAPIs\\b") to "a p i's",
        Pattern.compile("\\bAPI\\b") to "a p i",
        Pattern.compile("\\bCLIs\\b") to "c l i's",
        Pattern.compile("\\bCLI\\b") to "c l i",
        Pattern.compile("\\bCPUs\\b") to "c p u's",
        Pattern.compile("\\bCPU\\b") to "c p u",
        Pattern.compile("\\bGPUs\\b") to "g p u's",
        Pattern.compile("\\bGPU\\b") to "g p u",
        Pattern.compile("\\bAve\\b") to "avenue",
        Pattern.compile("\\betc\\b") to "etcetera"
    )

    private val NUM_PREFIX_RE = Pattern.compile("#(\\d)")
    private val NUM_SUFFIX_RE = Pattern.compile("(\\d)([KMBT])", Pattern.CASE_INSENSITIVE)
    private val NUM_LETTER_SPLIT_RE = Pattern.compile("(\\d)([a-z])|([a-z])(\\d)", Pattern.CASE_INSENSITIVE)
    private val COMMA_NUMBER_RE = Pattern.compile("(\\d[\\d,]+\\d)")
    private val DATE_RE = Pattern.compile("(^|[^/])(\\d\\d?[/-]\\d\\d?[/-]\\d\\d(?:\\d\\d)?)($|[^/])")
    private val PHONE_NUMBER_RE = Pattern.compile("\\(?\\d{3}\\)?[-.\\s]\\d{3}[-.\\s]?\\d{4}")
    private val TIME_RE = Pattern.compile("(\\d\\d?):(\\d\\d)(?::(\\d\\d))?")
    private val POUNDS_RE = Pattern.compile("£([\\d,]*\\d+)")
    private val DOLLARS_RE = Pattern.compile("\\$([\\d.,]*\\d+)")
    private val DECIMAL_NUMBER_RE = Pattern.compile("(\\d+(?:\\.\\d+)+)")
    private val MULTIPLY_RE = Pattern.compile("(\\d)\\s?\\*\\s?(\\d)")
    private val DIVIDE_RE = Pattern.compile("(\\d)\\s?/\\s?(\\d)")
    private val ADD_RE = Pattern.compile("(\\d)\\s?\\+\\s?(\\d)")
    private val SUBTRACT_RE = Pattern.compile("(\\d)?\\s?-\\s?(\\d)")
    private val FRACTION_RE = Pattern.compile("(\\d+)/(\\d+)")
    private val ORDINAL_RE = Pattern.compile("(\\d+)(st|nd|rd|th)", Pattern.CASE_INSENSITIVE)
    private val NUMBER_RE = Pattern.compile("\\d+")

    private val SPECIAL_CHARACTERS = listOf(
        Pattern.compile("@") to " at ",
        Pattern.compile("&") to " and ",
        Pattern.compile("%") to " percent ",
        Pattern.compile(":") to ".",
        Pattern.compile(";") to ",",
        Pattern.compile("\\+") to " plus ",
        Pattern.compile("\\\\") to " backslash ",
        Pattern.compile("~") to " about ",
        Pattern.compile("(^| )<3") to " heart ",
        Pattern.compile("<=") to " less than or equal to ",
        Pattern.compile(">=") to " greater than or equal to ",
        Pattern.compile("<") to " less than ",
        Pattern.compile(">") to " greater than ",
        Pattern.compile("=") to " equals ",
        Pattern.compile("/") to " slash ",
        Pattern.compile("_") to " "
    )

    private val LINK_HEADER_RE = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE)
    private val DASH_RE = Pattern.compile("(.) - (.)")
    private val DOT_RE = Pattern.compile("([A-Z])\\.([A-Z])", Pattern.CASE_INSENSITIVE)
    private val PARENTHESES_RE = Pattern.compile("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}](.)?")


    fun cleanText(text: String): String {
        var t = convertToAscii(text)
        t = normalizeNewlines(t)
        t = normalizeNumbers(t)
        t = normalizeSpecial(t)
        t = expandAbbreviations(t)
        t = expandSpecialCharacters(t)
        t = t.lowercase()
        t = removeUnknownCharacters(t)
        t = collapseWhitespace(t)
        t = dedupPunctuation(t)
        return t.trim()
    }

    private fun numberToWords(num: Long, options: Map<String, Any> = emptyMap()): String {
        val andword = options["andword"] as? String ?: ""
        val zero = options["zero"] as? String ?: "zero"
        val group = options["group"] as? Int ?: 0

        if (num == 0L) return zero

        if (group == 2 && num > 1000 && num < 10000) {
            val high = num / 100
            val low = num % 100
            if (low == 0L) {
                return convertNum(high, andword) + " hundred"
            } else if (low < 10) {
                return convertNum(high, andword) + " " + (if (zero == "oh") "oh" else zero) + " " + ONES[low.toInt()]
            } else {
                return convertNum(high, andword) + " " + convertNum(low, andword)
            }
        }
        return convertNum(num, andword)
    }

    private fun convertNum(n: Long, andword: String): String {
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + (if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else "")
        if (n < 1000) {
            val remainder = n % 100
            val sep = if (remainder != 0L) (if (andword.isNotEmpty()) " $andword " else " ") else ""
            return ONES[(n / 100).toInt()] + " hundred" + (if (remainder != 0L) sep + convertNum(remainder, andword) else "")
        }
        if (n < 1000000) {
            val thousands = n / 1000
            val remainder = n % 1000
            return convertNum(thousands, andword) + " thousand" + (if (remainder != 0L) " " + convertNum(remainder, andword) else "")
        }
        if (n < 1000000000) {
            val millions = n / 1000000
            val remainder = n % 1000000
            return convertNum(millions, andword) + " million" + (if (remainder != 0L) " " + convertNum(remainder, andword) else "")
        }
        val billions = n / 1000000000
        val remainder = n % 1000000000
        return convertNum(billions, andword) + " billion" + (if (remainder != 0L) " " + convertNum(remainder, andword) else "")
    }

    private fun ordinalToWords(num: Long): String {
        if (num < 20) return ORDINAL_ONES.getOrNull(num.toInt()) ?: (numberToWords(num) + "th")
        if (num < 100) {
            val tens = num / 10
            val ones = num % 10
            if (ones == 0L) return ORDINAL_TENS.getOrNull(tens.toInt()) ?: ""
            return TENS.getOrNull(tens.toInt()) ?: "" + " " + (ORDINAL_ONES.getOrNull(ones.toInt()) ?: "")
        }
        val cardinal = numberToWords(num)
        return when {
            cardinal.endsWith("y") -> cardinal.dropLast(1) + "ieth"
            cardinal.endsWith("one") -> cardinal.dropLast(3) + "first"
            cardinal.endsWith("two") -> cardinal.dropLast(3) + "second"
            cardinal.endsWith("three") -> cardinal.dropLast(5) + "third"
            cardinal.endsWith("ve") -> cardinal.dropLast(2) + "fth"
            cardinal.endsWith("e") -> cardinal.dropLast(1) + "th"
            cardinal.endsWith("t") -> cardinal + "h"
            else -> cardinal + "th"
        }
    }

    private fun convertToAscii(text: String): String {
        val sb = StringBuilder()
        for (char in text) {
            sb.append(UNICODE_MAP[char] ?: char)
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
    }

    private fun expandAbbreviations(text: String): String {
        var t = text
        for ((pattern, replacement) in ABBREVIATIONS + CASED_ABBREVIATIONS) {
            t = pattern.matcher(t).replaceAll(replacement)
        }
        return t
    }

    private fun normalizeNumbers(text: String): String {
        var t = text
        t = NUM_PREFIX_RE.matcher(t).replaceAll("number $1")

        t = NUM_SUFFIX_RE.matcher(t).replaceAll { match ->
            val num = match.group(1)
            val suffix = match.group(2)?.lowercase()
            val word = when (suffix) {
                "k" -> "thousand"
                "m" -> "million"
                "b" -> "billion"
                "t" -> "trillion"
                else -> ""
            }
            "$num $word"
        }

        repeat(2) {
            t = NUM_LETTER_SPLIT_RE.matcher(t).replaceAll { match ->
                val d1 = match.group(1)
                val l1 = match.group(2)
                val l2 = match.group(3)
                val d2 = match.group(4)
                if (d1 != null && l1 != null) "$d1 $l1"
                else if (l2 != null && d2 != null) "$l2 $d2"
                else match.group(0)
            }
        }

        t = COMMA_NUMBER_RE.matcher(t).replaceAll { it.group(1).replace(",", "") }

        t = DATE_RE.matcher(t).replaceAll { match ->
            val pre = match.group(1)
            val date = match.group(2)
            val post = match.group(3)
            val parts = date.split(Regex("[./-]"))
            pre + parts.joinToString(" dash ") + post
        }

        t = PHONE_NUMBER_RE.matcher(t).replaceAll { match ->
            val digits = match.group(0).replace(Regex("\\D"), "")
            if (digits.length == 10) {
                "${digits.substring(0, 3).map { "$it" }.joinToString(" ")}, ${digits.substring(3, 6).map { "$it" }.joinToString(" ")}, ${digits.substring(6).map { "$it" }.joinToString(" ")}"
            } else {
                match.group(0)
            }
        }

        t = TIME_RE.matcher(t).replaceAll { match ->
            val hours = match.group(1)
            val minutes = match.group(2)
            val seconds = match.group(3)

            val h = hours.toInt()
            val m = minutes.toInt()
            if (seconds == null) {
                if (m == 0) {
                    if (h == 0) "0"
                    else if (h > 12) "$hours minutes"
                    else "$hours o'clock"
                } else if (minutes.startsWith("0")) {
                    "$hours oh ${minutes[1]}"
                } else {
                    "$hours $minutes"
                }
            } else {
                val s = seconds.toInt()
                val minStr = if (m == 0) "oh oh" else if (minutes.startsWith("0")) "oh ${minutes[1]}" else minutes
                val secStr = if (s == 0) "" else if (seconds.startsWith("0")) "oh ${seconds[1]}" else seconds

                if (h != 0) {
                   "$hours $minStr $secStr".trim()
                } else if (m != 0) {
                   "$minutes $secStr".trim()
                } else {
                    seconds
                }
            }
        }

        t = POUNDS_RE.matcher(t).replaceAll { match ->
            match.group(1).replace(",", "") + " pounds"
        }

        t = DOLLARS_RE.matcher(t).replaceAll { match ->
            val amount = match.group(1).replace(",", "")
            val parts = amount.split(".")
            val dollars = parts[0].toLongOrNull() ?: 0L
            val cents = if (parts.size > 1) parts[1].toLongOrNull() ?: 0L else 0L

            if (dollars > 0 && cents > 0) {
                "$dollars ${if (dollars == 1L) "dollar" else "dollars"}, $cents ${if (cents == 1L) "cent" else "cents"}"
            } else if (dollars > 0) {
                "$dollars ${if (dollars == 1L) "dollar" else "dollars"}"
            } else if (cents > 0) {
                "$cents ${if (cents == 1L) "cent" else "cents"}"
            } else {
                "zero dollars"
            }
        }

        t = DECIMAL_NUMBER_RE.matcher(t).replaceAll { match ->
            val parts = match.group(1).split(".")
            parts[0] + " point " + parts.drop(1).joinToString(" point ") { it.map { c -> "$c" }.joinToString(" ") }
        }

        t = MULTIPLY_RE.matcher(t).replaceAll("$1 times $2")
        t = DIVIDE_RE.matcher(t).replaceAll("$1 over $2")
        t = ADD_RE.matcher(t).replaceAll("$1 plus $2")
        t = SUBTRACT_RE.matcher(t).replaceAll { match ->
            val a = match.group(1) ?: ""
            val b = match.group(2)
            "$a minus $b"
        }

        t = FRACTION_RE.matcher(t).replaceAll("$1 over $2")

        t = ORDINAL_RE.matcher(t).replaceAll { match ->
            ordinalToWords(match.group(1).toLong())
        }

        t = NUMBER_RE.matcher(t).replaceAll { match ->
            val num = match.group(0).toLongOrNull() ?: 0L
            if (num > 1000 && num < 3000) {
                if (num == 2000L) "two thousand"
                else if (num > 2000 && num < 2010) "two thousand " + numberToWords(num % 100)
                else if (num % 100 == 0L) numberToWords(num / 100) + " hundred"
                else numberToWords(num, mapOf("zero" to "oh", "group" to 2))
            } else {
                numberToWords(num)
            }
        }
        return t
    }

    private fun normalizeSpecial(text: String): String {
        var t = text
        t = LINK_HEADER_RE.matcher(t).replaceAll("h t t p s colon slash slash ")
        t = DASH_RE.matcher(t).replaceAll("$1, $2")
        t = DOT_RE.matcher(t).replaceAll("$1 dot $2")
        t = PARENTHESES_RE.matcher(t).replaceAll { match ->
            val after = match.group(1)
            var result = match.group(0).replace(Regex("[\\(\\[\\{]"), ", ").replace(Regex("[\\)\\]\\}]"), ", ")
            if (after != null && after.matches(Regex("[$.!?,]"))) {
                result = result.substring(0, result.length - 2) + after
            }
            result
        }
        return t
    }

    private fun expandSpecialCharacters(text: String): String {
        var t = text
        for ((pattern, replacement) in SPECIAL_CHARACTERS) {
            t = pattern.matcher(t).replaceAll(replacement)
        }
        return t
    }

    private fun normalizeNewlines(text: String): String {
        return text.split("\n").joinToString(" ") { line ->
            var l = line.trim()
            if (l.isEmpty()) return@joinToString ""
            if (!l.matches(Regex(".*[.!?]$"))) l += "."
            l
        }
    }

    private fun removeUnknownCharacters(text: String): String {
        var t = text.replace(Regex("[^A-Za-z !\\$%&'\\*\\+,\\-./0123456789<>\\?_]"), "")
        t = t.replace(Regex("[<>\\/_+]"), "")
        return t
    }

    private fun collapseWhitespace(text: String): String {
        var t = text.replace(Regex("\\s+"), " ")
        t = t.replace(Regex(" ([.\\?!,])"), "$1")
        return t
    }

    private fun dedupPunctuation(text: String): String {
        var t = text
        t = t.replace(Regex("\\.\\.\\.+"), "[ELLIPSIS]")
        t = t.replace(Regex(",+"), ",")
        t = t.replace(Regex("[.,]*\\.[.,]*"), ".")
        t = t.replace(Regex("[.,!]*![.,!]*"), "!")
        t = t.replace(Regex("[.,!?]*\\?[.,!?]*"), "?")
        t = t.replace(Regex("\\[ELLIPSIS\\]"), "...")
        return t
    }
}
