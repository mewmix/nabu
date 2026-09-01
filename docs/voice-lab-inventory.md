# Voice Lab TTS Inventory

Voice Lab is an internal prototype for evaluating Nabu's current production-usable TTS engines before any standalone Creator Voice Studio work. This document is factual inventory only; it does not rate voice quality and does not provide legal conclusions.

## Architecture Summary

Nabu currently routes TTS through a small `TTSEngine` interface and `TTSManager`, but the production implementations expose engine-specific controls outside the common interface.

| Area | Current implementation |
| --- | --- |
| Common interface | `app/src/main/java/com/mewmix/nabu/tts/TTSEngine.kt` exposes `synthesize(text, speed)`, `sampleRate`, `name`, and `provider`. |
| Engine selection | `app/src/main/java/com/mewmix/nabu/tts/TTSManager.kt` selects Kokoro, Supertonic, or Soprano from persisted settings. |
| Voice Lab adapter | `app/src/main/java/com/mewmix/nabu/voicelab/VoiceLabRepository.kt` adapts engines without changing global TTS settings during comparisons. |
| Kokoro synthesis | Text is phonemized with `PhonemeConverter`, converted to tokens, then synthesized with `KokoroEngine.synth(...)` through `createAudio(...)`. |
| Supertonic synthesis | Downloaded ONNX bundles are loaded from `filesDir/models/<modelId>`; style JSON files under `voice_styles/` are passed to the engine. |
| Soprano synthesis | Downloaded ONNX files are loaded from `filesDir/models/soprano-80m-onnx`; sampling controls are applied through `SopranoSamplingConfig`. |
| Playback | `KokoroAudioPlayer` plays mono `FloatArray` PCM through `AudioTrack`. |
| WAV export | `saveAudio(...)` and `saveAudioInternal(...)` write 16-bit mono WAV files. |
| Model downloads | `ModelDownloader` downloads TTS model bundles from the allowlist and `TtsModelValidator` checks required files. |

## Engine Inventory

| Engine | Implementation classes | Model(s) | Available parameters | Runtime requirements | Offline | Export |
| --- | --- | --- | --- | --- | --- | --- |
| Kokoro | `KokoroEngine`, `KokoroLoader`, `OnnxRuntimeManager`, `PhonemeConverter`, `createAudio` | `kokoro_fp16`, `kokoro_int8`; bundled `assets/kokoro/kokoro.onnx` / `res/raw/kokoro.onnx` fallback | voice/style vector, speed | ONNX Runtime Android; CPU or NNAPI preference via `RunEp` | Yes after bundled/provisioned/downloaded model is present | WAV via existing exporter |
| Supertonic 2 | `DebugSupertonicEngine`, `Supertonic2Engine`, `SupertonicShared` | `supertonic-2-onnx` downloaded from `Supertone/supertonic-2` | voice style, speed, total inference steps, language | ONNX Runtime Android CPU; downloaded model directory with four ONNX files, `tts.json`, `unicode_indexer.json`, and style JSONs | Yes after download | WAV via existing exporter |
| Supertonic 3 | `DebugSupertonicEngine`, `Supertonic2Engine`, `SupertonicShared` | `supertonic-3-onnx` downloaded from `Supertone/supertonic-3` | voice style, speed, total inference steps, language | ONNX Runtime Android CPU; same local bundle layout as Supertonic 2 | Yes after download | WAV via existing exporter |
| Soprano | `SopranoEngine`, `SopranoTokenizer`, `SopranoTextNormalizer` | `soprano-80m-onnx` downloaded from `KevinAHM/soprano-onnx` | temperature, topK, topP, repetition penalty | ONNX Runtime Android CPU; backbone, decoder, external decoder data, tokenizer | Yes after download | WAV via existing exporter |

## Voice Inventory

`Creator Quality`, `Suggested Category`, and `Evaluator Notes` are intentionally blank for later human evaluation.

| Engine | Voice ID | Model | Supported parameters | Model/download requirement | Approximate model size | Render status | Creator Quality | Suggested Category | Evaluator Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Kokoro | af_alloy | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | INT8 manifest 92,361,271 bytes; FP16 manifest 341,139,456 bytes; voice asset about 510 KB | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_aoede | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_bella | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_heart | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_jessica | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_kore | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_nicole | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_nova | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_river | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_sarah | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | af_sky | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_adam | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_echo | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_eric | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_fenrir | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_liam | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_michael | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_onyx | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_puck | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | am_santa | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | bf_alice | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | bf_emma | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | bf_isabella | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | bf_lily | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | bm_daniel | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | bm_fable | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | bm_george | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | bm_lewis | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | ef_dora | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | em_alex | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | em_santa | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | ff_siwis | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | hf_alpha | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | hf_beta | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | hm_omega | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | hm_psi | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | if_sara | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | im_nicola | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | jf_alpha | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | jf_gongitsune | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | jf_nezumi | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | jf_tebukuro | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | jm_kumo | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | pf_dora | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | pm_alex | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | pm_santa | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | zf_xiaobei | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | zf_xiaoni | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | zf_xiaoxiao | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | zf_xiaoyi | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | zm_yunjian | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | zm_yunxi | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | zm_yunxia | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Kokoro | zm_yunyang | kokoro-82m | speed, style vector | Bundled assets or Kokoro model download | same as Kokoro | Ready if Kokoro initializes |  |  |  |
| Supertonic 2 | F1 | supertonic-2-onnx | speed, totalStep, language | Download required unless already present in app model storage | 255 MB on API 35 emulator after download | Emulator preview/export passed on 2026-08-28; 17.61s audio generated in 1.237s, RTF about 0.070 |  |  | Technical validation only; audible quality not evaluated in headless emulator. |
| Supertonic 2 | F2 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 2 | F3 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 2 | F4 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 2 | F5 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 2 | M1 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 2 | M2 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 2 | M3 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 2 | M4 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 2 | M5 | supertonic-2-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | F1 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | F2 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | F3 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | F4 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | F5 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | M1 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | M2 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | M3 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | M4 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Supertonic 3 | M5 | supertonic-3-onnx | speed, totalStep, language | Download required | exact size determined on device after download | Ready only when validator passes |  |  |  |
| Soprano | soprano-default | soprano-80m-onnx | temperature, topK, topP, repetitionPenalty | Download required | validator requires backbone >=200 MB and decoder data >=20 MB | Ready only when validator passes |  |  | Current implementation exposes no alternate voices. |

