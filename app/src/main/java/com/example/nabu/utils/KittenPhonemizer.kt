package com.example.nabu.utils

import com.mewmix.nabu.core.tts.G2PConfig
import com.mewmix.nabu.core.tts.Phonemizer
import com.mewmix.nabu.core.tts.TextMode
import com.mewmix.nabu.core.tts.EspeakNgAdapter
import com.mewmix.nabu.kitten.KittenTextPolicy
import com.mewmix.nabu.kitten.KittenTokenizer
import kotlinx.coroutines.runBlocking

object KittenPhonemizer {
    private const val MAX_PHONEME_LENGTH = 400

    private val VOCAB: Map<String, Int>

    init {
        val pad = '$'
        val punctuation = ";:,.!?¡¿—…\"«»“” "
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val lettersIpa = "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗"
        val symbols = listOf(pad) + punctuation.toList() + letters.toList() + lettersIpa.toList()
        VOCAB = symbols.withIndex().associate { (index, char) -> char.toString() to index }
    }

    private val tokenizer = KittenTokenizer(VOCAB, unkId = 0)
    private val phonemizer: Phonemizer = KittenTextPolicy(
        EspeakNgAdapter(setOf("en-us", "en-gb", "es", "fr", "de", "it", "pt", "ru", "ja", "zh"))
    )

    fun phonemize(text: String, lang: String = "en-us"): Pair<String, LongArray> {
        val ipa = runBlocking { phonemizer.toIpa(text, TextMode.AUTO_G2P, G2PConfig(lang)) }
        val truncated = ipa.take(MAX_PHONEME_LENGTH)
        val tokens = tokenizer.encode(truncated)
        val padded = LongArray(tokens.size + 2)
        padded[0] = 0L
        tokens.forEachIndexed { index, value -> padded[index + 1] = value.toLong() }
        padded[padded.size - 1] = 0L
        return Pair(truncated, padded)
    }
}
