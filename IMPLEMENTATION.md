# aRDP - Android Remote Desktop Client for GNOME Remote Desktop

Native Android app that connects to a Linux machine running GNOME Remote Desktop (RDP) with dynamic resolution support.

## Core Library

Use **FreeRDP's freeRDPCore** Android library module:
- Repository: https://github.com/FreeRDP/FreeRDP
- License: Apache 2.0
- Library path in repo: `client/Android/Studio/freeRDPCore/`
- Reference app: `client/Android/Studio/aFreeRDP/`
- JNI bindings: `client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java`
- Native glue: `client/Android/android_freerdp.c`
- Current version: 3.26.0 (May 2026) - Android client was overhauled in this release

The build compiles OpenSSL, OpenH264, and libJPEG as native dependencies, producing `libfreerdp-android.so`.

## Architecture Overview

```
┌─────────────────────────────────┐
│  Kotlin / Jetpack Compose UI    │
│  (connection manager, viewer)   │
├─────────────────────────────────┤
│  freeRDPCore (Android library)  │
│  LibFreeRDP.java (JNI bridge)   │
├─────────────────────────────────┤
│  libfreerdp (C) + plugins       │
│  disp channel, gfx pipeline     │
└─────────────────────────────────┘
```

## Dynamic Resolution - The Key Feature

This is the main differentiator. The RDP protocol supports in-session resolution changes via the **Display Update Virtual Channel Extension** (MS-RDPEDISP).

### How it works

1. On connection, enable the `disp` dynamic virtual channel (`/dynamic-resolution` flag)
2. This sets `FreeRDP_SupportDisplayControl` and `FreeRDP_DynamicResolutionUpdate` in the settings
3. When the Android window size changes (orientation, multi-window, foldable unfold), send a `DISPLAYCONTROL_MONITOR_LAYOUT_PDU` with the new dimensions
4. The server resizes its framebuffer and sends updated graphics at the new resolution - no client-side scaling needed

### FreeRDP internals for this

- Channel plugin: `channels/disp/client/disp_main.c`
- API: `DispClientContext` struct with `SendMonitorLayout` callback
- Settings flags: `FreeRDP_SupportDisplayControl`, `FreeRDP_DynamicResolutionUpdate`
- Related issue: https://github.com/FreeRDP/FreeRDP/issues/4265

### Android integration point

Wire these Android events to trigger a resolution update via JNI:

- `Activity.onConfigurationChanged()` - orientation changes
- `SurfaceHolder.Callback.surfaceChanged()` - surface dimensions change
- Multi-window resize events
- Foldable device posture changes (Jetpack WindowManager library)

The call chain should be:
```
Android surface size change
  -> JNI call to native layer
    -> DispClientContext.SendMonitorLayout(width, height, dpi)
      -> server receives PDU and resizes
```

A debounce/throttle (200-300ms) is recommended during resize drags to avoid flooding the server.

## GNOME Remote Desktop Compatibility

### Server configuration

Dynamic resolution ONLY works in headless/extend mode:
```bash
gsettings set org.gnome.desktop.remote-desktop.rdp screen-share-mode 'extend'
```

In `mirror` (screen-share) mode, the resolution is locked to the physical monitor and resize PDUs are silently ignored.

### Authentication

GNOME Remote Desktop uses NLA (Network Level Authentication) by default with TLS. The FreeRDP library handles this, but:
- Credentials must be provided upfront (NLA authenticates before the session starts)
- Certificate validation needs handling - either trust-on-first-use or a certificate store
- Some GNOME versions use RDSTLS for server redirection scenarios

### Connection flags to use

```
/dynamic-resolution    - enable display resize channel
/gfx                   - use graphics pipeline (better performance, required for resize)
/network:auto          - auto-detect network conditions
/sec:nla               - NLA authentication (GNOME default)
+clipboard             - clipboard sharing
```

## Project Structure (suggested)

