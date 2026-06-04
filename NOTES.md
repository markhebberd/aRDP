# Development Notes & Gotchas

Notes for whoever picks this up - things that aren't obvious from the code or docs.

## FreeRDP Android Build

The FreeRDP Android build is notoriously fiddly. Some things to know:

- The build downloads and compiles OpenSSL, OpenH264, and libjpeg-turbo from source as part of the Gradle build. This takes a long time on first build.
- NDK version matters. Check `client/Android/Studio/gradle.properties` in the FreeRDP repo for the expected NDK version. Mismatched NDK versions cause cryptic C compilation errors.
- The 3.26.0 release overhauled the Android client significantly. Use this version or newer - older versions have a very different (worse) Android integration.
- If building freeRDPCore from source, you need CMake 3.22+ and the Android NDK installed via SDK Manager.

### Integration approaches (pick one)

**Option A: Git submodule** - Clone FreeRDP as a submodule, reference `client/Android/Studio/freeRDPCore` as a Gradle module. Most control, hardest build setup.

**Option B: Pre-built AAR** - Build freeRDPCore once, publish the AAR to a local Maven repo or check it into the project. Simpler builds, harder to debug native issues.

**Option C: Fork aFreeRDP** - Fork the entire FreeRDP repo and modify the aFreeRDP app directly. Easiest start, but carries the weight of the entire FreeRDP codebase.

Recommendation: Start with Option C to get something working, then extract into a cleaner structure once the integration is proven.

## Dynamic Resolution - What I Learned

### The disp channel timing matters

The `DISPLAYCONTROL_MONITOR_LAYOUT_PDU` can only be sent after the connection is fully established and the disp channel is open. Sending it during connection setup will be ignored or cause errors. Wait for the `OnChannelConnected` callback with the disp channel name before sending resize requests.

### DPI is part of the resize PDU

The monitor layout PDU includes physical dimensions and DPI. Android devices vary wildly in DPI (120-640). You should:
- Report the actual device DPI from `DisplayMetrics.densityDpi`
- Or normalize to a standard DPI (96 or 144) and scale the resolution accordingly
- GNOME Remote Desktop respects the DPI field for font/UI scaling on the remote side

### Screen-share mode silently ignores resizes

If the GNOME server is in `mirror` mode (the default), resize PDUs are accepted but nothing happens. The app should ideally detect this and fall back to client-side scaling, or at minimum document that extend mode is required. There's no protocol-level way to detect which mode the server is in - you just don't get a resize response.

## Input Handling Complexity

Touch-to-mouse translation is harder than it sounds:

- **Tap = left click**: Simple, but need to distinguish from drag start. Use a short delay (~100ms) or distance threshold.
- **Long press = right click**: Conflicts with drag. Need to detect if the finger moves during the press.
- **Two-finger scroll**: RDP scroll events are discrete (wheel clicks), not smooth. Map fling velocity to multiple wheel events.
- **Pinch zoom**: This should zoom the LOCAL viewport, not send zoom to the remote. The remote desktop is at a fixed resolution; pinch-zoom pans/zooms the local view of it.
- **Drag**: Touch down + move = mouse move with button held. But on a phone screen, precision is terrible. Consider a "trackpad mode" where touch movement is relative (like a laptop trackpad) rather than absolute (tap position = cursor position).

The aFreeRDP reference app has input handling code that's worth studying even if you rewrite the UI.

## GNOME Remote Desktop Quirks

- **First-time certificate**: GNOME generates a self-signed TLS certificate. FreeRDP will reject it by default. You need to handle the certificate verification callback and implement trust-on-first-use.
- **Headless sessions**: In extend mode, GNOME creates a virtual monitor with no physical display. If the user also has a physical session, they're separate. This is actually ideal for remote access - the remote user gets their own session.
- **Audio**: GNOME Remote Desktop supports audio redirection. FreeRDP has audio channel support but the Android client's audio playback may need work. This is a nice-to-have, not essential for v1.
- **File transfer**: Not supported by GNOME Remote Desktop as of mid-2026. Don't bother implementing drive redirection.

## Android-Specific Considerations

- **Foreground service**: The RDP session should run in a foreground service with a persistent notification. Otherwise Android will kill the connection when the app goes to background.
- **Wake lock**: Consider a partial wake lock during active sessions to prevent the CPU from sleeping mid-connection. Release it when the session is idle or backgrounded.
- **Picture-in-picture**: Could be interesting for monitoring a remote machine - show the RDP session in a PiP window. Low priority but architecturally plan for the session being rendered to a detachable surface.
- **ChromeOS / desktop mode**: Android apps on ChromeOS run in resizable windows. This is a great test case for dynamic resolution - the window can be freely resized by dragging edges.

## Minimum Viable Product (v1 scope)

1. Connect to a single GNOME Remote Desktop server via RDP
2. Display the remote desktop in a full-screen view
3. Touch input (tap, drag, scroll)
4. Dynamic resolution on orientation change
5. Software keyboard for text input
6. Certificate trust-on-first-use
7. Save one or more connection profiles

### Explicitly NOT in v1
- Audio redirection
- Clipboard sync
- File transfer
- Multi-monitor
- SSH tunneling
- VNC/SPICE support
- Gamepad input