## Implementation Notes And Limitations

- Kokoro's `TTSEngine.synthesize(text, speed)` throws by design; use the higher-level adapter path that phonemizes first.
- Supertonic's generic `TTSEngine.synthesize(text, speed)` also depends on a selected style. Voice Lab loads the selected style JSON and calls the language-aware synthesis overload.
- Supertonic 3 model docs mention expression tags such as `<laugh>`, `<breath>`, and `<sigh>`, but Nabu's current implementation does not expose a dedicated expression control. Voice Lab treats those as text-level behavior, not a UI parameter.
- Soprano accepts a `speed` argument through `TTSEngine`, but the current implementation does not appear to apply speed during generation. Voice Lab does not expose Soprano speed until implementation support is verified.
- Exact Supertonic model size is determined from downloaded files on-device because the current Nabu model allowlist stores bundle URLs, not byte-size metadata.
- Voice Lab currently stores recent preview renders only in memory for the active screen session.
- Real-device audio smoke testing is still required. Use `docs/voice-lab-smoke-test.md` and copy confirmed render results back into this inventory.
- Initial headless emulator smoke test confirmed Kokoro preview/export on Android 15/API 35. A later headless emulator run confirmed Supertonic 2 download, preview, and WAV export. Supertonic 3 and Soprano still require downloaded-model testing.

## Licensing Inventory

This section records observed license metadata and files. It is not legal advice.

| Component | Observed license | Source URL/file | Covered material | Commercial-review flag |
| --- | --- | --- | --- | --- |
| Nabu app | GPL-3.0-only in app metadata; GPL-3.0 text in `LICENSE` | `com.mewmix.nabu.yml`, `LICENSE` | Nabu repository code | Required before extracting reusable code into a paid app. |
| Upstream Kokoro Android app base | GPL-3.0 license shown by GitHub | https://github.com/puff-dayo/Kokoro-82M-Android | Android demo app code lineage | Required because Nabu appears GPL and README credits this base. |
| `kokoro-onnx` vendor submodule | MIT | `vendor/kokoro-onnx/LICENSE`, `vendor/kokoro-onnx/README.md` | ONNX helper/reference code | Attribution/notice review required. |
| Kokoro model | Apache-2.0 on Hugging Face model card | https://huggingface.co/hexgrad/Kokoro-82M | Original Kokoro model weights/assets | Review training-data attribution notes before commercial distribution. |
| Kokoro ONNX FP16 | Apache-2.0 inherited from Kokoro model card; source is ONNX community packaging | https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX | ONNX model artifact used by Nabu manifest | Confirm packaging license and attribution file before commercial distribution. |
| Kokoro INT8 | `kokoro-onnx` MIT code plus Kokoro Apache-2.0 model attribution noted upstream | https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0 | Quantized ONNX model artifact | Confirm release artifact license chain before commercial distribution. |
| Supertonic 2 model | OpenRAIL / OpenRAIL-M shown on Hugging Face model card | https://huggingface.co/Supertone/supertonic-2 | Supertonic 2 ONNX model and voice styles | Required because OpenRAIL use restrictions may affect commercial product policy. |
| Supertonic 3 model | OpenRAIL / OpenRAIL-M shown on Hugging Face model card | https://huggingface.co/Supertone/supertonic-3 | Supertonic 3 ONNX model and voice styles | Required because OpenRAIL use restrictions may affect commercial product policy. |
| Supertonic sample code / SDK | MIT according to model card / PyPI metadata | https://pypi.org/project/supertonic/ | Upstream reference code, not directly vendored here | Confirm whether any copied implementation code exists in Nabu. |
| Soprano original repo | Apache-2.0 | https://github.com/ekwek1/soprano | Original Soprano code/reference implementation | Attribution/notice review required. |
| Soprano original model | Apache-2.0 on Hugging Face model card | https://huggingface.co/ekwek/Soprano-1.1-80M | Soprano 1.1 model weights | Review limitations and model license file before commercial distribution. |
| Soprano ONNX package | Apache-2.0 on Hugging Face model card | https://huggingface.co/KevinAHM/soprano-onnx | ONNX export loaded by Nabu | Confirm ONNX packaging license file and upstream model compatibility. |
| ONNX Runtime Android | Microsoft / MIT-style ONNX Runtime license expected from dependency | `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` | Runtime dependency | Include dependency notice in full third-party audit. |
| IPA-Transcribers | Dependency license not reviewed here | `com.github.medavox:IPA-Transcribers:v0.2` | Kokoro phonemization fallback | Needs license lookup before commercial extraction. |
| `org.jetbrains.bio:npy` | Dependency license not reviewed here | `org.jetbrains.bio:npy:0.3.5` | `.npy` voice-vector loading | Needs license lookup before commercial extraction. |
