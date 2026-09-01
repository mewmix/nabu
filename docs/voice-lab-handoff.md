# Voice Lab Handoff

The Voice Lab prototype has been incorporated into Nabu's production Audio and Mixer workspaces. Audio now owns direct voice generation; Mixer owns voice blending and A/B configuration comparison. Both use the same explicit per-render engine boundary, and the standalone Voice Lab destination has been retired.

## Product Goal

Give creators one coherent TTS workflow: generate and tune a single voice in Audio, shape and compare configurations in Mixer, and keep engine selection, playback, export, diagnostics, and saved workspace state consistent between them.

## Repository

- Upstream repository: `https://github.com/mewmix/nabu`
- Working fork: `https://github.com/djzeroone/nabu`
- Branch: `codex/voice-lab-prototype`
- Pull request: `https://github.com/mewmix/nabu/pull/95`

## Get the Code

```bash
git clone https://github.com/djzeroone/nabu.git
cd nabu
git checkout codex/voice-lab-prototype
```

If the repository was already cloned:

```bash
git fetch fork codex/voice-lab-prototype
git checkout codex/voice-lab-prototype
git pull
```

## Local Requirements

- JDK 17
- Android SDK / command line tools
- Android platform/build tools compatible with the Gradle project
- Optional: Android emulator or physical Android device for runtime TTS validation

On the current development Mac, builds used:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

## Build

```bash
./gradlew :app:assembleDebug
```

The debug APK is generated locally under:

```text
app/build/outputs/apk/debug/
```

That directory is intentionally not committed to Git. It is generated machine output and should be rebuilt locally from source.

## Quality Gate

Run the Voice Lab quality gate before making or reviewing changes:

```bash
scripts/check-voice-lab.sh
```

The gate currently runs:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`

When an Android emulator or physical device is attached, run the connected Voice Lab smoke gate:

```bash
scripts/check-voice-lab-connected.sh
```

The connected gate runs `TtsWorkbenchSmokeTest` and `ModelsSmokeTest`.

- `TtsWorkbenchSmokeTest` verifies that the Audio workbench can be reached from the app shell and that the script, engine, voice, parameter, preview, render, and playback controls are addressable by stable test tags.
- `ModelsSmokeTest` verifies that the Models screen is reachable and that every known TTS model row exposes stable row/action hooks without starting downloads or deleting files.

## Current Validation State

The latest pushed validation notes are in:

- `docs/voice-lab-quality-baseline.md`
- `docs/voice-lab-smoke-test.md`
- `docs/voice-lab-inventory.md`
- `docs/voice-lab-runtime-diagnostics.md`

The recorded baseline applies to the original prototype checkpoint: it built, passed unit tests and lint, smoke-tested Kokoro, and exercised Supertonic 2 through preview, playback/export, and WAV inspection. Rerun both quality gates for the incorporated Audio/Mixer workbench before release.
