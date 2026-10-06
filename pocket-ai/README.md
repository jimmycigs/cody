# Pocket AI (Bernard)

Bernard is a private AI assistant for Android that runs entirely on your phone. It has no internet permission, and it learns new things only from talking to you.

## How it works

- **Brain:** a small language model (Gemma 3 1B) runs on the phone through Google's MediaPipe LLM Inference engine.
- **Knowledge base:** facts are stored in a local SQLite database. Before every reply, the most relevant facts are handed to the model, so it "remembers" what you've taught it.
- **Learning from you:**
  - `Remember that …` saves a fact right away.
  - `Forget about …` deletes the matching facts.
  - `What do you know about me?` lists what it has learned.
  - With "Learn while we chat" on, it also pulls facts out of statements you make about yourself.
- **Who is who:** Bernard is the AI and Master is the user. This is built in and can't be changed or forgotten, and facts are stored in the third person ("Master's dog is Rex").
- **Base knowledge:** edit `app/src/main/assets/base_knowledge.txt`. It's loaded once, the first time the app runs.
- **Your control:** the "What I know" screen lets you add, edit, delete, back up and restore every memory.

## Install on your phone

1. Open the latest **pocket-ai** run under the repo's **Actions** tab and download the `pocket-ai-apk` artifact. It's a zip containing `app-release.apk`.
2. Open the APK on your phone and allow "Install unknown apps" for your browser or file manager when asked.
3. Launch **Pocket AI** from your home screen. On first launch it walks you through getting the model:
   download `gemma3-1b-it-int4.task` (about 550 MB) from
   https://huggingface.co/litert-community/Gemma3-1B-IT (this needs a free Hugging Face account and accepting Google's Gemma license), then pick the file in the app.

You need a phone with about 6 GB of RAM or more. Target phone: Pixel 8 Pro (12 GB, Android 16). The app tries the GPU first and falls back to the CPU. On Android 16, Play Protect may warn that the app is from an unknown developer: tap "More details" → "Install anyway".

## Build it yourself

Open this folder in Android Studio, or run `./gradlew assembleRelease`. Unit tests for the memory logic: `./gradlew testDebugUnitTest`.
