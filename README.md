# Nabu

Nabu is an on-device test bench for TTS and chat:
- ONNX Runtime (`NNAPI`/CPU) TTS with [Kokoro-82M v1.0](https://huggingface.co/hexgrad/Kokoro-82M), [Supertonic v1](https://huggingface.co/Supertone/supertonic), [Supertonic v2](https://huggingface.co/Supertone/supertonic-2), and [Soprano 1.1 (80M)](https://huggingface.co/ekwek/Soprano-1.1-80M) via [soprano-onnx](https://huggingface.co/KevinAHM/soprano-onnx)
- On-device LLM chat with LiteRT `.task` models (MediaPipe runtime) and experimental `.gguf` support via `llama.cpp`
- E-reader and long-form playback workflows

## Demo Video

- [Watch the demo video](https://github.com/user-attachments/assets/43d1b910-8453-4471-8d85-78144c8b9914)

## Screenshots

<p align="center">
<img src="https://github.com/user-attachments/assets/fa7e8816-41f4-48eb-83c3-29aac0f98251" alt="Mixer" loading="lazy" />
<img src="https://github.com/user-attachments/assets/882237cc-1c0f-40d7-bdb0-2a528846ae32" alt="Conversation Settings" loading="lazy" />
<img src="https://github.com/user-attachments/assets/a34eda22-09a9-4d29-9e26-9ab9208eb3eb" alt="Settings" loading="lazy" />
<img src="https://github.com/user-attachments/assets/da19d300-2a05-4f2b-9b49-0cc0547aa79f" alt="Basic Screen" loading="lazy" />
</p>


## Playground Workflows

- `TTS workbench`: switch engines (`kokoro`, `supertonic`, `soprano`) and compare runtime behavior on-device.
- `LLM workbench`: run local chat models from managed LiteRT `.task` downloads or imported `.gguf` files.
- `Book workflow`: open documents, edit text, save projects/bookmarks, and pre-generate per-line WAV audio for offline playback.
- `Chat + TTS loop`: generate responses with local LLMs and speak them through the active TTS engine.

## TTS Engines Integrated

### Kokoro
- Runtime: ONNX Runtime (`NNAPI` when available, CPU fallback)
- Credits chain:
  - Original Kokoro model: https://huggingface.co/hexgrad/Kokoro-82M
  - ONNX conversion/runtime reference: https://github.com/thewh1teagle/kokoro-onnx
  - Original Android Kokoro app base: https://github.com/puff-dayo/Kokoro-82M-Android

### Supertonic (v1 and v2 ONNX)
- Runtime: ONNX Runtime (CPU)
- Integrated model ids in app: `supertonic-onnx`, `supertonic-2-onnx`
- Credits chain:
  - Original Supertonic project: https://github.com/supertonic-tts/supertonic
  - Supertonic v1 ONNX packaging/distribution: https://huggingface.co/Supertone/supertonic
  - Supertonic v2 ONNX packaging/distribution: https://huggingface.co/Supertone/supertonic-2

### Soprano (80M ONNX)
- Runtime: ONNX Runtime (CPU)
- Integrated model id in app: `soprano-80m-onnx`
- Credits chain:
  - Original Soprano repo and reference inference: https://github.com/ekwek1/soprano
  - ONNX web reference implementation used for behavior parity debugging: https://github.com/KevinAHM/soprano-web-onnx
  - ONNX packaging/distribution used by app downloader: https://huggingface.co/KevinAHM/soprano-onnx

## Model Artifacts and Sources

Source manifests used by the app:
- `app/src/main/java/com/mewmix/nabu/kokoro/Manifest.kt`
- `app/src/main/res/raw/model_allowlist.json`

### TTS Models

| Model | ID |  Source |
|---|---|---|
| Kokoro v1.0 (FP16/INT8) | `kokoro_fp16`, `kokoro_int8` | [ONNX fp16](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX), [INT8 release](https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0) |
| Supertonic v1 | `supertonic-onnx` | [Hugging Face](https://huggingface.co/Supertone/supertonic) |
| Supertonic v2 | `supertonic-2-onnx` | [Hugging Face](https://huggingface.co/Supertone/supertonic-2) |
| Soprano 1.1 (ONNX pkg) | `soprano-80m-onnx` | [Original model](https://huggingface.co/ekwek/Soprano-1.1-80M), [ONNX packaging](https://huggingface.co/KevinAHM/soprano-onnx) |

### LLM Models (`.task`)

| Model | ID |  Source | Access |
|---|---|---|---|
| Gemma 3n IT 4B int4 | `gemma-3n-E4B-it-int4` | [Hugging Face](https://huggingface.co/google/gemma-3n-E4B-it-litert-preview) | gated |
| Gemma3 1B IT q4 | `gemma3-1b-it-q4` | [Hugging Face](https://huggingface.co/litert-community/Gemma3-1B-IT) | public |
| Gemma3 270M IT q8 | `gemma3-270m-it-q8` | [Hugging Face](https://huggingface.co/litert-community/gemma-3-270m-it) | gated in allowlist |
| Qwen2.5 1.5B Instruct q8 | `qwen2.5-1.5b-instruct-q8` | [Hugging Face](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct) | public |


## Experimental GGUF Support

- Status: experimental local-import path for LLMs.
- Import flow: Models screen accepts LiteRT `.task` and `.gguf` files via file picker.
- Storage path: imported GGUF files are copied to `files/models/<model-id>.gguf`.
- Backend routing: imported `.gguf` models are tagged as backend `llama` and loaded through `LlamaCppBackend`.
- Current limits:
  - No allowlist downloader for GGUF (manual import only).
  - No remote size metadata/checksum flow for GGUF.
  - TTS engines remain ONNX-based (`kokoro`, `supertonic`, `soprano`); GGUF is not used for TTS inference.

## Audiobook Workflow File Types

| Type | Format(s) | Used for |
|---|---|---|
| Book input | `.epub` (`application/epub+zip`) | Full book/document ingestion |
| Book input | `.pdf` (`application/pdf`) | Page text extraction and playback |
| Book input | `.txt`, `text/*` | Plain text ingestion and playback |
| Edited book output | `.epub` | Save edited copy from the in-app editor |
| Pre-generated audio cache | `.wav` | Per-line cache in `files/pregenerated/...` |
| User audio export | `.wav` | Saved audio clips to Android `Music/` |

Unknown/other file types fall back to plain text extraction.

## Persistence and Conversation Database

- Local DB: `kokoro.db` (SQLite).
- Chat conversations:
  - Table: `conversations`
  - Stores: `title`, `model_id`, serialized `messages` JSON, `created_at`, `updated_at`
- Audiobook/project state:
  - Table: `projects` (URI, project name, style mix, speed, bookmark line, pregen path, pregen toggle)
  - Table: `audio_lines` (per-line cached WAV file path by document URI + line index)
- Result: chat history, selected model linkage, project settings, bookmarks, and pre-generated line audio survive app restarts.

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
