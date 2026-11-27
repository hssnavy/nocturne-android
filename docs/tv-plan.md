# Nocturne on TVs — plan (saved 27 Nov 2026, to continue when Hassan is home to test)

TVs: Hassan — LG OLED C1 (2021, webOS 6.0, Chromium 79: WebGL 2 should be available). Friend — Samsung (Tizen; model/year to check).

## Decisions so far
- The TV draws; the PHONE listens. TV apps can't hear other TV apps (Spotify on the TV), and the remote's mic is
  push-to-talk for the voice assistant only — no continuous sound for apps.
- Phone app sends only analysis numbers (~60/s: energy, kick, string levels/wavelength/flow/glow, tone map, BPM,
  picture/style/settings) over the home Wi-Fi to the TV, which renders full screen at its own resolution. Not video.
  Latency on local Wi-Fi ~5–20 ms.

## "Cast to TV" button in the phone app (like YouTube)
1. Tap Cast to TV -> list of TVs on the Wi-Fi by name (SSDP/mDNS discovery).
2. Pick one -> first time the TV asks "Allow Nocturne?" (OK on the remote once, remembered).
3. Phone opens Nocturne on the TV:
   - LG webOS: SSAP pairing (as LG ThinQ) -> open the TV browser at the phone's local page, or launch the installed app.
   - Samsung Tizen: remote-control WebSocket / Smart View (Multiscreen) -> same.
   - Chromecast-built-in TVs: Google Cast receiver (needs $5 Cast developer registration).
4. Phone plays the music and works as the remote; Stop casting ends it.
Fallback if auto-launch fails on a model: phone shows a short address to type in the TV browser.

## Order
1. Phone app: local web server + WebSocket data stream + TV page (the web renderer driven by phone data).
2. Test in the LG C1 browser (no install).
3. Cast button with LG discovery/pairing/launch.
4. Samsung discovery/launch; then packaged TV apps (LG IPK via Developer Mode, Samsung WGT via Developer Mode 12345 / Tizen Studio).
