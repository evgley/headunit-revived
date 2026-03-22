# Plan: Reconnecting overlay for USB error patience window

## Context

PR #205 introduces a 60-second patience window for USB read errors, allowing wireless dongles to recover from phone-side network disruptions. During this window, the projection screen shows a frozen last frame with **zero feedback** — the user thinks the app is hung. We need to show a "reconnecting" overlay with an option to disconnect manually.

## Approach

Detect **video frame stall** (no frames for 5 seconds after first frame was received) in `AapProjectionActivity`, then repurpose the existing `loading_overlay` to show an informative message explaining the problem is phone-side, with a Disconnect button. This is connection-type-agnostic (works for both USB and WiFi stalls).

### Why no special detection code is needed

When the overlay triggers, we already know:
1. Frames stopped arriving → phone stopped sending data
2. No `ByeByeRequest` was received → not a graceful phone-side disconnect
3. USB/WiFi connection is still alive (no `Disconnected` state yet)

This combination can **only** mean the phone stopped sending data without warning — exactly what happens during phone-side network changes. The video stall itself IS the detection.

### Overlay message (two lines)

**Title:** "Connection interrupted"
**Detail:** "Your phone stopped sending data. This is usually caused by a network change on your phone. Waiting for recovery…"

This tells the user: (a) what happened, (b) it's their phone's fault, (c) the app is trying to recover.

## Changes

### 1. `VideoDecoder.kt` — Add frame timestamp tracking
- Add `@Volatile var lastFrameRenderedMs: Long = 0L` (line ~51, next to `onFirstFrameListener`)
- Update it on each `releaseOutputBuffer` at line 412: `lastFrameRenderedMs = SystemClock.elapsedRealtime()`
- Reset to `0L` in `stop()` (line ~108)

### 2. `activity_headunit.xml` — Add IDs, detail text, and Disconnect button
- Add `android:id="@+id/overlay_text"` to the existing TextView (line 29)
- Add a second `TextView` (id `overlay_detail`, visibility `gone`) below the spinner+text row for the explanatory detail message (smaller text, ~14sp, white, centered)
- Add a `Button` (id `disconnect_button`, visibility `gone`) below the detail text, styled as a simple text button with white text on transparent background

### 3. `AapProjectionActivity.kt` — Reconnecting watchdog + overlay control
- Add `OverlayState` enum: `STARTING`, `RECONNECTING`, `HIDDEN`
- Track `overlayState = OverlayState.STARTING`
- Add `reconnectingWatchdog` Runnable (runs every 2s):
  - Skip if not connected or `lastFrameRenderedMs == 0` (first frame hasn't arrived)
  - If overlay is HIDDEN and frame gap > 5s → `showReconnectingOverlay()`
  - If overlay is RECONNECTING and frame gap < 2s → `hideReconnectingOverlay()`
- `showReconnectingOverlay()`: set overlay VISIBLE, change title to "Connection interrupted", show detail text with phone-blame message, show disconnect button, set state to RECONNECTING
- `hideReconnectingOverlay()`: set overlay GONE, set state to HIDDEN
- Modify `onFirstFrameListener` handler: also set `overlayState = HIDDEN`
- Wire disconnect button: call `commManager.disconnect()` (exists at `CommManager.kt:368`)
- Start/stop `reconnectingWatchdog` in `onResume()`/`onPause()` alongside existing watchdogs

### 4. `values/strings.xml` — Add new strings
- `<string name="connection_interrupted">Connection interrupted</string>`
- `<string name="connection_interrupted_detail">Your phone stopped sending data. This is usually caused by a network change on your phone. Waiting for recovery…</string>`
- Reuse existing `shortcut_disconnect_title` ("Disconnect") for the button

### 5. Locale files — Add translations for `connection_interrupted` and `connection_interrupted_detail`
- Add both strings to all 10 locale `strings.xml` files (`cs`, `de`, `es`, `es-rES`, `nl`, `pl`, `pt-rBR`, `ru`, `uk`, `zh-rTW`)

## Files to modify

| File | Change |
|------|--------|
| `app/src/main/java/.../decoder/VideoDecoder.kt` | Add `lastFrameRenderedMs` field + update in output loop |
| `app/src/main/res/layout/activity_headunit.xml` | Add TextView ID, add Disconnect button |
| `app/src/main/java/.../aap/AapProjectionActivity.kt` | Reconnecting watchdog, overlay state management, disconnect button handler |
| `app/src/main/res/values/strings.xml` | Add `connection_interrupted` + `connection_interrupted_detail` strings |
| `app/src/main/res/values-{10 locales}/strings.xml` | Add translated versions of both strings |

## Existing code reused

- `loading_overlay` layout (activity_headunit.xml)
- `videoWatchdogRunnable` pattern for keyframe requests (AapProjectionActivity.kt:47-56) — continues to work alongside, requesting keyframes while overlay is visible
- `commManager.disconnect()` (CommManager.kt:368) for the Disconnect button
- `shortcut_disconnect_title` string ("Disconnect") already exists (strings.xml:261)
- `watchdogHandler` already exists for scheduling (AapProjectionActivity.kt:45)

## Edge cases

- **Initial connection:** `lastFrameRenderedMs == 0` → watchdog skips, "Starting..." overlay handled as before
- **Quick fps dips:** 5s threshold avoids false positives from brief pauses
- **Disconnect during overlay:** `ConnectionState.Disconnected` observer calls `finish()` regardless
- **WiFi stalls:** Works identically — detection is based on frame arrival, not USB-specific state

## Verification

1. Build: `./gradlew assembleDebug`
2. Visual check: connect AA, verify normal flow (Starting overlay → first frame → overlay hides)
3. Simulate stall: disconnect phone WiFi during AA session → overlay should appear after ~5s with "Connection interrupted" + Disconnect button
4. Recovery: reconnect WiFi → overlay should hide when frames resume
5. Manual disconnect: tap Disconnect button → session should end cleanly
