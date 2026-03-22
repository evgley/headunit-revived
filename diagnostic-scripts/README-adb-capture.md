# ADB Phone Log Capture — Issue #203 Diagnostics

Captures filtered logcat from the **phone** to diagnose why Android Auto disconnects when the phone regains a network connection (mobile data, tunnel exit, BT disconnect).

## Prerequisites

- ADB installed (`adb` in PATH)
- Developer Options enabled on phone
- USB Debugging enabled on phone

## Setup (Wireless ADB)

Wireless ADB lets you capture logs while the phone is connected to the head unit via USB.

1. Connect phone to PC via USB
2. Run:
   ```bash
   adb tcpip 5555
   ```
3. Disconnect USB cable
4. Find phone's IP: **Settings > About Phone > Status > IP Address**
5. Ensure PC and phone are on the same WiFi network

## Capture

```bash
cd diagnostic-scripts/

# Wireless ADB (recommended — phone stays free for USB AA)
./capture-phone-logs.sh 192.168.1.100

# USB ADB (if wireless ADB not possible)
./capture-phone-logs.sh
```

The script will:
1. Connect to the phone (if IP provided)
2. Dump current device info and network state
3. Clear the logcat buffer
4. Start capturing filtered logs (WiFi, connectivity, telephony, AA, Xiaomi-specific)
5. Show live output in terminal
6. Save everything to `phone_log_YYYYMMDD_HHMMSS.txt`

## Reproduce the Bug

While the script is running:

1. Connect phone to head unit via wireless Android Auto
2. Wait for AA projection to be active and stable
3. Trigger one of these:
   - **Toggle mobile data** OFF then ON
   - **Drive through a tunnel** (or toggle airplane mode briefly)
   - **Disconnect a Bluetooth device**
   - **Switch WiFi networks**
4. Observe if AA freezes/disconnects
5. Press **Ctrl+C** to stop capture

## What the Log Contains

| Category | Tags | Why |
|---|---|---|
| WiFi stack | WifiService, WifiStateMachine, WifiClientModeImpl, wpa_supplicant | Detect if WiFi resets or supplicant disconnects |
| Connectivity | ConnectivityService, NetworkAgent, NetworkMonitor | Track default network switches |
| Android Auto | GearheadConnectivity, Gearhead, ProjectionService | See AA's reaction to network changes |
| DHCP/DNS | DhcpClient, DnsResolver, Netd | Detect IP/route changes |
| Telephony | TelephonyManager, DataConnection, DcTracker | Track mobile data state changes |
| Xiaomi/HyperOS | MiuiWifi, MiuiNetwork, MiuiConnectivity | Catch custom WiFi stack behavior |

## Comparing Phones

Run the same capture on a phone that does NOT reproduce the bug. Compare the event sequence when toggling mobile data:

**Buggy phone (expected):**
```
WiFi: DISCONNECTED          ← WiFi drops unnecessarily
WiFi: DISABLING → DISABLED  ← full stack reset
WiFi: ENABLING → ENABLED    ← WiFi comes back
WiFi: COMPLETED             ← reconnects
```

**Normal phone (expected):**
```
(no WiFi events)            ← WiFi stays connected
```

The diff reveals whether HyperOS resets the WiFi stack vs. just re-evaluating the default route.

## Output

Log files are saved in the `diagnostic-scripts/` directory:
```
diagnostic-scripts/
├── capture-phone-logs.sh
├── README-adb-capture.md
├── phone_log_20260312_143000.txt   ← example output
└── phone_log_20260312_150000.txt   ← another capture
```
