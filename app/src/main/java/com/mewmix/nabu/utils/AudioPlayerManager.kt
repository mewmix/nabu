package com.mewmix.nabu.utils

object AudioPlayerManager {
    var player: AudioPlayer? = null
    var onStop: (() -> Unit)? = null
}
