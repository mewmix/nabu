# TTS Workbench Smoke Test

Use this checklist for each device build before treating Audio or Mixer results as product evidence.

Current quality baseline and remaining risk notes are tracked in `docs/voice-lab-quality-baseline.md`.

Run `scripts/check-voice-lab.sh` before using a build for smoke testing.

When an emulator or physical Android device is attached, run:

```bash
scripts/check-voice-lab-connected.sh
```

That connected gate runs focused Compose smoke tests:

- `TtsWorkbenchSmokeTest` verifies Audio is reachable from the app shell and exposes stable automation tags for the script input, engine selector, voice selector, parameter controls, preview, full render, and playback controls.
- `ModelsSmokeTest` verifies the Models screen is reachable and every known TTS model row exposes stable row/action hooks without starting downloads or deleting files.

## Device Setup

- Install the debug APK from `app/build/outputs/apk/debug/app-debug.apk`.
- Open Nabu and navigate to Audio, then Mixer.
- Confirm each workspace restores its own saved engine, voice, parameters, and text.
- Confirm Android volume is audible and Do Not Disturb is not blocking playback.
- Confirm storage/export permissions are granted if prompted.

## Per-Engine Checklist

Run this checklist for Kokoro, Supertonic 2, Supertonic 3, and Soprano where the engine is available.

| Check | Result | Notes |
| --- | --- | --- |
| Engine appears in selector |  |  |
| Availability state is accurate |  |  |
| Missing model state is understandable |  |  |
| Voices/styles appear |  |  |
| Voice metadata is factual |  |  |
| Supported parameter controls appear |  |  |
| Reset restores defaults |  |  |
| Preview renders first sentence/window only |  |  |
| Full render completes |  |  |
| Play works |  |  |
| Pause works |  |  |
| Restart works |  |  |
| WAV export creates a playable file |  |  |
| Export filename includes workspace, engine, and voice |  |  |
| Diagnostics show generation time |  |  |
| Diagnostics show audio duration |  |  |
| Diagnostics show real-time factor |  |  |
| Diagnostics show output size |  |  |
| Diagnostics show model/backend |  |  |
| Repeated previews do not crash or leak obvious resources |  |  |

## Render Samples

Use the default script first:

> Nabu Voice Lab is testing creator narration for Alex Rivera in 2026. The sample includes 42 chapters, $19.95, commas, pauses, and a question: does this voice sound natural? Great, let's render it!

Then run one longer custom script with multiple paragraphs, at least one abbreviation, and at least one number.

## Emulator-Only Limits

Emulator smoke tests can validate initialization, render completion, generated WAV format, export path, model footprint, and relative generation speed. They do not replace real-device checks for audible quality, hardware playback, thermals, OEM storage behavior, or final creator-quality ratings.

The connected Compose smoke tests do not synthesize audio or start downloads yet. They are UI reachability and automation-hook checks. Engine render/export validation remains a manual or supervised emulator step until the model-download flow has an explicit, safe automation path.

## Results To Copy Into Inventory

After testing, update `docs/voice-lab-inventory.md` with:

- render status per engine and voice
- observed generation time and RTF ranges
- crashes or initialization failures
- model directory size after download
- device model and Android version
- backend actually reported by Voice Lab
- export/playback problems

Do not add subjective voice-quality ratings until a human evaluator supplies them.

## Smoke Runs

### 2026-08-27 Emulator: NabuVoiceLabApi35

- Device: `sdk_gphone64_arm64`
- Android: 15 / API 35
- Install: `app-debug.apk` installed successfully
- Navigation: first-run setup completed with default Kokoro selection; More -> Voice Lab opened successfully
- Engine states:
  - Kokoro: Ready, backend reported as `CPU/kokoro_int8`
  - Supertonic 2: Download required
  - Supertonic 3: Download required
  - Soprano: Download required; missing `soprano_backbone_kv.onnx`, `soprano_decoder.onnx`, `soprano_decoder.onnx.data`, `tokenizer.json`
- Kokoro preview:
  - voice: `af_alloy`
  - UI duration: 16.45s
  - log backend/model: `kokoro_int8`, CPU
  - playback started without crash in headless emulator
- Export:
  - exported file: `/sdcard/Music/VOICE_LAB_kokoro_af_alloy_20260827_205244.wav`
  - pulled file size: 789,644 bytes
  - local file inspection: RIFF/WAVE, 16-bit mono PCM, 24,000 Hz
- Storage:
  - app-private `files`: 88 MB
  - copied Kokoro model: `files/models/kokoro-int8/model_int8.onnx`, 92,360,686 bytes
- Not tested:
  - audible voice quality, because emulator was booted headless with `-no-audio`
  - Supertonic 2, Supertonic 3, and Soprano synthesis, because models were not downloaded

### 2026-08-28 Emulator: NabuVoiceLabApi35

- Device: `sdk_gphone64_arm64`
- Android: 15 / API 35
- Quality gate before smoke:
  - `scripts/check-voice-lab.sh` passed
- Install:
  - current `app-debug.apk` installed successfully with `adb install -r`
- Supertonic 2 download:
  - started from Models screen
  - completed through Nabu's `ModelDownloader`
  - downloaded files included `duration_predictor.onnx`, `text_encoder.onnx`, `vector_estimator.onnx`, `vocoder.onnx`, `tts.json`, `unicode_indexer.json`, and 10 `voice_styles/*.json` files
  - app-private model size after download: 255 MB for `files/models/supertonic-2-onnx`
  - total app-private model storage after download: 343 MB
- Supertonic 2 Voice Lab state:
  - engine selector reported `Supertonic 2 TTS (Ready)`
  - selected voice/style: `F1`
  - visible controls: speed, steps, language
- Supertonic 2 preview:
  - input: default Voice Lab script
  - log render time: 1,237 ms
  - UI/audio duration: 17.61s
  - calculated RTF: about 0.070
  - playback started without crash in headless emulator
- Export:
  - exported file: `/sdcard/Music/VOICE_LAB_supertonic-2-onnx_F1_20260828_163425.wav`
  - pulled file size: 1,552,972 bytes
  - local file inspection: RIFF/WAVE, 16-bit mono PCM, 44,100 Hz
- Not tested:
  - audible voice quality, because emulator was booted headless with `-no-audio`
  - Supertonic 3 and Soprano synthesis, because this run stopped after a coordinate-only tap became ambiguous
- Note:
  - A later coordinate-only attempt to start Supertonic 3 hit the wrong control in Models. App-private storage still contained Kokoro INT8 and Supertonic 2 afterward. Further downloadable-engine validation should use a safer UI automation hook, direct visible hierarchy parsing, or manual supervision.

### 2026-08-29 Emulator: NabuVoiceLabApi35

- Device: `sdk_gphone64_arm64`
- Android: 15 / API 35
- Connected smoke gate:
  - initial run failed because tests assumed the bottom navigation `MORE` item was immediately visible
  - tests were updated to seed first-run settings and launch `MainActivity` directly with `EXTRA_START_SCREEN`
  - `scripts/check-voice-lab-connected.sh` passed after the fix
- Coverage confirmed:
  - The prototype Compose smoke test passed
  - `ModelsSmokeTest` passed
  - Voice Lab screen is reachable and tagged controls are visible
  - Models screen is reachable and known TTS model rows expose download/delete automation hooks
- Quality gate:
  - `scripts/check-voice-lab.sh` passed after the connected smoke-test fix
- Remaining limitation:
  - connected smoke tests still do not synthesize audio or start model downloads
