package com.example.kokoro82m.utils

import com.github.medavox.ipa_transcribers.Language

object KittenPhonemizer {
    private const val MAX_PHONEME_LENGTH = 400

    private val VOCAB: Map<Char, Int>
    private val transcriber = Language.ENGLISH.transcriber

    init {
        val pad = '$'
        val punctuation = ";:,.!?¡¿—…\"«»“” "
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val lettersIpa = "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"
        val symbols = listOf(pad) + punctuation.toList() + letters.toList() + lettersIpa.toList()
        VOCAB = symbols.withIndex().associate { (index, char) -> char to index }
    }

    private fun getPhonemesLikeEspeak(text: String): String {
        var ipa = transcriber.transcribe(text)
        val replacements = listOf(
            "r" to "ɹ",
            "ɫ" to "l"
        )
        replacements.forEach { (from, to) -> ipa = ipa.replace(from, to) }
        ipa = ipa.replace("\\s+".toRegex(), " ").trim()
        return ipa
    }

    fun phonemize(text: String): Pair<String, LongArray> {
        val phonemeStr = getPhonemesLikeEspeak(text)
        val truncated = phonemeStr.take(MAX_PHONEME_LENGTH)
        val tokens = truncated.map { ch ->
            VOCAB[ch] ?: throw IllegalArgumentException("Kitten TTS: Unknown symbol '$ch'")
        }
        val padded = LongArray(tokens.size + 2)
        padded[0] = 0L
        tokens.forEachIndexed { index, value -> padded[index + 1] = value.toLong() }
        padded[padded.size - 1] = 0L
        return Pair(truncated, padded)
    }
}

