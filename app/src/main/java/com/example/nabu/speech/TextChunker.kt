package com.example.nabu.speech

/**
 * Simple text chunker that splits text into sentences or manageable chunks
 * for streaming TTS synthesis.
 */
object TextChunker {
    /**
     * Split text into chunks based on sentence boundaries.
     * Falls back to word-based chunking if sentences are too long.
     */
    fun chunkText(text: String, maxChunkLength: Int = 500): List<String> {
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<String>()
        
        // First, try to split by sentences
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        
        for (sentence in sentences) {
            if (sentence.length <= maxChunkLength) {
                chunks.add(sentence.trim())
            } else {
                // Sentence is too long, split by words
                val words = sentence.split(Regex("\\s+"))
                val currentChunk = StringBuilder()
                
                for (word in words) {
                    if (currentChunk.length + word.length + 1 > maxChunkLength) {
                        if (currentChunk.isNotEmpty()) {
                            chunks.add(currentChunk.toString().trim())
                            currentChunk.clear()
                        }
                    }
                    if (currentChunk.isNotEmpty()) {
                        currentChunk.append(" ")
                    }
                    currentChunk.append(word)
                }
                
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                }
            }
        }
        
        return chunks.filter { it.isNotBlank() }
    }
}
