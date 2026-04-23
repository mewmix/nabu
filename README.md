# Nabu

Nabu is an on-device test bench for TTS and chat:
- ONNX Runtime (`NNAPI`/CPU) TTS with [Kokoro-82M v1.0](https://huggingface.co/hexgrad/Kokoro-82M), [Supertonic v2](https://huggingface.co/Supertone/supertonic-2), and [Soprano 1.1 (80M)](https://huggingface.co/ekwek/Soprano-1.1-80M) via [soprano-onnx](https://huggingface.co/KevinAHM/soprano-onnx)
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

### Supertonic (v2 ONNX)
- Runtime: ONNX Runtime (CPU)
- Integrated model ids in app: `supertonic-2-onnx`
- Credits chain:
  - Original Supertonic project: https://github.com/supertonic-tts/supertonic
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
| Supertonic v2 | `supertonic-2-onnx` | [Hugging Face](https://huggingface.co/Supertone/supertonic-2) |
| Soprano 1.1 (ONNX pkg) | `soprano-80m-onnx` | [Original model](https://huggingface.co/ekwek/Soprano-1.1-80M), [ONNX packaging](https://huggingface.co/KevinAHM/soprano-onnx) |

### LLM Models (`.task`, `.litertlm`)

| Model | ID |  Source | Access |
|---|---|---|---|
| Gemma 3n IT 4B int4 | `gemma-3n-E4B-it-int4` | [Hugging Face](https://huggingface.co/google/gemma-3n-E4B-it-litert-preview) | gated |
| Gemma3 1B IT q4 | `gemma3-1b-it-q4` | [Hugging Face](https://huggingface.co/litert-community/Gemma3-1B-IT) | public |
| Gemma3 270M IT q8 | `gemma3-270m-it-q8` | [Hugging Face](https://huggingface.co/litert-community/gemma-3-270m-it) | gated in allowlist |
| Gemma 4 E2B IT | `gemma-4-E2B-it` | [Hugging Face](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | public |
| Qwen2.5 1.5B Instruct q8 | `qwen2.5-1.5b-instruct-q8` | [Hugging Face](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct) | public |


## Experimental GGUF Support

- Status: experimental local-import path for LLMs.
- Import flow: Models screen accepts LiteRT `.task`, LiteRT-LM `.litertlm`, and `.gguf` files via file picker.
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

## Local API Server

Nabu includes an opt-in local REST API server for on-device inference, exposing both text-to-speech and an OpenAI-compatible `/v1/chat/completions` endpoint.

- Default bind: `127.0.0.1:8455`
- Optional LAN bind: `0.0.0.0:8455` (enable in Settings)
- Security note: there is no API auth layer yet; use LAN exposure only on trusted networks.

Enable it from Settings:
- `Enable API Server`
- `Expose API on LAN` (optional)

### Agentic Tool Calling (OpenCode & Open Interpreter)

Nabu fully supports the OpenAI `tools` specification for agentic function calling over its local API. You can direct robust tooling environments like OpenCode and [Open Interpreter](https://github.com/OpenInterpreter/open-interpreter) to use Nabu as their LLM backend.

Nabu intercepts the system tool prompts, parses `<tool_call>` outputs efficiently, and maps them to standard JSON `{"finish_reason": "tool_calls"}` stream chunks.

#### Fuzzy Tool Context Injection

Nabu employs a **fuzzy matching** strategy to minimize prompt bloat. Instead of injecting all available tools into every request, it analyzes the user's query and the conversation context to dynamically inject only the most relevant tool definitions.

#### list_tools for On-Demand Discovery

Models can also utilize the `list_tools` function to discover available capabilities at runtime. This allows for a hierarchical discovery flow where the model first identifies relevant tools before asking for their full schemas.

#### Glaive File Manager & Local Tools

If you install the **[Glaive File Manager](https://github.com/mewmix/glaive)** alongside Nabu, you can grant Nabu direct tool calling capabilities over the Android device's file system. This allows in app or external providers to command Nabu or Glaive to list directories, read files, and manage external storage directly from the LLM context.

### Experimental Codex OAuth

Nabu includes experimental support for connecting to Codex model family via OAuth.

- You can authenticate with Codex directly from settings.
- Once authenticated, Codex models will appear in the `Remote` tab of the model selector.
- These remote models fully support the OpenCode and Open Interpreter API tooling workflows just like the local models.

### Health

`GET /health`

Returns:

```json
{"ok":true}
```

### Model Listing

Endpoint paths for checking loaded/downloaded resources:
- `GET /models` (Returns Nabu internal format)
- `GET /v1/models` (Returns standard OpenAI model list JSON footprint)
- `GET /tts/models`
- `GET /v1/tts/models`

Query by type: `?type=llm|tts|all`

### LLM Generation

- `POST /generate` (Nabu flat object payload)
- `POST /v1/chat/completions` (OpenAI-compatible shape)

`POST /v1/chat/completions` expects `messages` and optionally `tools`:

```json
{
  "model": "gemma3-1b-it-q4",
  "messages": [
    {"role": "user", "content": "What is the weather?"}
  ],
  "tools": [{
    "type": "function",
    "function": {
      "name": "get_weather",
      "description": "Get weather for a location",
      "parameters": {
        "type": "object",
        "properties": {
          "location": { "type": "string" }
        },
        "required": ["location"]
      }
    }
  }],
  "stream": true
}
```

Streaming `/v1/chat/completions` follows OpenAI-style SSE chunk events yielding `delta.content` strings, or `delta.tool_calls` JSON buffers, ending in `data: [DONE]`.

### TTS Generation

- `POST /tts/speech`
- `POST /v1/audio/speech`

Request fields:
- `input` or `text` (required)
- `engine` optional: `kokoro`, `supertonic`, `soprano`
- `model` optional: e.g. `soprano-80m-onnx`, `supertonic-2-onnx`
- `voice`/`style` optional
- `speed` optional (default `1.0`)
- `response_format` optional: `wav` (default) or `json`

`response_format: "wav"` returns `audio/wav` bytes.  
`response_format: "json"` returns base64-encoded WAV plus metadata.

---

### Curl Examples

#### ADB Port Forwarding

To test the API locally from your host machine over USB/WiFi:
```bash
adb forward tcp:8455 tcp:8455
```

#### Health Check
```bash
curl http://127.0.0.1:8455/health
```

#### List Available LLMs (OpenAI Format)
```bash
curl "http://127.0.0.1:8455/v1/models?type=llm"
```

#### Generate TTS WAV to File
```bash
curl -s -X POST "http://127.0.0.1:8455/v1/audio/speech" \
  -H "Content-Type: application/json" \
  -d '{"input":"Welcome to Nabu on device AI","engine":"kokoro","response_format":"wav"}' \
  --output test_speech.wav
```

#### Simple OpenAI Chat Completion
```bash
curl -X POST "http://127.0.0.1:8455/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model":"gemma3-1b-it-q4",
    "messages":[{"role":"user","content":"Name three fast animals."}],
    "stream":false
  }'
```

#### Stream OpenAI Chat Completion
```bash
curl -N -X POST "http://127.0.0.1:8455/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model":"gemma3-1b-it-q4",
    "messages":[{"role":"user","content":"Say hello in five words."}],
    "stream":true
  }'
```

#### Send OpenCode Tool Call Request
```bash
curl -X POST "http://127.0.0.1:8455/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma3-1b-it-q4",
    "messages": [
      { "role": "user", "content": "What is 55 times 12?" }
    ],
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "multiply",
          "description": "Multiply two numbers",
          "parameters": {
            "type": "object",
            "properties": {
              "a": { "type": "number" },
              "b": { "type": "number" }
            }
          }
        }
      }
    ],
    "stream": false
  }'
```

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
- Supertonic models: https://huggingface.co/Supertone/supertonic-2
- Soprano original model/repo: https://github.com/ekwek1/soprano
- Soprano ONNX web reference: https://github.com/KevinAHM/soprano-web-onnx
- Soprano ONNX model packaging: https://huggingface.co/KevinAHM/soprano-onnx
- Google AI Edge Gallery / MediaPipe LLM references: https://github.com/google-ai-edge/gallery
- IPA transcribers: https://github.com/kotlinguistics/IPA-Transcribers
- jsoup (EPUB/HTML parsing): https://jsoup.org/
