# Nocturne for Android

A native music visualizer for Android 8.0 or newer (listening to the phone's own sound needs Android 10 or newer).

## How it works

NOCTURNE — a music visualizer by Hassan Al-Yunis

ENGINE: native Android, no browser. Sound goes straight into a Kotlin analyser; OpenGL ES 3 draws at the screen's full refresh rate (up to 120 Hz) and resolution (up to 4K), lowering detail only if the chip can't keep up.

SOUND: This phone hears what the phone plays (Spotify included, through the output mix; screen share optional for more detail). Microphone only when you choose it. Demo track built in.

ANALYSIS (every frame, plus exact 10 ms steps):
- Tone map: 256 bands, 20 Hz–20 kHz, every note measured against its own recent level, so faint highs show.
- 10-band EQ + .1 sub channel; the kick drum is read from the .1 channel (a sharp sub jump above the rolling bass).
- Energy: a model learned from your hand-taught curves (bass and highs vs low-mids, loudness vs the song's peak, kick patterns). Rises instantly, settles slowly; songs open calm and build.
- BPM: kick + whole drum kit, first guess in seconds, then locked once per song.

STYLES: 10 styles share this analysis; energy sets how calm or aggressive, bright and colourful they are.
- Wave: 1–7 strings split 20 Hz–20 kHz. Each string's own band sets its height (bounces on hits), wavelength, right-to-left flow (a steady sound stands still) and glow. The kick punches the lowest string. Background smoke follows energy.
- Radial: the solid ring beats only with the kick; the outer fragments follow the spectrum.
- Light speed: a ship that starts at rest and speeds up with the song; kicks surge it.

LEARNING: each song leaves an anonymous record (sound features, energy, BPM, no audio), kept on the phone and uploaded when online, to improve future versions.

The same text is shown in the app under Settings → About Nocturne.

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
