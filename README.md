# GlassCast

[![Build](https://github.com/YangJongWon/glasscast/actions/workflows/build.yml/badge.svg)](https://github.com/YangJongWon/glasscast/actions/workflows/build.yml)

See what your Ray-Ban Meta glasses see, **live**, on your phone and on any computer on the same Wi-Fi network. You can view it in a browser, OBS, or VLC.

The official Meta AI app only shows photos and videos *after* you capture them. GlassCast uses Meta's [Wearables Device Access Toolkit](https://wearables.developer.meta.com) (DAT SDK) to pull the camera stream into a native iOS or Android app. The app shows it full-screen and re-broadcasts it over your local network as MJPEG.

```
Ray-Ban Meta ──Bluetooth (DAT SDK)──▶ Phone app ──Wi-Fi, HTTP MJPEG──▶ Browser / OBS / VLC
                                      (live preview)                   http://<phone-ip>:8080
```

- **iOS**: SwiftUI app, iOS 17.2+ (`ios/`)
- **Android**: Jetpack Compose app, Android 12+ / API 31 (`android/`)
- **Desktop**: any browser. `web/viewer.html` is served by the phone itself, so there's nothing to install.

> GlassCast is an independent project. It is not affiliated with Meta or Ray-Ban.

## Requirements

- Ray-Ban Meta glasses (Gen 1 or Gen 2), Ray-Ban Meta Optics, or Meta Ray-Ban Display, paired in the **Meta AI** app.
- Either **Developer Mode** enabled in the Meta AI app, or an app registered in the [Wearables Developer Center](https://wearables.developer.meta.com) (App ID + Client Token).
  - To enable Developer Mode: in Meta AI, open *Settings → App Info* and tap the version number 5 times.
- iOS: Xcode 16+ and an Apple Developer team for signing. Optionally, [XcodeGen](https://github.com/yonaskolb/XcodeGen).
- Android: a recent Android Studio. Gradle runs on its bundled JDK; from the command line, use JDK 17 or newer.

## Register the app (optional)

Developer Mode is enough to run GlassCast on your own phone. To share builds with others, register the app in the [Wearables Developer Center](https://wearables.developer.meta.com):

1. Create a project, then open *Configuration → Mobile app configuration*.
2. **iOS**: enter your Apple **Team ID**, the **Bundle ID** you put in `Secrets.xcconfig`, and `glasscast://` as the **Universal link**. GlassCast receives Meta AI's callback through that URL scheme.
3. **Android**: enter the package name `dev.glasscast.app`, or your own `applicationId`.
4. Under *Permissions*, turn on **Camera access**. Turn on **Microphone access** only if you use sound.
5. Copy the **App ID** and **Client Token** into `Secrets.xcconfig` (iOS) and `local.properties` (Android).

## Build and run

### iOS

```sh
cd ios
cp GlassCast/Config/Secrets.xcconfig.example GlassCast/Config/Secrets.xcconfig
# Edit Secrets.xcconfig: DEVELOPMENT_TEAM, PRODUCT_BUNDLE_IDENTIFIER,
# and META_APP_ID / CLIENT_TOKEN unless you use Developer Mode.
open GlassCast.xcodeproj
```

Run the `GlassCast` scheme on a real iPhone; the simulator can't reach the glasses. Xcode fetches the DAT SDK through Swift Package Manager.
`GlassCast.xcodeproj` is generated from `project.yml`. After you add files, run `xcodegen` in `ios/` to regenerate it.

### Android

```sh
cd android
cp local.properties.example local.properties
# Add mwdat_application_id / mwdat_client_token unless you use Developer Mode.
```

Open `android/` in Android Studio and run the `app` configuration on a real phone, or build from the command line with `./gradlew :app:installDebug`. The DAT SDK comes from Maven Central, so no token is needed. The build copies `web/viewer.html` into the app's assets automatically.

## Using it

1. Open GlassCast and tap **Connect my glasses**. Approve the connection in the Meta AI app.
2. Tap **Start live view**. The first time, approve camera access in Meta AI.
3. Tap **Desktop** to see the address (for example `http://192.168.0.12:8080`) and a QR code.
4. On a computer on the same Wi-Fi:
   - **Browser**: open the address. Add `?clean` to hide the overlay.
   - **OBS**: add a *Browser* source with `http://<phone-ip>:8080/?clean`, or a *Media Source* with "Local file" unchecked and input `http://<phone-ip>:8080/stream`.
   - **VLC**: *Open Network Stream* → `http://<phone-ip>:8080/stream`.

### Photos

While live view runs, tap the round shutter button to have the **glasses camera** take a full photo. It is much sharper than a frame of the live stream. A computer can do the same with the viewer's **Take photo** button.

- **iOS**: photos are kept in the app (tap the thumbnail) and in *Files → On My iPhone → GlassCast → Photos*. Use *Share → Save Image* to add one to your library.
- **Android**: photos are saved to *Pictures/GlassCast*, so they also appear in your gallery app.
- The newest photo is also available at `http://<phone-ip>:8080/photo.jpg`.

iOS Debug builds can switch to *⋯ → Photo quality → Full quality*, which uses the experimental `Camera.photo` API at full resolution. Like stream audio, it is compiled only into Debug builds (`EXPERIMENTAL_PHOTO`).

### Sound

Sound is off by default. Tap the microphone button in the app to stream your voice from the glasses' microphone. The app asks for microphone access the first time.

- **Browser**: click **Sound off** in the viewer to turn it on. Browsers only play audio after a click.
- **OBS**: a *Browser* source with `http://<phone-ip>:8080/?clean&audio` plays video and sound together. You can also add a second *Media Source* with input `http://<phone-ip>:8080/audio`.
- **VLC**: open `http://<phone-ip>:8080/audio` as a separate stream.

The glasses' microphones are reached over Bluetooth HFP, the hands-free calling profile, so sound is voice quality and tuned to the wearer's voice, not to the room. While sound is on, the glasses' speakers also drop to call quality.

iOS Debug builds can also take sound from the camera stream itself (*⋯ → Sound source*), which stays in sync with the video. This uses an experimental DAT feature, and apps that use experimental features can't be published yet. The code is compiled only when `EXPERIMENTAL_AUDIO` is set, which `project.yml` does for Debug only, so Release builds and archives never include it.

The phone keeps its screen on while streaming. On Android, a foreground service also keeps streaming when the screen is off.

## Protocol

Both apps (and `tools/mock_server.py`) serve the same HTTP API on port **8080**. They also advertise it over Bonjour/mDNS as `_glasscast._tcp`.

| Route | Response |
|---|---|
| `GET /` | the viewer page (`web/viewer.html`) |
| `GET /stream` | `multipart/x-mixed-replace; boundary=glasscast`, one JPEG per part |
| `GET /audio` | `audio/wav`, an endless 16 kHz mono 16-bit stream (`503` while sound is off) |
| `GET /photo.jpg` | the newest photo taken with the glasses (`404` before the first) |
| `POST /capture` | asks the glasses to take a photo: `202`, or `409` unless live view is running |
| `GET /snapshot.jpg` | the newest frame (`503` before the first frame) |
| `GET /status` | `{"state", "fps", "width", "height", "clients", "platform", "audio", "photo"}` |

`state` is one of `streaming`, `paused`, `starting`, `waitingForDevice`, `stopped`. Up to 4 viewers can watch `/stream` at once; extra viewers get `503`. The server keeps only the newest frame, so a slow viewer skips frames instead of falling behind.

`/audio` starts with a 44-byte WAV header whose size fields are `0xFFFFFFFF`, followed by raw little-endian PCM until the connection closes. The server buffers about one second per listener and drops the oldest audio beyond that. Up to 4 listeners are allowed. Turning sound off closes every `/audio` connection. Audio and video are separate connections, so they can drift slightly apart.

`photo` in `/status` counts the photos taken since the app started. A viewer that sees it go up can fetch the new photo from `/photo.jpg`.

## Development tools

```sh
# Desktop stand-in for the phone: loops a folder of JPEGs through the same API,
# with a beep once a second on /audio and the current frame as the photo for /capture.
ffmpeg -i clip.mp4 -vf "fps=24,scale=504:896" frames/%04d.jpg
python3 tools/mock_server.py frames/ --port 8080

# Check that a phone (or the mock) speaks the protocol correctly.
# Add --capture to also take a photo with the glasses and fetch it.
python3 tools/check_stream.py 192.168.0.12:8080
```

## Limitations and notes

- **No authentication.** Anyone on the same network can open the stream while it's running, including sound when it is on, and can take photos with `POST /capture`. Use it on networks you trust.
- **Same network only.** Remote viewing over the internet (WebRTC) is planned but not built yet.
- **iOS foreground only.** The SDK pauses decoded (`raw`) streams when the app is backgrounded, so keep GlassCast open.
- **Bandwidth.** Video travels over Bluetooth Classic. The SDK may lower resolution, then frame rate (never below 15 fps), when the link is weak. Lower quality settings often look sharper.
- **Distribution.** The DAT SDK is in developer preview. Meta does not currently support publishing DAT apps to the App Store; share builds through the Wearables Developer Center release channels instead.

## License

GlassCast's source code is released under the [MIT License](LICENSE).

The Meta Wearables DAT SDK is **not** part of this repository and is not MIT-licensed. Your build tool downloads it, and it is governed by the [Meta Wearables Developer Terms](https://wearables.developer.meta.com/terms). See [NOTICE](NOTICE).
