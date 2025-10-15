package com.example.nabu.utils

import android.content.Context
import android.content.res.Resources
import com.example.nabu.R
import com.github.medavox.ipa_transcribers.Language
import java.io.IOException
import java.util.Locale

class PhonemeConverter private constructor(
    private val phonemeMap: MutableMap<String, String>
) {

    constructor(context: Context) : this(mutableMapOf()) {
        loadDictionary(context)
    }

    private val pronunciationOverrides = mapOf(
        "IT" to "ɪt",
        "ITS" to "ɪts",
        "US" to "ʌs"
    )

    private val letterPronunciations = mapOf(
        'A' to "eɪ",
        'B' to "biː",
        'C' to "siː",
        'D' to "diː",
        'E' to "iː",
        'F' to "ɛf",
        'G' to "dʒiː",
        'H' to "eɪtʃ",
        'I' to "aɪ",
        'J' to "dʒeɪ",
        'K' to "keɪ",
        'L' to "ɛl",
        'M' to "ɛm",
        'N' to "ɛn",
        'O' to "oʊ",
        'P' to "piː",
        'Q' to "kjuː",
        'R' to "ɑːr",
        'S' to "ɛs",
        'T' to "tiː",
        'U' to "juː",
        'V' to "viː",
        'W' to "dʌbəljuː",
        'X' to "ɛks",
        'Y' to "waɪ",
        'Z' to "ziː"
    )

    private val englishTranscriber by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { Language.ENGLISH.transcriber }.getOrNull()
    }

    private fun loadDictionary(context: Context) {
        try {
            context.resources.openRawResource(R.raw.cmudict_ipa).bufferedReader()
                .useLines { lines ->
                    lines.filter { !it.startsWith(";;;") }.forEach { line ->
                        val parts = line.split("\t", limit = 2)
                        if (parts.size == 2) {
                            registerEntry(parts[0], parts[1])
                        } else {
                            DebugLogger.log("Invalid line format: $line")
                        }
                    }
                }
            DebugLogger.log("Dictionary loaded successfully. Total entries: ${phonemeMap.size}")
        } catch (e: IOException) {
            DebugLogger.log("Error loading dictionary: ${e.message}")
            e.printStackTrace()
        } catch (e: Resources.NotFoundException) {
            DebugLogger.log("Dictionary file not found: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun loadDictionary(entries: Map<String, String>) {
        entries.forEach { (key, value) ->
            registerEntry(key, value)
        }
        DebugLogger.log("Dictionary loaded from override. Total entries: ${phonemeMap.size}")
    }

    private fun registerEntry(key: String, value: String) {
        val normalizedKey = key.uppercase(Locale.US)
        val normalizedValue = pronunciationOverrides[normalizedKey] ?: value
        phonemeMap[normalizedKey] = normalizedValue
    }

    private fun convertToPhonemes(word: String): String {
        if (word.matches(Regex("[^a-zA-Z']+"))) {
            return word
        }

        val cleanedWord = word.replace(Regex("[^a-zA-Z']"), "")
        val cleanKey = cleanedWord.uppercase(Locale.US)
        val isAllUppercase = cleanedWord.isNotEmpty() && cleanedWord.all { it.isUpperCase() }

        if (!isAllUppercase) {
            pronunciationOverrides[cleanKey]?.let { return it }
        }

        val phonemesFromDict = phonemeMap[cleanKey]

        if (phonemesFromDict != null) {
            val sanitized = phonemesFromDict.split(",").first().trim()
            val stressCount = sanitized.count { it == 'ˈ' || it == 'ˌ' }
            if (shouldSpellOutAsInitialism(isAllUppercase, cleanedWord.length, stressCount)) {
                return fallbackTranscribe(cleanedWord)
            }

            return sanitized
        }

        return fallbackTranscribe(cleanedWord)
    }

    private fun shouldSpellOutAsInitialism(
        isAllUppercase: Boolean,
        length: Int,
        stressCount: Int
    ): Boolean {
        if (!isAllUppercase) {
            return false
        }
        if (length > 4) {
            return false
        }
        return stressCount > 1
    }

    private fun fallbackTranscribe(word: String): String {
        if (word.isEmpty()) {
            return word
        }

        val key = word.uppercase(Locale.US)
        pronunciationOverrides[key]?.let { return it }

        englishTranscriber?.let { transcriber ->
            runCatching { transcriber.transcribe(word.lowercase(Locale.US)) }
                .onSuccess { return it }
        }

        if (word.all { it.isLetter() }) {
            val pronunciation = key
                .mapNotNull { letterPronunciations[it] }
                .joinToString(" ")

            if (pronunciation.isNotEmpty()) {
                return pronunciation
            }
        }

        return word
    }

    fun phonemize(text: String, lang: String = "en-us", norm: Boolean = true): String {

        val normalizedText = if (norm) normalizeText(text) else text
        DebugLogger.log("normalText: $normalizedText")


        val wordsAndPunctuation = normalizedText
            .split(Regex("(?<=[^\\p{L}\\p{N}'])|(?=[^\\p{L}\\p{N}'])"))
            .filter { it.isNotBlank() }


        val phonemes = StringBuilder()
        for ((index, word) in wordsAndPunctuation.withIndex()) {
            DebugLogger.log("word: $word")
            val ipaPhonemes = if (word.matches(Regex("[^a-zA-Z']+"))) {
                word
            } else {
                val temp =
                    convertToPhonemes(word).replace(" ", "").replace("ˌ", "")
                adjustStressMarkers(temp)
            }


            if (index > 0 && !word.matches(Regex("[^a-zA-Z']+"))) {
                phonemes.append(" ")
            }
            phonemes.append(ipaPhonemes)
        }


        return postProcessPhonemes(phonemes.toString(), lang)
    }

    fun adjustStressMarkers(input: String): String {

        val vowels = setOf(
            'a',
            'e',
            'i',
            'o',
            'u',
            'ɑ',
            'ɐ',
            'ɒ',
            'æ',
            'ɔ',
            'ə',
            'ɘ',
            'ɚ',
            'ɛ',
            'ɜ',
            'ɝ',
            'ɞ',
            'ɪ',
            'ɨ',
            'ø',
            'ɵ',
            'œ',
            'ɶ',
            'ʉ',
            'ʊ',
            'ʌ',
            'A',
            'E',
            'I',
            'O',
            'U',
            'ː',
            'ˑ'
        )

        val builder = StringBuilder(input)
        var i = 0

        while (i < builder.length) {
            if (builder[i] == 'ˈ' || builder[i] == 'ˌ') {

                val stressIndex = i
                val stressChar = builder[i]

                for (j in stressIndex + 1 until builder.length) {
                    if (builder[j] in vowels) {

                        builder.deleteCharAt(stressIndex)
                        builder.insert(j - 1, stressChar)
                        i = j
                        break
                    }
                }
            }
            i++
        }

        return builder.toString()
    }


    private fun normalizeText(text: String): String {
        var normalizedText = text
            .lines()
            .joinToString("\n") { it.trim() }
            .replace("[‘’]".toRegex(), "'")
            .replace("[“”«»]".toRegex(), "\"")
            .replace("[、。！，：；？]".toRegex()) { match ->
                when (match.value) {
                    "、" -> ","
                    "。" -> "."
                    "！" -> "!"
                    "，" -> ","
                    "：" -> ":"
                    "；" -> ";"
                    "？" -> "?"
                    else -> match.value
                } + " "
            }


        normalizedText = normalizedText
            .replace(Regex("\\bD[Rr]\\.(?= [A-Z])"), "Doctor")
            .replace(Regex("\\b(?:Mr\\.|MR\\.(?= [A-Z]))"), "Mister")
            .replace(Regex("\\b(?:Ms\\.|MS\\.(?= [A-Z]))"), "Miss")
            .replace(Regex("\\b(?:Mrs\\.|MRS\\.(?= [A-Z]))"), "Mrs")
            .replace(Regex("\\betc\\.(?! [A-Z])"), "etc")


        normalizedText = normalizedText.replace(Regex("(?<=\\d),(?=\\d)"), "")
        normalizedText = normalizedText.replace(Regex("(?<=\\d)-(?=\\d)"), " to ")

        normalizedText = SymbolDictionary.replaceSymbols(normalizedText)
        normalizedText = normalizedText.replace(Regex("\\s+"), " ")

        return normalizedText.trim()
    }

    private fun postProcessPhonemes(phonemes: String, lang: String): String {
        var result = phonemes
            .replace("r", "ɹ")
            .replace("x", "k")
            .replace("ʲ", "j")
            .replace("ɬ", "l")


        result = result.replace("kəkˈoːɹoʊ", "kˈoʊkəɹoʊ")
            .replace("kəkˈɔːɹəʊ", "kˈəʊkəɹəʊ")


        if (lang == "en-us") {
            result = result.replace("ti", "di")
        }

        val originalChars = result.toList()
        val filteredChars = originalChars.filter { it in VOCAB.keys || it.toString().matches(Regex("[^a-zA-Z']+")) }
        val removedChars = originalChars.filterNot { it in VOCAB.keys || it.toString().matches(Regex("[^a-zA-Z']+")) }

        if (removedChars.isNotEmpty()) {
            DebugLogger.log("Removed invalid symbols: ${removedChars.joinToString("")}")
        }

        return filteredChars.joinToString("").trim()
    }

    companion object {

        fun fromDictionary(entries: Map<String, String>): PhonemeConverter {
            return PhonemeConverter(mutableMapOf()).apply {
                loadDictionary(entries)
            }
        }

        private val VOCAB: Map<Char, Int> = run {
            val pad = '$'
            val punctuation = ";:,.!?¡¿—…\"«»“” "
            val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            val lettersIpa =
                "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"

            val symbols =
                listOf(pad) + punctuation.toList() + letters.toList() + lettersIpa.toList()

            symbols.withIndex().associate { (index, char) -> char to index }
        }
    }
}