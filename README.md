# PocketDesk

A desktop from your Android phone on any screen with a browser: apps in windows you move and
resize, your mouse and keyboard, for phones without DeX or USB-C video out. Landing page:
`/about`; download and setup: `/get`. Forked from
[CarMirror](https://github.com/andreconde21/tesla-car-mirror), which shows phone apps on a
Tesla screen; PocketDesk reuses its streaming and pairing.

```
Android phone                                         Screen (laptop, mini PC, TV browser)
┌───────────────────────────────────────┐            ┌─────────────────────────────────────┐
│ PocketDesk app                        │  WebRTC    │ pocketdesk.outsmartis.dev           │
│  ├ foreground service: signaling,     │  data      │  ├ WebCodecs H.264 -> <canvas>      │
│  │  WebRTC peer, H.264 + Opus encoders│  channels  │  ├ taskbar, app menu                │
│  └ helper (shell uid): desktop display│ ─────────► │  ├ mouse / wheel / keys -> phone    │
│     (system decorations, freeform),   │ ◄───────── │  └ Opus -> Web Audio                │
│     input injection, window list      │  same Wi-Fi│                                     │
└───────────────────────────────────────┘            └─────────────────────────────────────┘
        │ pairing + SDP/ICE only                ┌─────────────────────────┐        │
        └─────────────────────────────────────► │ server/ (Node)          │ ◄──────┘
                                                └─────────────────────────┘
```

## How it works

- **A desktop display.** The helper creates a virtual display around the app's MediaCodec
  input surface with the flags a real external monitor gets: `TRUSTED`,
  `SHOULD_SHOW_SYSTEM_DECORATIONS` (wallpaper and the launcher's secondary home), `OWN_FOCUS`,
  `OWN_DISPLAY_GROUP`, `ALWAYS_UNLOCKED`, `DESTROY_CONTENT_ON_REMOVAL` (`createDesktop` in
  `PrivilegedService.kt`). It has the browser window's exact size in device pixels (capped by
  the Sharpness setting) and a dpi that keeps text the same physical size; it is resized live
  when the window changes.
- **Windows.** `enable_freeform_support`, `force_desktop_mode_on_external_displays` and
  `force_resizable_activities` (global settings, one phone restart), then apps are started with
  `am start --display N --windowingMode 5`. The taskbar lists the display's tasks
  (`IActivityTaskManager.getTasks`), focuses them (`setFocusedTask` / `startActivityFromRecents`)
  and closes them (`removeTask`).
- **Mouse.** Real `SOURCE_MOUSE` events: hover enter/move/exit, `ACTION_DOWN` +
  `ACTION_BUTTON_PRESS` per button (the browser's button bits match Android's), `ACTION_SCROLL`
  with `AXIS_VSCROLL`/`AXIS_HSCROLL`. Back/forward mouse buttons become key presses.
- **Keyboard.** A plain character is typed as that character (`KeyCharacterMap`, else
  `ACTION_MULTIPLE`), so the computer's layout wins; keys with Ctrl/Alt/Meta and keys that
  aren't characters go as key codes mapped from `KeyboardEvent.code` with the meta state.
  Esc is Back. The Windows key alone opens the app menu. Ctrl+V types the computer's clipboard.
  In full screen the page asks for Keyboard Lock, so Esc and system shortcuts reach the phone.
- **The helper without Shizuku** (`SelfAdb.kt`, `SelfHost.kt`, `SelfStart.kt`). The app is its
  own ADB client (vendored libadb-android + native SPAKE2 in `android/libadb`). Pairing is
  once, on the phone: the pairing port is found by mDNS and the code is typed into a
  notification. Over that connection the app grants itself `WRITE_SECURE_SETTINGS`. At boot (and
  after an update), once on Wi-Fi, it switches Wireless debugging on, connects to its own adbd,
  starts `SelfHost` with `app_process` (shell uid, `setsid nohup`), and switches Wireless
  debugging off again; the helper keeps running. `SelfHost` hands its binder to the app through
  `SelfHostProvider` (accepts uid 0/2000 only), so the Shizuku code paths work unchanged.
  `SelfHost` builds its system context on its main looper at start: app_process has no
  ActivityThread, and building it on a binder thread fails.
- **Streaming** is CarMirror's: H.264 in the app process, sent on an ordered data channel with
  backpressure (frames dropped up to the next key frame), decoded with WebCodecs onto a canvas;
  Opus audio on an unordered channel. If the phone's encoder falls behind for a while, the page
  switches to 1280 px / 30 fps once.

## Wire format (additions to CarMirror's)

Control channel, screen → phone: `start{sid,pkg:"@desktop",w,h,dpi,fps,bitrate}`,
`reconfigure{sid,w,h,dpi,fps,bitrate}`, `mouse{sid,a:move|down|up|leave,b,x,y,w,h,m}`,
`wheel{sid,x,y,w,h,dx,dy,m}`, `kb{sid,down,code,key,m,r}`, `text{sid,text}`, `open{sid,pkg}`,
`task{sid,id,a:focus|close}`, `allApps?`, `keyboard{mode}`, `audio{on}`, `key{sid,k}`.
Phone → screen: `allApps{apps}`, `icon{pkg,icon}`, `tasks{sid,tasks:[{id,pkg,label,visible,mode}]}`,
`started`, `ended{reason}`, `stats{lag,dropped}`, `error`. `m` = modifier bits (1 Shift,
2 Ctrl, 4 Alt, 8 Meta). Video channel `v:<sid>` as in CarMirror.

## Repo layout

- `server/`: signaling server + desktop web client (vanilla JS, no build step).
  - `public/index.html`, `public/js/desk.js`, `public/desk.css`: the desktop client.
  - `public/js/player.js`, `audio.js`, `rtc.js`: decoding, audio, WebRTC (from CarMirror).
  - `public/about.html` (landing page), `public/get.html` (download + setup), `public/diag.html`.
- `android/tv/`: PocketDesk for TV, a full-screen WebView of the desktop page for Android TV /
  Google TV. The page sees ` PocketDeskTV` in the user agent and turns the D-pad into a cursor
  (arrows move with acceleration, OK clicks, past the top/bottom edge scrolls); the app forwards
  Back and Menu to `window.pdRemote()`.
- `android/`: the phone app. `DeskSession.kt` (the desktop), `PrivilegedService.kt` (helper),
  `SelfAdb.kt` / `SelfHost.kt` / `SelfStart.kt` (helper without Shizuku), `MainActivity.kt`.
  The car code from CarMirror is still there (and stays off unless used).

## Run the server

```bash
cd server && npm ci --ignore-scripts && PORT=8080 DATA_DIR=./data node server.js
```

Behind any TLS reverse proxy (WebCodecs needs a secure context; WebSocket upgrade on `/ws`).
`/pocketdesk.apk` serves `APK_DIR/pocketdesk.apk` if present, else redirects to the latest
GitHub release. The app's default server is `DEFAULT_SERVER` in `android/app/build.gradle.kts`
(changeable under Advanced).

## Build the APK

```bash
cd android
export ANDROID_HOME=/path/to/sdk POCKETDESK_KEYSTORE=/path/to/pocketdesk.jks POCKETDESK_KEYSTORE_PASSWORD=...
./gradlew assembleRelease -PversionCode=1 -PversionName=0.1.0
```

Needs the NDK (for the SPAKE2 library in `android/libadb`). Keep the keystore out of the repo.

## Testing

Verified on an Android 16 emulator with headless Chrome as the screen: pairing, helper started
without Shizuku (also after an update), desktop with the launcher's home, app menu, freeform
windows, click, drag to move, wheel scroll, typing (no on-screen keyboard), live resize,
closing from the taskbar. TV app (Android 14 emulator at 1080p, WebView 113): pairing with the
remote's cursor, app menu with Menu and the D-pad, clicking a window's close button, Back.
Not yet verified: Samsung One UI hardware, sound on a real phone.

## Licenses

App and server: no license chosen yet. `android/libadb` vendors libadb-android (Apache-2.0) and
spake2-c (LGPL-3.0); see `android/libadb/licenses`.
