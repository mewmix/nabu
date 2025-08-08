package com.example.kokoro82m.utils

import com.agent.kitten.KittenPhonemizerStatic

object KittenPhonemizer {
    private const val MAX_PHONEME_LENGTH = 400

    private val VOCAB: Map<Char, Int>

    init {
        val pad = '$'
        val punctuation = ";:,.!?¡¿—…\"«»“” "
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val lettersIpa = "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"
        val symbols = listOf(pad) + punctuation.toList() + letters.toList() + lettersIpa.toList()
        VOCAB = symbols.withIndex().associate { (index, char) -> char to index }
    }

    fun phonemize(text: String): Pair<String, LongArray> {
        val phonemeStr = KittenPhonemizerStatic.phonemize(text)
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