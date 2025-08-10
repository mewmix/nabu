package com.example.kokoro82m.utils

import android.content.Context
import android.content.res.Resources
import com.example.kokoro82m.R
import com.github.medavox.ipa_transcribers.Language
import com.example.kokoro82m.utils.SymbolDictionary
import java.io.IOException

class PhonemeConverter(context: Context) {
    private val phonemeMap = mutableMapOf<String, String>()

    init {
        loadDictionary(context)
    }

    private fun loadDictionary(context: Context) {
        try {
            context.resources.openRawResource(R.raw.cmudict_ipa).bufferedReader()
                .useLines { lines ->
                    lines.filter { !it.startsWith(";;;") }.forEach { line ->
                        val parts = line.split("\t", limit = 2)
                        if (parts.size == 2) {
                            phonemeMap[parts[0]] = parts[1]
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

    private fun convertToPhonemes(word: String): String {

        if (word.matches(Regex("[^a-zA-Z']+"))) {
            return word
        }


        val cleanWord = word.replace(Regex("[^a-zA-Z']"), "").uppercase()
        val arpabetWithoutStress = cleanWord.replace(Regex("[0-9]"), "ˈ")
        val phonemes = phonemeMap[arpabetWithoutStress] ?: return fallbackTranscribe(word)



        return phonemes.split(",").first().trim()
    }

    private fun fallbackTranscribe(word: String): String {
        val englishTranscriber =
            Language.ENGLISH.transcriber

        val ipaText = englishTranscriber.transcribe(word)
        return ipaText
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