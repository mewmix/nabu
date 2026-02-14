# Nabu

Nabu is an Android app for fully on-device speech + chat:
- Text-to-speech generation
- On-device LLM chat
- Book/long-form playback workflows

This repository is no longer just a Kokoro demo fork. It now integrates multiple TTS engines and model-management flows in one app.

## Repository Layout

- `app/`: Android app UI, playback flows, model download/install, TTS manager
- `app-chat/`: LLM backends and chat integration
- `core-utils/`: shared logging/tracing/runtime helpers

## TTS Engines Integrated

### Kokoro
- Runtime: ONNX Runtime (`NNAPI` when available, CPU fallback)
- Primary upstream:
  - https://huggingface.co/hexgrad/Kokoro-82M
  - https://github.com/thewh1teagle/kokoro-onnx

### Supertonic (v1 and v2 ONNX)
- Runtime: ONNX Runtime (CPU)
- Sources:
  - https://huggingface.co/Supertone/supertonic
  - https://huggingface.co/Supertone/supertonic-2

### Soprano (80M ONNX)
- Runtime: ONNX Runtime (CPU)
- Integrated model id in app: `soprano-80m-onnx`
- Local required files:
  - `soprano_backbone_kv.onnx`
  - `soprano_decoder.onnx`
  - `soprano_decoder.onnx.data`
  - `tokenizer.json`
- Attribution chain:
  - Original Soprano repo and reference inference: https://github.com/ekwek1/soprano
  - ONNX web reference implementation used for behavior parity debugging: https://github.com/KevinAHM/soprano-web-onnx
  - ONNX packaging/distribution used by app downloader: https://huggingface.co/KevinAHM/soprano-onnx

## Model Artifacts and Sizes

Sizes below match the current app manifests/allowlist and Hugging Face metadata as of 2026-02-14.

### TTS Artifacts

| Model | ID | Downloaded artifacts in app | Exact bytes | Size |
|---|---|---|---:|---:|
| Kokoro FP16 graph | `kokoro_fp16` | `model_fp16.onnx` | `341,139,456` | `325.34 MiB` |
| Kokoro INT8 graph | `kokoro_int8` | `model_int8.onnx` | `92,361,271` | `88.08 MiB` |
| Kokoro total bundle | `Kokoro-82M` | FP16 + INT8 from manifest | `433,500,727` | `413.42 MiB` |
| Supertonic v1 bundle | `supertonic-onnx` | 6 ONNX/json files + 4 style files (`F1,F2,M1,M2`) | `264,679,978` | `252.42 MiB` |
| Supertonic v2 bundle | `supertonic-2-onnx` | 6 ONNX/json files + 10 style files (`F1..F5,M1..M5`) | `267,300,126` | `254.92 MiB` |
| Soprano 80M bundle | `soprano-80m-onnx` | `soprano_backbone_kv.onnx`, `soprano_decoder.onnx`, `soprano_decoder.onnx.data`, `tokenizer.json` | `379,409,196` | `361.83 MiB` |

### LLM Artifacts

| Model | ID | Downloaded artifact in app | Exact bytes | Size | Notes |
|---|---|---|---:|---:|---|
| Gemma 3n IT 4B int4 | `gemma-3n-E4B-it-int4` | `gemma-3n-E4B-it-int4.task` | `4,405,655,031` | `4.10 GiB` | gated (`HF token + accepted terms required`) |
| Gemma3 1B IT q4 | `gemma3-1b-it-q4` | `Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task` | `554,661,246` | `528.97 MiB` | public |
| Gemma3 270M IT q8 | `gemma3-270m-it-q8` | `gemma3-270m-it-q8.task` | `303,950,933` | `289.87 MiB` | gated in allowlist |
| Qwen2.5 1.5B Instruct q8 | `qwen2.5-1.5b-instruct-q8` | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` | `1,597,913,616` | `1.49 GiB` | public |

## Experimental GGUF Support

- Status: experimental local-import path for LLMs.
- Import flow: Models screen accepts `.task` and `.gguf` files via file picker.
- Storage path: imported GGUF files are copied to `files/models/<model-id>.gguf`.
- Backend routing: imported `.gguf` models are tagged as backend `llama` and loaded through `LlamaCppBackend`.
- Current limits:
  - No allowlist downloader for GGUF (manual import only).
  - No remote size metadata/checksum flow for GGUF.
  - TTS engines remain ONNX-based (`kokoro`, `supertonic`, `soprano`); GGUF is not used for TTS inference.

## Build

1. Open in Android Studio (Ladybug+ recommended), or use Gradle CLI.
2. Build:

```bash
./gradlew :app:assembleDebug
```

3. Install:

```bash
./gradlew :app:installDebug
```

## Test

Unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Targeted Soprano instrumentation test:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mewmix.nabu.tts.SopranoSelectionInstrumentedTest
```

This test exercises phrase synthesis with:

`do not be alarmed i am simply testing the update for alex`

## Credits

- Original Android base app: https://github.com/puff-dayo/Kokoro-82M-Android
- Kokoro model: https://huggingface.co/hexgrad/Kokoro-82M
- Kokoro ONNX conversion/runtime references: https://github.com/thewh1teagle/kokoro-onnx
- Supertonic models: https://huggingface.co/Supertone/supertonic and https://huggingface.co/Supertone/supertonic-2
- Soprano original model/repo: https://github.com/ekwek1/soprano
- Soprano ONNX web reference: https://github.com/KevinAHM/soprano-web-onnx
- Soprano ONNX model packaging: https://huggingface.co/KevinAHM/soprano-onnx
- Google AI Edge Gallery / MediaPipe LLM references: https://github.com/google-ai-edge/gallery
- IPA transcribers: https://github.com/kotlinguistics/IPA-Transcribers
- jsoup (EPUB/HTML parsing): https://jsoup.org/
