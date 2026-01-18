package com.mewmix.nabu.utils

interface AudioPlayer {
    fun prepare(audio: FloatArray, sampleRate: Int, position: Int = 0)
    fun play()
    suspend fun playBlocking()
    fun pause()
    fun stop()
    fun isPlaying(): Boolean
    fun getState(): PlayerState
    fun getPosition(): Int
}
