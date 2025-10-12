package com.example.nabu.tts.chatterbox

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap

interface LlamaTokenizer {
    fun encode(text: String): IntArray
}

class JsonLlamaTokenizer(tokenizerFile: File) : LlamaTokenizer {
    private val vocab: Map<String, Int>
    private val ranks: Map<Pair<String, String>, Int>
    private val addedTokens: Map<String, Int>
    private val unkId: Int
    private val startId: Int
    private val stopId: Int
    private val startSpeechId: Int
    private val exaggerationId: Int
    private val normalizerRegex = Regex("\\s+")
    private val cache = ConcurrentHashMap<String, List<String>>()

    init {
        val json = JSONObject(tokenizerFile.readText())
        val model = json.getJSONObject("model")
        val vocabObject = model.getJSONObject("vocab")
        val vocabMap = mutableMapOf<String, Int>()
        val keys = vocabObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            vocabMap[key] = vocabObject.getInt(key)
        }
        vocab = vocabMap

        val addedTokensArray = json.optJSONArray("added_tokens") ?: JSONArray()
        val addedMap = HashMap<String, Int>(addedTokensArray.length())
        for (i in 0 until addedTokensArray.length()) {
            val obj = addedTokensArray.getJSONObject(i)
            addedMap[obj.getString("content")] = obj.getInt("id")
        }
        addedTokens = addedMap

        val defaultUnk = vocab[model.optString("unk_token", "[UNK]")]
            ?: addedTokens[model.optString("unk_token", "[UNK]")] ?: 1
        unkId = defaultUnk
        startId = tokenId("[START]")
        stopId = tokenId("[STOP]")
        exaggerationId = tokenId("[EXAGGERATION]")
        startSpeechId = tokenId("[START_SPEECH]")

        val mergesArray = model.optJSONArray("merges") ?: JSONArray()
        val rankMap = HashMap<Pair<String, String>, Int>(mergesArray.length())
        for (i in 0 until mergesArray.length()) {
            val entry = mergesArray.getString(i)
            val parts = entry.split(" ")
            if (parts.size == 2) {
                rankMap[parts[0] to parts[1]] = i
            }
        }
        ranks = rankMap
    }

    override fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val normalized = normalizerRegex.replace(text, " ").trim()
        if (normalized.isEmpty()) return IntArray(0)

        val baseTokens = tokenize(normalized)
        val ids = ArrayList<Int>(baseTokens.size + 5)
        ids.add(exaggerationId)
        ids.add(startId)
        baseTokens.forEach { token ->
            ids.add(tokenId(token))
        }
        ids.add(stopId)
        ids.add(startSpeechId)
        ids.add(startSpeechId)
        return ids.toIntArray()
    }

    private fun tokenize(text: String): List<String> {
        val cached = cache[text]
        if (cached != null) return cached

        val symbols = text.map { it.toString() }.toMutableList()
        if (symbols.isEmpty()) return emptyList()
        if (symbols.size == 1) {
            cache[text] = symbols
            return symbols
        }

        var pairs = computePairs(symbols)
        while (pairs.isNotEmpty()) {
            val candidate = pairs.minByOrNull { ranks[it] ?: Int.MAX_VALUE } ?: break
            if (!ranks.containsKey(candidate)) break

            val first = candidate.first
            val second = candidate.second
            val merged = mutableListOf<String>()

            var i = 0
            while (i < symbols.size) {
                val current = symbols[i]
                if (i < symbols.size - 1 && current == first && symbols[i + 1] == second) {
                    merged.add(current + symbols[i + 1])
                    i += 2
                } else {
                    merged.add(current)
                    i += 1
                }
            }

            symbols.clear()
            symbols.addAll(merged)

            if (symbols.size == 1) break
            pairs = computePairs(symbols)
        }

        cache[text] = symbols.toList()
        return symbols
    }

    private fun computePairs(tokens: List<String>): Set<Pair<String, String>> {
        if (tokens.size < 2) return emptySet()
        val pairs = HashSet<Pair<String, String>>(tokens.size - 1)
        var prev = tokens[0]
        for (i in 1 until tokens.size) {
            val current = tokens[i]
            pairs.add(prev to current)
            prev = current
        }
        return pairs
    }

    private fun tokenId(token: String): Int =
        vocab[token] ?: addedTokens[token] ?: unkId
}
