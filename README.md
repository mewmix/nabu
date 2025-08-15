# Nabu 

Nabu is an advanced, on-device Text-to-Speech (TTS) and Chat application for Android. Built upon the foundation of an Kokoro-82M Android demo, significantly extending its capabilities with a powerful chat interface, dynamic model management, and a feature-rich audio book reader.

## Acknowledgment

This project is a fork of the excellent [Kokoro-82M-Android](https://github.com/puff-dayo/Kokoro-82M-Android/) app by **puff-dayo**. A huge thank you for creating the original demo and making it open-source! This work stands on the shoulders of that initial effort.

## Screenshots

<p align="center">

<img src="https://github.com/user-attachments/assets/03859019-6dea-4652-93ad-bc5a825d6aa9" alt="Models" style="width:20%;height:auto;" loading="lazy">
<img src="https://github.com/user-attachments/assets/151d9cf5-f367-4f7b-8e5a-b766a7507445" alt="Style Mixer" style="width:20%;height:auto;" loading="lazy">


</p>



## Features & Enhancements

We have taken the original demo and expanded it with several key features:

*   **💬 Chat & TTS:** A new, fully integrated screen where you can chat with an on-device Large Language Model (like Gemma or Qwen) and have its responses spoken aloud using the selected TTS engine and voice mix.

*   **🧠 Dynamic Model Management:** Download, manage, and switch between different chat models directly from the app. No need to bake them into the APK. Supports gated models from Hugging Face via user access tokens.

*   **🚀 Multi-Engine TTS Support:** Seamlessly switch between the original `Kokoro` engine and the new `Kitten` TTS engine for different voice characteristics.

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
    *   Kitten TTS Engine
*   **LLM Inference:**
*   **[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)**
    *   Google MediaPipe LLM Inference API
    *   Models like Gemma and Qwen2.5 from [Hugging Face LiteRT community](https://huggingface.co/litert-community/).
*   **Core Libraries:**
    *   ONNX Runtime for on-device inference.
    *   Jetpack Compose for the user interface.
    *   [IPA-Transcribers](https://github.com/kotlinguistics/IPA-Transcribers) for phoneme generation.
