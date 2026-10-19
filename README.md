# AirTV

An AirPlay receiver for Android TV, in the spirit of AirScreen. iPhones, iPads and Macs
on the same Wi-Fi see the TV under **Screen Mirroring** and can mirror their screen with
sound, or stream music to it.

Tested on a TCL Android 11 TV (Realtek decoder, 1080p) mirroring from an iPhone 13 Pro Max:
up to a steady 60 fps with no dropped frames, and about 25 ms from a frame arriving to it
being on screen.

## Features

- Screen mirroring (H.264) with audio (AAC-ELD), hardware-decoded via `MediaCodec`
- **Video casting:** the AirPlay button in YouTube, Safari and other players hands the
  video to the TV, which streams it itself with ExoPlayer (HLS or direct URLs) at the
  video's own frame rate. The phone can be locked. Pause, seek and volume work from the
  phone; **play/pause** on the remote toggles playback and **Back** ends the cast
- AirPlay music (ALAC) with a "now playing" overlay showing title, artist and album
- The sender's volume slider controls the TV's volume
- Smooth playback: frames are shown at a steady pace based on the sender's own
  timestamps, with a small jitter buffer (35 ms), instead of the moment they arrive
- Aspect-correct video that adapts when the phone rotates
- On-screen counter showing **fps · delay**; press **OK** on the remote to hide or show it
- Fixed AirPlay port 7000, so senders reconnect cleanly after the app restarts

## How it works

| Layer | What it does |
|---|---|
| `app/src/main/cpp/third_party/uxplay` | [UxPlay](https://github.com/FDH2/UxPlay)'s AirPlay core: RTSP/HTTP server, pairing, FairPlay handshake, stream decryption |
| `app/src/main/cpp/dnssd_android.c` | Replaces UxPlay's Bonjour/Avahi layer: builds the `_airplay._tcp` / `_raop._tcp` records and hands them to Kotlin |
| `app/src/main/cpp/airtv_jni.cpp` | JNI bridge: passes video frames, audio and connection events up to Kotlin; decodes ALAC with Apple's decoder |
| `ServiceAdvertiser.kt` | Publishes the services through Android's `NsdManager` (the system already owns mDNS port 5353) |
| `VideoRenderer.kt` | Asynchronous `MediaCodec` decoding onto a `SurfaceView`, paced on the sender's clock; logs fps and delay |
| `AudioRenderer.kt` | AAC-ELD / AAC-LC via `MediaCodec`, ALAC as PCM from native code, played through `AudioTrack` with a ~250 ms queue cap |
| `CastPlayer.kt` | ExoPlayer for video casts; answers the sender's playback-info polls from a snapshot, shows the stream's resolution and fps |
| `MainActivity.kt` | Waiting screen with the receiver name and IP, the video surface, now-playing and fps overlays |

The receiver identifies itself as an Apple TV HD (`AppleTV5,3`) rather than UxPlay's
default Apple TV 3, which senders limit to 30 fps.

Third-party code is vendored (versions in `app/src/main/cpp/third_party/VERSIONS.txt`):
UxPlay (GPL-3.0), libplist (LGPL-2.1) and Apple's ALAC decoder (Apache-2.0). OpenSSL 1.1.1
comes from Google's prefab package `com.android.ndk.thirdparty:openssl`.

## Build

Needs the Android SDK with NDK `27.3.13750724` and CMake `3.22.1`, plus JDK 17.

```bash
./gradlew assembleDebug
```

The APK includes `armeabi-v7a`, `arm64-v8a` and `x86_64`.

## Install on the TV

1. On the TV: Settings → Device Preferences → About, then click **Build** 7 times. Then in
   Developer options, turn on **USB debugging** (and network/ADB debugging if your TV
   lists it separately).
2. From a computer on the same network, connect, and accept the prompt on the TV:

```bash
adb connect <tv-ip>:5555
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **AirTV** from the TV's app row and leave it open. On the iPhone: Control Center →
Screen Mirroring → pick the TV.

## Tips

- **Lag:** set the TV's picture mode to **Game**. TVs add 50–150 ms of their own picture
  processing. The app asks for this automatically, but many TVs (including TCL) don't
  support the request, so set it on the TV.
- **Choppy motion in Game mode:** Game mode also turns off the TV's motion smoothing
  (MEMC), which makes 30 fps look smoother. Pick whichever you prefer.
- **Can't connect after an update:** stop mirroring and turn the phone's Wi-Fi off and on
  to clear its cached entry for the TV.

## Limitations

- The app must be open on screen to receive (no background service yet).
- **Mirroring frame rate is the sender's choice:** the receiver asks for 60 fps, but iOS only
  captures a new frame when the screen changes, and some apps play video at 30 fps while
  mirroring. Casting avoids this for apps that have an AirPlay button.
- DRM-protected apps (Netflix, Apple TV+, Disney+) can't cast.
- Apps without an AirPlay button in their player (e.g. Facebook) can only be mirrored, so
  the phone has to stay unlocked.
- H.264 only (H.265 is off), and no PIN or password.
- Audio isn't clock-synced to video; each plays as it arrives.

## License

GPL-3.0, inherited from UxPlay. See [LICENSE](LICENSE).
