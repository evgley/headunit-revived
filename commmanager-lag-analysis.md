# CommManager Lag Analysis

## Context

PR #181 (USB auto-connect permission fix) was merged then reverted via PR #182 because the repo owner reported "It is running super laggy on my devices." The lag was caused by the underlying CommManager refactor (PR #167), not by the USB permission fix itself.

## Root Causes (Ranked by Impact)

### 1. Coroutine-per-send in hot paths (HIGH)

**File:** `CommManager.kt:369-381`

Every `commManager.send()` wraps the actual send in `_scope.launch { _transport?.send(message) }`. The scope uses `Dispatchers.IO`, so every send does:

```
Caller thread → IO thread pool → sendHandler HandlerThread
```

The original code calls `transport.send()` directly, which uses Android's zero-allocation `Handler.obtainMessage()` pool:

```
Caller thread → sendHandler HandlerThread
```

Touch events fire at ~60Hz. That's 60+ coroutine allocations per second (each creates a `Job` + `CoroutineContinuation`), plus double the context switches. On a 2-4 core MediaTek head unit, this is the most likely cause of perceived lag.

### 2. IO dispatcher for all sends (HIGH)

**File:** `CommManager.kt:104`

```kotlin
private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

`Dispatchers.IO` shares a thread pool (up to 64 threads) with all other IO operations. Sends compete with USB reads, network discovery, and file operations for thread scheduling. The original architecture used a dedicated `HandlerThread` for sends — no contention.

### 3. Two-phase video startup latency (HIGH)

**Files:** `CommManager.kt:260-316`, `AapProjectionActivity.kt:147-161`

CommManager splits the connection into two phases:
1. `startHandshake()` — called from `AapService.onConnected()`
2. `startReading()` — called from `AapProjectionActivity` when `HandshakeComplete` is observed AND surface is ready

Between phase 1 and phase 2, there's a StateFlow emission → collection delay through `Dispatchers.Main`. On a busy device this can be 10-50ms. The original `transport.start()` did handshake + reading in one synchronous call — frames arrived immediately.

### 4. Eight concurrent StateFlow observers (MEDIUM)

**Files:** `AapService.kt` (4 observers), `AapProjectionActivity.kt` (2), `HomeFragment.kt` (2)

Every state transition (`Connecting → Connected → StartingTransport → HandshakeComplete → TransportStarted`) gets processed by all 8 `filterIsInstance<>()` pipelines. The original architecture used simple BroadcastReceivers which skip non-matching intents with zero overhead.

### 5. Non-resettable CommManager scope (MEDIUM)

**File:** `AppComponent.kt:22`

```kotlin
val commManager = CommManager(app, settings, audioDecoder, videoDecoder)
```

CommManager is a `val` — created once, never destroyed. Its `_scope` accumulates coroutine jobs across connections. Failed `send()` calls create error-handling coroutines that pile up. The original code called `resetTransport()` between connections for clean state.

### 6. destroy() race condition (LOW-MEDIUM)

**File:** `CommManager.kt:444-451`

```kotlin
fun destroy() {
    _scope.launch { withContext(Dispatchers.IO) { doDisconnect() } }
    _scope.cancel()  // cancels the coroutine we just launched!
}
```

`_scope.cancel()` fires immediately after launching the cleanup coroutine. On a busy device the cleanup may never execute, leaking transport and connection resources.

## What PR #182 (the revert) Changed

PR #182 is not a simple `git revert` — it's a **massive rewrite** touching 28 files:

- Removes `CommManager` entirely
- Moves connection management into `AapTransport`
- Replaces StateFlow observation with BroadcastReceivers and static flags (`AapService.isConnected`, `AapService.isScanning`, `AapService.pendingSocket`)
- Removes USB permission handling (`requestUsbPermission()`, `onUsbPermission()`)
- Removes `resolveUsbDevice()` fallback for Xtrons devices (issue #173)
- Changes `UsbAccessoryConnection.connect()` to `Dispatchers.Main` (ANR risk — contains `Thread.sleep()`)
- Disables SSL session caching

## Issues Reintroduced by the Revert

1. **USB auto-connect silently fails** when no permission is granted (issue #173 regression)
2. **Xtrons head units can't connect** via USB_DEVICE_ATTACHED intent (missing device extra workaround removed)
3. **ANR risk** — `UsbAccessoryConnection.connect()` now runs on Main with blocking `Thread.sleep()`
4. **Static mutable state** (`isConnected`, `pendingSocket`) — thread-unsafe, race-prone
5. **No handshake timeout** — stalled handshake blocks IO thread indefinitely

## How to Fix CommManager Without Removing It

The performance issues are fixable:

1. **`send()` should post directly to the handler** — no coroutine launch needed:
   ```kotlin
   fun send(message: AapMessage) {
       _transport?.send(message)  // already posts to sendHandler internally
   }
   ```

2. **Combine handshake + reading** into a single phase — no need for the StateFlow coordination dance

3. **Reduce observer count** — combine related observers or use a single `when` block on state type

4. **Cancel and recreate scope** between connections to prevent accumulation

## Comments Posted

- PR #167: https://github.com/andreknieriem/headunit-revived/pull/167#issuecomment-4011445475
- PR #182: https://github.com/andreknieriem/headunit-revived/pull/182#issuecomment-4011446527
