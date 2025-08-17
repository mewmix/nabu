package com.mewmix.nabu.kitten

/**
 * Simple tokenizer turning IPA characters into token ids using a fixed map.
 */
class KittenTokenizer(
    ipaToId: Map<String, Int>,
    private val unkId: Int
) {
    private val map = ipaToId

    fun encode(ipa: String): IntArray {
        val chars = ipa.toCharArray()
        val out = IntArray(chars.size)
        var i = 0
        for (c in chars) {
            out[i++] = map[c.toString()] ?: unkId
        }
        return out
    }
}
