package com.mewmix.nabu.soprano

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File

class SopranoTokenizer(private val modelDir: File) {
    private val vocab: Map<String, Int>
    private val merges: List<Pair<String, String>>
    private val specialTokens: Map<String, Int>
    private val sortedSpecialTokens: List<String>

    init {
        val tokenizerFile = File(modelDir, "tokenizer.json")
        if (!tokenizerFile.exists()) {
            throw IllegalStateException("tokenizer.json not found in ${modelDir.absolutePath}")
        }

        val root = JsonParser.parseString(tokenizerFile.readText()).asJsonObject
        val model = root.getAsJsonObject("model")
            ?: throw IllegalStateException("tokenizer.json missing model object")
        val vocabJson = model.getAsJsonObject("vocab")
            ?: throw IllegalStateException("tokenizer.json missing vocab object")
        vocab = buildMap(vocabJson.entrySet().size) {
            for ((token, idElement) in vocabJson.entrySet()) {
                put(token, idElement.asInt)
            }
        }

        // Merges can be either "a b" strings or ["a","b"] arrays depending on tokenizer export.
        val mergesJson = model.getAsJsonArray("merges")
            ?: throw IllegalStateException("tokenizer.json missing merges array")
        merges = List(mergesJson.size()) { index ->
            parseMergeEntry(mergesJson[index])
        }

        // Identify special tokens. Usually defined in added_tokens or just vocab with specific format.
        // We'll trust the vocab for [START], [STOP], [TEXT] etc.
        specialTokens = vocab.filterKeys { it.startsWith("[") && it.endsWith("]") }
        // Sort by length descending to match longest first
        sortedSpecialTokens = specialTokens.keys.sortedByDescending { it.length }
    }

    fun idFor(token: String): Int? = specialTokens[token]

    fun encode(text: String): LongArray {
        val ids = mutableListOf<Long>()

        // 1. Split by special tokens
        var remainingText = text
        val segments = mutableListOf<Pair<String, Boolean>>() // Text, IsSpecial

        while (remainingText.isNotEmpty()) {
            var foundSpecial: String? = null
            var foundIndex = -1

            for (st in sortedSpecialTokens) {
                val idx = remainingText.indexOf(st)
                if (idx != -1) {
                    if (foundIndex == -1 || idx < foundIndex) {
                        foundIndex = idx
                        foundSpecial = st
                    }
                }
            }

            if (foundSpecial != null && foundIndex != -1) {
                if (foundIndex > 0) {
                    segments.add(remainingText.substring(0, foundIndex) to false)
                }
                segments.add(foundSpecial to true)
                remainingText = remainingText.substring(foundIndex + foundSpecial.length)
            } else {
                segments.add(remainingText to false)
                break
            }
        }

        // 2. Process segments
        for ((segText, isSpecial) in segments) {
            if (isSpecial) {
                val id = specialTokens[segText] ?: 0
                ids.add(id.toLong())
            } else {
                // BPE on the segment
                // We treat the segment as a sequence of characters (including spaces)
                // This mimics "Char BPE" without pre-tokenization splitting constraints
                if (segText.isEmpty()) continue

                var tokens = segText.map { it.toString() }.toMutableList()

                // Map pair to rank
                val mergeRanks = merges.withIndex().associate { (index, pair) -> "${pair.first} ${pair.second}" to index }

                while (tokens.size > 1) {
                    var bestPairIdx = -1
                    var bestRank = Int.MAX_VALUE

                    for (i in 0 until tokens.size - 1) {
                        val pair = "${tokens[i]} ${tokens[i+1]}"
                        val rank = mergeRanks[pair]
                        if (rank != null && rank < bestRank) {
                            bestRank = rank
                            bestPairIdx = i
                        }
                    }

                    if (bestPairIdx == -1) break

                    val first = tokens[bestPairIdx]
                    val second = tokens[bestPairIdx+1]
                    val merged = first + second

                    tokens[bestPairIdx] = merged
                    tokens.removeAt(bestPairIdx + 1)
                }

                for (token in tokens) {
                    val id = vocab[token] ?: vocab["[UNK]"] ?: 0
                    ids.add(id.toLong())
                }
            }
        }

        return ids.toLongArray()
    }

    fun decode(ids: LongArray): String {
        val reverseVocab = vocab.entries.associate { (k, v) -> v to k }
        return ids.joinToString("") { reverseVocab[it.toInt()] ?: "" }
    }

    private fun parseMergeEntry(value: JsonElement): Pair<String, String> {
        return when {
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                val merge = value.asString
                val parts = merge.split(" ")
                if (parts.size != 2) throw IllegalStateException("Invalid merge: $merge")
                parts[0] to parts[1]
            }
            value.isJsonArray -> {
                val mergeArray = value.asJsonArray
                if (mergeArray.size() != 2) {
                    throw IllegalStateException("Invalid merge list length: ${mergeArray.size()}")
                }
                val first = mergeArray[0]
                val second = mergeArray[1]
                if (!first.isJsonPrimitive || !first.asJsonPrimitive.isString ||
                    !second.isJsonPrimitive || !second.asJsonPrimitive.isString
                ) {
                    throw IllegalStateException("Invalid merge list: $mergeArray")
                }
                first.asString to second.asString
            }
            else -> throw IllegalStateException("Unsupported merge token type: $value")
        }
    }
}
