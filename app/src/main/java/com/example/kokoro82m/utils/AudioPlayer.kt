package com.example.kokoro82m.utils

interface AudioPlayer {
    fun prepare(audio: FloatArray, position: Int = 0)
    fun play()
    suspend fun playBlocking()
    fun pause()
    fun stop()
    fun isPlaying(): Boolean
    fun getState(): PlayerState
    fun getPosition(): Int
}
