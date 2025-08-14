package com.example.nabu.utils

object PhonemeUtils {
    private val PUNCTUATION_REGEX = Regex("([.,!?;])")

    fun splitPhonemes(phonemes: String, maxLength: Int): List<String> {
        val words = mutableListOf<String>()
        var lastIndex = 0
        for (match in PUNCTUATION_REGEX.findAll(phonemes)) {
            if (lastIndex < match.range.first) {
                words.add(phonemes.substring(lastIndex, match.range.first))
            }
            words.add(match.value)
            lastIndex = match.range.last + 1
        }
        if (lastIndex < phonemes.length) {
            words.add(phonemes.substring(lastIndex))
        }

        val batches = mutableListOf<String>()
        var currentBatch = StringBuilder()
        for (part in words) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            if (currentBatch.length + trimmed.length + 1 >= maxLength) {
                batches.add(currentBatch.toString().trim())
                currentBatch = StringBuilder(trimmed)
            } else {
                if (trimmed in listOf(".", ",", "!", "?", ";")) {
                    currentBatch.append(trimmed)
                } else {
                    if (currentBatch.isNotEmpty()) currentBatch.append(' ')
                    currentBatch.append(trimmed)
                }
            }
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch.toString().trim())
        }
        return batches
    }
}

