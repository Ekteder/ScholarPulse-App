# ScholarPulse Android Synchronization & Reliability Deep-Dive Audit

**Document Version:** 1.0.0  
**Target Codebase:** Android Native Shell (`/media/sanglap/Movies/scholarpulsev2/app`) & Next.js 16 Web Backend (`/media/sanglap/Movies/scholarpulsev2/v2`)  
**Audit Scope:** Offline Queue Crash Recovery, Concurrency, Server Contract Reconciliation, FCM Security, Timer Persistence Lifecycles, and Cross-Entity Offline Coverage  
**Mode:** Empirical Code Analysis (READ-ONLY)

---

## Executive Summary & Severity Matrix

| ID | Vulnerability / Architectural Flaw | Subsystem | Severity | Impact |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-01** | **Logout Bypass in FCM Timer Sync Guard** | `AppFirebaseMessagingService.kt` | **HIGH** | Remote timer commands processed after user logout when `currentUserId == null`. |
| **DATA-01** | **Permanent Mutation Abandonment on Process Death** | `OfflineMutationDao` / `OfflineSyncWorker.kt` | **HIGH** | Mutations stuck in `SYNCING` state are never queried or retried after a process crash. |
| **DATA-02** | **Full Batch Rejection on Single Malformed Item** | `v2/src/app/api/sync/batch/route.ts` | **HIGH** | Whole batch blocked with HTTP 400; valid study sessions never sync. |
| **RACE-01** | **Unsynchronized Multi-Threaded Sync Execution** | `TimerActionReceiver.kt` vs `OfflineSyncWorker.kt` | **MEDIUM** | Dual concurrent uploads of identical Room mutations without mutex or DB transactions. |
| **TIME-01** | **Wall-Clock Time Skew & NTP Vulnerability** | `TimerForegroundService.kt` | **MEDIUM** | Uses `System.currentTimeMillis()` instead of `SystemClock.elapsedRealtime()`; clock changes break timer. |
| **SPEC-01** | **Unimplemented Room Offline Storage for Tasks/Revisions** | `NativeBridgeDispatcher.kt` | **MEDIUM** | TRD specifies native offline persistence for tasks/revisions, but bridge has no methods to write them. |
| **SYNC-01** | **Server Batch Response Body Ignored** | `OfflineSyncWorker.kt` | **LOW** | Client drops server reconciliation payload (`server_ahead`, conflict stats). |

---

## Question 1 — Offline Queue Crash Recovery

### 1.1 Can mutations remain stuck indefinitely after process termination?
**Yes.** This is a confirmed, critical data-loss vulnerability in the offline queue lifecycle.

