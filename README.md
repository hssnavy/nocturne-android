# Nocturne for Android

A native music visualizer for Android 8.0 or newer (listening to the phone's own sound needs Android 10 or newer).

## How it works

There is no browser inside. Sound goes straight from Android's audio system into a native analyser, and the visuals
are drawn with OpenGL ES 3.0 at the screen's full refresh rate (90/120 Hz where the phone has it), at full resolution
up to 4K, stepping down only if the graphics chip can't keep up.

The analysis is the same as the web version's: a fine tone map (every note, 20 Hz-20 kHz), the 10-band EQ + .1 channel,
the kick drum from the .1 channel (worked out in exact 10 ms steps), tempo, the energy learned from your teaching
(the Demo track follows your own taught curve), the Wave's 1/3/5/7 strings, and the Light speed ship.

- **This phone**: whatever the phone plays (Spotify included, through the output mix). Tap **Start now** when Android asks.
- **Automatic microphone fallback** only when the phone itself is silent.
- **Demo track** with pause, seek and ±10 s.
- Styles: Spectrum bars, Radial, Wave, Tunnel, Kaleidoscope, Shatter, Rising sparks, Warp, Fractal, Light speed.
- 16 pictures (13 photos + 3 generated), the controls minimise to a small chevron at the bottom-right.

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
