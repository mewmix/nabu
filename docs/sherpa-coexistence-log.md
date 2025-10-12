## 2025-10-11 — Sherpa/Kokoro Runtime Collision

### Context
- Integrated sherpa-onnx v1.12.14 artifacts (JNI libs + Kotlin wrapper) for new TTS engine.
- Kokoro/KITTEN still rely on `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`.
- To keep Kokoro stable we removed sherpa’s bundled `libonnxruntime*.so`, leaving only the Maven 1.20.0 copy in the APK.

### Observed Crash
- Reproduced on device using `adb logcat -d -v time`.
- Crash occurs when `OfflineTts` loads `libsherpa-onnx-jni.so`:
  ```
  java.lang.UnsatisfiedLinkError: dlopen failed: cannot locate symbol "OrtGetApiBase"
    at com.k2fsa.sherpa.onnx.OfflineTts.<clinit>(Tts.kt:188)
    at com.example.nabu.tts.sherpa.SherpaTtsEngine.acquireTts(SherpaTtsEngine.kt:75)
  ```
- Root cause: sherpa JNI library was built against ONNX Runtime 1.17.1; symbol version mismatch when linking against 1.20.0 (`OrtGetApiBase@@VERS_1.20.0`).

### Current State
- `app/src/main/jniLibs/arm64-v8a/` includes:
  - `libsherpa-onnx-c-api.so`
  - `libsherpa-onnx-cxx-api.so`
  - `libsherpa-onnx-jni.so`
- Kokoro loads successfully (runtime 1.20.0 present).
- Sherpa synthesis crashes immediately due to unresolved symbol.

### Next Steps (action plan)
1. Build a unified ONNX Runtime:
   - Clone microsoft/onnxruntime and build Android AAR with opset 5 support (`ai.onnx.ml`).
   - Replace Maven dependency with the custom `onnxruntime-android-custom.aar`.
2. Rebuild sherpa JNI layer against that runtime:
   - Use `vendor/sherpa-onnx/build-android-arm64-v8a.sh` with `SHERPA_ONNXRUNTIME_LIB_DIR/INCLUDE_DIR` pointing to the new build.
   - Copy the regenerated `libsherpa-onnx-*.so` into `app/src/main/jniLibs/arm64-v8a/`.
3. Retest:
   - `./gradlew assembleDebug`
   - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   - Verify both Kokoro and Sherpa flows.

### Notes
- Restoring sherpa’s original `libonnxruntime*.so` would fix Sherpa but re-break Kokoro (opset guard). Not acceptable.
- Awaiting custom build artifacts to proceed with integration.
