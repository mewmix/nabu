package com.mewmix.nabu

import NabuTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mewmix.nabu.kokoro.KokoroEngine
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.voicestudio.android.AndroidAudioExporter
import com.mewmix.nabu.voicestudio.android.AndroidAudioPlayer
import com.mewmix.nabu.voicestudio.android.AndroidOnnxTtsEngine
import com.mewmix.nabu.voicestudio.core.NarrationGenerator
import com.mewmix.nabu.voicestudio.core.VoicePreset
import com.mewmix.nabu.voicestudio.core.VoicePresetCatalog
import com.mewmix.nabu.voicestudio.ui.VoiceStudioRoot
import com.mewmix.nabu.voicestudio.ui.VoiceStudioViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NabuTheme {
                val vm: VoiceStudioViewModel = viewModel(factory = voiceStudioFactory())
                VoiceStudioRoot(vm)
            }
        }
    }

    private fun voiceStudioFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val presets = VoicePresetCatalog(
                listOf(VoicePreset("af_sarah", "Sarah", "Warm", "kokoro", "kokoro-v1", "af_sarah", 1.0f, "Hello from Nabu"))
            )
            val engine = AndroidOnnxTtsEngine(applicationContext, KokoroEngine(applicationContext), PhonemeConverter(applicationContext), StyleLoader(applicationContext))
            val generator = NarrationGenerator(engine, AndroidAudioExporter(applicationContext), presets)
            @Suppress("UNCHECKED_CAST")
            return VoiceStudioViewModel(presets, generator, AndroidAudioPlayer()) as T
        }
    }
}
