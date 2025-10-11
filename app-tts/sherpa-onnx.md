# sherpa-onnx TTS Integration

This module ships the Kotlin adapter used by the app when the sherpa-onnx TTS
engine is enabled.  The runtime pieces (the AAR and the model assets) are
intentionally **not** committed to source control.  Download them locally before
building the app:

1. Fetch the Android AAR and place it in `app-tts/libs/`:

   ```bash
   mkdir -p app-tts/libs
   curl -L -o app-tts/libs/sherpa-onnx.aar \
     https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.14/sherpa-onnx.aar
   ```

2. Download a voice package (for example, `vits-ljs`) and unpack it into
   `app-tts/src/main/assets/sherpa_tts/vits-ljs/`:

   ```bash
   mkdir -p app-tts/src/main/assets/sherpa_tts
   cd app-tts/src/main/assets/sherpa_tts
   curl -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-ljs.tar.bz2
   tar xjf vits-ljs.tar.bz2 && rm vits-ljs.tar.bz2
   ```

3. If you choose a multi-speaker voice package, update the `voiceToSpeakerId`
   map when instantiating `SherpaTtsEngine` to expose all available speakers.

Committers should avoid adding the downloaded binaries to the repository; the
`.gitignore` entries ensure they remain local-only.
