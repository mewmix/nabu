# Nabu 

Nabu is an advanced, on-device Text-to-Speech (TTS) and Chat application for Android. Built upon the foundation of an Kokoro-82M Android demo, significantly extending its capabilities with a powerful chat interface, dynamic model management, and a feature-rich audio book reader.

## Acknowledgment

This project is a fork of the excellent [Kokoro-82M-Android](https://github.com/puff-dayo/Kokoro-82M-Android/) app by **puff-dayo**. A huge thank you for creating the original demo and making it open-source! This work stands on the shoulders of that initial effort.
## Videos
[▶ Watch the video](https://github.com/user-attachments/assets/43d1b910-8453-4471-8d85-78144c8b9914)
## Screenshots

<p align="center">
   
<!-- 1 — mixer -->
<img src="https://github.com/user-attachments/assets/fa7e8816-41f4-48eb-83c3-29aac0f98251"
     alt="Mixer"
     loading="lazy"
     style="max-width:auto;height:auto;" />

<!-- 2 — conversation settings -->
<img src="https://github.com/user-attachments/assets/882237cc-1c0f-40d7-bdb0-2a528846ae32"
     alt="Conversation Settings"
     loading="lazy"
     style="max-width:auto;height:auto;" />

<!-- 3 — settings -->
<img src="https://github.com/user-attachments/assets/a34eda22-09a9-4d29-9e26-9ab9208eb3eb"
     alt="Settings"
     loading="lazy"
     style="max-width:auto;height:auto;" />

<!-- 4 — basic screen -->
<img src="https://github.com/user-attachments/assets/da19d300-2a05-4f2b-9b49-0cc0547aa79f"
     alt="Basic Screen"
     loading="lazy"
     style="max-width:auto;height:auto;" />

</p>



## Features & Enhancements

We have taken the original demo and expanded it with several key features:

*   **💬 Chat & TTS:** A new, fully integrated screen where you can chat with an on-device Large Language Model (like Gemma or Qwen) and have its responses spoken aloud using the selected TTS engine and voice mix.

*   **🧠 Dynamic Model Management:** Download, manage, and switch between different chat models directly from the app. No need to bake them into the APK. Supports gated models from Hugging Face via user access tokens.

*   **🚀 Accelerated Kokoro FP16:** Runs the Kokoro-82M ONNX graph with NNAPI acceleration when available, while keeping the legacy INT8 CPU path as a fallback.

*   **🎛 Supertonic 1 & 2 Support:** Works with both Supertonic v1 and v2 TTS models, selectable in-app.

*   **📖 Advanced Audio Book Reader:**
    *   Open local text (`.txt`) and EPUB (`.epub`) files.
    *   Listen to documents with your customized TTS voice mix and speed settings.
    *   Save your progress with automatic bookmarks.
    *   Pre-generate the entire book's audio for smooth, uninterrupted offline listening.

*   **📂 Project-Based Workflow:** Save your book reading sessions as "projects," which remember your document, voice mixer settings, speed, and reading position for easy access later.

## How to Build

The project is configured for a standard Android Studio build.

1.  Clone this repository.
2.  Open the project in Android Studio (Ladybug or newer is recommended).
3.  Let Gradle sync the project dependencies.
4.  Build and run the `app` module on an Android device or emulator.

## Prebuilt APK files

Pre-compiled `.apk` files are available in the [Releases](https://github.com/mewmix/nabu/releases/) section of this repository.

## Credits & Technologies

This project would not be possible without the amazing work of the open-source community.

*   **Original App:** [Kokoro-82M-Android](https://github.com/puff-dayo/Kokoro-82M-Android) by puff-dayo.
*   **TTS Models:**
    *   [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (Apache 2.0)
    *   [kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx) (MIT)
*   **LLM Inference:**
*   **[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)**
    *   Google MediaPipe LLM Inference API
    *   Models like Gemma and Qwen2.5 from [Hugging Face LiteRT community](https://huggingface.co/litert-community/).
*   **Core Libraries:**
    *   ONNX Runtime for on-device inference.
    *   Jetpack Compose for the user interface.
    *   [IPA-Transcribers](https://github.com/kotlinguistics/IPA-Transcribers) for phoneme generation.
    *   [jsoup](https://jsoup.org/) HTML parser used for EPUB support (MIT) by Jonathan Hedley.