```
src/aRDP/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/nz/co/ardp/
│       │   ├── MainActivity.kt              # Connection list / launcher
│       │   ├── SessionActivity.kt           # RDP session viewer
│       │   ├── connection/
│       │   │   ├── ConnectionConfig.kt       # Server address, credentials, settings
│       │   │   ├── ConnectionManager.kt      # Connect/disconnect lifecycle
│       │   │   └── ConnectionStore.kt        # Persist saved connections (DataStore)
│       │   ├── session/
│       │   │   ├── RdpSession.kt             # Wraps LibFreeRDP session lifecycle
│       │   │   ├── RdpSurface.kt             # SurfaceView/TextureView rendering
│       │   │   ├── DynamicResolution.kt      # Monitor size change -> disp channel
│       │   │   ├── InputHandler.kt           # Touch -> mouse/keyboard translation
│       │   │   └── ClipboardBridge.kt        # Android <-> RDP clipboard sync
│       │   └── ui/
│       │       ├── theme/
│       │       ├── screens/                  # Compose screens
│       │       └── components/               # Reusable compose components
│       ├── res/
│       └── AndroidManifest.xml
├── freeRDPCore/                              # Git submodule or Gradle dependency
├── build.gradle.kts                          # Root build
├── settings.gradle.kts
└── gradle.properties
```

## Key Implementation Tasks

### 1. Build system setup
- Set up Android project with Gradle (Kotlin DSL)
- Integrate freeRDPCore - either as a git submodule from FreeRDP repo or build the AAR separately
- Configure NDK for native library compilation
- Target SDK 34+, min SDK 26 (Android 8.0)

### 2. Connection management
- UI to add/edit/delete server connections
- Store connections with encrypted credentials (AndroidKeystore + EncryptedSharedPreferences or DataStore)
- Connection profiles: hostname, port (default 3389), username, password, display settings

### 3. RDP session lifecycle
- Initialize LibFreeRDP with connection settings
- Handle session events: connected, disconnected, certificate prompts, auth failures
- Manage session in a foreground service so it survives activity recreation
- Graceful disconnect on back/app close

### 4. Dynamic resolution (core feature)
- On surface creation: report initial size to RDP session
- On configuration change: debounce, then send new resolution via disp channel
- Handle DPI scaling - report Android's actual DPI to the server
- Test with: orientation change, split-screen, freeform window, foldable unfold

### 5. Input handling
- Touch-to-mouse translation (tap = click, long press = right click, two-finger = scroll)
- Optional trackpad/pointer mode (relative mouse movement)
- Software keyboard trigger for text input
- Physical keyboard passthrough when connected
- Pinch-to-zoom for navigating the remote desktop

### 6. Rendering
- Receive framebuffer updates from FreeRDP via JNI callbacks
- Render to a SurfaceView or TextureView
- Handle partial updates (dirty rectangles) for efficiency
- OpenGL ES surface for hardware-accelerated compositing if needed

### 7. Certificate handling
- Trust-on-first-use (TOFU) with local certificate fingerprint store
- Prompt user on first connect or certificate change
- Option to import CA certificates

## Testing

### Local testing setup
1. Enable GNOME Remote Desktop on a Linux machine:
   ```bash
   # Enable the RDP backend
   gsettings set org.gnome.desktop.remote-desktop.rdp enable true
   # Set to extend mode for dynamic resolution
   gsettings set org.gnome.desktop.remote-desktop.rdp screen-share-mode 'extend'
   # Set credentials
   grdctl rdp set-credentials
   ```
2. Connect from aFreeRDP first to verify the server works
3. Then test with the custom app

### Test cases for dynamic resolution
- Portrait -> landscape rotation
- Enter/exit split-screen mode
- Freeform window resize (tablets, ChromeOS, desktop mode)
- Connect in one orientation, rotate, verify remote desktop resizes
- Rapid rotation (debounce should prevent flooding)

## References

- FreeRDP source: https://github.com/FreeRDP/FreeRDP
- FreeRDP Android docs: https://github.com/FreeRDP/FreeRDP/blob/master/docs/README.android
- FreeRDP high DPI guide: https://freerdp-freerdp.mintlify.app/guides/high-dpi
- MS-RDPEDISP spec: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-rdpedisp/
- GNOME Remote Desktop: https://gitlab.gnome.org/GNOME/gnome-remote-desktop
- Jetpack WindowManager (foldables): https://developer.android.com/jetpack/androidx/releases/window
