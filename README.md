# Nocturne for Android

A native music visualizer for Android 8.0 or newer (listening to the phone's own sound needs Android 10 or newer).

## How it works

There is no browser inside. Sound goes straight from Android's audio system into a Kotlin analyser that runs every ~5 ms (FFT, 10 EQ bands plus a .1 sub channel, beat and tempo tracking, song energy), and the visuals are drawn with OpenGL ES 3.0 from the newest analysis on every frame.

- **This phone**: visualises whatever the phone plays (Spotify, YouTube, games). Android asks for permission each time; tap **Start now**. A "Nocturne is listening" notification shows while it runs.
- **Automatic microphone fallback**: when nothing has played for 2.5 seconds it listens through the microphone, and switches back as soon as the phone plays again. This also covers apps that block capture of their sound.
- **Microphone**: microphone only.
- **Demo track**: the built-in track, with a seek bar.
- Styles in this build: **Wave**, **Tunnel**, **Light speed**. Quiet passages stay calm and dark; full energy is much more aggressive.

## Build the APK

### Option A: Android Studio (on your computer)

1. Install Android Studio from developer.android.com/studio.
2. **File › Open** and choose this `Nocturne` folder. Wait for the first sync to finish (it downloads what it needs).
3. **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
4. Click **locate** in the message that appears: the file is `app/build/outputs/apk/debug/app-debug.apk`.
5. Copy it to the phone and open it. Allow "install unknown apps" for your file manager when asked.

Or connect the phone with USB debugging on and press the green **Run** button.

### Option B: GitHub builds it for you (no Android Studio)

1. Create a new repository on github.com and upload everything in this folder (including the hidden `.github` folder).
2. Open the repository's **Actions** tab. The "Build Nocturne APK" job runs on every push (or press **Run workflow**).
3. When it finishes (about 5 minutes), open the run and download **Nocturne-apk** under Artifacts. Unzip it and install `app-release.apk` on the phone.

## Notes

- The release APK is signed with a debug key so it installs directly. Publishing on Google Play needs your own signing key.
- The visuals live in `app/src/main/assets/web/index.html`. To update them, replace that file with a newer version of the page.