#### Code Evidence:
In [OfflineSyncWorker.kt](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/workers/OfflineSyncWorker.kt#L127-L129):
```kotlin
val mutationIds = pendingMutations.map { it.id }
mutationDao.markStatuses(mutationIds, "SYNCING")
```
In [Daos.kt](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/db/Daos.kt#L104-L105):
```kotlin
@Query("SELECT * FROM offline_mutations WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAtMs ASC")
suspend fun getPendingMutations(): List<OfflineMutationEntity>
```

#### Failure Path:
1. `OfflineSyncWorker.performSync()` reads mutations with `status IN ('PENDING', 'FAILED')`.
2. It transitions those rows to `status = 'SYNCING'`.
3. If Android's Low Memory Killer (LMK) kills the process, the battery dies, or the user force-stops the app while `uploadBatch()` is in flight:
   - The database transaction or HTTP call aborts.
   - The rows remain stored in SQLite with `status = 'SYNCING'`.
4. When `OfflineSyncWorker` runs again (on next network reconnect, boot, or 15-minute periodic trigger), `getPendingMutations()` executes:
   ```sql
   SELECT * FROM offline_mutations WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAtMs ASC
   ```
5. **Result:** Any mutation marked `'SYNCING'` is omitted from the result set. It is never processed, never retried, and never purged. It is permanently orphaned in Room SQLite.

### 1.2 Does the app reset stale `SYNCING` records anywhere?
**No.** A repository-wide static analysis of `/media/sanglap/Movies/scholarpulsev2/app` reveals that the string literal `"SYNCING"` appears in exactly **one** place in the entire codebase: [OfflineSyncWorker.kt:128](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/workers/OfflineSyncWorker.kt#L128).
There are zero reset queries in `ScholarPulseApp.onCreate()`, `BootCompletedReceiver`, or `OfflineMutationDao`.

### 1.3 Can we safely reset all `SYNCING` records to `PENDING` on worker startup?
**Yes, but only if protected by a process-wide mutex.**  
Because server endpoints (`/api/sync/batch`) are protected by `idempotencyKey` deduplication, resetting `'SYNCING'` to `'PENDING'` is safe against duplicate insertions. However, if two threads run `performSync()` simultaneously without a lock, Thread B could reset Thread A's active upload back to `'PENDING'`.

### 1.4 What happens if a previous upload succeeded on the server but the response was lost?
1. The server committed the transaction to PostgreSQL.
2. The phone experiences a network drop or timeout before receiving the HTTP 200 header.
3. `HttpURLConnection` throws a `SocketTimeoutException` or `IOException`.
4. If the mutation is reset to `'PENDING'` and re-uploaded, the client sends the exact same payload containing the original `idempotencyKey`.
5. On the backend ([v2/src/app/api/sync/batch/route.ts:108](file:///media/sanglap/Movies/scholarpulsev2/v2/src/app/api/sync/batch/route.ts#L108)):
   ```typescript
   .onConflictDoNothing({ target: studySessionsTable.idempotencyKey })
   ```
   PostgreSQL skips the duplicate insertion. The server reports `sessionsDuplicates: 1` and returns HTTP 200, allowing Android to safely delete the local record.

### 1.5 Does server idempotency protect every mutation type, or only study sessions?
Inspection of [v2/src/app/api/sync/batch/route.ts](file:///media/sanglap/Movies/scholarpulsev2/v2/src/app/api/sync/batch/route.ts) reveals that protection varies by entity type:
- **Study Sessions (`study_sessions`)**: **Fully Protected.** Uses `.onConflictDoNothing({ target: studySessionsTable.idempotencyKey })`.
- **Tasks (`tasks`)**: **Protected against duplicates, but updates on higher version.** Matches by `item.id` or `item.idempotencyKey`. If a duplicate arrives, it updates only if `item.version >= existing.version`.
- **Revision Records (`revision_records`)**: **Protected by composite key conflict target.** Uses `.onConflictDoUpdate({ target: [userId, topicId, category] })`. Gamification XP calculation checks `wasCompleted`:
  ```typescript
  if (!wasCompleted && item.isCompleted) {
    await tx.update(profiles).set({ gamificationXp: sql`${profiles.gamificationXp} + 25` });
  }
  ```
  If the previous request already marked it completed, `wasCompleted` evaluates to `true`, preventing duplicate XP allocation.
- **Active Timer (`active_timer_sessions`)**: **Protected by versioning.** Upserted with `onConflictDoUpdate({ target: [userId] })` if `stateVersion >= currentServerTimer.stateVersion`.
- **Side-Effect Leaks:** Even when data rows are deduplicated, every successful batch sync unconditionally triggers SSE events (`timer:delete`, `timer:update`, `workspace:refresh`). Re-uploads cause duplicate SSE broadcasts to desktop web clients.

### Recommended Crash-Safe Queue Design
```sql
-- 1. Atomic Claim via SQLite UPDATE with lease timeout
UPDATE offline_mutations 
SET status = 'SYNCING', 
    updatedAtMs = :nowMs 
WHERE id IN (
    SELECT id FROM offline_mutations 
    WHERE status = 'PENDING' 
       OR status = 'FAILED' 
       OR (status = 'SYNCING' AND updatedAtMs < (:nowMs - 300000)) -- 5-minute stale lease
    ORDER BY createdAtMs ASC 
    LIMIT 50
);
```

---

## Question 2 — Concurrent Synchronization

### 2.1 Can `OfflineSyncWorker` and `TimerActionReceiver` run simultaneously?
**Yes.** They execute on concurrent threads with zero mutual exclusion.

#### Code Evidence:
1. **WorkManager Invocations**:
   - [OfflineSyncWorker.kt:69](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/workers/OfflineSyncWorker.kt#L69) uses `ExistingPeriodicWorkPolicy.KEEP`.
   - [OfflineSyncWorker.kt:90](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/workers/OfflineSyncWorker.kt#L90) uses `ExistingWorkPolicy.REPLACE`.
   WorkManager serializes its own jobs.
2. **The Direct Execution Bypass**:
   In [TimerActionReceiver.kt:104](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/TimerActionReceiver.kt#L104) and [TimerActionReceiver.kt:210](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/TimerActionReceiver.kt#L210):
   ```kotlin
   OfflineSyncWorker.enqueueImmediateSync(context)
   try {
       OfflineSyncWorker.performSync(context) // DIRECT CALL ON IO DISPATCHER!
   } catch (syncErr: Exception) { ... }
   ```
   `TimerActionReceiver` launches an uncoordinated coroutine on `Dispatchers.IO` and invokes `performSync()` directly to bypass WorkManager scheduling latency.

### 2.2 Could two workers upload the same Room records concurrently?
**Yes.**
1. Thread 1 (`TimerActionReceiver` coroutine) calls `mutationDao.getPendingMutations()` and receives Mutation `[ID_1]`.
2. Before Thread 1 can execute `mutationDao.markStatuses(mutationIds, "SYNCING")`, Thread 2 (WorkManager worker) calls `getPendingMutations()` and also receives Mutation `[ID_1]`.
3. Both threads construct payloads containing `ID_1`.
4. Both threads open concurrent HTTP connections to `/api/sync/batch`.

### 2.3 Is there a database transaction or mutex protecting selection and claiming?
**No.**
In [OfflineSyncWorker.kt:108-128](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/workers/OfflineSyncWorker.kt#L108-L128):
```kotlin
val pendingMutations = mutationDao.getPendingMutations() // Step 1: SELECT
...
val mutationIds = pendingMutations.map { it.id }
mutationDao.markStatuses(mutationIds, "SYNCING")         // Step 2: UPDATE
```
There is no Kotlin `Mutex`, no `@Transaction` block, and no atomic check-and-set.

### 2.4 What happens if the same mutation is uploaded twice concurrently?
1. Both requests hit Next.js concurrently.
2. Postgres transactions resolve via row-level locks on `study_sessions.idempotency_key`. The first commits `inserted: 1`; the second commits `duplicates: 1`.
3. Both return HTTP 200 to Android.
4. Thread 1 calls `mutationDao.deleteMutations(mutationIds)` and succeeds.
5. Thread 2 calls `mutationDao.deleteMutations(mutationIds)`. The rows are already deleted (no-op).
6. **Destructive Edge Case:** If Thread 2's socket times out while Thread 1 succeeds, Thread 2 runs its `catch` block:
   ```kotlin
   for (id in mutationIds) {
       mutationDao.updateStatus(id, "FAILED", "Network request failed")
   }
   ```
   If Thread 1 hasn't deleted them yet, Thread 2 overwrites their status to `FAILED`.

---

## Question 3 — Server Response Handling

### 3.1 Actual `/api/sync/batch` Response Format
The backend route ([v2/src/app/api/sync/batch/route.ts:382-391](file:///media/sanglap/Movies/scholarpulsev2/v2/src/app/api/sync/batch/route.ts#L382-L391)) returns:
```json
{
  "success": true,
  "synced": {
    "studySessions": { "inserted": 1, "duplicates": 0 },
    "tasks": { "updated": 0, "inserted": 0 },
    "revisionRecords": { "upserted": 0 },
    "activeTimer": { "updated": false, "cleared": true },
    "timerCleared": true,
    "updatedTimerRecord": null
  },
  "data": {
    "success": true,
    "synced": { ... },
    "syncedAt": "2026-11-08T01:30:00.000Z"
  },
  "error": null
}
```

### 3.2 Can HTTP 200 contain partial failures?
**No.** All operations in `route.ts` are wrapped inside a single interactive database transaction:
```typescript
const syncStats = await db.transaction(async (tx) => { ... });
```
If an unhandled exception or database constraint fails, Neon Postgres rolls back the entire transaction and the endpoint returns **HTTP 500** (`INTERNAL_ERROR`).

### 3.3 Does it return `server_ahead`?
**In the specification, yes; in the actual implementation, no.**
- In `BACKEND_SPEC.md:799`, the spec stated it would return `{ status: 'server_ahead', activeTimer: currentServerTimer }`.
- In [v2/src/app/api/sync/batch/route.ts:283](file:///media/sanglap/Movies/scholarpulsev2/v2/src/app/api/sync/batch/route.ts#L283), the server does:
  ```typescript
  if (!currentServerTimer || activeTimer.stateVersion >= currentServerTimer.stateVersion) {
    // update timer
  }
  ```
  If `stateVersion` is lower, it simply omits the update and returns `activeTimer: { updated: false, cleared: false }`. It does not return a `server_ahead` error code.

### 3.4 Does Android need to reconcile returned timer state?
**Yes, but it currently does not.**
[OfflineSyncWorker.kt:240-243](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/workers/OfflineSyncWorker.kt#L240-L243) reads `responseBody` as a raw string for debug logging, but never parses it. If the server holds an updated active timer, `OfflineSyncWorker` completely ignores it.

### 3.5 Can the worker delete all local mutations after receiving HTTP 200 even if individual items failed?
In [OfflineSyncWorker.kt:186-189](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/workers/OfflineSyncWorker.kt#L186-L189):
```kotlin
if (syncSuccess) {
    Log.i(TAG, "Batch sync succeeded. Removing ${mutationIds.size} synced mutations.")
    mutationDao.deleteMutations(mutationIds)
```
`syncSuccess` is evaluated solely as `responseCode in 200..299`. Since the server uses an all-or-nothing transaction, all mutations included in an HTTP 200 response were successfully committed.

### 3.6 What happens if the server accepts some mutations but rejects others?
The server does **not** perform partial accepts. Schema parsing is executed upfront via Zod:
```typescript
const parsed = BatchSyncPayloadSchema.safeParse(rawBody);
if (!parsed.success) {
    return NextResponse.json({ error: { code: 'VALIDATION_ERROR' } }, { status: 400 });
}
```
**Data Loss / Queue Blocking Scenario:**  
If a single mutation in the batch has a corrupt or invalid field (e.g., an invalid ISO date in a task), the server returns **HTTP 400**. `OfflineSyncWorker` marks all mutations in the batch as `FAILED`. On subsequent retries, `OfflineSyncWorker` bundles the exact same corrupt mutation with all other pending study sessions, failing repeatedly with HTTP 400. **The single corrupt mutation blocks the entire offline queue forever.**

---

## Question 4 — FCM Delivery & Account Security

### 4.1 Are all relevant web mutations sent through FCM, or only timer updates and refresh signals?
**Only timer updates and workspace refresh signals.**
- `syncActiveTimerAction`: sends FCM `type: "timer_state"` (`PAUSE` / `RESUME`).
- `deleteActiveTimerAction`: sends FCM `type: "timer_state"` (`STOP`).
- `publishSyncEvent('workspace:refresh')`: sends FCM data payload.
- **Excluded**: Creating study sessions, updating tasks, or modifying subjects on the web triggers only SSE (`publishSyncEvent`). They generate no FCM data messages. If the phone is backgrounded or screen locked, it receives no notification of web-side CRUD changes until the app is opened.

### 4.2 Is FCM delivery treated as guaranteed anywhere?
**No.** Android considers FCM high-priority data messages best-effort. FCM packets can be delayed or throttled by OEM battery managers (MIUI / EMUI) and Doze maintenance windows.

### 4.3 What happens when the device is offline for several hours?
- FCM messages are held by Google servers until the device reconnects (subject to message TTL).
- When the phone reconnects, queued messages are delivered in a burst.
- Timestamp guards in [TimerForegroundService.kt:275](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/TimerForegroundService.kt#L275) drop older messages if a newer state has already been applied.

### 4.4 What happens when FCM arrives after logout?
### 4.5 The Current-User Guard Flaw (Confirmed Vulnerability)
**There is a high-severity logic flaw in `AppFirebaseMessagingService.kt`.**

#### Code Comparison:
In [AppFirebaseMessagingService.kt:164-171](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/AppFirebaseMessagingService.kt#L164-L171) (Timer Sync Block):
```kotlin
// User match guard
if (!targetUserId.isNullOrBlank() && targetUserId != "all") {
    val currentUserId = CookieSessionManager(applicationContext).getUserIdFromSessionToken()
    if (currentUserId != null && currentUserId != targetUserId) {
        Log.w(TAG, "Dropping remote timer sync: intended for $targetUserId, phone is logged in as $currentUserId")
        return
    }
}
```
In [AppFirebaseMessagingService.kt:197-213](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/AppFirebaseMessagingService.kt#L197-L213) (Display Notification Block):
```kotlin
if (currentUserId != null && currentUserId != targetUserId) {
    return
}
if (currentUserId == null) {
    Log.w(TAG, "Silently dropping push notification targeted to '$targetUserId' because no active user is logged in on this phone")
    return
}
```

#### Vulnerability Analysis:
- In the display notification handler, `if (currentUserId == null) return` correctly drops notifications when logged out.
- **In the timer sync handler, `if (currentUserId == null)` is completely missing!**
- If User A logs out, `CookieSessionManager.getUserIdFromSessionToken()` returns `null`.
- The condition `currentUserId != null && currentUserId != targetUserId` evaluates to **`false`**.
- The guard **does not return**.
- The method continues and calls `TimerForegroundService.dispatchRemoteSync()`.
- **Impact:** An unauthenticated or logged-out device will start, pause, or stop foreground timer services and display active study notifications for a user who has logged out.

### 4.6 Can a stale FCM message overwrite newer timer state?
**No.** Guarded by monotonic timestamp check:
```kotlin
if (updatedAt > 0L && updatedAt <= lastAppliedUpdatedAt) return
```
and session key guard:
```kotlin
if (remoteAction != "START" && expectedKey.isNotBlank() && idempotencyKey.isNotBlank() && expectedKey != idempotencyKey) return
```

### 4.7 Is there a reconciliation process when the app returns to the foreground?
**Partially.**
- The native Android lifecycle (`MainActivity.onResume()`) contains no native sync call.
- However, when the WebView regains focus, Next.js executes `window.addEventListener('focus')` inside `useRealtimeSync.ts`, which triggers `refreshWorkspace()` over HTTPS and queries the native bridge method `timer.getSyncState` ([NativeBridgeDispatcher.kt:622](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/bridge/NativeBridgeDispatcher.kt#L622)) to reconcile native tombstones.

---

## Question 5 — Timer Persistence & Reboot

### 5.1 Which timer state is persisted before Android kills the process?
- While `TimerForegroundService` is `RUNNING` or `PAUSED`, **no periodic disk snapshots are taken**. State is maintained exclusively in volatile RAM variables (`accumulatedSeconds`, `lastResumedAtMs`, `status`).
- When the user taps Pause or Resume on the notification, [TimerActionReceiver.kt:201-206](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/TimerActionReceiver.kt#L201-L206) enqueues an `ActiveTimerBatchItem` into Room (`offline_mutations`).
- When the session is completed or discarded, [TimerStateStore.kt:47-51](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/TimerStateStore.kt#L47-L51) synchronously writes the `idempotencyKey` and review JSON to `SharedPreferences`.

### 5.2 How is a RUNNING timer reconstructed after reboot?
**It is not reconstructed by native Android.**
1. In [TimerForegroundService.kt:235](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/TimerForegroundService.kt#L235), `onStartCommand()` returns `START_NOT_STICKY`. Android will not restart the service after reboot.
2. In [BootCompletedReceiver.kt:34-40](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/alarms/BootCompletedReceiver.kt#L34-L40), the receiver re-arms timetable schedule alarms and schedules periodic WorkManager sync. **It never checks Room for an active timer and never starts `TimerForegroundService`.**
3. Reconstruction occurs only when the student manually opens the app. The WebView mounts, reads `localStorage` or queries `/api/timer`, and reconstructs the timer in React state.

### 5.3 Is elapsed time calculated correctly across a device restart?
**No.**
If the timer was running when the device powered off, Next.js calculates elapsed time on recovery as:
```typescript
elapsed = accumulatedSeconds + Math.floor((Date.now() - lastResumedAt) / 1000)
```
If the phone remained powered off for 8 hours overnight, the timer accumulates those 8 hours as active study time.

### 5.4 What happens if the system clock changes?
**The timer calculation is vulnerable to clock jumps.**
In [TimerForegroundService.kt:473-477](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/TimerForegroundService.kt#L473-L477):
```kotlin
fun getElapsedSeconds(): Long {
    if (status == TimerStatus.RUNNING || status == TimerStatus.BREAK) {
        val additional = (System.currentTimeMillis() - lastResumedAtMs) / 1000L
        return accumulatedSeconds + maxOf(0L, additional)
    }
    return accumulatedSeconds
}
```
- It relies on `System.currentTimeMillis()` (wall-clock time), which changes on automatic network time adjustments (NTP) or manual user changes.
- **Clock Forward Jump**: If time jumps forward by 1 hour, `additional` gains 3,600 seconds immediately.
- **Clock Backward Jump**: If time jumps backward, `additional` is negative, `maxOf(0L, additional)` returns `0`, and the timer appears frozen until wall-clock time catches up to `lastResumedAtMs`.
- **Fix**: Must use `SystemClock.elapsedRealtime()` (monotonic hardware clock unaffected by NTP or user time adjustments).

### 5.5 Is the `PARTIAL_WAKE_LOCK` necessary for the entire duration of a study session?
- Currently, [TimerForegroundService.kt:375](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/services/TimerForegroundService.kt#L375) acquires a continuous, indefinite `PARTIAL_WAKE_LOCK`.
- While a continuous wake lock guarantees that low-end aggressive OEMs (Xiaomi, Transsion) do not freeze the coroutine ticker loop, it prevents the CPU from entering low-power states, increasing battery consumption.
- Best practice is to release the wake lock and calculate elapsed time upon wakeups, using `AlarmManager.setExactAndAllowWhileIdle()` solely for phase completion chimes.

### 5.6 Detailed Lifecycle Behavior Comparison

| Event | `TimerForegroundService` | Notification Shade | Room Mutations | Web React State |
| :--- | :--- | :--- | :--- | :--- |
| **Normal App Closure (Back/Home)** | Continues running via foreground service. | Continues ticking every second. | Unchanged. | Suspended / paused in background tab. |
| **Swiped Away from Recents** | Continues running via foreground service. | Continues ticking every second. | Unchanged. | WebView destroyed. |
| **Low Memory Killer (Process Death)** | Process killed. `START_NOT_STICKY` prevents OS recreation. | Disappears immediately. | Stays in Room (`offline_mutations`). Stale `SYNCING` records orphaned. | WebView destroyed. Restored from `localStorage` on next app launch. |
| **Device Reboot** | Terminated on shutdown. Not restored on boot. | Disappears. | Persists across reboot in SQLite. | Reconstructed from `localStorage` / backend when user launches app. |
| **OS Force Stop (Settings)** | Immediate SIGKILL. All services, notifications, alarms canceled. | Disappears immediately. | Persists in SQLite. | Destroyed. App cannot run any receivers until user manually taps app icon. |

---

## Question 6 — Offline Feature Coverage Matrix

| Entity | React State | LocalStorage | Room Database | Queued for Sync | Reconciled on Reconnect | Code Reality & Deficiencies |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **Study Sessions (Finish)** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | Fully implemented. Enqueued via `TimerActionReceiver` and `handleTimerStop` into `offline_mutations`. |
| **Active Timer (Pause/Resume)** | **Yes** | **Yes** | **Partial** | **Partial** | **Yes** | Enqueued only when tapped from native notification shade. Bridge calls do not enqueue to Room. |
| **Tasks (Create/Edit/Toggle/Delete)** | **Yes** | **Yes** | **No** | **No** | **Rollback** | **Missing Bridge Binding.** Handled via web React/Server Actions. If offline, Server Actions fail and React rolls back. |
| **Subjects (Create/Edit/Delete)** | **Yes** | **Yes** | **No** | **No** | **Rollback** | `SubjectDao` has read/upsert methods, but no offline mutation queueing path exists in the bridge. |
| **Schedules / Timetable** | **Yes** | **Yes** | **Cache Only** | **No** | **Rollback** | Synced to Room only to schedule `AlarmManager` alarms via `handleSyncScheduleAlarms`. Cannot create schedules offline. |
| **Goals (Create/Edit/Delete)** | **Yes** | **Yes** | **No** | **No** | **Rollback** | Zero Room tables or DAOs exist for goals. Handled purely on web. |
| **Revision Matrix Toggles** | **Yes** | **Yes** | **No** | **No** | **Rollback** | `RevisionRecordEntity` exists in Room, but no bridge method routes offline toggles to it. |

---

## Prioritized Action Plan & Recommended Fixes

### 1. Fix Logout Guard in `AppFirebaseMessagingService.kt` (Severity: HIGH)
Add missing `currentUserId == null` check to prevent processing timer sync for logged-out users:
```kotlin
// File: AppFirebaseMessagingService.kt:164
if (!targetUserId.isNullOrBlank() && targetUserId != "all") {
    val currentUserId = CookieSessionManager(applicationContext).getUserIdFromSessionToken()
    if (currentUserId == null || currentUserId != targetUserId) {
        Log.w(TAG, "Dropping remote timer sync: intended for $targetUserId, phone session is $currentUserId")
        return
    }
}
```

### 2. Fix Orphaned `SYNCING` Mutations (Severity: HIGH)
In `OfflineMutationDao`:
```kotlin
// File: Daos.kt:104
@Query("SELECT * FROM offline_mutations WHERE status IN ('PENDING', 'FAILED', 'SYNCING') ORDER BY createdAtMs ASC")
suspend fun getPendingMutations(): List<OfflineMutationEntity>

@Query("UPDATE offline_mutations SET status = 'PENDING' WHERE status = 'SYNCING'")
suspend fun resetStaleSyncing(): Int
```

### 3. Add Concurrency Mutex to `OfflineSyncWorker.performSync()` (Severity: MEDIUM)
Prevent simultaneous execution from `TimerActionReceiver` and `WorkManager`:
```kotlin
// File: OfflineSyncWorker.kt
private val syncMutex = Mutex()

suspend fun performSync(context: Context): Boolean = withContext(Dispatchers.IO) {
    if (!syncMutex.tryLock()) {
        Log.d(TAG, "Sync already in progress; skipping concurrent execution.")
        return@withContext true
    }
    try {
        // execute sync
    } finally {
        syncMutex.unlock()
    }
}
```

### 4. Switch Timer Calculation to Monotonic Clock (Severity: MEDIUM)
Replace `System.currentTimeMillis()` with `SystemClock.elapsedRealtime()` in `TimerForegroundService.kt` to eliminate vulnerability to NTP and system clock adjustments:
```kotlin
// File: TimerForegroundService.kt
private var lastResumedAtElapsedRealtime: Long = 0L

// When starting/resuming:
lastResumedAtElapsedRealtime = SystemClock.elapsedRealtime()

// When computing elapsed:
fun getElapsedSeconds(): Long {
    if (status == TimerStatus.RUNNING || status == TimerStatus.BREAK) {
        val additional = (SystemClock.elapsedRealtime() - lastResumedAtElapsedRealtime) / 1000L
        return accumulatedSeconds + maxOf(0L, additional)
    }
    return accumulatedSeconds
}
```
