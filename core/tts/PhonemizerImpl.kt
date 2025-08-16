package com.mewmix.nabu.core.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class PhonemizerImpl(
    private val misaki: MisakiAdapter?,
    private val espeak: EspeakAdapter?
) : Phonemizer {

    // Simple lock-free cache; replace with LRU if you want eviction
    private val cache = ConcurrentHashMap<Int, String>() // key = (mode,lang,hash(input))

    override suspend fun toIpa(input: String, mode: TextMode, cfg: G2PConfig): String =
        withContext(Dispatchers.Default) {
            require(input.isNotBlank()) { "Empty input" }
            require(cfg.langCode.isNotBlank()) { "Missing langCode" }

            val key = (mode.name + "|" + cfg.langCode + "|" + input).hashCode()
            cache[key]?.let { return@withContext it }

            val out = when (mode) {
                TextMode.IPA -> {
                    val norm = normalizeIpa(input)
                    validateIpa(norm)
                    norm
                }
                TextMode.ARPABET -> {
                    val ipa = arpabetToIpaSafe(input)
                    validateIpa(ipa)
                    ipa
                }
                TextMode.AUTO_G2P -> {
                    val text = normalizeText(input)
                    val ipa = runG2P(text, cfg)
                    validateIpa(ipa)
                    ipa
                }
            }

            cache[key] = out
            out
        }

    // --- G2P orchestration ---
    private fun runG2P(text: String, cfg: G2PConfig): String {
        if (cfg.preferMisaki && misaki?.supports(cfg.langCode) == true) {
            misaki.g2p(text, cfg.langCode)?.let { return normalizeIpa(it) }
        }
        if (cfg.fallbackEspeak && espeak?.supports(cfg.langCode) == true) {
            espeak.g2pToIpa(text, cfg.langCode)?.let { return normalizeIpa(it) }
        }
        throw IllegalStateException("No G2P available for ${cfg.langCode}")
    }

    // --- Normalizers/validators (fast & strict) ---
    private fun normalizeText(s: String): String =
        s.replace('\u2019', '\'')
         .replace('\u2014', '-')
         .replace(Regex("\\s+"), " ")
         .trim()

    private fun normalizeIpa(ipa: String): String =
        ipa.replace(Regex("\\s+"), " ").trim()

    private fun validateIpa(ipa: String) {
        val ok = ipa.matches(Regex("""^[a-zA-Zʰʷɾɬɮʃʒɹɲŋɣðθʈɖɟɡɢʔɸβʋɭʎçʝxχʕħʙrʀmɱnɳɴpbdtkgqfvszʂʐhjiueoɑæʊʌɔəɚɝɐɜɞɒːˑˈˌːːː˞˩˨˧˦˥̃ˠˤʲˈˌːˑ ̩ ̯ ̈ ̃ ̊ ̥ ̬ ̹ ̜ ̟ ̠ ̽ .,'-]+$"""))
        if (!ok) throw IllegalArgumentException("Invalid IPA content")
    }

    // --- ARPAbet conversion (minimal, extend as needed) ---
    private fun arpabetToIpaSafe(arpabet: String): String {
        val map = ARPA_TO_IPA
        val tokens = arpabet.trim().split(Regex("\\s+"))
        if (tokens.isEmpty()) throw IllegalArgumentException("Empty ARPAbet")

        val out = buildString {
            tokens.forEachIndexed { i, t ->
                val (base, stress) = t.partitionLastDigit()
                val ipa = map[base] ?: throw IllegalArgumentException("Unknown ARPAbet: $base")
                append(ipa)
                when (stress) {
                    '1' -> append('ˈ')
                    '2' -> append('ˌ')
                }
                if (i != tokens.lastIndex) append(' ')
            }
        }
        return out
    }

    private fun String.partitionLastDigit(): Pair<String, Char?> {
        val last = this.last()
        return if (last.isDigit()) this.dropLast(1) to last else this to null
    }

    companion object {
        private val ARPA_TO_IPA = mapOf(
            "AA" to "ɑ", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔ", "AW" to "aʊ",
            "AX" to "ə", "AY" to "aɪ", "EH" to "ɛ", "ER" to "ɝ", "EY" to "eɪ",
            "IH" to "ɪ", "IY" to "i", "OW" to "oʊ", "OY" to "ɔɪ", "UH" to "ʊ", "UW" to "u",
            "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð", "F" to "f", "G" to "g",
            "HH" to "h", "JH" to "dʒ", "K" to "k", "L" to "l", "M" to "m", "N" to "n",
            "NG" to "ŋ", "P" to "p", "R" to "ɹ", "S" to "s", "SH" to "ʃ", "T" to "t",
            "TH" to "θ", "V" to "v", "W" to "w", "Y" to "j", "Z" to "z", "ZH" to "ʒ"
        )
    }
}

