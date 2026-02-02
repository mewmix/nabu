package com.mewmix.nabu.soprano

import com.google.gson.Gson
import java.io.File

class SopranoTokenizer(private val modelDir: File) {
    private val gson = Gson()
    private val vocab: Map<String, Int>
    private val merges: List<Pair<String, String>>
    private val specialTokens: Map<String, Int>
    private val sortedSpecialTokens: List<String>

    init {
        val tokenizerFile = File(modelDir, "tokenizer.json")
        if (!tokenizerFile.exists()) {
            throw IllegalStateException("tokenizer.json not found in ${modelDir.absolutePath}")
        }

        val json = gson.fromJson(tokenizerFile.readText(), TokenizerJson::class.java)
        vocab = json.model.vocab
        // Merges in JSON are usually "a b", we split them into pairs
        merges = json.model.merges.map {
            val parts = it.split(" ")
            if (parts.size != 2) throw IllegalStateException("Invalid merge: $it")
            parts[0] to parts[1]
        }

        // Identify special tokens. Usually defined in added_tokens or just vocab with specific format.
        // We'll trust the vocab for [START], [STOP], [TEXT] etc.
        specialTokens = vocab.filterKeys { it.startsWith("[") && it.endsWith("]") }
        // Sort by length descending to match longest first
        sortedSpecialTokens = specialTokens.keys.sortedByDescending { it.length }
    }

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

    // JSON Structure
    private data class TokenizerJson(
        val model: Model
    )

    private data class Model(
        val vocab: Map<String, Int>,
        val merges: List<String>
    )
}
