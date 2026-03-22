#!/usr/bin/env bash
#
# capture-phone-logs.sh — Capture filtered logcat from phone for Issue #203 diagnostics
#
# Captures WiFi stack, connectivity, Android Auto, DHCP/DNS, telephony,
# and Xiaomi/HyperOS-specific events from the phone via ADB.
#
# Usage: ./capture-phone-logs.sh [phone-ip]
#   phone-ip: optional, for wireless ADB connection (e.g. 192.168.1.100)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
OUTPUT_FILE="${SCRIPT_DIR}/phone_log_${TIMESTAMP}.txt"

# --- ADB Connection ---

if ! command -v adb &>/dev/null; then
    echo "ERROR: adb not found. Install Android SDK platform-tools."
    exit 1
fi

PHONE_IP="${1:-}"

if [ -n "$PHONE_IP" ]; then
    echo "Connecting to phone at ${PHONE_IP}:5555..."
    adb connect "${PHONE_IP}:5555" || {
        echo ""
        echo "Connection failed. Make sure:"
        echo "  1. Phone and PC are on the same WiFi network"
        echo "  2. You previously ran: adb tcpip 5555 (while phone was USB-connected)"
        echo "  3. Phone IP is correct (Settings > About Phone > Status > IP Address)"
        exit 1
    }
    echo ""
fi

# Verify device connected
DEVICE_COUNT="$(adb devices | grep -c 'device$' || true)"
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: No device connected."
    echo ""
    echo "For wireless ADB:"
    echo "  1. Connect phone via USB"
    echo "  2. Run: adb tcpip 5555"
    echo "  3. Disconnect USB"
    echo "  4. Run: $0 <phone-ip>"
    echo ""
    echo "For USB ADB:"
    echo "  1. Connect phone via USB"
    echo "  2. Run: $0"
    exit 1
fi

echo "Device connected. Capturing to: ${OUTPUT_FILE}"

# Print device info header
{
    echo "=== Phone Diagnostics for Issue #203 ==="
    echo "=== Capture started: $(date) ==="
    echo ""
    echo "--- Device Info ---"
    adb shell getprop ro.product.model 2>/dev/null || echo "unknown"
    adb shell getprop ro.build.display.id 2>/dev/null || echo "unknown"
    adb shell getprop ro.build.version.release 2>/dev/null || echo "unknown"
    adb shell getprop ro.build.version.sdk 2>/dev/null || echo "unknown"
    adb shell getprop ro.miui.ui.version.name 2>/dev/null || echo "n/a"
    echo ""
    echo "--- Current Network State ---"
    adb shell dumpsys connectivity 2>/dev/null | head -30 || echo "failed"
    echo ""
    echo "--- WiFi Info ---"
    adb shell dumpsys wifi 2>/dev/null | head -20 || echo "failed"
    echo ""
    echo "=== Live logcat follows ==="
    echo ""
} > "$OUTPUT_FILE"

# --- Logcat Tags ---
# Each tag:priority pair filters to relevant subsystems

TAGS=(
    # WiFi stack
    "WifiService:V"
    "WifiStateMachine:V"
    "WifiNative:V"
    "WifiMonitor:V"
    "WifiManager:V"
    "WifiNetworkFactory:V"
    "WifiConnectivityManager:V"
    "WifiClientModeImpl:V"
    "WifiScanningService:V"
    "WifiConfigManager:V"
    "wpa_supplicant:V"
    "WifiHAL:V"
    "WifiP2pService:V"

    # Connectivity
    "ConnectivityService:V"
    "ConnectivityManager:V"
    "NetworkAgent:V"
    "NetworkMonitor:V"
    "NetworkFactory:V"
    "NetworkController:V"
    "IpClient:V"
    "IpManager:V"

    # Android Auto
    "GearheadConnectivity:V"
    "Gearhead:V"
    "AndroidAuto:V"
    "ProjectionService:V"
    "AAWireless:V"
    "GearheadService:V"
    "AAProjection:V"

    # DHCP / DNS
    "DhcpClient:V"
    "DnsResolver:V"
    "Netd:V"

    # Telephony / mobile data
    "TelephonyManager:V"
    "DataConnection:V"
    "DcTracker:V"
    "ServiceState:V"
    "RIL:V"
    "Telephony:V"
    "CarrierConfigManager:V"
    "MobileDataStateTracker:V"

    # Xiaomi / HyperOS specific
    "MiuiWifi:V"
    "MiuiNetwork:V"
    "MiuiConnectivity:V"
    "MiuiNetworkPolicy:V"
    "MiuiWifiService:V"

    # Suppress everything else
    "*:S"
)

TAG_STRING="${TAGS[*]}"

echo ""
echo "========================================"
echo "  Reproduction Steps Reminder"
echo "========================================"
echo ""
echo "  1. Connect phone to head unit via wireless AA"
echo "  2. Wait for AA projection to be active and stable"
echo "  3. Trigger one of these scenarios:"
echo "     a) Toggle mobile data OFF then ON"
echo "     b) Drive through a tunnel (or toggle airplane mode briefly)"
echo "     c) Disconnect a Bluetooth device"
echo "     d) Switch between WiFi networks"
echo "  4. Observe if AA freezes/disconnects"
echo "  5. Press Ctrl+C when done"
echo ""
echo "========================================"
echo ""
echo "Capturing... (Ctrl+C to stop)"
echo ""

# Clear logcat buffer and start fresh capture
adb logcat -c 2>/dev/null || true
adb logcat -v threadtime $TAG_STRING >> "$OUTPUT_FILE" &
LOGCAT_PID=$!

# Also show filtered output in terminal
tail -f "$OUTPUT_FILE" &
TAIL_PID=$!

# Cleanup on exit
cleanup() {
    echo ""
    echo "Stopping capture..."
    kill "$LOGCAT_PID" 2>/dev/null || true
    kill "$TAIL_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
    wait "$TAIL_PID" 2>/dev/null || true

    LINE_COUNT="$(wc -l < "$OUTPUT_FILE")"
    FILE_SIZE="$(du -h "$OUTPUT_FILE" | cut -f1)"

    echo ""
    echo "========================================"
    echo "  Capture complete!"
    echo "  File: ${OUTPUT_FILE}"
    echo "  Size: ${FILE_SIZE} (${LINE_COUNT} lines)"
    echo "========================================"
}

trap cleanup EXIT INT TERM

# Wait for Ctrl+C
wait "$LOGCAT_PID"
