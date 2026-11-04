# SlimIPTV — a tiny IPTV player for Android TV

Minimal IPTV player for TV boxes: channel list, D-pad switching, HLS/HTTP playback, custom M3U playlist.
Written in Java with no third-party libraries — **41 KB APK**, no ads, no accounts, no analytics.

## Features

- **M3U playlist** — 1145 verified Russian channels bundled, or your own `/sdcard/Download/ru.m3u`
  (external file takes priority).
- **Channel list** — name and category, large type, focus highlight.
- **Playback** — system `MediaPlayer` (HLS/HTTP); stream preparation happens on a background thread,
  so the UI never freezes on dead channels.
- **Statuses** — connecting, buffering, unavailable, stream finished.
- **Playlist refresh** — drop a file into `Download` and press MENU, no reinstall needed.

## Controls

| key | action |
|---|---|
OK | play selected channel |
↑ / ↓ (while playing) | next / previous channel |
BACK | back to the list |
MENU | reload playlist from `Download/ru.m3u` |

## Install

```bash
adb install -r release/SlimIPTV-1.1.apk
```

Requires Android TV 8.0+ (API 26), ARM.

## Playlist checker

```bash
python tools/verify_playlist.py ru.m3u -o ru_working.m3u --workers 20 --dedupe
```

Keeps only streams that actually respond (needs `ffprobe`).

## Build

```bash
./gradlew assembleRelease
```

MIT licensed. Code and design by **Andrey Masloboev**.