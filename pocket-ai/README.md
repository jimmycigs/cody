# Pocket AI

A private AI assistant for Android that runs entirely on your phone. It has no internet permission, and it learns new things only from talking to you.

## How it works

- **Brain:** a small language model (Gemma 3 1B) runs on the phone through Google's MediaPipe LLM Inference engine.
- **Knowledge base:** facts are stored in a local SQLite database. Before every reply, the most relevant facts are handed to the model, so it "remembers" what you've taught it.
- **Learning from you:**
  - `Remember that …` saves a fact right away.
  - `Forget about …` deletes the matching facts.
  - `What do you know about me?` lists what it has learned.
  - With "Learn while we chat" on, it also pulls facts out of statements you make about yourself.
- **Base knowledge:** edit `app/src/main/assets/base_knowledge.txt`. It's loaded once, the first time the app runs.
- **Your control:** the "What I know" screen lets you add, edit, delete, back up and restore every memory.

## Install on your phone

1. Open the latest **pocket-ai** run under the repo's **Actions** tab and download the `pocket-ai-apk` artifact. It's a zip containing `app-release.apk`.
2. Open the APK on your phone and allow "Install unknown apps" for your browser or file manager when asked.
3. Launch **Pocket AI** from your home screen. On first launch it walks you through getting the model:
   download `gemma3-1b-it-int4.task` (about 550 MB) from
   https://huggingface.co/litert-community/Gemma3-1B-IT (this needs a free Hugging Face account and accepting Google's Gemma license), then pick the file in the app.

You need a phone with about 6 GB of RAM or more.

## Build it yourself

Open this folder in Android Studio, or run `./gradlew assembleRelease`. Unit tests for the memory logic: `./gradlew testDebugUnitTest`.
