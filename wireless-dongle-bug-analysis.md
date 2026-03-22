# Wireless AA Dongle Bug Analysis

## Device Info
- **Dongle:** Wireless Android Auto USB dongle (reports itself as "smartlinkBox" with serial "LY0123456789" in USB descriptors — this is the dongle's internal firmware identity, not a brand name)
- **Phone:** Xiaomi (vendor ID `0x2717`, product ID `0xFF40`)
- **Head unit:** Chinese Android head unit (CMZX65-U1) with MediaTek SoC

## The Two Device Identities

The wireless AA dongle presents **two different USB identities** depending on its state:

| State | Vendor ID | Product ID | Name shown in USB list | When |
|-------|-----------|------------|----------------------|------|
| **Native mode** | `0x2717` (Xiaomi) | `0xFF40` | `Xiaomi 2717:FF40` | Fresh power-on, or after full reset |
| **Accessory mode** | `0x18D1` (Google) | `0x2D00` | `Google 18D1:2D00` | After AOA mode switch, or still in accessory mode from previous session |

The dongle in native mode reports the **phone's** vendor ID (Xiaomi 2717) — it passes through the phone's USB identity over WiFi. The dongle's USB product name is "smartlinkBox".

## What Works vs What Fails

### Working flow (device appears as "Xiaomi 2717:FF40")

```
1. Dongle appears as Xiaomi 2717:FF40 (native mode, fresh boot)
2. Stability check waits 8s
3. performSingleUsbConnect() → connectAndSwitch() sends AOA descriptors
4. Dongle detaches, re-enumerates as Google 18D1:2D00
5. onUsbAttach() detects accessory device
6. connectUsbWithRetry() → opens USB connection
7. SSL handshake succeeds (fresh session)
8. Android Auto projection works
```

**Evidence:** Logs from Mar 1 16:09, Mar 2 09:45, Feb 28 00:45 all show this successful path.

### Failing flow (device appears as "Google 18D1:2D00")

```
1. Previous AA session ended (phone sent ByeBye or dongle lost WiFi to phone)
2. Dongle STAYS in accessory mode as Google 18D1:2D00
3. App detects "device already in accessory mode" → skips AOA switch
4. connectUsbWithRetry() → opens USB connection → claims interface
5. Handshake sends VERSION_REQUEST
6. Receives garbage/stale data instead of VERSION_RESPONSE
   ("Ignoring unexpected message ch=6 type=0x1703 len=8235")
7. VERSION_REQUEST/RESPONSE fails after 3 attempts
8. Dongle eventually detaches
9. (Later, dongle may re-enumerate as Xiaomi 2717:FF40 → NOW it works)
```

**Evidence:** Logs from Mar 6 00:12:22-00:12:26, Mar 1 15:59, Feb 28 00:45:56 all show this failure.

## Root Cause

### Primary bug: Stale accessory-mode device after session teardown

The core issue is in `checkAlreadyConnectedUsb()` at **AapService.kt:410-416**:

```kotlin
// Check for devices already in accessory mode first
for (device in deviceList.values) {
    if (UsbDeviceCompat.isInAccessoryMode(device)) {
        AppLog.i("Found device already in accessory mode: ...")
        serviceScope.launch { connectUsbWithRetry(device) }
        return
    }
}
```

This code **always** attempts to connect directly to any device already in accessory mode (18D1:2D00). It has **no way to know** whether the device's AA session is still alive. For a wireless dongle, the device stays in accessory mode even after:
- The phone disconnects from the dongle's WiFi
- The phone's Android Auto app closes (ByeBye USER_SELECTION)
- The dongle loses connectivity to the phone

The dongle's USB interface remains as 18D1:2D00, but the AA protocol endpoint is dead. The USB bulk endpoints have stale data from the old session in their buffers. When the app opens a new connection and sends a VERSION_REQUEST, it reads back old protocol messages (type 0x1703 = encrypted application data from the previous SSL session) instead of a fresh VERSION_RESPONSE.

### Secondary issue: USB read errors during active sessions

Even during a working session, the Mar 6 log shows repeated:
```
Too many read errors, attempting interface reset...
USB interface re-claimed successfully
```

These happen every ~1 second starting from 00:10:21 (just after the handshake completes). The dongle's USB implementation is flaky — it drops data and needs interface resets. This causes:
- The session to be fragile
- Video/audio may have glitches
- The phone may eventually give up and send ByeBye

### Why "Xiaomi" works but "Google" doesn't

- **"Xiaomi 2717:FF40"** = dongle in native mode → the app sends AOA control transfers → dongle negotiates a **fresh** session with the phone → re-enumerates as 18D1:2D00 with clean buffers → handshake succeeds

- **"Google 18D1:2D00"** = dongle still in stale accessory mode → the app skips AOA (thinks device is ready) → opens USB endpoints that have stale data → handshake fails because there's no active phone session behind the USB interface

## The Bug Flow in `checkAlreadyConnectedUsb()`

```
checkAlreadyConnectedUsb()
  ├── Scans deviceList for isInAccessoryMode (18D1:2D00)
  ├── FINDS the stale dongle → connectUsbWithRetry()
  └── RETURNS early → never reaches single-USB mode check
                         (which would handle Xiaomi 2717:FF40)
```

The accessory-mode check at line 410-417 runs **before** the single-USB mode check at line 442-456. Since the stale dongle matches `isInAccessoryMode`, the function returns early and never considers whether there's a non-accessory device to connect to.

## Proposed Fix Strategy

### Option A: Detect stale accessory sessions and force re-enumeration (Recommended)

When connecting to a device already in accessory mode, if the handshake fails (VERSION_RESPONSE timeout), the app should:

1. **Close the USB connection** (release interface, close device connection)
2. **Send AOA descriptors again** — this can force the dongle to reset and re-enumerate
3. Or simply **wait for the dongle to re-enumerate** on its own as Xiaomi 2717:FF40, then retry through the normal path

Implementation: In `connectUsbWithRetry()`, after all retries fail, call `connectAndSwitch()` on the failed device to force a re-enumeration. Or add a fallback in `checkAlreadyConnectedUsb()` that removes stale accessory devices from consideration after a failed connection attempt.

### Option B: Don't auto-connect to accessory-mode devices after a clean disconnect

After a ByeBye (clean disconnect), mark the session as terminated. When `checkAlreadyConnectedUsb()` runs again, skip the accessory-mode shortcut and instead wait for the device to re-enumerate as its native identity.

Implementation: Add a flag like `lastSessionEndedCleanly` that prevents the accessory-mode fast path from firing immediately after disconnect. Or use a cooldown timer.

### Option C: Try AOA re-init even for accessory-mode devices

Instead of directly connecting to 18D1:2D00, first try sending AOA control transfers (ACC_REQ_GET_PROTOCOL, strings, ACC_REQ_START) to the accessory-mode device. The dongle may respond by re-enumerating with a fresh session. If the control transfers fail, fall through to the direct connection attempt.

### Option D: Improve stale data handling in the handshake

When the handshake receives unexpected messages (the `Ignoring unexpected message` path in `AapTransport`), flush the USB read buffer more aggressively before retrying the VERSION_REQUEST. The current code ignores stale messages but doesn't drain the buffer, so the same garbage keeps coming back.

## Log Evidence Summary

### Mar 6 00:10 — First connection succeeds (via AOA switch)
```
00:10:01 — USB_DEVICE_ATTACHED (dongle appears, not logged which identity)
00:10:11 — USB_DEVICE_DETACHED (dongle re-enumerates)
00:10:12 — USB_DEVICE_ATTACHED (dongle re-appears)
00:10:19 — AOA control transfers succeed (acc_ver: 2, 6 strings, acc start)
00:10:20 — Re-enumerates as Google 18D1:2D00 (accessory mode)
00:10:20 — "USB accessory device attached, connecting"
00:10:20 — Connection established
00:10:21 — Handshake succeeds (SSL complete in 73ms!)
00:10:21 — Service Discovery, channels setup
00:10:21 — BUT: "Too many read errors" starts immediately (interface resets)
00:10:25 — H.265 video decoder starts (OMX.MTK.VIDEO.DECODER.HEVC)
00:10:28 — Audio starts
           (session runs but with periodic USB read errors)
```

### Mar 6 00:12 — Session torn down by phone, reconnect fails
```
00:12:18 — BYEBYE REQUEST from phone (reason: USER_SELECTION)
00:12:18 — Clean disconnect, projection activity destroyed
00:12:22 — HomeFragment attempts single-USB auto-connect
00:12:22 — "Found device already in accessory mode: Google 18D1:2D00"
00:12:22 — Connection established (USB opens OK)
00:12:23 — Handshake: VERSION_REQUEST sent
00:12:23 — "Ignoring unexpected message (ch=6, type=0x1703, len=8235)" × 6
00:12:25 — "No VERSION_RESPONSE within 2s (attempt 1)"
00:12:26 — "No VERSION_RESPONSE within 2s (attempt 2)"
00:12:26 — "Version request send failed (ret=-1), attempt 3"
00:12:26 — USB_DEVICE_DETACHED (dongle resets itself)
00:12:26 — Handshake failed after 3 attempts
00:12:28 — Falls through to WiFi network discovery (fails — no WiFi network)
           END — dongle never comes back as Xiaomi in this log session
```

### Mar 1 15:59 — Same failure pattern (cold start with stale dongle)
```
15:59:48 — ACTION_CHECK_USB → "Found device already in accessory mode: Google 18D1:2D00"
15:59:48 — Opens USB, releases old interface
15:59:49 — Handshake starts
15:59:49 — Version response recv failed (ret=-1), attempt 1
15:59:50 — Version request send failed (ret=-1), attempt 2
15:59:50 — USB_DEVICE_DETACHED
15:59:50 — Handshake failed after 2 attempts
```

### Feb 28 00:45 — Failure then success (dongle re-enumerates as Xiaomi)
```
00:45:56 — Handshake fails on stale Google 18D1:2D00
00:45:56 — USB detaches
00:45:56 — USB reattaches (dongle resets to native mode)
00:45:56 — "Single USB auto-connect: connecting to Xiaomi 2717:FF40"
00:45:56 — AOA switch succeeds
00:45:57 — Re-enumerates as Google 18D1:2D00
00:45:57 — Connection established (but race conditions cause stale attempts)
```

## Additional Issue: USB Read Errors

The "Too many read errors, attempting interface reset..." messages during active sessions suggest the dongle has a buggy USB stack. This is a dongle-side issue but the app could handle it better:

- The `maxConsecutiveErrorsBeforeReset = 3` threshold causes resets every ~0.8s
- Interface resets clear the endpoint state, which can drop data
- The phone may interpret lost data as a protocol error and send ByeBye

This is likely why the phone sent `ByeBye USER_SELECTION` — the session degraded due to USB errors until the phone gave up.

## Files Involved

| File | Relevant Code |
|------|--------------|
| `AapService.kt:410-417` | Accessory-mode shortcut that bypasses AOA switch |
| `AapService.kt:553-567` | `connectUsbWithRetry()` — no fallback after all retries fail |
| `AapService.kt:497-514` | `performSingleUsbConnect()` — only for non-accessory devices |
| `AapTransport.kt` | Handshake version exchange — `Ignoring unexpected message` path |
| `UsbAccessoryConnection.kt` | USB bulk transfer errors and interface reset logic |
| `UsbDeviceCompat.kt` | `isInAccessoryMode()` check (vendorId == 0x18D1 && productId == 0x2D00/0x2D01) |
| `UsbAccessoryMode.kt` | AOA control transfer sequence (`connectAndSwitch()`) |
